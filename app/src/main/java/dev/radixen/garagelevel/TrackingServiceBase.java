package dev.radixen.garagelevel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;

import dev.radixen.garagelevel.data.OverpassGarageRepository;
import dev.radixen.garagelevel.estimator.FloorEstimator;
import dev.radixen.garagelevel.estimator.GarageDetector;
import dev.radixen.garagelevel.estimator.PressureFilter;
import dev.radixen.garagelevel.model.Garage;
import dev.radixen.garagelevel.model.GarageEntrance;
import dev.radixen.garagelevel.model.TelemetryState;
import dev.radixen.garagelevel.util.DebugLog;

import java.util.ArrayList;
import java.util.List;

abstract class TrackingServiceBase extends Service implements SensorEventListener, LocationListener {
    public static final String ACTION_START = "dev.radixen.garagelevel.START";
    public static final String ACTION_STOP = "dev.radixen.garagelevel.STOP";
    public static final String ACTION_REFRESH_OSM = "dev.radixen.garagelevel.REFRESH_OSM";
    public static final String ACTION_REANCHOR = "dev.radixen.garagelevel.REANCHOR";
    public static final String ACTION_SET_FLOOR_HEIGHT = "dev.radixen.garagelevel.SET_FLOOR_HEIGHT";
    public static final String EXTRA_LEVEL = "level";
    public static final String EXTRA_FLOOR_HEIGHT = "floor_height";

    protected static final String CHANNEL_ID = "garage_tracking";
    protected static final int NOTIFICATION_ID = 8021;
    protected static final long OSM_MIN_REFRESH_MS = 120_000L;
    protected static final long OSM_MIN_ATTEMPT_INTERVAL_MS = 30_000L;
    protected static final double OSM_REFRESH_DISTANCE_M = 250.0;
    protected static final long LATE_PRESSURE_ANCHOR_WINDOW_MS = 5_000L;

    protected final TelemetryState state = new TelemetryState();
    protected final PressureFilter pressureFilter = new PressureFilter();
    protected final GarageDetector garageDetector = new GarageDetector();
    protected final FloorEstimator floorEstimator = new FloorEstimator();
    protected final DebugLog debugLog = new DebugLog(24);
    protected final OverpassGarageRepository garageRepository = new OverpassGarageRepository();

    protected SensorManager sensorManager;
    protected LocationManager locationManager;
    protected Sensor pressureSensor;
    protected Sensor accelerometer;
    protected Sensor gyroscope;
    protected final List<Garage> nearbyGarages = new ArrayList<>();
    protected volatile boolean osmFetchInFlight;
    protected long osmRequestSerial;
    protected long activeOsmRequestId;
    protected long lastOsmFetchMillis;
    protected long lastOsmAttemptElapsed;
    protected double lastFetchLat = Double.NaN;
    protected double lastFetchLon = Double.NaN;
    protected Location lastLocation;
    protected Handler mainHandler;
    protected double pressureBaselineHpa = Double.NaN;
    protected double configuredFloorHeightMeters = 3.0;
    protected String configuredFloorHeightSource = "default";
    protected double floorAnchorConfidence;
    protected Garage activeGarage;
    protected long lastNotificationUpdate;
    protected long lastStatePublishElapsed;
    protected long garageEntryElapsed;
    protected volatile boolean destroyed;

    protected final GnssStatus.Callback gnssCallback = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(GnssStatus status) {
            if (!state.tracking) return;
            int visible = status.getSatelliteCount();
            int used = 0;
            double cn0 = 0.0;
            int cn0Count = 0;
            for (int i = 0; i < visible; i++) {
                if (status.usedInFix(i)) used++;
                float value = status.getCn0DbHz(i);
                if (value > 0) {
                    cn0 += value;
                    cn0Count++;
                }
            }
            state.satellitesVisible = visible;
            state.satellitesUsed = used;
            state.averageCn0DbHz = cn0Count == 0 ? Double.NaN : cn0 / cn0Count;
            publishThrottled();
        }
    };

    protected abstract void evaluateGarage(Location location);

    protected void publish() {
        state.lastUpdateMillis = System.currentTimeMillis();
        state.recentEvents = debugLog.render();
        AppState.get().publish(state);
        maybeUpdateNotification();
    }

    protected void maybeUpdateNotification() {
        if (!state.tracking) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotificationUpdate < 2_000L) return;
        lastNotificationUpdate = now;
        String text = state.garageDetected
                ? state.garageName + (state.floorAvailable ? " — " + state.levelLabel : " — floor unknown")
                : "Watching for parking garages";
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    protected Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("Garage Level Lab")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    protected void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Garage tracking",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Visible while Garage Level Lab is actively reading location and sensors.");
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    protected static Location preferLocation(Location oldLocation, Location newLocation) {
        if (oldLocation == null) return newLocation;
        long timeDelta = (newLocation.getElapsedRealtimeNanos() - oldLocation.getElapsedRealtimeNanos()) / 1_000_000L;
        if (timeDelta > 10_000L) return newLocation;
        if (timeDelta < -2_000L) return oldLocation;

        float oldAcc = oldLocation.hasAccuracy() ? oldLocation.getAccuracy() : Float.MAX_VALUE;
        float newAcc = newLocation.hasAccuracy() ? newLocation.getAccuracy() : Float.MAX_VALUE;
        if (newAcc <= oldAcc) return newLocation;
        if (timeDelta > 5_000L && newAcc <= Math.max(oldAcc * 2.0f, oldAcc + 35.0f)) return newLocation;
        return oldLocation;
    }

    protected void publishThrottled() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastStatePublishElapsed < 200L) return;
        lastStatePublishElapsed = now;
        publish();
    }

    protected void clearOsmCache() {
        synchronized (nearbyGarages) {
            nearbyGarages.clear();
        }
        lastOsmFetchMillis = 0L;
        lastOsmAttemptElapsed = 0L;
        lastFetchLat = Double.NaN;
        lastFetchLon = Double.NaN;
        state.nearbyGarages = 0;
        state.nearbyEntrances = 0;
        state.lastOsmFetchMillis = 0L;
        state.lastOsmError = "—";
        state.osmStatus = "Waiting for location fix";
    }

    protected void invalidateOsmRequest() {
        activeOsmRequestId = ++osmRequestSerial;
        osmFetchInFlight = false;
    }

    protected void cleanupRegistrations() {
        try { sensorManager.unregisterListener(this); } catch (Exception ignored) { }
        try { locationManager.removeUpdates(this); } catch (Exception ignored) { }
        try { locationManager.unregisterGnssStatusCallback(gnssCallback); } catch (Exception ignored) { }
    }

    protected void resetTrackingTelemetry() {
        state.garageDetected = false;
        state.garageConfidence = 0.0;
        state.garageReason = "No evidence";
        state.garageName = "—";
        state.garageId = "—";
        state.garageType = "—";
        state.garageDistanceMeters = Double.NaN;
        state.validLevels = "unknown";
        state.garageLevelMetadata = "unknown";
        state.floorAvailable = false;
        state.logicalLevel = 0;
        state.levelLabel = "—";
        state.floorConfidence = 0.0;
        state.transitioning = false;
        state.levelSource = "—";
        state.entryLevelSource = "—";
        state.entryLevelConfidence = 0.0;
        state.floorHeightMeters = configuredFloorHeightMeters;
        state.floorHeightSource = configuredFloorHeightSource;
        state.rawPressureHpa = Double.NaN;
        state.filteredPressureHpa = Double.NaN;
        state.pressureBaselineHpa = Double.NaN;
        state.pressureStdDevHpa = Double.NaN;
        state.relativeAltitudeMeters = Double.NaN;
        state.verticalSpeedMps = Double.NaN;
        state.locationAvailable = false;
        state.latitude = Double.NaN;
        state.longitude = Double.NaN;
        state.horizontalAccuracyMeters = Float.NaN;
        state.gnssAltitudeMeters = Double.NaN;
        state.verticalAccuracyMeters = Float.NaN;
        state.speedMps = Float.NaN;
        state.bearingDegrees = Float.NaN;
        state.locationProvider = "—";
        state.locationAgeMillis = 0L;
        state.locationFixElapsedRealtimeNanos = 0L;
        state.satellitesVisible = 0;
        state.satellitesUsed = 0;
        state.averageCn0DbHz = Double.NaN;
        state.accelX = state.accelY = state.accelZ = state.accelMagnitude = Double.NaN;
        state.gyroX = state.gyroY = state.gyroZ = state.gyroMagnitude = Double.NaN;
        lastLocation = null;
    }

    protected static long locationAgeMillis(Location location) {
        long ageNanos = SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
        return Math.max(0L, ageNanos / 1_000_000L);
    }

    protected static final class EntranceMatch {
        final GarageEntrance entrance;
        final double distanceMeters;
        final double confidence;

        EntranceMatch(GarageEntrance entrance, double distanceMeters, double confidence) {
            this.entrance = entrance;
            this.distanceMeters = distanceMeters;
            this.confidence = confidence;
        }
    }

    protected static double magnitude(float[] values) {
        if (values == null || values.length < 3) return Double.NaN;
        return Math.sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2]);
    }

    protected static int nearestValidLevel(List<Integer> levels, int preferred) {
        if (levels == null || levels.isEmpty()) return preferred;
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

    protected static double clampFloorHeight(double value) {
        if (!Double.isFinite(value)) return 3.0;
        return Math.max(2.2, Math.min(5.5, value));
    }

    protected static int clampRadius(int radius) {
        return Math.max(200, Math.min(1500, radius));
    }

    protected static String renderLevels(List<Integer> levels, boolean topologyKnown) {
        if (!topologyKnown) return "map incomplete; estimator range " + levels.get(0) + "…" + levels.get(levels.size() - 1);
        StringBuilder b = new StringBuilder();
        for (Integer level : levels) {
            if (b.length() > 0) b.append(", ");
            b.append(level);
        }
        return b.toString();
    }

    protected static String metadata(Garage g) {
        return "building:levels=" + value(g.buildingLevels)
                + ", underground=" + value(g.undergroundLevels)
                + ", min=" + value(g.minLevel)
                + ", max=" + value(g.maxLevel)
                + ", height=" + (g.heightMeters == null ? "?" : g.heightMeters + "m")
                + (g.nonExistentLevels.isEmpty() ? "" : ", missing=" + g.nonExistentLevels);
    }

    protected static String value(Object value) {
        return value == null ? "?" : String.valueOf(value);
    }
}
