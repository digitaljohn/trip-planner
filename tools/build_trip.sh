#!/bin/zsh
# Builds the app for one trip folder and puts it in <trip_dir>/out/.
# Usage: tools/build_trip.sh <trip_dir> [--install | --ios | --ios-install]
#   (none)         Android: out/trip.apk
#   --install      also installs it on the one connected Android phone (adb), keeping the app's data
#   --ios          iPhone (macOS with Xcode): out/Trip.app for the simulator
#   --ios-install  builds for a real iPhone, signed with ios.team, and installs it on the connected one
set -e
REPO=${0:a:h:h}
TRIP=${1:a}
[ -f "$TRIP/MyTrip.kt" ] || { echo "No MyTrip.kt in $TRIP"; exit 1; }
cd "$REPO"
[ -n "$JAVA_HOME" ] || for j in /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
  [ -x "$j/bin/java" ] && export JAVA_HOME="$j" && break
done
mkdir -p "$TRIP/out"

# A trip setting from trip.properties, else local.properties, else $2.
prop() {
  local v
  for f in "$TRIP/trip.properties" "$REPO/local.properties"; do
    [ -f "$f" ] && v=$(grep -E "^$1=" "$f" | tail -1 | cut -d= -f2-) && [ -n "$v" ] && { echo "$v"; return; }
  done
  echo "$2"
}

case "$2" in
  --ios|--ios-install)
    # The trip-rules tests first, against iOS's own time-zone data; a failure names the leg or flight to fix.
    ./gradlew -q :shared:iosSimulatorArm64Test -PtripDir="$TRIP"
    cat > iosApp/Trip.xcconfig <<EOF
TRIP_BUNDLE_ID = $(prop trip.applicationId app.trip.planner)
TRIP_APP_NAME = $(prop trip.appName Trip)
TRIP_DIR = $TRIP
DEVELOPMENT_TEAM = $(prop ios.team "")
EOF
    if [ "$2" = "--ios" ]; then
      xcodebuild -quiet -project iosApp/Trip.xcodeproj -scheme Trip -configuration Release \
        -destination 'generic/platform=iOS Simulator' -derivedDataPath iosApp/build build
      rm -rf "$TRIP/out/Trip.app"
      cp -R iosApp/build/Build/Products/Release-iphonesimulator/Trip.app "$TRIP/out/"
      echo "Built $TRIP/out/Trip.app (simulator)"
    else
      [ -n "$(prop ios.team "")" ] || { echo "Set ios.team (your Apple developer team ID) in local.properties first"; exit 1; }
      xcodebuild -quiet -project iosApp/Trip.xcodeproj -scheme Trip -configuration Release \
        -destination 'generic/platform=iOS' -derivedDataPath iosApp/build -allowProvisioningUpdates build
      rm -rf "$TRIP/out/Trip-iphone.app"
      cp -R iosApp/build/Build/Products/Release-iphoneos/Trip.app "$TRIP/out/Trip-iphone.app"
      DEVICE=$(xcrun devicectl list devices 2>/dev/null | awk '/connected/ && /iPhone/ {print $3; exit}')
      [ -n "$DEVICE" ] || { echo "Built $TRIP/out/Trip-iphone.app, but no connected iPhone found"; exit 1; }
      xcrun devicectl device install app --device "$DEVICE" "$TRIP/out/Trip-iphone.app"
    fi
    ;;
  *)
    # The trip-rules tests run first; a failure names the leg or flight to fix.
    ./gradlew -q testReleaseUnitTest assembleRelease -PtripDir="$TRIP"
    cp app/build/outputs/apk/release/app-release.apk "$TRIP/out/trip.apk"
    echo "Built $TRIP/out/trip.apk"
    if [ "$2" = "--install" ]; then
      adb install -r "$TRIP/out/trip.apk"
    fi
    ;;
esac
