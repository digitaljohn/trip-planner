#!/bin/zsh
# Records every visible text in the app, screen by screen, on a running emulator (1080×2400).
# Usage: LEGS=<number of legs in MyTrip.kt> tools/screen_text.sh <apk> <out.txt> [applicationId]
# Compare two runs with: python3 tools/compare_screen_text.py before.txt after.txt
# Run it on the build before and after a change, then compare each "=== section" as a set of lines:
# anything only in one file is a difference to explain. Clock-driven text (countdowns, "Updated")
# will differ if the runs are minutes apart. Reinstalls the app, so local app data is wiped.
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
S=$(mktemp -d)
APK=$1; OUT=$2; PKG=${3:-app.trip.planner}; LEGS=${LEGS:-13}; : > $OUT

dump() { # appends visible text, one line per node, with a section header
  adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
  adb pull -q /sdcard/u.xml $S/u.xml
  python3 -I -c "
import re,html,sys
s=open(sys.argv[1]).read()
for m in re.finditer(r'text=\"([^\"]*)\"',s):
  t=html.unescape(m.group(1)).strip()
  if t: print(t)" $S/u.xml >> $OUT
}
section() { echo "=== $1" >> $OUT; }
scrolls() { for i in $(seq 1 $1); do adb shell input swipe 540 1500 540 600 400; sleep 1.2; dump; done; }
tab() { adb shell input tap $1 2270; sleep 2; for i in 1 2 3 4 5 6; do adb shell input swipe 540 500 540 1700 150; done; sleep 1; }

adb uninstall $PKG >/dev/null 2>&1
adb install -r $APK >/dev/null
for p in POST_NOTIFICATIONS ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do adb shell pm grant $PKG android.permission.$p; done
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 7

# Now, at the middle of every leg via the preview slider
for i in 1 2 3; do adb shell input swipe 540 1600 540 300 250; sleep 0.6; done
adb shell input tap 960 1945; sleep 2
for k in $(seq 0 $((LEGS - 1))); do
  x=$(( 96 + (k * 2 + 1) * (996 - 96) / (LEGS * 2) ))
  adb shell input tap $x 1916; sleep 2.5
  for i in 1 2 3 4; do adb shell input swipe 540 400 540 1300 150; done; sleep 1.5
  section "now leg $k"; dump
  adb shell input swipe 540 1350 540 450 400; sleep 1.2; dump
  adb shell input swipe 540 1350 540 450 400; sleep 1.2; dump
done
adb shell input keyevent KEYCODE_BACK; sleep 1

adb shell am force-stop $PKG; adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 7
section flights; tab 320; dump; scrolls 7
section stay; tab 540; dump; scrolls 5
section tools; tab 760; dump; scrolls 4
section info; tab 980; dump; scrolls 9
adb shell dumpsys notification --noredact | grep -E "android\.(title|text|bigText|subText)=" | grep -v "USB\|Charging\|Android System" | sed 's/^ *//' | sed "1s/^/=== notification\n/" >> $OUT
