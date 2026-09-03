package dev.radixen.garagelevel.util;

import java.util.List;

public final class Geo {
    private static final double EARTH_RADIUS_M = 6371008.8;

    private Geo() {}

    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_M * c;
    }

    public static boolean pointInPolygon(double lat, double lon, List<double[]> polygon) {
        if (polygon == null || polygon.size() < 3) return false;
        boolean inside = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            double yi = polygon.get(i)[0];
            double xi = polygon.get(i)[1];
            double yj = polygon.get(j)[0];
            double xj = polygon.get(j)[1];
            boolean intersect = ((yi > lat) != (yj > lat))
                    && (lon < (xj - xi) * (lat - yi) / ((yj - yi) == 0 ? 1e-12 : (yj - yi)) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }


    public static double distanceToPolylineMeters(double lat, double lon, List<double[]> path) {
        if (path == null || path.isEmpty()) return Double.NaN;
        if (path.size() == 1) return distanceMeters(lat, lon, path.get(0)[0], path.get(0)[1]);

        double lat0 = Math.toRadians(lat);
        double cosLat = Math.max(1e-6, Math.cos(lat0));
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i + 1 < path.size(); i++) {
            double[] a = path.get(i);
            double[] b = path.get(i + 1);
            double ax = Math.toRadians(a[1] - lon) * EARTH_RADIUS_M * cosLat;
            double ay = Math.toRadians(a[0] - lat) * EARTH_RADIUS_M;
            double bx = Math.toRadians(b[1] - lon) * EARTH_RADIUS_M * cosLat;
            double by = Math.toRadians(b[0] - lat) * EARTH_RADIUS_M;
            double dx = bx - ax;
            double dy = by - ay;
            double denom = dx * dx + dy * dy;
            double t = denom <= 1e-12 ? 0.0 : -(ax * dx + ay * dy) / denom;
            t = Math.max(0.0, Math.min(1.0, t));
            best = Math.min(best, Math.hypot(ax + t * dx, ay + t * dy));
        }
        return best;
    }

    public static boolean isClosedPath(List<double[]> path) {
        if (path == null || path.size() < 4) return false;
        double[] first = path.get(0);
        double[] last = path.get(path.size() - 1);
        return Math.abs(first[0] - last[0]) < 1e-9 && Math.abs(first[1] - last[1]) < 1e-9;
    }

    public static double distanceToPolygonMeters(double lat, double lon, List<double[]> polygon) {
        if (polygon == null || polygon.size() < 2) return Double.NaN;
        if (polygon.size() >= 3 && pointInPolygon(lat, lon, polygon)) return 0.0;

        double lat0 = Math.toRadians(lat);
        double cosLat = Math.max(1e-6, Math.cos(lat0));
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < polygon.size(); i++) {
            double[] a = polygon.get(i);
            double[] b = polygon.get((i + 1) % polygon.size());
            double ax = Math.toRadians(a[1] - lon) * EARTH_RADIUS_M * cosLat;
            double ay = Math.toRadians(a[0] - lat) * EARTH_RADIUS_M;
            double bx = Math.toRadians(b[1] - lon) * EARTH_RADIUS_M * cosLat;
            double by = Math.toRadians(b[0] - lat) * EARTH_RADIUS_M;
            double dx = bx - ax;
            double dy = by - ay;
            double denom = dx * dx + dy * dy;
            double t = denom <= 1e-12 ? 0.0 : -(ax * dx + ay * dy) / denom;
            t = Math.max(0.0, Math.min(1.0, t));
            double px = ax + t * dx;
            double py = ay + t * dy;
            best = Math.min(best, Math.hypot(px, py));
        }
        return best;
    }

    public static double[] centroid(List<double[]> points) {
        if (points == null || points.isEmpty()) return new double[]{Double.NaN, Double.NaN};
        double lat = 0;
        double lon = 0;
        for (double[] p : points) {
            lat += p[0];
            lon += p[1];
        }
        return new double[]{lat / points.size(), lon / points.size()};
    }

    public static double[] boundingBox(double lat, double lon, double radiusMeters) {
        double dLat = Math.toDegrees(radiusMeters / EARTH_RADIUS_M);
        double cosLat = Math.max(1e-6, Math.abs(Math.cos(Math.toRadians(lat))));
        double dLon = Math.toDegrees(radiusMeters / (EARTH_RADIUS_M * cosLat));
        dLon = Math.min(180.0, dLon);
        // Overpass bounding boxes require longitudes in [-180, 180]. Near the
        // antimeridian we conservatively clamp rather than emit an invalid box; a full
        // production implementation should split a crossing query into two boxes.
        double west = Math.max(-180.0, lon - dLon);
        double east = Math.min(180.0, lon + dLon);
        return new double[]{Math.max(-90.0, lat - dLat), west,
                Math.min(90.0, lat + dLat), east};
    }

    public static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
