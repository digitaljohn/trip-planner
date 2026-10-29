---
name: add-terminal-map
description: Add an airport terminal to the app's terminal maps (GPS position, gate pin and route, trains, food, showers, smoking areas, free-time card). Use when a trip departs from a terminal that isn't in app/src/main/java/app/trip/Terminals.kt, or someone asks for a map of an airport.
---

# Add a terminal map

A terminal map is real geometry from OpenStreetMap (outlines, gates, airside trains) plus
hand-placed facilities, all in metres from an origin, so a phone's GPS fix lands in the right
place with no fitting. Read `Terminal` in `app/src/main/java/app/trip/Terminal.kt` and the three
existing entries in `Terminals.kt` before starting.

## 1. Research first

Before writing anything, find for the terminal:

- The **official** airside floor plan or map (airport website). Note which level gates are on,
  where security comes out, transfer desks, trains between piers or terminals and their walk or
  ride times.
- The airport's official list of **smoking areas** (most publish one; some have none airside).
- Food, showers, lounges, and anything notable (gardens, quiet zones) near the gates.

Say where each fact came from. Don't guess positions or walk times; mark estimates as estimates
in the spot notes.

## 2. Fetch and generate the geometry

1. Find the terminal's rough centre and a bounding box around its buildings and any airside train.
2. `tools/fetch_osm.sh <IATA> <lat> <lon> "<south>,<west>,<north>,<east>" <osm dir>`.
3. Inspect `<IATA>_b.json`: list the `aeroway=terminal` ways (id, name, point count) and railway
   ways. Choose the outline way(s) for this terminal, neighbouring terminals to draw dimmed, and a
   rail filter for airside people-movers. Inspect `<IATA>.json` gate refs and pick a filter that
   keeps only this terminal's gates.
4. Add an entry to `tools/terminals.json` (fields are documented in the script's header) with the
   origin near the terminal's centre.
5. Run `python3 tools/terminals_from_osm.py <osm dir> app/src/main/java/app/trip/TerminalData.kt`.
   The other terminals' data must come out unchanged: `git diff` should only add the new object.
   That needs every listed airport's JSON in `<osm dir>`; fetch the existing ones too if missing.

If OSM has no usable outline (Sydney T1 didn't), leave `outlines` empty and draw the piers with
`corridors` in `Terminals.kt` as lines through the real gate positions; see `sydneyT1`.

## 3. Place the facilities

Add a `Terminal(...)` to `Terminals.kt` and register it in the `terminals` map by IATA code:

- `hub`: where you start (after security, or the transfer hall for connections).
- `spots`: each with lat/lon. To place things from an official floor-plan image, fit an affine
  transform from image pixels to lat/lon using five or more gates visible on both (least squares),
  check the residuals (under ~40 m is good), then convert each facility's pixel position.
- `via`: the pier root(s) between hub and each group of gates, with `train = true` for legs ridden
  on a people-mover, and `trainMin` for waiting plus riding.
- `levels`, `note` (what level you need, what's optional, smoking rules), `searchName`, and a
  `hint` for gate groups that need more than "follow the signs".

## 4. Use it and check it

1. Add a `Stop` for the departure in `trip/MyTrip.kt` (see the new-trip skill).
2. Build, install on a headless emulator, preview the airport leg, open **Terminal map**, and check
   the outline, gate dots, spot pins and (with a sample gate, temporarily) the route and walk time,
   in light and dark. Remove any temporary sample gate before committing.
3. `adb emu geo fix <lon> <lat>` on the emulator checks the GPS dot inside the terminal, and
   somewhere far away should say you're not in the terminal.
