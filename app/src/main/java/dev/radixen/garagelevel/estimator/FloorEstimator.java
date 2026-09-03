package dev.radixen.garagelevel.estimator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FloorEstimator {
    public static final class Estimate {
        public final int logicalLevel;
        public final String label;
        public final double confidence;
        public final boolean transitioning;
        public final double expectedOffsetMeters;

        Estimate(int logicalLevel, String label, double confidence, boolean transitioning, double expectedOffsetMeters) {
            this.logicalLevel = logicalLevel;
            this.label = label;
            this.confidence = confidence;
            this.transitioning = transitioning;
            this.expectedOffsetMeters = expectedOffsetMeters;
        }
    }

    private List<Integer> levels = new ArrayList<>();
    private Map<Integer, String> refs = new HashMap<>();
    private int entryLevel;
    private int currentLevel;
    private int entryIndex;
    private double floorHeightMeters = 3.0;
    private boolean active;

    public synchronized void start(int entryLevel, List<Integer> validLevels, Map<Integer, String> levelRefs, double floorHeightMeters) {
        this.levels = validLevels == null ? new ArrayList<>() : new ArrayList<>(validLevels);
        this.levels.sort(Integer::compareTo);
        if (this.levels.isEmpty()) this.levels.add(entryLevel);
        int normalizedEntry = this.levels.contains(entryLevel) ? entryLevel : nearestLevel(this.levels, entryLevel);
        this.entryLevel = normalizedEntry;
        this.refs = levelRefs == null ? new HashMap<>() : new HashMap<>(levelRefs);
        this.floorHeightMeters = clampFloorHeight(floorHeightMeters);
        this.entryIndex = levels.indexOf(normalizedEntry);
        this.currentLevel = normalizedEntry;
        this.active = true;
    }

    public synchronized void stop() {
        active = false;
    }

    public synchronized boolean isActive() {
        return active;
    }

    public synchronized void setFloorHeightMeters(double floorHeightMeters) {
        this.floorHeightMeters = clampFloorHeight(floorHeightMeters);
    }

    public synchronized double getFloorHeightMeters() {
        return floorHeightMeters;
    }

    public synchronized int getAnchorLevel() {
        return entryLevel;
    }

    public synchronized void reanchorLevel(int logicalLevel) {
        if (!levels.contains(logicalLevel)) {
            levels.add(logicalLevel);
            levels.sort(Integer::compareTo);
        }
        entryLevel = logicalLevel;
        currentLevel = logicalLevel;
        entryIndex = levels.indexOf(logicalLevel);
    }

    public synchronized Estimate update(double relativeAltitudeMeters, double verticalSpeedMps) {
        if (!active || levels.isEmpty() || !Double.isFinite(relativeAltitudeMeters)) return null;

        double sigma = 0.72;
        if (Double.isFinite(verticalSpeedMps)) sigma += Math.min(0.55, Math.abs(verticalSpeedMps) * 0.25);

        double[] weights = new double[levels.size()];
        double total = 0.0;
        int currentIndex = Math.max(0, levels.indexOf(currentLevel));
        for (int i = 0; i < levels.size(); i++) {
            double expected = (i - entryIndex) * floorHeightMeters;
            double residual = relativeAltitudeMeters - expected;
            double likelihood = Math.exp(-0.5 * (residual / sigma) * (residual / sigma));

            int delta = i - currentIndex;
            double prior;
            if (delta == 0) prior = 1.35;
            else if (Math.abs(delta) == 1) prior = 1.0;
            else if (Math.abs(delta) == 2) prior = 0.12;
            else prior = 0.015;

            if (Double.isFinite(verticalSpeedMps)) {
                if (verticalSpeedMps > 0.18 && delta < 0) prior *= 0.2;
                if (verticalSpeedMps < -0.18 && delta > 0) prior *= 0.2;
            }
            weights[i] = likelihood * prior;
            total += weights[i];
        }

        if (total <= 1e-12) return new Estimate(currentLevel, labelFor(currentLevel), 0.0, true, expectedOffset(currentLevel));
        for (int i = 0; i < weights.length; i++) weights[i] /= total;

        int bestIndex = 0;
        for (int i = 1; i < weights.length; i++) if (weights[i] > weights[bestIndex]) bestIndex = i;
        int bestLevel = levels.get(bestIndex);
        int oldIndex = levels.indexOf(currentLevel);
        double oldWeight = oldIndex >= 0 ? weights[oldIndex] : 0.0;
        double bestExpected = (bestIndex - entryIndex) * floorHeightMeters;
        double residualToBest = Math.abs(relativeAltitudeMeters - bestExpected);

        if (bestLevel != currentLevel) {
            boolean adjacent = oldIndex < 0 || Math.abs(bestIndex - oldIndex) <= 1;
            boolean decisive = weights[bestIndex] > Math.max(0.54, oldWeight * 1.35);
            boolean nearFloorPlane = residualToBest < 0.72;
            // Normal driving should advance one physical level at a time. A very decisive
            // non-adjacent result is nevertheless allowed so the estimator can recover
            // after sensor/OS scheduling gaps or rapid elevator-like pressure changes.
            boolean recoveryJump = !adjacent && weights[bestIndex] > 0.80 && residualToBest < 0.60;
            if ((adjacent && decisive && nearFloorPlane) || recoveryJump) currentLevel = bestLevel;
        }

        int selectedIndex = levels.indexOf(currentLevel);
        double selectedWeight = selectedIndex >= 0 ? weights[selectedIndex] : 0.0;
        double expected = selectedIndex < 0 ? 0.0 : (selectedIndex - entryIndex) * floorHeightMeters;
        boolean moving = Double.isFinite(verticalSpeedMps) && Math.abs(verticalSpeedMps) > 0.16;
        boolean betweenFloors = Math.abs(relativeAltitudeMeters - expected) > floorHeightMeters * 0.28;
        return new Estimate(currentLevel, labelFor(currentLevel), selectedWeight, moving || betweenFloors, expected);
    }

    private double expectedOffset(int logicalLevel) {
        int idx = levels.indexOf(logicalLevel);
        return idx < 0 ? 0.0 : (idx - entryIndex) * floorHeightMeters;
    }

    public synchronized String labelFor(int level) {
        String ref = refs.get(level);
        if (ref != null && !ref.trim().isEmpty()) return ref.trim() + " (OSM level " + level + ")";
        if (level == 0) return "Ground (OSM level 0)";
        return "OSM level " + level;
    }

    private static int nearestLevel(List<Integer> levels, int preferred) {
        int best = levels.get(0);
        int bestDistance = Math.abs(best - preferred);
        for (int i = 1; i < levels.size(); i++) {
            int candidate = levels.get(i);
            int distance = Math.abs(candidate - preferred);
            if (distance < bestDistance || (distance == bestDistance && candidate > best)) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static double clampFloorHeight(double value) {
        if (!Double.isFinite(value)) return 3.0;
        return Math.max(2.2, Math.min(5.5, value));
    }
}
