---
name: new-trip
description: Re-point this app at a different trip. Use when someone gives new travel details (flights, booking emails, hotel, dates, a pasted itinerary) or asks to "make this my trip", "change the trip", "set this up for my flight to X". Rewrites trip/MyTrip.kt, moves personal numbers into local.properties, adds terminal maps where possible, then builds and checks every leg.
---

# Re-point the app at a new trip

Everything specific to a trip is in `trip/MyTrip.kt`. Read `AGENTS.md` first (especially
**Invariants**), then read `trip/MyTrip.kt` end to end: it's the worked example you'll mirror.

## 1. Gather the details

Ask once, in a single message, for whatever the person hasn't already given. Booking emails and
PDFs are the best source; offer to read them. You need:

- **Flights**: airline, flight numbers, cabin, every leg's airports, local take-off and landing
  times and dates, terminals, and the seat if known.
- **Home and destination**: city names and time zones.
- **Stay**: hotel name, address, room, dates, phone, confirmation number.
- **Places** they'll go (offices, venues) and anything on their mind (visa, plug, transport, SIM).
- **Personal numbers**: booking reference, surname as on the booking, frequent-flyer number and
  balances, e-ticket, visa grant, hotel and loyalty numbers, eSIM details.

Don't invent facts. Where something is unknown, leave it out (most sections are optional: set
`cityMap`, `taxRefund`, `loyalty`, `esim` to `null`) rather than guess. Look up airport procedures,
transit rules and check-in windows from official sources, and say what you looked up.

## 2. Personal numbers go to local.properties, never the repo

For each personal value, use `secret("<key>", "<placeholder>")` in `MyTrip.kt` and write the real
value to `local.properties` as `trip.private.<key>=<value>`. Placeholders should look like the real
format (`"ABC123"`, `"00000000"`). Also set `trip.appName` (short, e.g. the city) and, if they
already have another trip installed, a different `trip.applicationId`. Check `git status` shows no
personal values staged.

## 3. Rewrite trip/MyTrip.kt, section by section

Keep the file's structure and doc comments; replace the content.

1. **Who and where**: `TRIP_TITLE`, `home`, `away` (IANA zone ids), `AWAY_GREETING`,
   `ENTRY_NOTE`, `DISRUPTION_HELP`.
2. **Flights**: `OUT` and `RET` are the UTC take-off instants of the first leg out and the first
   leg back. Convert local times with the airport's zone; double-check the UTC offset on that
   date (DST changes catch people out). Then express every other time as minutes from `OUT`/`RET`
   with `plusMin`. Fill `segments` in flight order with each leg's **local** departure date.
   `airline`, `SEAT_OUT`, `checkIns` (opens 24 h before take-off unless the airline says
   otherwise; `remindAt` the morning of the flight), `journeys` (the Flights tab cards: durations
   as "13h 25m", stopovers as `JourneyStop(code, "1H40", ...)`).
3. **Timeline**: build `legs` first, contiguous, in the PRE / GROUND / AIR / GROUND / AIR / GROUND
   / STAY / ... / POST shape (fewer legs for a direct flight: PRE, GROUND, AIR, GROUND, STAY, ...).
   Then `airports`, one per GROUND leg, with `Airport.leg` = that leg's index and the leg's
   `airport` field set to the same index. Steps are minute offsets with local clock strings
   ("18:00", "~20:20" for estimates). Give every AIR and PRE/POST leg a short `tipTitle`/`tip`.
   Set each GROUND leg's `watch` to the flight it departs on (or `arriving = true` for arrivals),
   and `CHECKED_IN_NOTE`.
4. **Stops**: one `Stop` per departure airport whose terminal is in `Terminals.kt` (check the
   `terminals` map). If a departure terminal isn't there, offer the `add-terminal-map` skill; the
   app works without it. Phases are minutes before take-off: what happens before free time (bag
   drop, security, getting off), when free time starts, when boarding starts, the default walk to
   the gate, and any queue at the gate.
5. **Stay**: `hotel`, `stayCalendar` (start on the Monday of the departure week; tag travel days),
   `stayTips` (jet lag, local events on those dates, day trips, best times to call home),
   `places` (with Google Maps queries), `cityMap` (redraw for the new city, keeping the hotel at
   the routes' start, or set to `null`).
6. **Tools**: `money` (local currency, home currency first in `others`, with rough estimates),
   `taxRefund` (visitor tax refund rules, or `null`).
7. **Info**: `todos` (keep ids `checkin` and `checkin_home` for the two check-ins), `references`,
   `contacts` (include the local emergency number), `CONTACTS_NOTE`, `loyalty`, `esim`.

## 4. Build and check every leg

1. `./gradlew assembleRelease`. Fix any type errors; they're usually a missing field.
2. Install on a headless emulator (`emulator -avd <name> -no-window`), or the person's phone if
   they ask. Don't pop windows on their screen.
3. On Now, turn on **Preview another moment** and tap through every leg chip: title, "Do now",
   countdown and next step must all make sense, and airport legs with a stop must show the
   free-time card with the right local times. Check Flights, Stay, Tools and Info, in light and
   dark mode.
4. `LEGS=<count> tools/screen_text.sh <apk> after.txt <applicationId>` gives every screen's text in
   one file: read it through for leftovers from the old trip (old city, airline, flight numbers).
5. Run `grep -rn` over `trip/` and `app/src` for the old trip's names to be sure none remain
   outside `Terminals.kt` (which is a shared airport library and keeps every airport).

## 5. Hand over

Summarise the trip as the app now has it (flights in local times, stay, stops with maps), list
anything you couldn't confirm, and remind them to add the Aviationstack key to `local.properties`
for live gates.
