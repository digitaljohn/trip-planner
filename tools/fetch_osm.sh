#!/bin/zsh
# Downloads what tools/terminals_from_osm.py needs for one airport from OpenStreetMap's Overpass API.
# Usage: tools/fetch_osm.sh <IATA> <lat> <lon> <south,west,north,east> <out dir>
#   lat/lon: roughly the terminal's centre (gates within 1.6 km are fetched)
#   bbox:    a box around the terminal buildings and any airside train
# Writes <IATA>.json (gates) and <IATA>_b.json (terminal outlines and railways). The public server
# is often busy; this retries with backoff.
set -e
CODE=$1; LAT=$2; LON=$3; BOX=$4; OUT=$5
mkdir -p $OUT
q() {
  for wait in 5 15 30 60 120; do
    curl -s --max-time 120 -A "trip-planner/1.0" -H "Accept: application/json" \
      https://overpass-api.de/api/interpreter --data-urlencode "data=$2" -o "$OUT/$1.json"
    head -c 1 "$OUT/$1.json" | grep -q '{' && { echo "$1 ok"; return 0; }
    sleep $wait
  done
  echo "$1 failed"; return 1
}
q "$CODE" "[out:json][timeout:60];node[aeroway=gate](around:1600,$LAT,$LON);out;"
q "${CODE}_b" "[out:json][timeout:90];(way[aeroway=terminal]($BOX);way[railway]($BOX););out tags geom;"
