package dev.radixen.garagelevel.estimator;

import dev.radixen.garagelevel.model.Garage;
import dev.radixen.garagelevel.util.Geo;

import java.util.Collections;
import java.util.List;

public final class GarageDetector {
    public static final class Observation {
        public final boolean detected;
        public final Garage garage;
        public final double confidence;
        public final double distanceMeters;
        public final String reason;
        public final boolean justEntered;
        public final boolean justExited;
        public final boolean fallbackUnmapped;

        Observation(boolean detected, Garage garage, double confidence, double distanceMeters, String reason,
                    boolean justEntered, boolean justExited, boolean fallbackUnmapped) {
            this.detected = detected;
            this.garage = garage;
            this.confidence = confidence;
            this.distanceMeters = distanceMeters;
            this.reason = reason;
            this.justEntered = justEntered;
            this.justExited = justExited;
            this.fallbackUnmapped = fallbackUnmapped;
        }
    }

    private boolean detected;
    private Garage activeGarage;
    private boolean activeFallback;
    private int enterStreak;
    private int exitStreak;
    private double baselineAccuracy = Double.NaN;
    private double baselineCn0 = Double.NaN;
    private double baselineSatUsed = Double.NaN;

    public synchronized Observation update(
            double latitude,
            double longitude,
            float horizontalAccuracy,
            int satellitesUsed,
            double averageCn0,
            float speedMps,
            double pressureAltitudeSpan20s,
            List<Garage> garages) {

        if (garages == null) garages = Collections.emptyList();
        boolean wasDetected = detected;

        Garage nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        boolean inside = false;
        for (Garage garage : garages) {
            boolean contains = garage.containsMappedArea(latitude, longitude);
            double distance = garage.distanceMeters(latitude, longitude);
            if (nearest == null
                    || (contains && !inside)
                    || (contains == inside && distance < nearestDistance)) {
                nearest = garage;
                nearestDistance = distance;
                inside = contains;
            }
        }

        double mapScore = nearest == null ? 0.0 : mapScore(inside, nearestDistance);

        if (!detected && mapScore < 0.35 && horizontalAccuracy > 0 && horizontalAccuracy < 35) {
            baselineAccuracy = ewma(baselineAccuracy, horizontalAccuracy, 0.08);
            if (Double.isFinite(averageCn0) && averageCn0 > 0) baselineCn0 = ewma(baselineCn0, averageCn0, 0.08);
            if (satellitesUsed > 0) baselineSatUsed = ewma(baselineSatUsed, satellitesUsed, 0.08);
        }

        double gnssDegrade = 0.0;
        if (horizontalAccuracy > 0) {
            double reference = Double.isFinite(baselineAccuracy) ? Math.max(12.0, baselineAccuracy) : 12.0;
            gnssDegrade += 0.45 * Geo.clamp01((horizontalAccuracy - reference * 1.35) / 35.0);
        }
        if (Double.isFinite(baselineCn0) && Double.isFinite(averageCn0) && baselineCn0 > 5) {
            gnssDegrade += 0.35 * Geo.clamp01((baselineCn0 - averageCn0) / Math.max(8.0, baselineCn0 * 0.45));
        }
        if (Double.isFinite(baselineSatUsed) && satellitesUsed >= 0 && baselineSatUsed > 2) {
            gnssDegrade += 0.20 * Geo.clamp01((baselineSatUsed - satellitesUsed) / Math.max(3.0, baselineSatUsed * 0.7));
        }
        gnssDegrade = Geo.clamp01(gnssDegrade);

        double vehicleScore = speedMps >= 2.0f ? 1.0 : speedMps >= 0.5f ? 0.55 : 0.20;
        double confidence = Geo.clamp01(0.76 * mapScore + 0.14 * gnssDegrade + 0.10 * vehicleScore);
        if (inside) confidence = Math.max(confidence, 0.84);

        boolean fallback = false;
        double verticalScore = Geo.clamp01((pressureAltitudeSpan20s - 1.8) / 3.2);
        double fallbackConfidence = Geo.clamp01(0.58 * gnssDegrade + 0.22 * vehicleScore + 0.20 * verticalScore);
        if (nearest == null || mapScore < 0.15) {
            if (fallbackConfidence > 0.83 && pressureAltitudeSpan20s > 2.4) {
                confidence = fallbackConfidence;
                fallback = true;
            }
        }

        if (!detected) {
            boolean proximitySupport = inside || gnssDegrade >= 0.15 || pressureAltitudeSpan20s >= 1.0;
            boolean enterCandidate = (!fallback && confidence >= 0.64 && nearest != null
                    && nearestDistance <= 100 && proximitySupport)
                    || (fallback && confidence >= 0.83);
            enterStreak = enterCandidate ? enterStreak + 1 : 0;
            if (enterStreak >= 3) {
                detected = true;
                activeGarage = fallback ? null : nearest;
                activeFallback = fallback;
                exitStreak = 0;
            }
        } else {
            double activeDistance = activeGarage == null ? nearestDistance
                    : activeGarage.distanceMeters(latitude, longitude);
            boolean stillInsideActive = activeGarage != null && activeGarage.containsMappedArea(latitude, longitude);

            if (activeFallback) {
                boolean mappedSupport = inside || gnssDegrade >= 0.15 || pressureAltitudeSpan20s >= 1.0;
                boolean promoteToMapped = nearest != null && nearestDistance <= 100
                        && confidence >= 0.64 && mappedSupport;
                if (promoteToMapped) {
                    activeGarage = nearest;
                    activeFallback = false;
                    fallback = false;
                    exitStreak = 0;
                } else {
                    // Keep displaying the strength of the fallback evidence even after it falls
                    // below the entry threshold; exit hysteresis decides when the session ends.
                    confidence = fallbackConfidence;
                    fallback = true;
                    mapScore = 0.0;
                }
            } else if (activeGarage != null) {
                mapScore = mapScore(stillInsideActive, activeDistance);
                confidence = Geo.clamp01(0.76 * mapScore + 0.14 * gnssDegrade + 0.10 * vehicleScore);
                if (stillInsideActive) confidence = Math.max(confidence, 0.84);
            }

            boolean weak = activeFallback
                    ? (gnssDegrade < 0.20 && pressureAltitudeSpan20s < 1.3 && speedMps > 1.0f)
                    : (!stillInsideActive && activeDistance > 110 && confidence < 0.30);
            exitStreak = weak ? exitStreak + 1 : 0;
            if (exitStreak >= 7) {
                detected = false;
                activeGarage = null;
                activeFallback = false;
                enterStreak = 0;
            } else if (activeGarage != null) {
                nearest = activeGarage;
                nearestDistance = activeDistance;
                inside = stillInsideActive;
            }
        }

        String reason = "map=" + round2(mapScore) + ", gnssLoss=" + round2(gnssDegrade)
                + ", vehicle=" + round2(vehicleScore)
                + (fallback ? ", vertical-fallback" : inside ? ", inside-polygon" : "");
        boolean justEntered = !wasDetected && detected;
        boolean justExited = wasDetected && !detected;
        return new Observation(detected, detected ? activeGarage : nearest, confidence,
                nearestDistance, reason, justEntered, justExited, detected && activeFallback);
    }

    public synchronized void reset() {
        detected = false;
        activeGarage = null;
        activeFallback = false;
        enterStreak = 0;
        exitStreak = 0;
        baselineAccuracy = Double.NaN;
        baselineCn0 = Double.NaN;
        baselineSatUsed = Double.NaN;
    }

    private static double mapScore(boolean inside, double distanceMeters) {
        if (inside) return 1.0;
        if (distanceMeters <= 20) return 0.90;
        if (distanceMeters <= 40) return 0.75;
        if (distanceMeters <= 75) return 0.50;
        if (distanceMeters <= 120) return 0.25;
        return 0.0;
    }

    private static double ewma(double previous, double value, double alpha) {
        return Double.isFinite(previous) ? alpha * value + (1.0 - alpha) * previous : value;
    }

    private static String round2(double v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}
