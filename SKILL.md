---
name: trip-planner
description: Build someone a personal Android travel app for a specific trip — live "what to do now" timeline, lock-screen status, flight gates and alerts, terminal maps with GPS, hotel, tools. Use when someone gives travel details (booking emails, flights, hotel, an itinerary) or asks for a trip app, to update their trip, or to put it on their phone. Ask, confirm, build, verify, install, remember.
---

# Trip planner

You turn booking details into an Android app that walks one person through one trip. The app is
fixed; the trip is data. You write the data, build, check it, and install it on their phone.

First-time setup is in `install.md`; don't re-run it each session. Paths below are relative to the
directory containing this file (usually `~/Developer/trip-planner`, symlinked as a skill). Read
`AGENTS.md` once per session for the model and the code layout.

## Principles

1. **One folder per trip, outside this repo.** The person runs you from a trip folder such as
   `~/Trips/sydney-2026/`. Everything for that trip lives there; this repo never changes during a
   trip session.
2. **Ask → confirm → build → verify → install → persist.** Never build until they've agreed the
   trip in plain English; never install on their phone without saying so first.
3. **Facts come from their documents or official sources, never guesses.** Unknown is better than
   wrong: leave an optional section out (`null`) rather than invent it.
4. **The example in `trip/MyTrip.kt` is a worked example, not a template to copy blindly.** Its
   steps, tips and wording show what good looks like for one trip; write what fits this one.
5. **Check your own work on screen before showing it.** If a leg reads wrong in preview, fix it
   first.

## Hard rules (break these and the app fails quietly or leaks data)

1. **Never write inside this repo during a trip session.** All trip files go in the trip folder.
   (Improving the app itself is a different job: see `AGENTS.md`.)
2. **Personal numbers only in `<trip>/trip.properties`** as `trip.private.<key>=<value>`, read in
   `MyTrip.kt` with `secret("<key>", "<placeholder>")`. Booking reference + surname is enough to
   change someone's flights; it must never be in a `.kt` file or a repo.
3. **Legs are contiguous** (each leg's `from` is the previous leg's `to`), and every
   `Airport.leg` is the index of its GROUND leg, whose `airport` field is that same index.
4. **`OUT`/`RET` are UTC instants.** Convert from local time with the airport's zone on that
   date; every other time is a minute offset via `plusMin`. Check DST.
5. **`Segment.departDate` is the local take-off date at the origin**, and segments are in flight
   order. A wrong date means no live data.
6. **Each trip gets its own `trip.applicationId`** (`app.trip.<slug>`), so two trips on one phone
   don't overwrite each other. Keep it stable across rebuilds of the same trip, or the app's data
   is lost.
7. **The Aviationstack free plan is 100 lookups a month.** Don't spend lookups testing; the
   person's refresh button and the app's rationed checks are the only spenders.
8. **Verify every leg in preview before installing.** Never declare done from a successful build.

## The trip folder

```
<trip>/
├── MyTrip.kt          ← the trip (start from trip/MyTrip.kt)
├── trip.properties    ← app id, app name, private numbers (never shared)
├── project.md         ← memory: what's been done, decided, and still open; append each session
├── docs/              ← booking emails, PDFs, screenshots they give you (optional)
└── out/trip.apk       ← the built app
```

`trip.properties`:

```
trip.applicationId=app.trip.sydney2026
trip.appName=Sydney
trip.private.booking=...
trip.private.surname=...
```

The machine-wide Aviationstack key lives in this repo's `local.properties` (set at install); a
`trip.properties` value of `aviationstack.key` overrides it for one trip.

## Session flow

### 1. Orient

If `<trip>/project.md` exists, read it and pick up where it left off. Otherwise this is a new trip:
say so and continue.

### 2. Gather

Read anything in the folder (`docs/`, PDFs, `.eml`, screenshots). If they have an email connector,
offer to search for the booking confirmations. Then ask once, in a single message, for what's
still missing:

- Flights: airline, numbers, cabin, every leg's airports, local times and dates, terminals, seat.
- Home and destination: city names, time zones.
- Stay: hotel, address, room, dates, phone, confirmation number.
- Places they'll go, and anything on their mind (visa, plugs, transport, SIM, loyalty scheme).

Look up airport procedures, transit rules, check-in windows, visitor tax refunds and local
emergency numbers from official sources, and note the source in `project.md`.

### 3. Propose

Summarise the trip back in plain English: each flight in local times, connection lengths, the
stay, the places, which departure airports will get terminal maps (those in `Terminals.kt`), and
anything you couldn't confirm. Wait for their OK.

### 4. Write

Copy `trip/MyTrip.kt` into the trip folder if it isn't there, then rewrite it section by section,
keeping its structure and doc comments:

1. **Who and where**: `TRIP_TITLE`, `home`, `away`, `AWAY_GREETING`, `ENTRY_NOTE`,
   `DISRUPTION_HELP`.
2. **Flights**: `OUT`, `RET`, `segments`, `airline`, `SEAT_OUT`, `checkIns` (24 h before
   take-off unless the airline says otherwise; `remindAt` the morning of), `journeys` (Flights tab
   cards; stopovers as `JourneyStop(code, "1H40", ...)`).
3. **Timeline**: `legs` first (PRE, then per journey GROUND/AIR/GROUND/AIR/GROUND, STAY between,
   POST at the end; fewer for direct flights), then `airports` (one per GROUND leg: clock strings,
   "~" for estimates, practical one-line details), `CHECKED_IN_NOTE`. Give AIR, PRE and POST legs a
   short `tipTitle`/`tip` (sleep, stay awake, eat).
4. **Stops**: a `Stop` per departure airport whose terminal is in `Terminals.kt`. Phases are
   minutes before take-off. If a terminal is missing, offer `skills/add-terminal-map/SKILL.md`
   (that one does change this repo, so say so and get their OK).
5. **Stay**: `hotel`, `stayCalendar` (from the Monday of the departure week), `stayTips` (jet
   lag, events on those dates, day trips, calling home), `places` (with Maps queries), `cityMap`
   (redraw for the city with the hotel as the routes' start, or `null`).
6. **Tools**: `money` (home currency first in `others`), `taxRefund` (or `null`).
7. **Info**: `todos` (keep the ids `checkin` and `checkin_home`), `references`, `contacts` (local
   emergency number first), `CONTACTS_NOTE`, `loyalty`, `esim` (each `null` if not relevant).

Write `trip.properties` with the app id, name and every personal value.

### 5. Build and verify

1. `tools/build_trip.sh <trip>` (from this repo). Type errors are usually a missing field.
2. Install on a headless emulator (`emulator -avd <name> -no-window`; nothing should pop up on
   their screen): `adb -s emulator-5554 install -r <trip>/out/trip.apk`.
3. On Now, turn on **Preview another moment** and tap every leg chip: title, "Do now",
   countdown and next step must all read right, and departure legs with a stop must show the
   free-time card in the right local times. Check Flights, Stay, Tools and Info, in light and dark
   (`adb shell cmd uimode night yes|no`). Screenshot with `adb exec-out screencap -p` and look.
4. `LEGS=<n> tools/screen_text.sh <trip>/out/trip.apk <trip>/out/screens.txt <applicationId>`
   dumps every screen's text: read it for leftovers from the example (other cities, airlines,
   flight numbers, hotels).

### 6. Install

Tell them it's ready and ask before installing on their phone. They need USB debugging on
(Settings → About phone → tap Build number 7 times; then Developer options → USB debugging) and
the phone plugged in and unlocked. Then `tools/build_trip.sh <trip> --install`, open the app, and
check the Now screen shows the right moment.

### 7. Persist

Append to `<trip>/project.md`: date, what was built, decisions and their reasons, sources looked
up, open questions, and what to check nearer the time (gates, check-in opening). Keep it short and
factual; it's for the next session.

## Later sessions

"Update the hotel", "my flight changed", "add a todo": read `project.md`, edit `MyTrip.kt` or
`trip.properties`, rebuild, re-verify the legs that changed, reinstall (same app id keeps their
check-ins and settings), and append to `project.md`.
