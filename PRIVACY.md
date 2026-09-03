# Privacy

Garage Level Lab is a local Android research prototype.

## Stored on the device

The app persists only user-selected settings such as the configured floor-height override.

It does **not** persist raw histories of:

- latitude/longitude;
- GNSS measurements;
- accelerometer or gyroscope samples;
- barometric pressure;
- inferred garage visits.

Live telemetry and recent estimator events are held in process memory and disappear when tracking/process state is cleared.

## Network requests

The app sends a bounded geographic query around the current position to public OpenStreetMap Overpass infrastructure in order to retrieve nearby parking-garage map data. That necessarily reveals the approximate query area to the selected Overpass operator.

The app otherwise has no project backend, account system, analytics SDK or advertising SDK.

## Android permissions

The app uses precise location while its visible foreground tracking service is active. It does not request Android background-location permission.

## Field reports

If you publish an issue or test report, remove exact routes/locations and any other information you do not want made public.
