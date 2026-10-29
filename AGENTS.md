# Trip planner: guide for coding agents

An Android app (Kotlin, Jetpack Compose, Material 3) that walks one person through one trip:
a live "what to do now" screen, a lock-screen Live Update, flight status with gate alerts,
terminal maps with GPS, the stay, and travel tools. It is built to be cloned and re-pointed at a
new trip by editing data, not code.

## Where things live

| Path | What it is |
|------|------------|
| `trip/MyTrip.kt` | **The trip.** Every name, time, flight, step, place and note. Usually the only file a new trip touches. |
| `local.properties` | Git-ignored. SDK path, API key, app id/name, and every personal number (`trip.private.*`). |
| `app/src/main/java/app/trip/TripModel.kt` | The types `MyTrip.kt` is written in, plus values derived from it. |
| `.../Timeline.kt` | Steps, legs and `statusAt()`: which leg you're in and what to do now. |
| `.../Terminals.kt` | The airport terminals the app can map (spots, routes, notes), keyed by IATA code. |
| `.../TerminalData.kt` | Generated outlines and gate positions. Don't edit; regenerate with `tools/terminals_from_osm.py`. |
| `.../Net.kt` | Aviationstack flight status (quota-rationed) and exchange rates. |
| `.../Notifier.kt` | Lock-screen status, step alerts, check-in alerts, gate/delay/belt alerts, alarms. |
| `.../Ui.kt`, `Flights.kt`, `Stay.kt`, `Tools.kt`, `Terminal.kt` | Screens. |
| `.claude/skills/` | `new-trip` (re-point the app at a new trip) and `add-terminal-map`. |
| `tools/` | Map generator, OSM fetcher, and the screen-text regression check. |

## The model in one paragraph

Two journeys: outbound (take-off `OUT`) and return (take-off `RET`). Every `Step` and `Leg` time
is a minute offset from one of those. `legs` covers the whole trip back to back:
`PRE`, then per journey `GROUND` (departure airport) → `AIR` → `GROUND` (connection) → `AIR` →
`GROUND` (arrival), a `STAY` between the journeys, and `POST`. Each `Airport` in `airports` holds
the steps for one `GROUND` leg and names that leg by index. `segments` is the list of flight legs
that live data, alerts and check timings key off. `stops` adds the free-time card and terminal map
to departure airports whose terminal exists in `Terminals.kt`.

## Invariants (break these and something fails quietly)

- **Legs are contiguous**: each leg's `from` equals the previous leg's `to`; only `PRE.from` and
  `POST.to` are null. `statusAt()` picks the first leg containing now.
- **`Airport.leg` is the index of a `GROUND` leg** in `legs`, and that leg's `airport` field is the
  same index. Reordering legs means renumbering both.
- **Segments are in flight order.** A segment whose flight number matches the one before it is a
  connection (fewer quota checks, no belt alert); the last segment of each flight number is where
  bags come off.
- **`Segment.departDate` is the local take-off date at the origin.** Aviationstack returns several
  days of the same flight number; this picks the right one. A wrong date means no live data.
- **`Watch(flight, from, arriving)` must name a real segment key** (`"$flight:$from"`).
- **Personal numbers go through `secret("key", "placeholder")`** and live in `local.properties` as
  `trip.private.key=value`. Never write a real booking reference, membership number, passport,
  visa grant, e-ticket or confirmation code into the repo: a booking reference plus surname is
  enough to change someone's flights.
- **Engine code that reads trip values must be `by lazy`.** `MyTrip.kt` calls into engine files
  while it is being built; an eager top-level value there that reads trip data sees nulls and the
  app crashes on launch.
- **The Aviationstack free plan is 100 lookups a month** and is counted in the app. Don't add
  automatic fetches outside `FlightRepo.due()`; the refresh button is the only other spender.

## Build and run

```
./gradlew assembleRelease          # JDK 17; signed with the debug key
adb install -r app/build/outputs/apk/release/app-release.apk
```

`local.properties` needs `sdk.dir`; everything else has a working default (see
`local.properties.example`). With no keys the app builds and runs with placeholder numbers and no
live flight data.

## Verifying a change

1. Build. The trip file is type-checked, so most data mistakes stop here.
2. Install on an emulator (start it with `-no-window` so nothing pops up on the user's screen).
3. On Now, turn on **Preview another moment** and tap every leg chip. Each leg should show the
   right title, "Do now" text, countdown and next step; airport legs with a terminal show the
   free-time card. Then check Flights, Stay, Tools and Info.
4. Check light and dark (`adb shell cmd uimode night yes|no`).
5. For refactors, prove nothing changed: `tools/screen_text.sh old.apk old.txt`, then the same for
   the new build, and compare per screen (see the script's header). Differences must be explained.

## Style

Comments say what the code can't: invariants, units, ordering constraints, why the obvious approach
fails. No change narration. Match the surrounding code; don't run formatters over whole files.
