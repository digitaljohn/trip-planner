"""Compares two tools/screen_text.sh captures screen by screen, ignoring order and scroll position.

Usage: python3 tools/compare_screen_text.py before.txt after.txt
Prints the lines only one side has, per screen; prints nothing and exits 0 when they match.
"""
import sys


def sections(path):
    out, cur = {}, None
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if line.startswith("=== "):
            cur = line[4:]
            out[cur] = set()
        elif cur:
            out[cur].add(line)
    return out


before, after = sections(sys.argv[1]), sections(sys.argv[2])
differs = False
for name in list(before) + [n for n in after if n not in before]:
    gone, new = before.get(name, set()) - after.get(name, set()), after.get(name, set()) - before.get(name, set())
    if gone or new:
        differs = True
        print(f"## {name}")
        for line in sorted(gone):
            print(f"  - {line}")
        for line in sorted(new):
            print(f"  + {line}")
sys.exit(1 if differs else 0)
