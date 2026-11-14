# Trip planner: guide for coding agents

An app for Android and iPhone (Kotlin Multiplatform, Compose Multiplatform, Material 3) that walks
one person through one trip: a live "what to do now" screen, a lock-screen status (Android Live
Update, iOS Live Activity), flight status with gate alerts, terminal maps with GPS, the stay, and
travel tools. Both platforms run the same screens and logic from `shared/`; only the system parts
(notifications, lock screen, widget, links to other apps) are per platform. The app is fixed; each trip is data in its own
folder outside this repo. This file is for changing the app itself. Planning a trip is `SKILL.md`;
installing is `install.md`.

## Where things live

| Path | What it is |
|------|------------|
| `trip/MyTrip.kt` | The worked example trip. Real trips live in their own folder (`<trip>/MyTrip.kt` plus `trip.properties`), built with `-PtripDir=<trip>` or `tools/build_trip.sh`. |
| `local.properties` | Git-ignored, per machine: SDK path, Aviationstack key, `ios.team`. A trip's `trip.properties` adds and overrides (app id, name, `trip.private.*`). |
| `shared/src/commonMain/kotlin/app/trip/TripModel.kt` | The types `MyTrip.kt` is written in, plus values derived from it. |
| `.../Timeline.kt` | Steps, legs and `statusAt()`: which leg you're in and what to do now. |
| `.../Schedule.kt` | Which flight to watch and when to look it up, alert moments, and which gate/delay/belt alerts fresh data calls for. |
| `.../Terminals.kt` | The airport terminals the app can map (spots, routes, notes), keyed by IATA code. |
| `.../TerminalData.kt` | Generated outlines and gate positions. Don't edit; regenerate with `tools/terminals_from_osm.py`. |
| `.../Net.kt` | Aviationstack flight status (quota-rationed) and exchange rates. |
| `.../Weather.kt` | Open-Meteo forecast for the away city (no key; found by name and time zone). |
| `.../Ui.kt`, `Flights.kt`, `Stay.kt`, `Tools.kt`, `Terminal.kt` | Screens. |
| `.../Platform.kt` | What each platform supplies (`expect`): storage, HTTP, formatting, other apps (`Phone`), haptics, location, and `Alerts`. |
| `.../Time.kt` | java.time's names (`Instant`, `Duration`, `LocalDate`, `ZoneId` and their methods) over kotlin.time and kotlinx-datetime, so trips and the engine read the same on both platforms. |
| `shared/src/androidMain/` | Android's side: `Notifier.kt` (Live Update, step/check-in/gate alerts, alarm chain), `TripWidget.kt`, `MainActivity.kt`, `Platform.android.kt`, resources. |
| `shared/src/iosMain/` | iOS's side: `IosAlerts.kt` (scheduled notifications, Live Activity state, widget timeline, background refresh), `Platform.ios.kt`. |
| `app/` | The Android app shell: app id and name only. |
| `iosApp/` | The iPhone app shell (SwiftUI), the widget extension (home-screen widget and Live Activity), and the Xcode project generated from `project.yml` with `xcodegen`. |
| `SKILL.md`, `install.md` | The agent skill (plan, build, verify, install a trip) and its first-time setup. |
| `skills/add-terminal-map/` | Sub-skill: add an airport terminal to the maps. |
| `tools/` | `build_trip.sh`, the map generator and OSM fetcher, and the screen-text regression check. |

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
- **Each `Segment` carries its origin's time zone.** Aviationstack returns several days of the same
  flight number; the local take-off date worked out from it picks ours. A wrong zone means no live data.
- **`Watch(flight, from, arriving)` must name a real segment key** (`"$flight:$from"`).
- **Personal numbers go through `secret("key", "placeholder")`** and live in the trip's
  `trip.properties` (or `local.properties`) as `trip.private.key=value`. Never write a real booking reference, membership number, passport,
  visa grant, e-ticket or confirmation code into the repo: a booking reference plus surname is
  enough to change someone's flights.
- **Engine code that reads trip values must be `by lazy`.** `MyTrip.kt` calls into engine files
  while it is being built; an eager top-level value there that reads trip data sees nulls and the
  app crashes on launch.
- **The Aviationstack free plan is 100 lookups a month** and is counted in the app. Don't add
  automatic fetches outside `FlightRepo.due()`; the refresh button is the only other spender.
- **Shared code calls `Clock.System.now()`, not `Instant.now()`.** `kotlin.time.Instant` has a
  deprecated `now()` member that beats any extension, so `Time.kt` can't map that one.
- **Android's stores are its old SharedPreferences files** (same names and keys), so an update keeps
  an installed app's check-ins, balances and cache. Don't rename a `store()` area or key.
- **The iOS Live Activity and widget are drawn in Swift from JSON** written by `IosAlerts.kt`
  (`liveState`, `widgetTimeline`); a field renamed on one side must be renamed on the other
  (`iosApp/Shared/TripActivity.swift`, `iosApp/Widgets/TripWidgets.swift`).

## Build and run

```
tools/build_trip.sh trip                     # the example; JDK 17, signed with the debug key
tools/build_trip.sh ~/Trips/x --install      # a trip folder, installed on the connected Android phone
tools/build_trip.sh trip --ios               # the iPhone app for the simulator (Xcode)
tools/build_trip.sh ~/Trips/x --ios-install  # signed with ios.team, installed on the connected iPhone
```

`local.properties` needs `sdk.dir`; everything else has a working default (see
`local.properties.example`). With no keys the app builds and runs with placeholder numbers and no
live flight data. The iOS build writes `iosApp/Trip.xcconfig` (git-ignored) with the trip's id, name,
folder and team; Xcode's "Kotlin" build phase compiles `shared` for it. After editing
`iosApp/project.yml`, run `xcodegen` in `iosApp/` and commit the regenerated project.

## Verifying a change

1. `./gradlew testReleaseUnitTest` runs `TripRulesTest` (the hard rules in `SKILL.md`, plus a
   15-minute walk through the whole trip) against the example, or a trip with `-PtripDir`;
   `:shared:iosSimulatorArm64Test` runs it on iOS. CI runs both, builds both apps, and scans history
   for secrets. Then build; the trip file is type-checked, so most data mistakes stop there.
2. Install on an emulator (start it with `-no-window` so nothing pops up on the user's screen).
3. On Now, turn on **Preview another moment** and tap every leg chip. Each leg should show the
   right title, "Do now" text, countdown and next step; airport legs with a terminal show the
   free-time card. Then check Flights, Stay, Tools and Info.
4. Check light and dark (`adb shell cmd uimode night yes|no`).
5. For iOS, the same on a simulator booted with `xcrun simctl boot` (no window opens), with
   `xcrun simctl ui <device> appearance dark|light`; screenshots with `xcrun simctl io <device> screenshot`.
6. For refactors, prove nothing changed: `tools/screen_text.sh old.apk old.txt`, then the same for
   the new build, and compare per screen (see the script's header). Differences must be explained.

## Style

Comments say what the code can't: invariants, units, ordering constraints, why the obvious approach
fails. No change narration. Match the surrounding code; don't run formatters over whole files.
