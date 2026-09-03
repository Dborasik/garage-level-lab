#!/usr/bin/env python3
"""Dependency-free structural checks that do not require an Android SDK."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
errors = []

for xml_path in (ROOT / "app/src/main").rglob("*.xml"):
    try:
        ET.parse(xml_path)
    except Exception as exc:
        errors.append(f"invalid XML {xml_path.relative_to(ROOT)}: {exc}")

manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
required = [
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.FOREGROUND_SERVICE',
    'android.permission.FOREGROUND_SERVICE_LOCATION',
    'android:foregroundServiceType="location"',
    'android:usesCleartextTraffic="false"',
]
for text in required:
    if text not in manifest:
        errors.append(f"manifest missing {text}")
if 'android.permission.ACCESS_BACKGROUND_LOCATION' in manifest:
    errors.append("background location permission must not be present")

app_gradle = (ROOT / "app/build.gradle").read_text()
if not re.search(r"\bminSdk\s+24\b", app_gradle):
    errors.append("minSdk is not 24")

layout = (ROOT / "app/src/main/res/layout/activity_main.xml").read_text()
for id_name in ["tvPrimary", "tvGarage", "tvFloor", "tvBarometer", "tvLocation", "tvMotion", "tvData", "tvEvents"]:
    if f"@+id/{id_name}" not in layout:
        errors.append(f"diagnostic view missing: {id_name}")

repo = (ROOT / "app/src/main/java/dev/radixen/garagelevel/data/OverpassGarageRepository.java").read_text()
if 'https://overpass-api.de/api/interpreter' not in repo:
    errors.append("primary Overpass HTTPS endpoint missing")
if 'https://overpass.private.coffee/api/interpreter' not in repo:
    errors.append("secondary Overpass HTTPS endpoint missing")
if 'building:min_level' in repo:
    errors.append("OSM building:min_level must not be treated as indoor min_level")
if 'parseStrictInt(tags.optString("level"' not in repo:
    errors.append("parking entrance level must use strict integer parsing")

tracking_dir = ROOT / "app/src/main/java/dev/radixen/garagelevel"
tracking = "\n".join(path.read_text() for path in sorted(tracking_dir.glob("TrackingService*.java")))
for required_text in [
    'SystemClock.elapsedRealtime()',
    'locationFixElapsedRealtimeNanos',
    'requestId != activeOsmRequestId',
    'clearOsmCache()',
    'invalidateOsmRequest()',
]:
    if required_text not in tracking:
        errors.append(f"tracking hardening missing: {required_text}")

altitude = (ROOT / "app/src/main/java/dev/radixen/garagelevel/estimator/AltitudeMath.java").read_text()
if 'currentPressureHpa / baselinePressureHpa' not in altitude:
    errors.append("relative altitude is not using the session pressure ratio")

if errors:
    print("Project structural checks FAILED:")
    for e in errors:
        print(" -", e)
    sys.exit(1)
print("Project structural checks passed.")
