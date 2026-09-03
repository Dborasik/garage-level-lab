# Testing and field-validation plan

## Automated checks included

### Core self-test

`tools/run_core_self_tests.sh` compiles the platform-independent Java estimator/model utilities and checks:

- pressure-to-relative-altitude math around a 3 m change;
- adjacent level transition;
- skipped/nonexistent level mapping;
- sustained mapped-garage entry detection.

### JUnit

`app/src/test` contains JVM unit tests for altitude math, floor estimation and garage detection. Run through Gradle/Android Studio.

### CI

`.github/workflows/android.yml` runs core self-tests, Android unit tests, lint and debug APK assembly with JDK 17 + Gradle 8.9.

## Required real-device test matrix

Before treating this as more than a prototype, collect ground truth with at least:

- Samsung Galaxy S8 on its supported Android release;
- one recent Samsung flagship;
- one non-Samsung device with a barometer;
- above-ground open-sided garage;
- enclosed above-ground garage;
- underground garage;
- split-level garage;
- continuous spiral/sloped deck if available;
- garage with skipped/odd semantic level labels;
- mapped and poorly mapped OSM cases.

Repeat runs with:

- HVAC high/low/off;
- windows closed/open where safe;
- phone dashboard-mounted vs center console;
- warm/cold weather if possible;
- data network enabled/disabled after initial map load.

## Ground-truth logging procedure

For each run record manually:

- garage name and entrance;
- actual entry level;
- timestamp of crossing threshold;
- every actual level transition timestamp;
- final parked level;
- exit timestamp;
- unusual events (elevator, stop on ramp, window/door opened).

This prototype intentionally does not persist location/sensor traces. For scientific tuning, add an **explicit opt-in export mode** that writes timestamped sensor/estimator rows to app-private storage and allows the tester to export them. Do not silently add telemetry.

## Metrics

### Garage detection

- entry precision/recall;
- false-positive rate per driving hour;
- entry detection latency;
- exit precision/latency;
- performance stratified by mapped vs unmapped fallback.

### Floor estimation

- exact-floor accuracy while parked/stable;
- ±1 physical-floor accuracy;
- transition detection latency;
- time-to-correct after a wrong transition;
- confidence calibration (reliability diagram/Brier score if confidence is to be called probability).

### Pressure

- stable-floor standard deviation;
- apparent drift m/min on stable floor;
- pressure transient from garage door/window/HVAC changes;
- measured altitude difference per known floor.

## Parameter tuning protocol

Do **not** tune and evaluate on the same garages.

Suggested split:

- training/tuning garages: tune smoothing, sigma, entry/exit thresholds and default floor-height behavior;
- validation garages: select final parameter set;
- held-out test garages: report final results only once.

Stratify by garage geometry and device model. Otherwise the estimator will overfit to one structure or one barometer.

## Acceptance targets for a useful follow-up prototype

These are proposed engineering targets, not claims about this version:

- mapped garage entry precision >= 98%;
- mapped garage entry recall >= 95%;
- stable parked exact-floor accuracy >= 95% where topology and entry level are known;
- median level-settling time <= 8 s after leaving a ramp;
- false unmapped-garage activation < 1 per 10 driving hours in representative urban/highway testing.

If these are not met, prioritize false-positive reduction over aggressive detection.

## Static/package review checklist

- minSdk remains 24;
- no accidental background-location permission;
- foreground service type remains `location`;
- no cleartext traffic;
- OSM attribution remains visible;
- Overpass query remains bounded/throttled;
- app does not persist raw location history;
- pressure sensor absence handled without crash;
- relation geometry not flattened into invalid containment polygons;
- estimator cannot invent a mapped nonexistent floor.
