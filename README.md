# Trip planner

A personal Android travel companion you re-point at your own trip by editing one data file,
ideally with Claude doing the editing. It ships with a made-up London → Singapore → Sydney trip as
the worked example.

- **Now**: where you are in the trip, what to do, a countdown to the next step, and a free-time
  budget at each departure airport.
- **Lock screen**: an Android 16 Live Update with the current step, gate or belt, and progress.
- **Live flights**: gates, delays and belts from Aviationstack's free plan, with alerts when a gate
  is announced or changes, plus an explicit refresh button. Lookups are rationed to the quota.
- **Terminal maps**: real terminal outlines and gates from OpenStreetMap, with your GPS position,
  the route to your gate, trains between terminals, food, showers and smoking areas.
- **Stay, tools and info**: the hotel, a calendar and sketch map, currency, time and tax-refund
  tools, check-in links and alerts, references, contacts, loyalty and eSIM cards.

## Make it your trip

1. Clone, open the folder in Claude Code, and say something like *"set this up for my trip"*,
   then paste or attach your booking emails. The `new-trip` skill rewrites `trip/MyTrip.kt`, keeps
   your personal numbers out of the repo, adds terminal maps where it can, and checks every leg.
2. Or edit `trip/MyTrip.kt` yourself: it's the only file a new trip needs, and its comments explain
   each section. `AGENTS.md` covers the model and the rules that keep it working.

Personal numbers (booking reference, surname, membership and confirmation numbers) live in the
git-ignored `local.properties` as `trip.private.*` and reach the app through `secret()`. A fresh
clone shows placeholders.

## Build

Needs JDK 17 and the Android SDK. Copy `local.properties.example` to `local.properties`, set
`sdk.dir`, and add a free Aviationstack key for live flight data. Then:

```
./gradlew assembleRelease
```

The APK is in `app/build/outputs/apk/release/`, signed with the debug key for side-loading.

## Layout

```
trip/MyTrip.kt            the trip: the file you edit
app/src/main/java/app/trip/
  TripModel.kt            the types MyTrip.kt is written in
  Timeline.kt             legs, steps and "what to do now"
  Terminals.kt            airport terminals the maps know
  TerminalData.kt         generated from OpenStreetMap
  Net.kt, Notifier.kt     live data, alerts, lock screen
  Ui.kt, Flights.kt, Stay.kt, Tools.kt, Terminal.kt   screens
tools/                    map generator, OSM fetcher, screen-text regression check
.claude/skills/           new-trip, add-terminal-map
```

Map data © OpenStreetMap contributors (ODbL). Exchange rates by ExchangeRate-API.
