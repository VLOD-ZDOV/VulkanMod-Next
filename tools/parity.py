#!/usr/bin/env python3
"""How far each port has caught up with 1.12.2, counted from the sources.

Why this exists
---------------

"Do the versions match in what they can do" is a question that gets answered
from memory, and memory says yes for longer than it is true. Every setting the
mod has is declared in one place per port, and each port's declaration carries a
flag saying whether anything reads it yet, so the answer is already written
down — it just was not being read. It is read here.

A setting that is declared but steers nothing is not a lie: the menu greys it
out and says "(not yet ported)". It is a promise not yet kept, and this counts
the promises.

Usage
-----

    python3 tools/parity.py                 # the summary and what is missing
    python3 tools/parity.py --category OPTIMIZATION
    python3 tools/parity.py --key dropVanillaBuffers
"""

import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The original, whose settings every other port is measured against, and then
# the ports. Each is (name, path, how to read it).
ORIGINAL = "1.12.2"
PORTS = ["1.16.5", "1.21.11"]

ORIGINAL_FILE = "src/main/java/net/vulkanmodnext/client/VulkanConfig.java"
PORT_FILE = "src/main/java/net/vulkanmodnext/client/Settings.java"


def original_keys():
    """Every setting 1.12.2 reads out of its config file."""
    path = os.path.join(HERE, ORIGINAL_FILE)
    text = open(path, errors="replace").read()
    return set(re.findall(r'config\.get(?:Boolean|Int)\("(\w+)"', text))


def port_settings(port):
    """Each of a port's settings and whether anything there reads it."""
    path = os.path.join(HERE, port, PORT_FILE)
    if not os.path.exists(path):
        return None
    text = open(path, errors="replace").read()
    found = re.findall(
        r'add\("(\w+)",\s*Category\.(\w+),\s*\w+,\s*-?\d+,\s*-?\d+,\s*-?\d+,\s*(\w+),',
        text)
    return {key: (category, live == "true") for key, category, live in found}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--category", help="only this one, e.g. OPTIMIZATION")
    parser.add_argument("--key", help="only this setting, across every port")
    args = parser.parse_args()

    origin = original_keys()
    ports = {}
    for port in PORTS:
        settings = port_settings(port)
        if settings is None:
            print("%s: no settings file, skipped" % port)
            continue
        ports[port] = settings

    if not ports:
        sys.exit("No ports found to compare.")

    if args.key:
        for port, settings in ports.items():
            entry = settings.get(args.key)
            if entry is None:
                print("%-8s %s: not declared" % (port, args.key))
            else:
                print("%-8s %s: %s, %s" % (port, args.key, entry[0],
                                           "works" if entry[1] else "NOT PORTED"))
        print("%-8s %s: %s" % (ORIGINAL, args.key,
                               "present" if args.key in origin else "not declared"))
        return

    print("%s declares %d settings.\n" % (ORIGINAL, len(origin)))
    for port, settings in ports.items():
        live = [k for k, v in settings.items() if v[1]]
        # Declared here but unknown to the original, which would mean the two
        # have drifted rather than one being behind.
        stray = sorted(set(settings) - origin)
        missing = sorted(origin - set(settings))
        print("%s: %d of %d work (%d%%)"
              % (port, len(live), len(settings), 100 * len(live) // len(settings)))
        if missing:
            print("   not even declared here: " + ", ".join(missing))
        if stray:
            print("   declared here and not in %s: %s" % (ORIGINAL, ", ".join(stray)))

        by_category = {}
        for key, (category, works) in sorted(settings.items()):
            if args.category and category != args.category:
                continue
            if not works:
                by_category.setdefault(category, []).append(key)
        for category in sorted(by_category):
            names = by_category[category]
            print("   %s, %d still to do:" % (category, len(names)))
            for i in range(0, len(names), 3):
                print("      " + "  ".join("%-26s" % n for n in names[i:i + 3]).rstrip())
        print()


if __name__ == "__main__":
    main()
