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

The first form follows a process until it exits. The second reads a recording
back and summarises one slice of it — hand it the two timestamps that the
flight's own log lines carry, so the slice is the route and not the loading
screen.
"""

import argparse
import collections
import os
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
                for word in argv[1:4]:
                    word = word.decode("utf-8", "replace")
                    if word and not word.startswith("-"):
                        name += " " + os.path.basename(word)
                        break
        except (OSError, ValueError, IndexError):
            continue
        out[int(entry)] = (name, used)
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
    try:
        apps = subprocess.run(
            ["nvidia-smi", "--query-compute-apps=pid,used_memory",
             "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=4).stdout.strip()
        gpu["apps"] = len([line for line in apps.splitlines() if line.strip()])
    except (OSError, subprocess.SubprocessError):
        gpu["apps"] = -1
    return gpu


def follow(pattern, out_path, interval, limit):
    """Sample until the watched process exits, writing a row per sample."""
    ours = None
    previous = process_jiffies()
    busy, idle = cpu_busy_idle()
    started = time.time()
    rows = 0
    with open(out_path, "w") as out:
        out.write("time,busy_percent,ours_percent,gpu_percent,gpu_clock,"
                  "gpu_temperature,gpu_apps,gpu_events,others\n")
        while True:
            time.sleep(interval)
            current = process_jiffies()
            new_busy, new_idle = cpu_busy_idle()
            spent = (new_busy - busy) + (new_idle - idle)
            busy_share = 100.0 * (new_busy - busy) / spent if spent else 0.0
            busy, idle = new_busy, new_idle

            if ours is None or ours not in current:
                matches = [pid for pid, (name, _) in current.items()
                           if pattern in name]
                if matches:
                    # The largest, because a launcher and the thing it launched
                    # both match, and it is the second one that is being timed.
                    ours = max(matches, key=lambda pid: current[pid][1])
                elif ours is not None:
                    break

            movers = []
            mine = 0.0
            for pid, (name, used) in current.items():
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
            out.write("%s,%.1f,%.1f,%s,%s,%s,%s,%s,%s\n" % (
                time.strftime("%H:%M:%S"), busy_share, mine,
                gpu.get("util", ""), gpu.get("clock", ""),
                gpu.get("temperature", ""), gpu.get("apps", ""),
                "0x%x" % gpu["events"] if "events" in gpu else "",
                " | ".join("%s %.0f%%" % (name, share)
                           for share, name in movers[:4])))
            out.flush()
            rows += 1
            if limit and time.time() - started > limit:
                break
    return rows


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
    clock = column("gpu_clock")
    if clock and max(clock) > 0:
        drop = 100.0 * (max(clock) - min(clock)) / max(clock)
        print("  card clock    %.0f to %.0f MHz, a %.0f%% spread"
              % (min(clock), max(clock), drop))
    temperature = column("gpu_temperature")
    if temperature:
        print("  card heat     %.0f to %.0f degrees"
              % (min(temperature), max(temperature)))

    reasons = collections.Counter()
    for row in rows:
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

    apps = column("gpu_apps")
    extra = [value for value in apps if value > 1]

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
    if extra:
        complaints.append("a second program was on the card")
    if complaints:
        print("NOT CLEAN: " + "; ".join(complaints) + ".")
        print("Throw this run away and fly it again.")
    else:
        print("CLEAN: the machine belonged to this run.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--until", metavar="NAME",
                        help="sample until the process whose command contains "
                             "this exits")
    parser.add_argument("--out", default="machine-load.csv")
    parser.add_argument("--interval", type=float, default=1.0)
    parser.add_argument("--limit", type=float, default=0.0,
                        help="stop after this many seconds no matter what")
    parser.add_argument("--report", metavar="CSV",
                        help="summarise a recording instead of making one")
    parser.add_argument("--from", dest="start", metavar="HH:MM:SS")
    parser.add_argument("--to", dest="end", metavar="HH:MM:SS")
    args = parser.parse_args()

    if args.report:
        report(args.report, args.start, args.end)
        return
    if not args.until:
        sys.exit("Give either --until NAME to record or --report CSV to read one.")
    rows = follow(args.until, args.out, args.interval, args.limit)
    print("%d samples written to %s" % (rows, args.out))
    report(args.out, None, None)


if __name__ == "__main__":
    main()
