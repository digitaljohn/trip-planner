---
name: trip-planner-install
description: Install trip-planner into the current agent (Claude Code, Codex, or another with shell access), check the Android build tools, register the skill, and set up the Aviationstack key, so the person can start planning a trip.
---

# trip-planner install

Use this file only for first-time install or to repair one. For planning trips, read `SKILL.md`.

## What you're setting up

A skill that builds a personal Android travel app for one trip. After install, the person makes a
folder for a trip, runs their agent there, and says "set up my Sydney trip" (or pastes booking
details). You do the rest from `SKILL.md`.

Four things must be true on this machine:

1. The repo is cloned to a stable path (`~/Developer/trip-planner`).
2. JDK 17 and the Android SDK are available, and the example trip builds.
3. The skill is registered with the current agent.
4. An Aviationstack key is in `local.properties` (optional: without it the app has no live gates).

## Install contract

- Do everything yourself. Ask only for what you can't do: approval before installing large
  software (the Android SDK is a few gigabytes), the API key, and anything needing their password.
- Prefer a stable clone path, not `/tmp` or `~/Downloads`.
- Never print an API key back in tool output. Never commit `local.properties`.
- Prove it works by building the example trip. Don't declare success from files existing.
- Don't start planning a trip during install. Finish, hand over, and wait.

## Steps

### 1. Clone

```bash
test -d ~/Developer/trip-planner || git clone https://github.com/digitaljohn/trip-planner ~/Developer/trip-planner
cd ~/Developer/trip-planner && git pull --ff-only
```

### 2. Java 17

Check in this order and stop at the first that works: `$JAVA_HOME` (version 17+), Homebrew
`/opt/homebrew/opt/openjdk@17` or `/usr/local/opt/openjdk@17`, Android Studio's bundled JDK
(`/Applications/Android Studio.app/Contents/jbr/Contents/Home`). If none, ask, then
`brew install openjdk@17` (macOS) or the distribution's `openjdk-17-jdk`.

### 3. Android SDK

Find an existing SDK first: `$ANDROID_HOME`, `$ANDROID_SDK_ROOT`, `~/Library/Android/sdk`
(Android Studio on macOS), `~/Android/Sdk` (Linux). If one exists, use it.

If not, tell them it's a few-gigabyte download and ask before installing. Then either:

- They install Android Studio themselves (simplest; it sets up the SDK on first launch), or
- You install the command-line tools:

  ```bash
  brew install --cask android-commandlinetools
  export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
  yes | sdkmanager --licenses
  sdkmanager "platforms;android-36" "build-tools;35.0.0" "platform-tools"
  ```

  On Linux, download the command-line tools zip from developer.android.com, then the same
  `sdkmanager` lines.

`adb` comes with platform-tools; make sure it's on `PATH` (`$ANDROID_HOME/platform-tools`).

Write the SDK path into the repo's git-ignored `local.properties`:

```bash
cd ~/Developer/trip-planner
grep -q '^sdk.dir=' local.properties 2>/dev/null || echo "sdk.dir=<path to the SDK>" >> local.properties
```

### 4. Register the skill

The whole repo is the skill: symlink the directory, not just `SKILL.md`, because the tools and
the example trip need to sit beside it.

- Claude Code: `mkdir -p ~/.claude/skills && ln -sfn ~/Developer/trip-planner ~/.claude/skills/trip-planner`
- Codex: `mkdir -p "${CODEX_HOME:-$HOME/.codex}/skills" && ln -sfn ~/Developer/trip-planner "${CODEX_HOME:-$HOME/.codex}/skills/trip-planner"`
- Another agent: symlink into its skills directory as `trip-planner`, or import
  `~/Developer/trip-planner/SKILL.md` from its system prompt or config.

If you can't tell which agent you're running under, ask once.

### 5. Aviationstack key (live gates and delays)

Check whether `local.properties` already has a non-empty `aviationstack.key=`. If not, ask once:

> For live gates, delays and baggage belts I need a free Aviationstack key (100 lookups a month,
> no card): sign up for the Free plan at https://aviationstack.com/pricing and paste the key here, or say "skip"
> and the app will work without live flight data.

Write it without echoing it:

```bash
printf 'aviationstack.key=%s\n' "$KEY" >> ~/Developer/trip-planner/local.properties
chmod 600 ~/Developer/trip-planner/local.properties
```

Don't test the key with a lookup: each one spends a real quota unit. It's checked the first time
someone taps refresh in the app.

### 6. Verify

Build the example trip. This downloads Gradle and the app's dependencies the first time (a few
minutes):

```bash
cd ~/Developer/trip-planner && tools/build_trip.sh trip
```

`Built …/trip/out/trip.apk` means everything works (`trip/out/` is git-ignored).

### 7. Hand over

Tell them, in one short message:

- It's installed at `~/Developer/trip-planner`.
- To start: make a folder for the trip (e.g. `~/Trips/sydney-2026`), optionally drop booking
  emails or PDFs into it, open their agent there, and say something like *"set up my trip"*.
- To put the app on their phone they'll need USB debugging on; you'll walk them through it when
  the app is ready.

## Keeping it current

`cd ~/Developer/trip-planner && git pull --ff-only`. The symlink picks it up. Trip folders are
untouched by updates; rebuild a trip to get the new app version.

## Cold-start reminders

- `sdk.dir` in `local.properties` must point at an SDK with platform 36; the build says so if not.
- The first Gradle build needs internet; later builds work offline.
- A phone shows "unauthorized" in `adb devices` until they tap Allow on the USB debugging prompt.
