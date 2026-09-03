package dev.radixen.garagelevel;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import dev.radixen.garagelevel.model.TelemetryState;
import dev.radixen.garagelevel.util.Format;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 4001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView tvPrimary;
    private TextView tvSecondary;
    private TextView tvGarage;
    private TextView tvFloor;
    private TextView tvBarometer;
    private TextView tvLocation;
    private TextView tvMotion;
    private TextView tvData;
    private TextView tvEvents;
    private Button btnStart;
    private Button btnStop;
    private Button btnRefresh;
    private Button btnReanchor;
    private EditText editAnchorLevel;
    private EditText editFloorHeight;

    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            render(AppState.get().snapshot());
            handler.postDelayed(this, 500L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();

        float savedFloorHeight = getSharedPreferences("settings", MODE_PRIVATE).getFloat("floor_height", 3.0f);
        editFloorHeight.setText(String.valueOf(savedFloorHeight));

        btnStart.setOnClickListener(v -> requestPermissionsAndStart());
        btnStop.setOnClickListener(v -> sendCommand(TrackingService.ACTION_STOP));
        btnRefresh.setOnClickListener(v -> sendCommand(TrackingService.ACTION_REFRESH_OSM));
        btnReanchor.setOnClickListener(v -> reanchor());
        findViewById(R.id.btnFloorHeight).setOnClickListener(v -> applyFloorHeight());
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshRunnable);
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    private void bindViews() {
        tvPrimary = findViewById(R.id.tvPrimary);
        tvSecondary = findViewById(R.id.tvSecondary);
        tvGarage = findViewById(R.id.tvGarage);
        tvFloor = findViewById(R.id.tvFloor);
        tvBarometer = findViewById(R.id.tvBarometer);
        tvLocation = findViewById(R.id.tvLocation);
        tvMotion = findViewById(R.id.tvMotion);
        tvData = findViewById(R.id.tvData);
        tvEvents = findViewById(R.id.tvEvents);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnReanchor = findViewById(R.id.btnReanchor);
        editAnchorLevel = findViewById(R.id.editAnchorLevel);
        editFloorHeight = findViewById(R.id.editFloorHeight);
    }

    private void requestPermissionsAndStart() {
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        } else {
            startTrackingService();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;
        boolean preciseLocationGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (preciseLocationGranted) {
            startTrackingService();
        } else {
            Toast.makeText(this, "Precise location is required to distinguish nearby parking structures.", Toast.LENGTH_LONG).show();
        }
    }

    private void startTrackingService() {
        Intent intent = new Intent(this, TrackingService.class).setAction(TrackingService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (RuntimeException e) {
            Toast.makeText(this, "Unable to start tracking: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private boolean sendServiceIntent(Intent intent, String failurePrefix) {
        try {
            startService(intent);
            return true;
        } catch (RuntimeException e) {
            Toast.makeText(this, failurePrefix + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void sendCommand(String action) {
        sendServiceIntent(new Intent(this, TrackingService.class).setAction(action), "Command failed");
    }

    private void reanchor() {
        String raw = editAnchorLevel.getText().toString().trim();
        if (raw.isEmpty()) {
            Toast.makeText(this, "Enter a logical level, e.g. 0, 1, or -1.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            int level = Integer.parseInt(raw);
            Intent intent = new Intent(this, TrackingService.class)
                    .setAction(TrackingService.ACTION_REANCHOR)
                    .putExtra(TrackingService.EXTRA_LEVEL, level);
            sendServiceIntent(intent, "Re-anchor failed");
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid logical level.", Toast.LENGTH_SHORT).show();
        }
    }

    private void applyFloorHeight() {
        String raw = editFloorHeight.getText().toString().trim();
        try {
            double value = Double.parseDouble(raw);
            if (value < 2.2 || value > 5.5) throw new NumberFormatException();
            getSharedPreferences("settings", MODE_PRIVATE).edit().putFloat("floor_height", (float) value).apply();
            if (AppState.get().snapshot().tracking) {
                Intent intent = new Intent(this, TrackingService.class)
                        .setAction(TrackingService.ACTION_SET_FLOOR_HEIGHT)
                        .putExtra(TrackingService.EXTRA_FLOOR_HEIGHT, value);
                if (!sendServiceIntent(intent, "Unable to update active tracker")) return;
            }
            Toast.makeText(this, "Floor height set to " + value + " m.", Toast.LENGTH_SHORT).show();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Use a floor height from 2.2 to 5.5 meters.", Toast.LENGTH_SHORT).show();
        }
    }

    private void render(TelemetryState s) {
        long fixAgeMillis = s.locationFixElapsedRealtimeNanos > 0L
                ? Math.max(0L, (SystemClock.elapsedRealtimeNanos() - s.locationFixElapsedRealtimeNanos) / 1_000_000L)
                : s.locationAgeMillis;
        btnStart.setEnabled(!s.tracking);
        btnStop.setEnabled(s.tracking);
        btnRefresh.setEnabled(s.tracking);
        btnReanchor.setEnabled(s.tracking && s.garageDetected && s.floorAvailable);

        if (!s.tracking) {
            tvPrimary.setText("TRACKING STOPPED");
            tvSecondary.setText(s.statusMessage);
        } else if (s.garageDetected) {
            tvPrimary.setText(s.floorAvailable ? s.levelLabel : "GARAGE — FLOOR UNKNOWN");
            tvSecondary.setText(s.garageName + "  •  garage " + Format.pct(s.garageConfidence)
                    + (s.floorAvailable ? "  •  floor " + Format.pct(s.floorConfidence) : ""));
        } else {
            tvPrimary.setText("NO GARAGE DETECTED");
            tvSecondary.setText(s.statusMessage + "  •  " + s.nearbyGarages + " mapped candidate(s) nearby");
        }

        tvGarage.setText(
                "detected:       " + s.garageDetected + "\n" +
                "confidence:     " + Format.pct(s.garageConfidence) + "\n" +
                "name:           " + s.garageName + "\n" +
                "OSM id:         " + s.garageId + "\n" +
                "type:           " + s.garageType + "\n" +
                "distance:       " + Format.d(s.garageDistanceMeters, 1) + " m\n" +
                "evidence:       " + s.garageReason + "\n" +
                "level metadata: " + s.garageLevelMetadata);

        tvFloor.setText(
                "available:      " + s.floorAvailable + "\n" +
                "prediction:     " + s.levelLabel + "\n" +
                "logical level:  " + (s.floorAvailable ? s.logicalLevel : "—") + "\n" +
                "confidence:     " + Format.pct(s.floorConfidence) + "\n" +
                "transitioning:  " + s.transitioning + "\n" +
                "valid levels:   " + s.validLevels + "\n" +
                "floor height:   " + Format.d(s.floorHeightMeters, 2) + " m\n" +
                "height source:  " + s.floorHeightSource + "\n" +
                "entry anchor:   " + s.entryLevelSource + "\n" +
                "anchor conf:    " + Format.pct(s.entryLevelConfidence) + "\n" +
                "estimator:      " + s.levelSource);

        tvBarometer.setText(
                "available:      " + s.pressureAvailable + "\n" +
                "sensor:         " + s.pressureSensorName + "\n" +
                "raw pressure:   " + Format.d(s.rawPressureHpa, 3) + " hPa\n" +
                "filtered:       " + Format.d(s.filteredPressureHpa, 3) + " hPa\n" +
                "baseline:       " + Format.d(s.pressureBaselineHpa, 3) + " hPa\n" +
                "3.5s std dev:   " + Format.d(s.pressureStdDevHpa, 4) + " hPa\n" +
                "relative alt:   " + Format.d(s.relativeAltitudeMeters, 2) + " m\n" +
                "vertical speed: " + Format.d(s.verticalSpeedMps, 2) + " m/s");

        tvLocation.setText(
                "available:      " + s.locationAvailable + "\n" +
                "lat:            " + Format.d(s.latitude, 7) + "\n" +
                "lon:            " + Format.d(s.longitude, 7) + "\n" +
                "provider:       " + s.locationProvider + "\n" +
                "h accuracy:     " + Format.f(s.horizontalAccuracyMeters, 1) + " m\n" +
                "GNSS altitude:  " + Format.d(s.gnssAltitudeMeters, 1) + " m\n" +
                "v accuracy:     " + Format.f(s.verticalAccuracyMeters, 1) + " m\n" +
                "speed:          " + Format.f(s.speedMps, 2) + " m/s\n" +
                "bearing:        " + Format.f(s.bearingDegrees, 1) + "°\n" +
                "satellites:     " + s.satellitesUsed + " used / " + s.satellitesVisible + " visible\n" +
                "avg C/N0:       " + Format.d(s.averageCn0DbHz, 1) + " dB-Hz\n" +
                "fix age:        " + (s.locationAvailable ? fixAgeMillis + " ms" : "—"));

        tvMotion.setText(
                "accelerometer:  " + s.accelerometerName + "\n" +
                "accel x/y/z:    " + Format.d(s.accelX, 3) + " / " + Format.d(s.accelY, 3) + " / " + Format.d(s.accelZ, 3) + " m/s²\n" +
                "accel magnitude:" + Format.d(s.accelMagnitude, 3) + " m/s²\n" +
                "gyroscope:      " + s.gyroscopeName + "\n" +
                "gyro x/y/z:     " + Format.d(s.gyroX, 3) + " / " + Format.d(s.gyroY, 3) + " / " + Format.d(s.gyroZ, 3) + " rad/s\n" +
                "gyro magnitude: " + Format.d(s.gyroMagnitude, 3) + " rad/s");

        tvData.setText(
                "source:         OpenStreetMap via public Overpass API\n" +
                "query radius:   " + s.osmQueryRadiusMeters + " m\n" +
                "garage records: " + s.nearbyGarages + "\n" +
                "entrances:      " + s.nearbyEntrances + "\n" +
                "last fetch:     " + Format.clock(s.lastOsmFetchMillis) + "\n" +
                "status:         " + s.osmStatus + "\n" +
                "last error:     " + s.lastOsmError + "\n" +
                "persistence:    settings only; no location history");

        tvEvents.setText(s.recentEvents == null || s.recentEvents.isEmpty() ? "No events" : s.recentEvents);
    }
}
