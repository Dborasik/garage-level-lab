package dev.radixen.garagelevel;

import android.location.Location;
import android.os.SystemClock;

import dev.radixen.garagelevel.estimator.AltitudeMath;
import dev.radixen.garagelevel.estimator.FloorEstimator;
import dev.radixen.garagelevel.estimator.GarageDetector;
import dev.radixen.garagelevel.model.Garage;
import dev.radixen.garagelevel.model.GarageEntrance;
import dev.radixen.garagelevel.util.Geo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

abstract class TrackingServiceSession extends TrackingServiceOsm {
    @Override
    protected void evaluateGarage(Location location) {
        long now = SystemClock.elapsedRealtime();
        double altitudeSpan = pressureFilter.altitudeSpanMeters(now, 20_000L);
        List<Garage> copy;
        synchronized (nearbyGarages) { copy = new ArrayList<>(nearbyGarages); }
        GarageDetector.Observation observation = garageDetector.update(
                location.getLatitude(), location.getLongitude(),
                location.hasAccuracy() ? location.getAccuracy() : 99f,
                state.satellitesUsed, state.averageCn0DbHz,
                location.hasSpeed() ? location.getSpeed() : 0f,
                altitudeSpan, copy);

        state.garageDetected = observation.detected;
        state.garageConfidence = observation.confidence;
        state.garageReason = observation.reason;
        state.garageDistanceMeters = observation.distanceMeters;

        if (observation.justEntered) {
            activeGarage = observation.garage;
            garageEntryElapsed = SystemClock.elapsedRealtime();
            beginFloorSession(location, observation.fallbackUnmapped);
        } else if (observation.detected && activeGarage == null && observation.garage != null
                && !observation.fallbackUnmapped) {
            activeGarage = observation.garage;
            promoteFallbackToMappedGarage();
        } else if (observation.justExited) {
            debugLog.add("Garage exit detected");
            activeGarage = null;
            garageEntryElapsed = 0L;
            floorEstimator.stop();
            pressureBaselineHpa = Double.NaN;
            state.floorAvailable = false;
            state.floorConfidence = 0.0;
            state.transitioning = false;
            state.entryLevelSource = "—";
            state.entryLevelConfidence = 0.0;
            floorAnchorConfidence = 0.0;
            state.levelLabel = "—";
            state.validLevels = "unknown";
            state.garageName = "—";
            state.garageId = "—";
            state.garageType = "—";
            state.garageLevelMetadata = "unknown";
            state.levelSource = "—";
            state.floorHeightMeters = configuredFloorHeightMeters;
            state.floorHeightSource = configuredFloorHeightSource;
            state.pressureBaselineHpa = Double.NaN;
            state.relativeAltitudeMeters = Double.NaN;
        } else if (observation.detected && activeGarage != null) {
            fillGarageState(activeGarage);
        }
    }

    protected void beginFloorSession(Location location, boolean fallbackUnmapped) {
        long now = SystemClock.elapsedRealtime();
        double baseline = Double.NaN;
        if (pressureFilter.hasRecentSample(now, 5_000L)) {
            baseline = pressureFilter.historicalPressure(now, 3L);
            if (!Double.isFinite(baseline)) baseline = pressureFilter.filteredPressure();
        }
        pressureBaselineHpa = baseline;
        state.pressureBaselineHpa = baseline;

        int entryLevel = 0;
        floorAnchorConfidence = fallbackUnmapped ? 0.35 : 0.50;
        state.entryLevelSource = fallbackUnmapped ? "assumed logical 0; garage unmapped" : "assumed OSM logical 0";
        List<Integer> validLevels;
        Map<Integer, String> refs = Collections.emptyMap();
        double floorHeight = configuredFloorHeightMeters;
        String heightSource = configuredFloorHeightSource;

        if (activeGarage != null) {
            EntranceMatch entranceMatch = nearestLevelledEntrance(activeGarage, location);
            if (entranceMatch != null && entranceMatch.entrance.logicalLevel != null) {
                GarageEntrance entrance = entranceMatch.entrance;
                entryLevel = entrance.logicalLevel;
                floorAnchorConfidence = entranceMatch.confidence;
                state.entryLevelSource = (entrance.levelRef == null
                        ? "OSM parking_entrance level"
                        : "OSM parking_entrance level/ref " + entrance.levelRef)
                        + " (" + Math.round(entranceMatch.distanceMeters) + " m away)";
            } else if (activeGarage.topologyKnown()) {
                floorAnchorConfidence = 0.62;
                state.entryLevelSource = "assumed OSM logical 0; no levelled entrance";
            }
            validLevels = activeGarage.validLevels(entryLevel);
            if (!validLevels.contains(entryLevel)) {
                int excludedLevel = entryLevel;
                entryLevel = nearestValidLevel(validLevels, entryLevel);
                floorAnchorConfidence *= 0.65;
                state.entryLevelSource += "; mapped topology excludes " + excludedLevel
                        + ", using nearest valid level " + entryLevel;
            }
            refs = activeGarage.levelRefs(validLevels);
            if (!"user setting".equals(configuredFloorHeightSource)) {
                double suggested = activeGarage.suggestedFloorHeightMeters(floorHeight);
                if (Math.abs(suggested - floorHeight) > 0.01) {
                    floorHeight = suggested;
                    heightSource = "OSM height / building:levels";
                }
            }
            fillGarageState(activeGarage);
            state.validLevels = renderLevels(validLevels, activeGarage.topologyKnown());
            state.garageLevelMetadata = metadata(activeGarage);
        } else {
            validLevels = new ArrayList<>();
            for (int i = -10; i <= 20; i++) validLevels.add(i);
            state.garageName = "Possible unmapped garage";
            state.garageId = "unmapped";
            state.garageType = "heuristic fallback";
            state.validLevels = "unknown; relative range only";
            state.garageLevelMetadata = "No mapped topology";
        }

        state.floorHeightMeters = floorHeight;
        state.floorHeightSource = heightSource;
        state.entryLevelConfidence = floorAnchorConfidence;
        if (Double.isFinite(baseline) && pressureSensor != null) {
            floorEstimator.start(entryLevel, validLevels, refs, floorHeight);
            state.floorAvailable = true;
            state.logicalLevel = entryLevel;
            state.levelLabel = floorEstimator.labelFor(entryLevel);
            state.floorConfidence = floorAnchorConfidence;
            state.levelSource = fallbackUnmapped ? "barometer + inferred entry" : "barometer + OSM constraints";
        } else {
            state.floorAvailable = false;
            state.levelSource = "No barometer baseline";
        }
        debugLog.add("Garage entry: " + state.garageName + "; anchor level " + entryLevel);
    }

    protected void promoteFallbackToMappedGarage() {
        if (activeGarage == null) return;
        fillGarageState(activeGarage);
        int anchorLevel = floorEstimator.isActive() ? floorEstimator.getAnchorLevel() : state.logicalLevel;
        List<Integer> validLevels = activeGarage.validLevels(anchorLevel);
        if (!validLevels.contains(anchorLevel)) {
            int excludedLevel = anchorLevel;
            anchorLevel = nearestValidLevel(validLevels, anchorLevel);
            floorAnchorConfidence *= 0.65;
            state.entryLevelConfidence = floorAnchorConfidence;
            state.entryLevelSource += "; mapped topology excludes " + excludedLevel
                    + ", using nearest valid level " + anchorLevel;
        }
        Map<Integer, String> refs = activeGarage.levelRefs(validLevels);
        double floorHeight = configuredFloorHeightMeters;
        String heightSource = configuredFloorHeightSource;
        if (!"user setting".equals(configuredFloorHeightSource)) {
            double suggested = activeGarage.suggestedFloorHeightMeters(configuredFloorHeightMeters);
            if (Math.abs(suggested - configuredFloorHeightMeters) > 0.01) {
                floorHeight = suggested;
                heightSource = "OSM height / building:levels";
            }
        }

        state.validLevels = renderLevels(validLevels, activeGarage.topologyKnown());
        state.garageLevelMetadata = metadata(activeGarage);
        state.floorHeightMeters = floorHeight;
        state.floorHeightSource = heightSource;
        if (floorEstimator.isActive() && Double.isFinite(pressureBaselineHpa)) {
            floorEstimator.start(anchorLevel, validLevels, refs, floorHeight);
            state.levelSource = "barometer + late OSM constraints";
            updateFloorFromPressure();
        }
        state.entryLevelSource = state.entryLevelSource + "; mapped topology loaded after entry";
        debugLog.add("Promoted fallback session to mapped garage: " + activeGarage.name);
    }

    protected void updateFloorFromPressure() {
        state.pressureBaselineHpa = pressureBaselineHpa;
        if (!state.garageDetected || !floorEstimator.isActive() || !Double.isFinite(pressureBaselineHpa)) {
            state.relativeAltitudeMeters = Double.NaN;
            return;
        }
        double current = pressureFilter.filteredPressure();
        double relativeAltitude = AltitudeMath.relativeAltitudeMeters(pressureBaselineHpa, current);
        state.relativeAltitudeMeters = relativeAltitude;
        FloorEstimator.Estimate estimate = floorEstimator.update(relativeAltitude, state.verticalSpeedMps);
        if (estimate != null) {
            int previous = state.logicalLevel;
            state.floorAvailable = true;
            state.logicalLevel = estimate.logicalLevel;
            state.levelLabel = estimate.label;
            state.floorConfidence = estimate.confidence * floorAnchorConfidence;
            state.transitioning = estimate.transitioning;
            if (previous != estimate.logicalLevel) debugLog.add("Floor transition: " + previous + " → " + estimate.logicalLevel);
        }
    }

    protected void fillGarageState(Garage garage) {
        state.garageName = garage.name;
        state.garageId = garage.osmId();
        state.garageType = garage.parkingType;
    }

    protected EntranceMatch nearestLevelledEntrance(Garage garage, Location location) {
        GarageEntrance best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        double accuracy = location.hasAccuracy() ? location.getAccuracy() : 25.0;
        double maxDistance = Math.min(60.0, Math.max(25.0, accuracy * 1.5));
        for (GarageEntrance entrance : garage.entrances) {
            if (entrance.logicalLevel == null) continue;
            double d = Geo.distanceMeters(location.getLatitude(), location.getLongitude(), entrance.latitude, entrance.longitude);
            if (d < bestDistance && d <= maxDistance) {
                bestDistance = d;
                best = entrance;
            }
        }
        if (best == null) return null;
        double confidence = bestDistance <= 15.0 ? 0.95 : bestDistance <= 30.0 ? 0.85 : 0.70;
        if (accuracy > 30.0) confidence *= 0.85;
        return new EntranceMatch(best, bestDistance, confidence);
    }

    protected void reanchor(int logicalLevel) {
        if (!state.tracking) {
            debugLog.add("Re-anchor ignored: tracking is stopped");
            publish();
            stopSelf();
            return;
        }
        if (!state.garageDetected || !floorEstimator.isActive()) {
            debugLog.add("Re-anchor ignored: not in an active garage session");
            publish();
            return;
        }
        if (activeGarage != null
                && (activeGarage.nonExistentLevels.contains(logicalLevel)
                || (activeGarage.topologyKnown()
                && !activeGarage.validLevels(state.logicalLevel).contains(logicalLevel)))) {
            debugLog.add("Re-anchor ignored: logical level " + logicalLevel + " is not valid in mapped topology");
            publish();
            return;
        }
        double current = pressureFilter.filteredPressure();
        if (!Double.isFinite(current)) {
            debugLog.add("Re-anchor ignored: no pressure sample");
            publish();
            return;
        }
        pressureBaselineHpa = current;
        floorEstimator.reanchorLevel(logicalLevel);
        state.pressureBaselineHpa = current;
        state.relativeAltitudeMeters = 0.0;
        state.logicalLevel = logicalLevel;
        state.levelLabel = floorEstimator.labelFor(logicalLevel);
        floorAnchorConfidence = 1.0;
        state.entryLevelConfidence = 1.0;
        state.entryLevelSource = "manual re-anchor";
        state.floorConfidence = 1.0;
        debugLog.add("Manual re-anchor to logical level " + logicalLevel);
        publish();
    }

    protected void setFloorHeight(double meters) {
        double value = clampFloorHeight(meters);
        configuredFloorHeightMeters = value;
        configuredFloorHeightSource = "user setting";
        state.floorHeightMeters = value;
        state.floorHeightSource = configuredFloorHeightSource;
        getSharedPreferences("settings", MODE_PRIVATE).edit().putFloat("floor_height", (float) value).apply();

        if (floorEstimator.isActive()) {
            double currentPressure = pressureFilter.filteredPressure();
            double currentConfidence = state.floorConfidence;
            floorEstimator.setFloorHeightMeters(value);
            if (Double.isFinite(currentPressure) && state.floorAvailable) {
                pressureBaselineHpa = currentPressure;
                floorEstimator.reanchorLevel(state.logicalLevel);
                state.pressureBaselineHpa = currentPressure;
                state.relativeAltitudeMeters = 0.0;
                floorAnchorConfidence = Geo.clamp01(currentConfidence);
                state.entryLevelConfidence = floorAnchorConfidence;
                state.entryLevelSource = "current estimate after floor-height change";
            }
        } else {
            floorEstimator.setFloorHeightMeters(value);
        }
        debugLog.add("Floor height set to " + value + " m");
        publish();
        if (!state.tracking) stopSelf();
    }
}
