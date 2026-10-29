"""Regenerates TerminalData.kt from OpenStreetMap data, for every terminal listed in tools/terminals.json.

Usage: python3 tools/terminals_from_osm.py <osm dir> app/src/main/java/app/trip/TerminalData.kt
<osm dir> holds <CODE>.json and <CODE>_b.json per airport, as written by tools/fetch_osm.sh.

terminals.json, per terminal:
  object      Kotlin object name, used from Terminals.kt (ChangiT1.outlines, ...)
  code        IATA code (picks the input files)
  lat0, lon0  map origin; coordinates come out in metres east/south of it
  outlines    OSM way ids of the terminal buildings to draw (empty: draw piers from gates in Terminals.kt)
  dim         way ids drawn faded (neighbouring terminals)
  rails       null, or a tag filter for railway ways to draw dashed ({"railway": "monorail"}, {"name": "..."})
  gates       which aeroway=gate nodes to keep: "prefixes" (ref's first letter), "maxLat"/"maxLon",
              "exclude" (refs), "rename" ([regex, replacement] applied to each ref)
"""
import json
import math
import os
import re
import sys

OSM, OUT = sys.argv[1], sys.argv[2]
SPEC = json.load(open(os.path.join(os.path.dirname(__file__), "terminals.json")))


def simplify(pts, eps):
    """Douglas–Peucker; closed rings are split at their farthest point so the first/last point can't collapse them."""
    if len(pts) < 3:
        return pts
    if pts[0] == pts[-1]:
        i = max(range(len(pts)), key=lambda k: math.hypot(pts[k][0] - pts[0][0], pts[k][1] - pts[0][1]))
        return simplify(pts[:i + 1], eps)[:-1] + simplify(pts[i:], eps)
    (x1, y1), (x2, y2) = pts[0], pts[-1]
    length = math.hypot(x2 - x1, y2 - y1) or 1e-9
    i, dmax = 0, 0
    for k in range(1, len(pts) - 1):
        x, y = pts[k]
        d = abs((y2 - y1) * x - (x2 - x1) * y + x2 * y1 - y2 * x1) / length
        if d > dmax:
            i, dmax = k, d
    if dmax > eps:
        return simplify(pts[:i + 1], eps)[:-1] + simplify(pts[i:], eps)
    return [pts[0], pts[-1]]


def projector(lat0, lon0):
    c = math.cos(math.radians(lat0))
    return lambda la, lo: (round((lo - lon0) * 111320 * c, 1), round(-(la - lat0) * 110540, 1))


def arr(pts):
    return "floatArrayOf(" + ", ".join(f"{x}f, {y}f" for x, y in pts) + ")"


def load(name):
    path = os.path.join(OSM, name + ".json")
    return json.load(open(path))["elements"] if os.path.exists(path) else []


out = ["// Generated from OpenStreetMap (© OpenStreetMap contributors, ODbL). Metres east/south of each origin.", "package app.trip", ""]
for t in SPEC:
    P = projector(t["lat0"], t["lon0"])
    ways = {e["id"]: e for e in load(t["code"] + "_b")}
    geom = lambda e: [P(p["lat"], p["lon"]) for p in e["geometry"]]
    outlines = [simplify(geom(ways[i]), 2.5) for i in t["outlines"]]
    dim = [simplify(geom(ways[i]), 4) for i in t["dim"]]
    rails = []
    if t["rails"]:
        rails = [simplify(geom(e), 2) for e in ways.values() if all(e.get("tags", {}).get(k) == v for k, v in t["rails"].items())]
    g, rule = {}, t["gates"]
    for e in load(t["code"]):
        ref = e["tags"].get("ref", "")
        if "prefixes" in rule and ref[:1] not in rule["prefixes"]:
            continue
        if not ref or ref in rule.get("exclude", []) or e["lon"] >= rule.get("maxLon", 999) or e["lat"] >= rule.get("maxLat", 999):
            continue
        for r in re.split(r"[/;]", ref):
            for pattern, repl in rule.get("rename", []):
                r = re.sub(pattern, repl, r)
            if r and r not in g:
                g[r] = P(e["lat"], e["lon"])
    order = lambda kv: (re.sub(r"\d", "", kv[0]), int(re.sub(r"\D", "", kv[0]) or 0))
    out.append(f"internal object {t['object']} {{")
    out.append(f"    const val LAT0 = {t['lat0']}\n    const val LON0 = {t['lon0']}")
    out.append("    val outlines = listOf<FloatArray>(\n" + "".join("        " + arr(o) + ",\n" for o in outlines) + "    )")
    out.append("    val dim = listOf<FloatArray>(\n" + "".join("        " + arr(o) + ",\n" for o in dim) + "    )")
    out.append("    val rails = listOf<FloatArray>(\n" + "".join("        " + arr(o) + ",\n" for o in rails) + "    )")
    out.append("    val gates = mapOf(\n" + "".join(f'        "{k}" to floatArrayOf({v[0]}f, {v[1]}f),\n' for k, v in sorted(g.items(), key=order)) + "    )")
    out.append("}\n")
open(OUT, "w").write("\n".join(out))
print(f"wrote {len(SPEC)} terminals to {OUT}")
