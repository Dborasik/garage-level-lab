# Architecture

## Design goals

- Android 7.0/API 24 through current Android.
- No backend/account required.
- No Google Play Services dependency.
- No third-party runtime dependency.
- All estimator inputs/results visible for field debugging.
- No persisted location trace by default.
- Conservative failure behavior and explicit uncertainty.

## Data flow

```text
Android barometer ──> PressureFilter ──> relative altitude / vertical speed ─┐
                                                                            │
Android GNSS ───────> Location + GnssStatus ──> outdoor baseline / loss ────┤
                                                                            ├─> GarageDetector
OSM/Overpass ───────> GarageRepository ──> candidate topology ──────────────┘       │
                                                                                   │ entry
                                                                                   v
                                                                            FloorEstimator
                                                                                   │
                                                                                   v
                                                                           TelemetryState
                                                                              /        \
                                                                     Activity UI    notification
```

## Components

### `TrackingService`

Foreground service owning Android sensors/location, OSM refresh cadence and estimator lifecycle. It is the only component that mutates the live estimator session.

### `PressureFilter`

Pure Java. Maintains median/EWMA pressure and a bounded in-memory history. Computes pressure noise, vertical-speed regression, historical entry anchor and altitude span.

### `GarageDetector`

Pure Java state machine. Combines map, GNSS and vehicle/vertical evidence. Learns its GNSS baseline outside mapped structures.

### `FloorEstimator`

Pure Java constrained discrete estimator. It maps semantic OSM levels to physical ordinal indices and applies likelihood/transition priors/hysteresis.

### `OverpassGarageRepository`

Single-threaded HTTP repository. Queries nearby garage records, parses relevant OSM tags and associates nearby parking entrances. Multipolygon member paths are retained separately for proximity calculations instead of being incorrectly concatenated into one polygon. It has bounded responses, connect/read timeouts and a second public endpoint fallback. Stale responses are request-tokened so a previous stop/restart session cannot overwrite current state.

### `AppState`

In-process snapshot handoff. The Activity polls an immutable/copied snapshot rather than binding directly to the foreground service. This keeps the prototype simple and avoids lifecycle coupling.

## Threading

- Android sensor and location callbacks are delivered by Android callbacks/registered handler.
- Overpass network I/O occurs on a dedicated single-thread executor.
- UI renders on the main thread every ~500 ms.
- shared garage-list updates are synchronized;
- Overpass completion is marshalled to the service main looper and stale request IDs are discarded;
- estimator/filter public methods are synchronized where stateful.

For this one-screen prototype, the in-process snapshot keeps lifecycle behavior explicit and dependency-light.

## Network behavior

- HTTPS only (`usesCleartextTraffic=false`).
- Public Overpass endpoint.
- Default radius 500 m.
- Successful map data is refreshed after ~120 seconds or ~250 m of movement. Automatic attempts are additionally limited to at most one every 30 seconds so a failed public endpoint cannot be retried on every location callback; manual refresh is explicit.
- No long-term map cache in this prototype.

The public Overpass service is appropriate only for light experimental use; this repository intentionally does not define a deployment architecture beyond the prototype.

## Permissions

- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` (fine/precise is required at runtime by the algorithm)
- `INTERNET`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_LOCATION`
- `POST_NOTIFICATIONS` (Android 13+, runtime request)

No contacts, storage, account, microphone, Bluetooth or background-location permission.

## Failure modes

- **No barometer:** garage detection can still run; floor estimate is disabled. If the first usable pressure sample arrives within five seconds of detected entry, the floor estimator may initialize late; after that window it stays unavailable rather than pretending the late sample represents the entrance.
- **No network/Overpass:** records already loaded during the current tracking session remain; unmapped fallback can still fire conservatively. Stopping tracking clears the in-memory garage/location cache.
- **No OSM levels:** relative broad floor range; UI marks topology incomplete.
- **No levelled entrance:** entry logical level defaults to OSM 0; manual re-anchor can correct it.
- **Bad GNSS:** map proximity plus pressure/motion still contribute; fallback is conservative.
- **Service killed:** `START_NOT_STICKY`; the app does not silently restart location tracking.

## Privacy

The app stores only the floor-height setting (SharedPreferences). Coordinates, GNSS metrics, pressure samples and event logs are process-memory state. Stopping/terminating the process removes them.

Overpass sees the requested geographic bounding box. This behavior is disclosed in `PRIVACY.md`.
