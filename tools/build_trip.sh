#!/bin/zsh
# Builds the app for one trip folder and puts the APK in <trip_dir>/out/.
# Usage: tools/build_trip.sh <trip_dir> [--install]
#   --install  also installs it on the one connected Android device (adb), keeping the app's data.
set -e
REPO=${0:a:h:h}
TRIP=${1:a}
[ -f "$TRIP/MyTrip.kt" ] || { echo "No MyTrip.kt in $TRIP"; exit 1; }
cd "$REPO"
[ -n "$JAVA_HOME" ] || for j in /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
  [ -x "$j/bin/java" ] && export JAVA_HOME="$j" && break
done
# The trip-rules tests run first; a failure names the leg or flight to fix.
./gradlew -q testReleaseUnitTest assembleRelease -PtripDir="$TRIP"
mkdir -p "$TRIP/out"
cp app/build/outputs/apk/release/app-release.apk "$TRIP/out/trip.apk"
echo "Built $TRIP/out/trip.apk"
if [ "$2" = "--install" ]; then
  adb install -r "$TRIP/out/trip.apk"
fi
