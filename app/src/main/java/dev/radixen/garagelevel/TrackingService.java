package dev.radixen.garagelevel;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

public final class TrackingService extends TrackingServiceSession {
    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        state.pressureAvailable = pressureSensor != null;
        state.pressureSensorName = pressureSensor == null ? "Unavailable" : pressureSensor.getName();
        state.accelerometerName = accelerometer == null ? "Unavailable" : accelerometer.getName();
        state.gyroscopeName = gyroscope == null ? "Unavailable" : gyroscope.getName();
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        configuredFloorHeightMeters = clampFloorHeight(prefs.getFloat("floor_height", 3.0f));
        configuredFloorHeightSource = prefs.contains("floor_height") ? "user setting" : "default";
        state.floorHeightMeters = configuredFloorHeightMeters;
        state.floorHeightSource = configuredFloorHeightSource;
        floorEstimator.setFloorHeightMeters(configuredFloorHeightMeters);
        state.osmQueryRadiusMeters = clampRadius(prefs.getInt("query_radius", 500));
        createNotificationChannel();
        debugLog.add("Service created");
        publish();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            state.statusMessage = "Stopped; explicit Start required";
            debugLog.add("Ignored service recreation without an explicit command");
            publish();
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            startTracking();
        } else if (ACTION_STOP.equals(action)) {
            stopTracking();
        } else if (ACTION_REANCHOR.equals(action)) {
            reanchor(intent.getIntExtra(EXTRA_LEVEL, 0));
        } else if (ACTION_SET_FLOOR_HEIGHT.equals(action)) {
            setFloorHeight(intent.getDoubleExtra(EXTRA_FLOOR_HEIGHT, 3.0));
        } else if (ACTION_REFRESH_OSM.equals(action)) {
            forceRefreshOsm();
        } else {
            debugLog.add("Ignored unknown service action: " + action);
            publish();
            if (!state.tracking) stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private void startTracking() {
        if (state.tracking) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            state.statusMessage = "Precise location permission required";
            debugLog.add("Cannot start: precise location permission missing");
            publish();
            stopSelf();
            return;
        }

        boolean gpsEnabled;
        boolean networkEnabled;
        try {
            gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            state.statusMessage = "Unable to read device location state";
            debugLog.add("Location provider check failed: " + e.getMessage());
            publish();
            stopSelf();
            return;
        }
        if (!gpsEnabled && !networkEnabled) {
            state.statusMessage = "Enable device Location/GPS, then press Start";
            debugLog.add("Cannot start: no location provider is enabled");
            publish();
            stopSelf();
            return;
        }

        resetTrackingTelemetry();
        pressureFilter.reset();
        garageDetector.reset();
        floorEstimator.stop();
        pressureBaselineHpa = Double.NaN;
        floorAnchorConfidence = 0.0;
        activeGarage = null;
        garageEntryElapsed = 0L;
        lastNotificationUpdate = 0L;
        lastStatePublishElapsed = 0L;
        invalidateOsmRequest();
        clearOsmCache();

        try {
            startForeground(NOTIFICATION_ID, buildNotification("Starting sensors…"));
            state.tracking = true;
            state.statusMessage = "Tracking";

            if (pressureSensor != null) sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL);
            if (accelerometer != null) sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
            if (gyroscope != null) sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL);

            if (gpsEnabled) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this);
                locationManager.registerGnssStatusCallback(gnssCallback, mainHandler);
            }
            if (networkEnabled) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, this);
            }
        } catch (SecurityException e) {
            state.statusMessage = "Location/foreground-service permission rejected";
            debugLog.add("Tracking start failed: " + e.getMessage());
            cleanupRegistrations();
            state.tracking = false;
            stopForeground(true);
            publish();
            stopSelf();
            return;
        } catch (RuntimeException e) {
            state.statusMessage = "Tracking start failed: " + e.getClass().getSimpleName();
            debugLog.add("Tracking start failed: " + e.getMessage());
            cleanupRegistrations();
            state.tracking = false;
            stopForeground(true);
            publish();
            stopSelf();
            return;
        }
        debugLog.add("Tracking started");
        publish();
    }

    private void stopTracking() {
        state.tracking = false;
        cleanupRegistrations();
        floorEstimator.stop();
        garageDetector.reset();
        activeGarage = null;
        garageEntryElapsed = 0L;
        pressureBaselineHpa = Double.NaN;
        floorAnchorConfidence = 0.0;
        resetTrackingTelemetry();
        invalidateOsmRequest();
        clearOsmCache();
        state.statusMessage = "Stopped";
        debugLog.add("Tracking stopped");
        publish();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        cleanupRegistrations();
        invalidateOsmRequest();
        garageRepository.shutdown();
        floorEstimator.stop();
        garageDetector.reset();
        activeGarage = null;
        garageEntryElapsed = 0L;
        pressureBaselineHpa = Double.NaN;
        floorAnchorConfidence = 0.0;
        state.tracking = false;
        resetTrackingTelemetry();
        clearOsmCache();
        state.statusMessage = "Service stopped";
        publish();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!state.tracking) return;
        long callbackNow = SystemClock.elapsedRealtime();
        long sampleTime = event.timestamp > 0L ? event.timestamp / 1_000_000L : callbackNow;
        if (event.sensor.getType() == Sensor.TYPE_PRESSURE) {
            double raw = event.values[0];
            double filtered = pressureFilter.add(raw, sampleTime);
            state.rawPressureHpa = raw;
            state.filteredPressureHpa = filtered;
            state.pressureStdDevHpa = pressureFilter.standardDeviationHpa(sampleTime, 3_500L);
            state.verticalSpeedMps = pressureFilter.verticalSpeedMps(sampleTime, 4_000L);
            state.pressureAvailable = true;
            if (state.garageDetected && !floorEstimator.isActive() && lastLocation != null
                    && garageEntryElapsed > 0L && callbackNow - garageEntryElapsed <= LATE_PRESSURE_ANCHOR_WINDOW_MS) {
                debugLog.add("Pressure became available shortly after garage entry; starting floor estimator");
                beginFloorSession(lastLocation, activeGarage == null);
            } else {
                updateFloorFromPressure();
            }
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            state.accelX = event.values[0];
            state.accelY = event.values[1];
            state.accelZ = event.values[2];
            state.accelMagnitude = magnitude(event.values);
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            state.gyroX = event.values[0];
            state.gyroY = event.values[1];
            state.gyroZ = event.values[2];
            state.gyroMagnitude = magnitude(event.values);
        }
        publishThrottled();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Sensor accuracy is device-defined; raw values remain visible in the dashboard.
    }

    @Override
    public void onLocationChanged(Location location) {
        if (!state.tracking) return;
        Location previous = lastLocation;
        Location preferred = preferLocation(previous, location);
        if (previous != null && preferred == previous && preferred != location) return;
        lastLocation = preferred;
        Location current = lastLocation;
        if (current == null) return;
        state.locationAvailable = true;
        state.latitude = current.getLatitude();
        state.longitude = current.getLongitude();
        state.horizontalAccuracyMeters = current.hasAccuracy() ? current.getAccuracy() : Float.NaN;
        state.gnssAltitudeMeters = current.hasAltitude() ? current.getAltitude() : Double.NaN;
        if (Build.VERSION.SDK_INT >= 26 && current.hasVerticalAccuracy()) {
            state.verticalAccuracyMeters = current.getVerticalAccuracyMeters();
        } else {
            state.verticalAccuracyMeters = Float.NaN;
        }
        state.speedMps = current.hasSpeed() ? current.getSpeed() : 0f;
        state.bearingDegrees = current.hasBearing() ? current.getBearing() : Float.NaN;
        state.locationProvider = current.getProvider() == null ? "unknown" : current.getProvider();
        state.locationAgeMillis = locationAgeMillis(current);
        state.locationFixElapsedRealtimeNanos = current.getElapsedRealtimeNanos();

        maybeFetchOsm(current, false);
        evaluateGarage(current);
        publish();
    }

    @Override public void onProviderEnabled(String provider) { debugLog.add(provider + " enabled"); publish(); }
    @Override public void onProviderDisabled(String provider) { debugLog.add(provider + " disabled"); publish(); }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
}
