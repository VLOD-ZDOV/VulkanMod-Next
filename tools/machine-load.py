#!/usr/bin/env python3
"""Record what else the machine was doing while a flight was being measured.

Why this exists
---------------

Two runs of an identical build reported medians 1389 and 799 — seventy-four per
cent apart, with step-shaped plateaus: six seconds at exactly one number, then a
jump. A renderer does not do that. Something else on the machine does, and the
runs had no witness: nothing recorded whether the computer belonged to the
measurement while the measurement was being taken.

So this is the sentinel. It samples, once a second, how much of the machine the
flight had and who took the rest, and it prints a verdict rather than a wall of
numbers. A run whose verdict is not clean is thrown away instead of being
argued about.

It records the graphics card as well as the processor, and for the same reason:
a second program holding the card, or the card dropping its clock because it is
hot or capped, both look exactly like "our change made it slower".

Usage
-----

    python3 tools/machine-load.py --until java --out load.csv
    python3 tools/machine-load.py --report load.csv --from 16:50:14 --to 16:50:34
    python3 tools/machine-load.py --report load.csv --log run/logs/latest.log --tag vk-a

The first form follows a process until it exits. The second reads a recording
back and summarises one slice of it — hand it the two timestamps that the
flight's own log lines carry, so the slice is the route and not the loading
screen. The third finds those two timestamps itself, in the game's log, which
is the same thing done without a chance to mistype either of them.
"""

import argparse
import collections
import os
import re
import subprocess
import sys
import time

CORES = os.cpu_count() or 1

# Anything under this is the operating system breathing, not a competitor.
# A percentage of the whole machine, so on a thirty-two core one this is about a
# third of a core.
QUIET_PERCENT = 1.0

# Named so a report says "the card was hot" rather than "0x60".
CLOCK_EVENTS = [
    (0x0001, "idle"),
    (0x0002, "clocks pinned by an application"),
    (0x0004, "power cap"),
    (0x0008, "hardware slowdown"),
    (0x0010, "sync boost"),
    (0x0020, "thermal, by software"),
    (0x0040, "thermal, by hardware"),
    (0x0080, "power brake"),
    (0x0100, "display clock"),
]


def ancestors():
    """Every process between this one and the top, this one included.

    The pattern being searched for is one of our own arguments, so it is also
    in the command line of the shell that started us and of its shell, and a
    sentinel that matches its own family reports the thing it is watching as
    using no processor at all.
    """
    seen = set()
    pid = os.getpid()
    while pid > 1 and pid not in seen:
        seen.add(pid)
        try:
            with open("/proc/%d/stat" % pid) as handle:
                text = handle.read()
            pid = int(text[text.rindex(")") + 2:].split()[1])
        except (OSError, ValueError, IndexError):
            break
    return seen


def cpu_busy_idle():
    """Busy and idle jiffies across every core, from the kernel's own counters."""
    with open("/proc/stat") as handle:
        fields = handle.readline().split()[1:]
    numbers = [int(value) for value in fields]
    idle = numbers[3] + (numbers[4] if len(numbers) > 4 else 0)
    return sum(numbers) - idle, idle


def process_jiffies():
    """Processor time per live process, with the name it would be recognised by.

    The command line is preferred over the short name because on this kind of
    machine half the interesting processes are interpreters, and "python3" says
    nothing about which program is running.
    """
    out = {}
    for entry in os.listdir("/proc"):
        if not entry.isdigit():
            continue
        try:
            with open("/proc/%s/stat" % entry) as handle:
                text = handle.read()
            # The second field is the executable name in brackets, and it may
            # itself contain spaces and brackets, so the split has to be from
            # the last one rather than the first.
            fields = text[text.rindex(")") + 2:].split()
            used = int(fields[11]) + int(fields[12])
            with open("/proc/%s/cmdline" % entry, "rb") as handle:
                argv = handle.read().split(b"\0")
            name = text[text.index("(") + 1:text.rindex(")")]
            if argv and argv[0]:
                name = os.path.basename(argv[0].decode("utf-8", "replace"))
                # The whole argument list rather than the first few of it. A
                # game launched by a build tool puts thirty switches before the
                # class it is going to run, and stopping at the fourth left two
                # entirely different programs both called "java", collapsed
                # into one row that belonged to neither.
                skip = False
                for word in argv[1:]:
                    word = word.decode("utf-8", "replace")
                    if not word:
                        continue
                    if skip:
                        skip = False
                        continue
                    if word.startswith("-"):
                        skip = word in ("-cp", "-classpath", "-p", "--module-path",
                                        "-m", "--module", "--add-modules")
                        continue
                    name += " " + os.path.basename(word)
                    break
        except (OSError, ValueError, IndexError):
            continue
        out[int(entry)] = (name, used, b" ".join(argv).decode("utf-8", "replace"))
    return out


def gpu_sample():
    """The card's own view of itself, or None where there is no such card."""
    query = ("utilization.gpu,memory.used,clocks.current.graphics,"
             "temperature.gpu,clocks_event_reasons.active")
    try:
        text = subprocess.run(
            ["nvidia-smi", "--query-gpu=" + query,
             "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=4).stdout.strip()
        parts = [piece.strip() for piece in text.split(",")]
        gpu = {"util": float(parts[0]), "memory": float(parts[1]),
               "clock": float(parts[2]), "temperature": float(parts[3]),
               "events": int(parts[4], 16)}
    except (OSError, ValueError, IndexError, subprocess.SubprocessError):
        return None
    return gpu


def gpu_processes(ours):
    """What each process actually did with the card in the last second.

    Residency is not use. A program can hold gigabytes of the card's memory and
    take none of its time, and a verdict that fails a run for that fails every
    run on a machine with a model loaded — which is to say it stops meaning
    anything. This asks the card's own sampler who used it, not who is sitting
    on it.

    It returns at once, reporting the card's own most recent window rather than
    opening one, so it is not a clock and the caller still has to keep its own.
    Taking it for a clock cost eighty-eight samples in four seconds, each one
    running two subprocesses — a sentinel that had become the loudest thing on
    the machine it was there to listen to.

    Where there is no such card it returns None.
    """
    try:
        text = subprocess.run(["nvidia-smi", "pmon", "-c", "1", "-s", "u"],
                              capture_output=True, text=True, timeout=20).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    busy = 0.0
    resident = 0
    for line in text.splitlines():
        if line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) < 4:
            continue
        try:
            pid = int(parts[1])
        except ValueError:
            continue
        resident += 1
        if pid == ours:
            continue
        try:
            busy += float(parts[3])
        except ValueError:
            # A dash, which is the sampler saying this process did nothing
            # worth recording in the window.
            pass
    return busy, resident


def follow(pattern, out_path, interval, limit):
    """Sample until the watched process exits, writing a row per sample."""
    ours = None
    ours_own = ancestors()
    previous = process_jiffies()
    busy, idle = cpu_busy_idle()
    started = time.time()
    rows = 0
    with open(out_path, "w") as out:
        out.write("time,busy_percent,ours_percent,gpu_percent,gpu_clock,"
                  "gpu_temperature,gpu_apps,gpu_others,gpu_events,others\n")
        while True:
            time.sleep(interval)
            paced = gpu_processes(ours)
            current = process_jiffies()
            new_busy, new_idle = cpu_busy_idle()
            spent = (new_busy - busy) + (new_idle - idle)
            busy_share = 100.0 * (new_busy - busy) / spent if spent else 0.0
            busy, idle = new_busy, new_idle

            if ours is None or ours not in current:
                matches = [pid for pid, entry in current.items()
                           if pid not in ours_own
                           and (pattern in entry[0] or pattern in entry[2])]
                if matches:
                    # The largest, because a launcher and the thing it launched
                    # both match, and it is the second one that is being timed.
                    ours = max(matches, key=lambda pid: current[pid][1])
                    # Said out loud, because the alternative is what happened
                    # the first time this ran: it matched nothing, reported our
                    # share as zero for four hundred samples, and looked from
                    # the outside exactly like a game that used no processor.
                    print("watching %d: %s" % (ours, current[ours][2][:160]),
                          flush=True)
                elif ours is not None:
                    break

            movers = []
            mine = 0.0
            for pid, (name, used, _) in current.items():
                was = previous.get(pid)
                if was is None or was[0] != name:
                    continue
                share = 100.0 * (used - was[1]) / spent if spent else 0.0
                if pid == ours:
                    mine = share
                elif share >= QUIET_PERCENT:
                    movers.append((share, name))
            previous = current

            gpu = gpu_sample() or {}
            movers.sort(reverse=True)
            out.write("%s,%.1f,%.1f,%s,%s,%s,%s,%s,%s,%s\n" % (
                time.strftime("%H:%M:%S"), busy_share, mine,
                gpu.get("util", ""), gpu.get("clock", ""),
                gpu.get("temperature", ""),
                "" if paced is None else paced[1],
                "" if paced is None else "%.0f" % paced[0],
                "0x%x" % gpu["events"] if "events" in gpu else "",
                " | ".join("%s %.0f%%" % (name, share)
                           for share, name in movers[:4])))
            out.flush()
            rows += 1
            if limit and time.time() - started > limit:
                break
    return rows, ours is not None


def flight_window(log_path, tag):
    """The wall clock at which a flight began and ended, from the game's log.

    Both lines are the flight's own, and the window between them is the route:
    before the first one the world is still settling, which is not what any of
    this is trying to describe.
    """
    start = end = rate = None
    begins = "Flight %s route" % tag
    ends = "Flight %s frame rate" % tag
    try:
        handle = open(log_path, errors="replace")
    except OSError as why:
        sys.exit("Cannot read %s: %s" % (log_path, why.strerror))
    with handle:
        for line in handle:
            stamp = re.match(r"\[\S+ (\d\d:\d\d:\d\d)", line)
            if not stamp:
                continue
            if begins in line:
                start = stamp.group(1)
            elif ends in line:
                end = stamp.group(1)
                found = re.search(r"median (\d+), 5% low (\d+)", line)
                if found:
                    rate = (int(found.group(1)), int(found.group(2)))
    return start, end, rate


def parse(path):
    rows = []
    with open(path) as handle:
        header = handle.readline().rstrip("\n").split(",")
        for line in handle:
            # The last column holds the competitors and contains commas of its
            # own, so the split has to stop where the fixed columns end.
            parts = line.rstrip("\n").split(",", len(header) - 1)
            rows.append(dict(zip(header, parts)))
    return rows


def number(row, key):
    try:
        return float(row[key])
    except (KeyError, ValueError):
        return None


def report(path, start, end):
    rows = parse(path)
    if start or end:
        rows = [row for row in rows
                if (not start or row["time"] >= start)
                and (not end or row["time"] <= end)]
    if not rows:
        sys.exit("No samples in that window — check the two timestamps.")

    def column(key):
        return [value for value in (number(row, key) for row in rows)
                if value is not None]

    print("%d samples, %s to %s" % (len(rows), rows[0]["time"], rows[-1]["time"]))

    # Every share below is a share of the whole machine rather than of one
    # core, because the divisor is the time every core spent in the interval.
    ours = column("ours_percent")
    if ours:
        print("  ours          %.0f%% of the machine on average, peak %.0f%%"
              % (sum(ours) / len(ours), max(ours)))
    busy = column("busy_percent")
    if busy:
        print("  whole machine %.0f%% busy on average, peak %.0f%%  (%d cores)"
              % (sum(busy) / len(busy), max(busy), CORES))

    # Who else was on the machine, weighted by how long they were on it, so a
    # process that took ten per cent for the whole route outranks one that took
    # sixty for a single second.
    others = collections.Counter()
    seconds = collections.Counter()
    for row in rows:
        for piece in row.get("others", "").split(" | "):
            if not piece.strip():
                continue
            name, _, share = piece.rpartition(" ")
            try:
                others[name] += float(share.rstrip("%"))
            except ValueError:
                continue
            seconds[name] += 1
    if others:
        print("  others on the machine:")
        for name, total in others.most_common(6):
            print("    %-40s %.0f%% of the machine, over %d of %d samples"
                  % (name[:40], total / len(rows), seconds[name], len(rows)))
    else:
        print("  others on the machine: none above %.0f%%" % QUIET_PERCENT)

    util = column("gpu_percent")
    if util:
        print("  card          %.0f%% busy on average, low %.0f%%"
              % (sum(util) / len(util), min(util)))
    # Only while the card was working. An idle card drops to a fifth of its
    # clock and climbs back the moment anything asks it to, which is healthy
    # behaviour and would otherwise fail every run flown on a quiet desktop.
    working = [row for row in rows
               if (number(row, "gpu_percent") or 0.0) >= 20.0
               and not (int(row.get("gpu_events", "") or "0", 16) & 0x0001)]
    clock = [value for value in (number(row, "gpu_clock") for row in working)
             if value is not None]
    if clock and max(clock) > 0:
        drop = 100.0 * (max(clock) - min(clock)) / max(clock)
        print("  card clock    %.0f to %.0f MHz, a %.0f%% spread, over %d busy samples"
              % (min(clock), max(clock), drop, len(clock)))
    temperature = column("gpu_temperature")
    if temperature:
        print("  card heat     %.0f to %.0f degrees"
              % (min(temperature), max(temperature)))

    reasons = collections.Counter()
    for row in working:
        try:
            bits = int(row.get("gpu_events", "") or "0", 16)
        except ValueError:
            continue
        for bit, what in CLOCK_EVENTS:
            if bits & bit and bit != 0x0001:
                reasons[what] += 1
    if reasons:
        print("  card held back by: " + ", ".join(
            "%s (%d samples)" % (what, count) for what, count in reasons.most_common()))

    # What the neighbours took from the card, as opposed to how many of them
    # were merely sitting on it.
    stolen = column("gpu_others")
    if stolen:
        print("  card, others  %.0f%% of its time on average, peak %.0f%%"
              % (sum(stolen) / len(stolen), max(stolen)))
    resident = column("gpu_apps")
    if resident and max(resident) > 1 and not stolen:
        print("  card          shared with %d other program(s)" % (max(resident) - 1))

    # The verdict, which is the whole point: a run either owned the machine or
    # it did not, and a run that did not is thrown away rather than explained.
    print()
    complaints = []
    if others and max(others.values()) / len(rows) >= 5.0:
        complaints.append("another program held a twentieth of the machine or more")
    if clock and max(clock) > 0 and (max(clock) - min(clock)) / max(clock) >= 0.15:
        complaints.append("the card changed its clock by more than a seventh")
    if reasons:
        complaints.append("the card was held back")
    if stolen and sum(stolen) / len(stolen) >= 2.0:
        complaints.append("another program was using the card")
    elif not stolen and resident and max(resident) > 1:
        complaints.append("another program was on the card, and this recording "
                          "cannot say whether it was using it")
    if complaints:
        print("NOT CLEAN: " + "; ".join(complaints) + ".")
        print("Throw this run away and fly it again.")
    else:
        print("CLEAN: the machine belonged to this run.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--until", metavar="NAME",
                        help="sample until the process whose name or command "
                             "line contains this exits")
    parser.add_argument("--out", default="machine-load.csv")
    parser.add_argument("--interval", type=float, default=1.0)
    parser.add_argument("--limit", type=float, default=0.0,
                        help="stop after this many seconds no matter what")
    parser.add_argument("--report", metavar="CSV",
                        help="summarise a recording instead of making one")
    parser.add_argument("--from", dest="start", metavar="HH:MM:SS")
    parser.add_argument("--to", dest="end", metavar="HH:MM:SS")
    parser.add_argument("--log", metavar="LATEST.LOG",
                        help="take the window from a flight's own log lines")
    parser.add_argument("--tag", help="which flight in that log, its -PflightTag")
    args = parser.parse_args()

    if args.report:
        if args.log:
            if not args.tag:
                sys.exit("--log needs --tag, the same one the flight was given.")
            args.start, args.end, rate = flight_window(args.log, args.tag)
            if not args.start or not args.end:
                sys.exit("No complete flight tagged %r in %s — it may not have "
                         "finished." % (args.tag, args.log))
            if rate:
                print("flight %s: median %d, 5%% low %d" % (args.tag, rate[0], rate[1]))
        report(args.report, args.start, args.end)
        return
    if not args.until:
        sys.exit("Give either --until NAME to record or --report CSV to read one.")
    rows, found = follow(args.until, args.out, args.interval, args.limit)
    print("%d samples written to %s" % (rows, args.out))
    if not found:
        print("NOTHING matched %r while this ran, so the \"ours\" column is "
              "empty rather than zero." % args.until)
    report(args.out, None, None)


if __name__ == "__main__":
    main()
