package dev.radixen.garagelevel.model;

public final class TelemetryState {
    public boolean tracking;
    public long lastUpdateMillis;
    public String statusMessage = "Idle";

    public boolean garageDetected;
    public double garageConfidence;
    public String garageReason = "No evidence";
    public String garageName = "—";
    public String garageId = "—";
    public String garageType = "—";
    public double garageDistanceMeters = Double.NaN;
    public String validLevels = "unknown";
    public String garageLevelMetadata = "unknown";

    public boolean floorAvailable;
    public int logicalLevel;
    public String levelLabel = "—";
    public double floorConfidence;
    public boolean transitioning;
    public String levelSource = "—";
    public double floorHeightMeters = 3.0;
    public String floorHeightSource = "default";
    public String entryLevelSource = "—";
    public double entryLevelConfidence;

    public boolean pressureAvailable;
    public String pressureSensorName = "—";
    public double rawPressureHpa = Double.NaN;
    public double filteredPressureHpa = Double.NaN;
    public double pressureBaselineHpa = Double.NaN;
    public double pressureStdDevHpa = Double.NaN;
    public double relativeAltitudeMeters = Double.NaN;
    public double verticalSpeedMps = Double.NaN;

    public boolean locationAvailable;
    public double latitude = Double.NaN;
    public double longitude = Double.NaN;
    public float horizontalAccuracyMeters = Float.NaN;
    public double gnssAltitudeMeters = Double.NaN;
    public float verticalAccuracyMeters = Float.NaN;
    public float speedMps = Float.NaN;
    public float bearingDegrees = Float.NaN;
    public String locationProvider = "—";
    public long locationAgeMillis;
    public long locationFixElapsedRealtimeNanos;
    public int satellitesVisible;
    public int satellitesUsed;
    public double averageCn0DbHz = Double.NaN;

    public String accelerometerName = "—";
    public double accelX = Double.NaN;
    public double accelY = Double.NaN;
    public double accelZ = Double.NaN;
    public double accelMagnitude = Double.NaN;

    public String gyroscopeName = "—";
    public double gyroX = Double.NaN;
    public double gyroY = Double.NaN;
    public double gyroZ = Double.NaN;
    public double gyroMagnitude = Double.NaN;

    public int nearbyGarages;
    public int nearbyEntrances;
    public long lastOsmFetchMillis;
    public String lastOsmError = "—";
    public String osmStatus = "Not queried";
    public int osmQueryRadiusMeters = 500;

    public String recentEvents = "";

    public TelemetryState copy() {
        TelemetryState s = new TelemetryState();
        s.tracking = tracking;
        s.lastUpdateMillis = lastUpdateMillis;
        s.statusMessage = statusMessage;
        s.garageDetected = garageDetected;
        s.garageConfidence = garageConfidence;
        s.garageReason = garageReason;
        s.garageName = garageName;
        s.garageId = garageId;
        s.garageType = garageType;
        s.garageDistanceMeters = garageDistanceMeters;
        s.validLevels = validLevels;
        s.garageLevelMetadata = garageLevelMetadata;
        s.floorAvailable = floorAvailable;
        s.logicalLevel = logicalLevel;
        s.levelLabel = levelLabel;
        s.floorConfidence = floorConfidence;
        s.transitioning = transitioning;
        s.levelSource = levelSource;
        s.floorHeightMeters = floorHeightMeters;
        s.floorHeightSource = floorHeightSource;
        s.entryLevelSource = entryLevelSource;
        s.entryLevelConfidence = entryLevelConfidence;
        s.pressureAvailable = pressureAvailable;
        s.pressureSensorName = pressureSensorName;
        s.rawPressureHpa = rawPressureHpa;
        s.filteredPressureHpa = filteredPressureHpa;
        s.pressureBaselineHpa = pressureBaselineHpa;
        s.pressureStdDevHpa = pressureStdDevHpa;
        s.relativeAltitudeMeters = relativeAltitudeMeters;
        s.verticalSpeedMps = verticalSpeedMps;
        s.locationAvailable = locationAvailable;
        s.latitude = latitude;
        s.longitude = longitude;
        s.horizontalAccuracyMeters = horizontalAccuracyMeters;
        s.gnssAltitudeMeters = gnssAltitudeMeters;
        s.verticalAccuracyMeters = verticalAccuracyMeters;
        s.speedMps = speedMps;
        s.bearingDegrees = bearingDegrees;
        s.locationProvider = locationProvider;
        s.locationAgeMillis = locationAgeMillis;
        s.locationFixElapsedRealtimeNanos = locationFixElapsedRealtimeNanos;
        s.satellitesVisible = satellitesVisible;
        s.satellitesUsed = satellitesUsed;
        s.averageCn0DbHz = averageCn0DbHz;
        s.accelerometerName = accelerometerName;
        s.accelX = accelX;
        s.accelY = accelY;
        s.accelZ = accelZ;
        s.accelMagnitude = accelMagnitude;
        s.gyroscopeName = gyroscopeName;
        s.gyroX = gyroX;
        s.gyroY = gyroY;
        s.gyroZ = gyroZ;
        s.gyroMagnitude = gyroMagnitude;
        s.nearbyGarages = nearbyGarages;
        s.nearbyEntrances = nearbyEntrances;
        s.lastOsmFetchMillis = lastOsmFetchMillis;
        s.lastOsmError = lastOsmError;
        s.osmStatus = osmStatus;
        s.osmQueryRadiusMeters = osmQueryRadiusMeters;
        s.recentEvents = recentEvents;
        return s;
    }
}
