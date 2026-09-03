package dev.radixen.garagelevel;

import android.location.Location;
import android.os.SystemClock;

import dev.radixen.garagelevel.data.OverpassGarageRepository;
import dev.radixen.garagelevel.model.Garage;
import dev.radixen.garagelevel.util.Geo;

import java.util.List;

abstract class TrackingServiceOsm extends TrackingServiceBase {
    protected void maybeFetchOsm(Location location, boolean force) {
        long now = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtime();
        double moved = Double.isFinite(lastFetchLat)
                ? Geo.distanceMeters(lastFetchLat, lastFetchLon, location.getLatitude(), location.getLongitude())
                : Double.POSITIVE_INFINITY;
        boolean stale = now - lastOsmFetchMillis >= OSM_MIN_REFRESH_MS;
        if (!force && !stale && moved < OSM_REFRESH_DISTANCE_M) return;
        if (!force && lastOsmAttemptElapsed > 0L
                && elapsed - lastOsmAttemptElapsed < OSM_MIN_ATTEMPT_INTERVAL_MS) return;
        if (osmFetchInFlight) return;
        osmFetchInFlight = true;
        lastOsmAttemptElapsed = elapsed;
        final long requestId = ++osmRequestSerial;
        activeOsmRequestId = requestId;
        state.osmStatus = "Querying nearby garages…";
        state.lastOsmError = "—";
        publish();

        int radius = state.osmQueryRadiusMeters;
        garageRepository.fetchNearby(location.getLatitude(), location.getLongitude(), radius, new OverpassGarageRepository.Callback() {
            @Override
            public void onSuccess(List<Garage> garages, int entranceCount) {
                mainHandler.post(() -> {
                    if (destroyed || requestId != activeOsmRequestId || !state.tracking) return;
                    synchronized (nearbyGarages) {
                        nearbyGarages.clear();
                        nearbyGarages.addAll(garages);
                    }
                    lastOsmFetchMillis = System.currentTimeMillis();
                    lastFetchLat = location.getLatitude();
                    lastFetchLon = location.getLongitude();
                    osmFetchInFlight = false;
                    state.nearbyGarages = garages.size();
                    state.nearbyEntrances = entranceCount;
                    state.lastOsmFetchMillis = lastOsmFetchMillis;
                    state.osmStatus = "Loaded " + garages.size() + " garage candidate(s)";
                    debugLog.add("OSM refresh: " + garages.size() + " garage(s), " + entranceCount + " entrance(s)");
                    if (state.tracking && lastLocation != null) evaluateGarage(lastLocation);
                    publish();
                });
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> {
                    if (destroyed || requestId != activeOsmRequestId || !state.tracking) return;
                    osmFetchInFlight = false;
                    state.lastOsmError = message == null ? "Unknown error" : message;
                    state.osmStatus = "OSM query failed; retaining prior in-memory data";
                    debugLog.add("OSM error: " + state.lastOsmError);
                    publish();
                });
            }
        });
    }

    protected void forceRefreshOsm() {
        if (!state.tracking) {
            debugLog.add("OSM refresh ignored: tracking is stopped");
            publish();
            stopSelf();
            return;
        }
        if (lastLocation == null) {
            debugLog.add("OSM refresh requested before first location fix");
            publish();
            return;
        }
        lastOsmFetchMillis = 0L;
        maybeFetchOsm(lastLocation, true);
    }
}
