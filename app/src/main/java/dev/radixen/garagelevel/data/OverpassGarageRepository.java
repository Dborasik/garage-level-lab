package dev.radixen.garagelevel.data;

import android.util.Log;

import dev.radixen.garagelevel.model.Garage;
import dev.radixen.garagelevel.model.GarageEntrance;
import dev.radixen.garagelevel.util.Geo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class OverpassGarageRepository {
    public interface Callback {
        void onSuccess(List<Garage> garages, int entranceCount);
        void onError(String message);
    }

    private static final String TAG = "OverpassGarageRepo";
    private static final String[] ENDPOINTS = {
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter"
    };
    private static final int MAX_RESPONSE_CHARS = 5_000_000;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public void fetchNearby(double lat, double lon, int radiusMeters, Callback callback) {
        executor.execute(() -> {
            try {
                String query = buildQuery(lat, lon, radiusMeters);
                Exception lastError = null;
                for (String endpoint : ENDPOINTS) {
                    try {
                        String body = execute(endpoint, query);
                        ParseResult result = parse(body);
                        assignEntrances(result.garages, result.entrances);
                        callback.onSuccess(result.garages, result.entrances.size());
                        return;
                    } catch (Exception e) {
                        lastError = e;
                        Log.w(TAG, "Overpass endpoint failed: " + endpoint, e);
                    }
                }
                callback.onError(lastError == null ? "All Overpass endpoints failed" : describe(lastError));
            } catch (Exception e) {
                Log.w(TAG, "Overpass fetch failed", e);
                callback.onError(describe(e));
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private static String buildQuery(double lat, double lon, int radiusMeters) {
        double[] bbox = Geo.boundingBox(lat, lon, radiusMeters);
        String box = bbox[0] + "," + bbox[1] + "," + bbox[2] + "," + bbox[3];
        return "[out:json][timeout:18];("
                + "nwr[\"amenity\"=\"parking\"][\"parking\"~\"multi-storey|underground\"](" + box + ");"
                + "nwr[\"building\"=\"parking\"](" + box + ");"
                + "node[\"amenity\"=\"parking_entrance\"](" + box + ");"
                + ");out body geom;";
    }

    private static String execute(String endpoint, String query) throws Exception {
        HttpURLConnection connection = null;
        try {
            byte[] payload = ("data=" + URLEncoder.encode(query, "UTF-8")).getBytes(StandardCharsets.UTF_8);
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "GarageLevelLab/0.2.1 Android research prototype");
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(payload);
            }

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            String body = readAll(stream);
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + (body.isEmpty() ? "" : ": " + abbreviate(body, 180)));
            }
            return body;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static ParseResult parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray elements = root.optJSONArray("elements");
        Map<String, Garage> garages = new LinkedHashMap<>();
        List<GarageEntrance> entrances = new ArrayList<>();
        if (elements == null) return new ParseResult(new ArrayList<>(), entrances);

        for (int i = 0; i < elements.length(); i++) {
            JSONObject element = elements.getJSONObject(i);
            JSONObject tags = element.optJSONObject("tags");
            if (tags == null) continue;
            String type = element.optString("type", "unknown");
            long id = element.optLong("id", -1L);

            if ("parking_entrance".equals(tags.optString("amenity"))) {
                double[] center = extractCenter(element);
                if (Double.isFinite(center[0])) {
                    // level=* may legally be fractional (for example 0.5 mezzanines). This
                    // integer estimator must not silently round such a value to another floor.
                    Integer level = parseStrictInt(tags.optString("level", null));
                    String levelRef = clean(tags.optString("level:ref", null));
                    entrances.add(new GarageEntrance(type + "/" + id, center[0], center[1], level, levelRef));
                }
                continue;
            }

            boolean parking = "parking".equals(tags.optString("amenity"));
            boolean parkingBuilding = "parking".equals(tags.optString("building"));
            String explicitParkingType = clean(tags.optString("parking", null));
            boolean explicitMultiLevelParking = parking
                    && ("multi-storey".equals(explicitParkingType) || "underground".equals(explicitParkingType));

            Integer buildingLevels = parseStrictInt(tags.optString("building:levels", null));
            Integer undergroundLevels = parseStrictInt(tags.optString("building:levels:underground", null));
            Integer minLevel = parseStrictInt(tags.optString("min_level", null));
            Integer maxLevel = parseStrictInt(tags.optString("max_level", null));

            // building=parking can describe a single-storey parking building. Only treat it as
            // a vertical garage when the tags provide actual multi-level/underground evidence.
            boolean buildingHasVerticalEvidence = parkingBuilding && (
                    "multi-storey".equals(explicitParkingType)
                            || "underground".equals(explicitParkingType)
                            || (buildingLevels != null && buildingLevels >= 2)
                            || (undergroundLevels != null && undergroundLevels >= 1)
                            || (minLevel != null && maxLevel != null && maxLevel > minLevel));
            if (!explicitMultiLevelParking && !buildingHasVerticalEvidence) continue;

            String parkingType = explicitParkingType;
            if (parkingType == null) parkingType = undergroundLevels != null && undergroundLevels > 0
                    ? "underground (inferred from levels)"
                    : "multi-storey (inferred from levels)";

            List<List<double[]>> geometryPaths = extractGeometryPaths(element);
            List<double[]> geometry = flattenGeometry(geometryPaths);
            double[] declaredCenter = extractCenter(element);
            double[] center = "relation".equals(type) && Double.isFinite(declaredCenter[0])
                    ? declaredCenter
                    : geometry.isEmpty() ? declaredCenter : Geo.centroid(geometry);
            if (!Double.isFinite(center[0])) continue;
            // A relation may contain multiple outer/inner rings. Never flatten those members
            // into one containment polygon. We retain each member path separately for proximity
            // distance, while point-in-polygon remains limited to a single OSM way.
            List<double[]> containmentPolygon = "way".equals(type) && !geometryPaths.isEmpty()
                    && Geo.isClosedPath(geometryPaths.get(0))
                    ? geometryPaths.get(0) : new ArrayList<>();

            Set<Integer> nonExistent = parseIntSet(tags.optString("non_existent_levels", null));
            Double height = parseMeters(tags.optString("height", null));
            List<String> refs = splitRefs(tags.optString("building:levels:ref", null));
            String name = clean(tags.optString("name", null));
            if (name == null) name = "Parking garage " + type + "/" + id;

            Garage garage = new Garage(type, id, name, parkingType, center[0], center[1], containmentPolygon, geometryPaths,
                    buildingLevels, undergroundLevels, minLevel, maxLevel, nonExistent, height, refs);
            garages.put(type + "/" + id, garage);
        }
        return new ParseResult(new ArrayList<>(garages.values()), entrances);
    }

    private static void assignEntrances(List<Garage> garages, List<GarageEntrance> entrances) {
        for (GarageEntrance entrance : entrances) {
            Garage best = null;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (Garage garage : garages) {
                double d = garage.distanceMeters(entrance.latitude, entrance.longitude);
                if (d < bestDistance) {
                    best = garage;
                    bestDistance = d;
                }
            }
            if (best != null && bestDistance <= 120.0) best.entrances.add(entrance);
        }
    }

    private static List<List<double[]>> extractGeometryPaths(JSONObject element) {
        List<List<double[]>> paths = new ArrayList<>();
        JSONArray geometry = element.optJSONArray("geometry");
        List<double[]> direct = readGeometry(geometry);
        if (!direct.isEmpty()) {
            paths.add(direct);
            return paths;
        }
        JSONArray members = element.optJSONArray("members");
        if (members != null) {
            for (int i = 0; i < members.length(); i++) {
                JSONObject member = members.optJSONObject(i);
                if (member == null) continue;
                // A point inside an inner ring (courtyard/void) is not inside the garage area.
                // Ignore explicitly tagged inner members rather than treating them as candidate
                // exterior boundaries. Outer rings and unclassified members remain available.
                if ("inner".equals(member.optString("role", ""))) continue;
                List<double[]> path = readGeometry(member.optJSONArray("geometry"));
                if (!path.isEmpty()) paths.add(path);
            }
        }
        return paths;
    }

    private static List<double[]> readGeometry(JSONArray geometry) {
        List<double[]> out = new ArrayList<>();
        if (geometry == null) return out;
        for (int i = 0; i < geometry.length(); i++) {
            JSONObject p = geometry.optJSONObject(i);
            if (p == null) continue;
            double lat = p.optDouble("lat", Double.NaN);
            double lon = p.optDouble("lon", Double.NaN);
            if (Double.isFinite(lat) && Double.isFinite(lon)) out.add(new double[]{lat, lon});
        }
        return out;
    }

    private static List<double[]> flattenGeometry(List<List<double[]>> paths) {
        List<double[]> out = new ArrayList<>();
        for (List<double[]> path : paths) out.addAll(path);
        return out;
    }

    private static double[] extractCenter(JSONObject element) {
        if (element.has("lat") && element.has("lon")) {
            return new double[]{element.optDouble("lat", Double.NaN), element.optDouble("lon", Double.NaN)};
        }
        JSONObject center = element.optJSONObject("center");
        if (center != null) return new double[]{center.optDouble("lat", Double.NaN), center.optDouble("lon", Double.NaN)};
        JSONObject bounds = element.optJSONObject("bounds");
        if (bounds != null) {
            double south = bounds.optDouble("minlat", Double.NaN);
            double west = bounds.optDouble("minlon", Double.NaN);
            double north = bounds.optDouble("maxlat", Double.NaN);
            double east = bounds.optDouble("maxlon", Double.NaN);
            if (Double.isFinite(south) && Double.isFinite(west) && Double.isFinite(north) && Double.isFinite(east)) {
                return new double[]{(south + north) / 2.0, (west + east) / 2.0};
            }
        }
        return new double[]{Double.NaN, Double.NaN};
    }

    private static Integer parseStrictInt(String raw) {
        if (raw == null) return null;
        try {
            String value = raw.trim();
            // Multi-valued and fractional levels are legal in some OSM contexts. This estimator
            // models one discrete integer level per anchor, so ambiguous values must not be
            // silently collapsed to the first token or rounded.
            if (value.isEmpty() || value.contains(".") || value.contains(";") || value.contains(",")) return null;
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Set<Integer> parseIntSet(String raw) {
        Set<Integer> result = new HashSet<>();
        if (raw == null) return result;
        for (String token : raw.split("[;,]")) {
            try {
                String value = token.trim();
                if (!value.contains(".")) result.add(Integer.parseInt(value));
            } catch (Exception ignored) { }
        }
        return result;
    }

    private static Double parseMeters(String raw) {
        if (raw == null) return null;
        try {
            String cleaned = raw.trim().toLowerCase()
                    .replace("meters", "").replace("meter", "").replace("m", "").trim();
            return Double.parseDouble(cleaned);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<String> splitRefs(String raw) {
        List<String> refs = new ArrayList<>();
        if (raw == null) return refs;
        for (String token : raw.split(";")) {
            String value = clean(token);
            if (value != null) refs.add(value);
        }
        return refs;
    }

    private static String clean(String value) {
        if (value == null) return null;
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (b.length() + line.length() + 1 > MAX_RESPONSE_CHARS) {
                    throw new IllegalStateException("Overpass response exceeded safety limit");
                }
                b.append(line).append('\n');
            }
        }
        return b.toString();
    }

    private static String abbreviate(String value, int max) {
        value = value.replaceAll("\\s+", " ").trim();
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.trim().isEmpty() ? "" : ": " + message);
    }

    private static final class ParseResult {
        final List<Garage> garages;
        final List<GarageEntrance> entrances;

        ParseResult(List<Garage> garages, List<GarageEntrance> entrances) {
            this.garages = garages;
            this.entrances = entrances;
        }
    }
}
