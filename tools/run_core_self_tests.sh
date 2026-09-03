#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/garage-level-core-test"
rm -rf "$OUT"
mkdir -p "$OUT"

javac -d "$OUT" \
  "$ROOT/app/src/main/java/dev/radixen/garagelevel/util/Geo.java" \
  "$ROOT/app/src/main/java/dev/radixen/garagelevel/model/GarageEntrance.java" \
  "$ROOT/app/src/main/java/dev/radixen/garagelevel/model/Garage.java" \
  "$ROOT/app/src/main/java/dev/radixen/garagelevel/estimator/AltitudeMath.java" \
  "$ROOT/app/src/main/java/dev/radixen/garagelevel/estimator/PressureFilter.java" \
  "$ROOT/app/src/main/java/dev/radixen/garagelevel/estimator/FloorEstimator.java" \
  "$ROOT/app/src/main/java/dev/radixen/garagelevel/estimator/GarageDetector.java" \
  "$ROOT/tools/CoreSelfTest.java"

java -cp "$OUT" CoreSelfTest
