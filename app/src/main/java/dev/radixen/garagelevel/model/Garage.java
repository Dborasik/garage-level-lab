package dev.radixen.garagelevel.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.radixen.garagelevel.util.Geo;

public final class Garage {
    public final String osmType;
    public final long osmNumericId;
    public final String name;
    public final String parkingType;
    public final double centerLatitude;
    public final double centerLongitude;
    public final List<double[]> polygon;
    public final List<List<double[]>> boundaryPaths;
    public final Integer buildingLevels;
    public final Integer undergroundLevels;
    public final Integer minLevel;
    public final Integer maxLevel;
    public final Set<Integer> nonExistentLevels;
    public final Double heightMeters;
    public final List<String> buildingLevelRefs;
    public final List<GarageEntrance> entrances = new ArrayList<>();

    public Garage(
            String osmType,
            long osmNumericId,
            String name,
            String parkingType,
            double centerLatitude,
            double centerLongitude,
            List<double[]> polygon,
            Integer buildingLevels,
            Integer undergroundLevels,
            Integer minLevel,
            Integer maxLevel,
            Set<Integer> nonExistentLevels,
            Double heightMeters,
            List<String> buildingLevelRefs) {
        this(osmType, osmNumericId, name, parkingType, centerLatitude, centerLongitude, polygon,
                polygon == null || polygon.isEmpty() ? Collections.emptyList() : Collections.singletonList(polygon),
                buildingLevels, undergroundLevels, minLevel, maxLevel, nonExistentLevels, heightMeters, buildingLevelRefs);
    }

    public Garage(
            String osmType,
            long osmNumericId,
            String name,
            String parkingType,
            double centerLatitude,
            double centerLongitude,
            List<double[]> polygon,
            List<List<double[]>> boundaryPaths,
            Integer buildingLevels,
            Integer undergroundLevels,
            Integer minLevel,
            Integer maxLevel,
            Set<Integer> nonExistentLevels,
            Double heightMeters,
            List<String> buildingLevelRefs) {
        this.osmType = osmType;
        this.osmNumericId = osmNumericId;
        this.name = name;
        this.parkingType = parkingType;
        this.centerLatitude = centerLatitude;
        this.centerLongitude = centerLongitude;
        this.polygon = polygon == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(polygon));
        List<List<double[]>> paths = new ArrayList<>();
        if (boundaryPaths != null) {
            for (List<double[]> path : boundaryPaths) {
                if (path != null && !path.isEmpty()) paths.add(Collections.unmodifiableList(new ArrayList<>(path)));
            }
        }
        this.boundaryPaths = Collections.unmodifiableList(paths);
        this.buildingLevels = buildingLevels;
        this.undergroundLevels = undergroundLevels;
        this.minLevel = minLevel;
        this.maxLevel = maxLevel;
        this.nonExistentLevels = nonExistentLevels == null ? Collections.emptySet() : Collections.unmodifiableSet(new HashSet<>(nonExistentLevels));
        this.heightMeters = heightMeters;
        this.buildingLevelRefs = buildingLevelRefs == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(buildingLevelRefs));
    }

    public String osmId() {
        return osmType + "/" + osmNumericId;
    }


    public boolean containsMappedArea(double latitude, double longitude) {
        if (!polygon.isEmpty()) return Geo.pointInPolygon(latitude, longitude, polygon);
        // A relation with exactly one closed geometry member can be treated as a usable outer
        // boundary. With multiple members we cannot safely distinguish outer rings from holes
        // without a full multipolygon assembler, so containment remains unknown.
        if (boundaryPaths.size() == 1 && Geo.isClosedPath(boundaryPaths.get(0))) {
            return Geo.pointInPolygon(latitude, longitude, boundaryPaths.get(0));
        }
        return false;
    }

    public double distanceMeters(double latitude, double longitude) {
        if (!polygon.isEmpty()) {
            double boundaryDistance = Geo.distanceToPolygonMeters(latitude, longitude, polygon);
            if (Double.isFinite(boundaryDistance)) return boundaryDistance;
        }
        double best = Double.POSITIVE_INFINITY;
        for (List<double[]> path : boundaryPaths) {
            if (Geo.isClosedPath(path) && Geo.pointInPolygon(latitude, longitude, path)) return 0.0;
            double distance = Geo.distanceToPolylineMeters(latitude, longitude, path);
            if (Double.isFinite(distance)) best = Math.min(best, distance);
        }
        if (Double.isFinite(best)) return best;
        return Geo.distanceMeters(latitude, longitude, centerLatitude, centerLongitude);
    }

    public List<Integer> validLevels(int entryLevel) {
        List<Integer> result = new ArrayList<>();
        if (minLevel != null && maxLevel != null && maxLevel >= minLevel) {
            for (int level = minLevel; level <= maxLevel; level++) {
                if (!nonExistentLevels.contains(level)) result.add(level);
            }
        } else if (buildingLevels != null || undergroundLevels != null) {
            int underground = undergroundLevels == null ? 0 : Math.max(0, undergroundLevels);
            int above = buildingLevels == null ? 0 : Math.max(0, buildingLevels);
            for (int level = -underground; level < 0; level++) {
                if (!nonExistentLevels.contains(level)) result.add(level);
            }
            for (int level = 0; level < above; level++) {
                if (!nonExistentLevels.contains(level)) result.add(level);
            }
        }

        if (result.isEmpty()) {
            for (int level = entryLevel - 10; level <= entryLevel + 20; level++) {
                if (!nonExistentLevels.contains(level)) result.add(level);
            }
            if (!result.contains(entryLevel) && !nonExistentLevels.contains(entryLevel)) {
                result.add(entryLevel);
                Collections.sort(result);
            }
        }
        return result;
    }

    public boolean topologyKnown() {
        if (minLevel != null && maxLevel != null && maxLevel >= minLevel) return true;
        return (buildingLevels != null && buildingLevels > 0)
                || (undergroundLevels != null && undergroundLevels > 0);
    }

    public double suggestedFloorHeightMeters(double fallback) {
        if (heightMeters != null && buildingLevels != null && buildingLevels > 1) {
            double estimate = heightMeters / buildingLevels;
            if (estimate >= 2.4 && estimate <= 4.5) return estimate;
        }
        return fallback;
    }

    public Map<Integer, String> levelRefs(List<Integer> validLevels) {
        Map<Integer, String> refs = new HashMap<>();
        if (buildingLevelRefs.size() == validLevels.size()) {
            for (int i = 0; i < validLevels.size(); i++) refs.put(validLevels.get(i), buildingLevelRefs.get(i));
        }
        for (GarageEntrance entrance : entrances) {
            if (entrance.logicalLevel != null && entrance.levelRef != null && !entrance.levelRef.trim().isEmpty()) {
                refs.put(entrance.logicalLevel, entrance.levelRef.trim());
            }
        }
        return refs;
    }
}
