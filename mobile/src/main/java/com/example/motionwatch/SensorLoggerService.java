package com.example.motionwatch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SensorLoggerService extends Service implements SensorEventListener {

    public static final String ACTION_START = "com.example.motionwatch.action.START";
    public static final String ACTION_STOP  = "com.example.motionwatch.action.STOP";

    public static final String ACTION_LOG_DONE = "com.example.motionwatch.LOG_DONE";

    // ✅ NEW: live UI updates + state
    public static final String ACTION_LOG_STATE = "com.example.motionwatch.LOG_STATE";
    public static final String ACTION_LIVE_SAMPLE = "com.example.motionwatch.LIVE_SAMPLE";

    private static final String TAG = "PhoneLogger";

    private static final int NOTIF_ID = 1;
    private static final String NOTIF_CH_ID = "logger";

    private SensorManager sensorManager;
    private Sensor acc, gyro;
    private BufferedWriter writer;

    private String label = "unlabeled";
    private String sessionId = "unknown";
    private long bootToEpochOffsetMs;

    private long startEpochMs = 0;
    private boolean running = false;
    private boolean stopping = false;

    // stats for summary
    private long accN = 0, gyroN = 0;
    private double accSx = 0, accSy = 0, accSz = 0;
    private double gyrSx = 0, gyrSy = 0, gyrSz = 0;

    // ✅ Throttle UI broadcasts (don’t spam UI at 200Hz)
    private int uiEveryN = 5;
    private int uiCounter = 0;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }

        bootToEpochOffsetMs = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        Log.d(TAG, "onCreate() acc=" + (acc != null) + " gyro=" + (gyro != null));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        Log.d(TAG, "onStartCommand action=" + action
                + " label=" + intent.getStringExtra("label")
                + " sessionId=" + intent.getStringExtra("sessionId"));

        if (ACTION_START.equals(action)) {
            if (running) {
                Log.d(TAG, "Already running; ignoring START");
                return START_STICKY;
            }

            sessionId = safe(intent.getStringExtra("sessionId"), "unknown");
            label = safe(intent.getStringExtra("label"), "unlabeled");
            startEpochMs = intent.getLongExtra("startEpochMs", System.currentTimeMillis());

            // 1) foreground immediately
            startForeground(NOTIF_ID, buildNotification("Logging PHONE… (" + label + ")"));

            // 2) init stats + file + sensors
            resetStats();
            uiCounter = 0;

            try {
                openCsv();
            } catch (Exception e) {
                Log.e(TAG, "openCsv failed, stopping service", e);
                stopNow();
                return START_NOT_STICKY;
            }

            registerSensors();
            running = true;
            stopping = false;

            // ✅ tell UI we started
            sendStateBroadcast(true);

            Log.d(TAG, "STARTED label=" + label + " sessionId=" + sessionId);
            return START_STICKY;
        }

        if (ACTION_STOP.equals(action)) {
            Log.d(TAG, "STOP received");
            stopNow();
            return START_NOT_STICKY;
        }

        return START_NOT_STICKY;
    }

    private void registerSensors() {
        if (sensorManager == null) return;

        int delay = SensorManager.SENSOR_DELAY_GAME;

        if (acc != null) {
            sensorManager.registerListener(this, acc, delay);
            Log.d(TAG, "ACC registered");
        } else {
            Log.e(TAG, "ACC not available");
        }

        if (gyro != null) {
            sensorManager.registerListener(this, gyro, delay);
            Log.d(TAG, "GYRO registered");
        } else {
            Log.e(TAG, "GYRO not available");
        }
    }

    private void stopNow() {
        if (stopping) return;
        stopping = true;

        boolean wasRunning = running;
        running = false;

        try { if (sensorManager != null) sensorManager.unregisterListener(this); } catch (Exception ignored) {}

        long durationMs = System.currentTimeMillis() - startEpochMs;

        try {
            if (writer != null) {
                writer.write("# SUMMARY,duration_ms=" + durationMs
                        + ",accN=" + accN + ",gyroN=" + gyroN
                        + ",accAvg=(" + (accN > 0 ? (accSx / accN) : 0) + "," + (accN > 0 ? (accSy / accN) : 0) + "," + (accN > 0 ? (accSz / accN) : 0) + ")"
                        + ",gyroAvg=(" + (gyroN > 0 ? (gyrSx / gyroN) : 0) + "," + (gyroN > 0 ? (gyrSy / gyroN) : 0) + "," + (gyroN > 0 ? (gyrSz / gyroN) : 0) + ")"
                        + "\n");
                writer.flush();
                writer.close();
                writer = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing writer", e);
        }

        try { stopForeground(true); } catch (Exception ignored) {}
        stopSelf();

        // ✅ tell UI we stopped
        sendStateBroadcast(false);

        // Broadcast summary for UI
        if (wasRunning) {
            Intent done = new Intent(ACTION_LOG_DONE);
            done.setPackage(getPackageName());
            done.putExtra("sessionId", sessionId);
            done.putExtra("label", label);
            done.putExtra("durationMs", durationMs);
            done.putExtra("accAvgX", accN > 0 ? accSx / accN : 0);
            done.putExtra("accAvgY", accN > 0 ? accSy / accN : 0);
            done.putExtra("accAvgZ", accN > 0 ? accSz / accN : 0);
            done.putExtra("gyrAvgX", gyroN > 0 ? gyrSx / gyroN : 0);
            done.putExtra("gyrAvgY", gyroN > 0 ? gyrSy / gyroN : 0);
            done.putExtra("gyrAvgZ", gyroN > 0 ? gyrSz / gyroN : 0);
            sendBroadcast(done);
        }

        Log.d(TAG, "STOPPED sessionId=" + sessionId);
    }

    private void openCsv() throws IOException {
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();

        File logsDir = new File(base, "logs");
        if (!logsDir.exists()) logsDir.mkdirs();

        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String safeLabel = safe(label, "unlabeled");
        String safeSession = safe(sessionId, "unknown");

        String name = "SESSION_" + safeSession + "_PHONE_" + safeLabel + "_" + ts + ".csv";
        File file = new File(logsDir, name);

        writer = new BufferedWriter(new FileWriter(file, false));
        writer.write("# epoch_ms,event_ts_ns,sensor,x,y,z,label,sessionId\n");
        writer.flush();

        Log.i(TAG, "Writing: " + file.getAbsolutePath());
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!running || writer == null) return;

        long epochMs = bootToEpochOffsetMs + e.timestamp / 1_000_000L;
        String type = (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) ? "ACC" :
                (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) ? "GYRO" : "NA";

        float x = e.values[0], y = e.values[1], z = e.values[2];

        if ("ACC".equals(type)) { accSx += x; accSy += y; accSz += z; accN++; }
        if ("GYRO".equals(type)) { gyrSx += x; gyrSy += y; gyrSz += z; gyroN++; }

        try {
            writer.write(epochMs + "," + e.timestamp + "," + type + ","
                    + x + "," + y + "," + z + "," + label + "," + sessionId + "\n");
        } catch (IOException ex) {
            Log.e(TAG, "write failed", ex);
        }

        // ✅ Live UI broadcast (throttled)
        uiCounter++;
        if (uiCounter % uiEveryN == 0) {
            Intent live = new Intent(ACTION_LIVE_SAMPLE);
            live.setPackage(getPackageName());
            live.putExtra("sessionId", sessionId);
            live.putExtra("label", label);
            live.putExtra("sensor", type);
            live.putExtra("x", x);
            live.putExtra("y", y);
            live.putExtra("z", z);
            sendBroadcast(live);
        }
    }

    private void sendStateBroadcast(boolean started) {
        Intent st = new Intent(ACTION_LOG_STATE);
        st.setPackage(getPackageName());
        st.putExtra("started", started);
        st.putExtra("sessionId", sessionId);
        st.putExtra("label", label);
        st.putExtra("startEpochMs", startEpochMs);
        sendBroadcast(st);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopNow();
    }

    private void resetStats() {
        accN = gyroN = 0;
        accSx = accSy = accSz = 0;
        gyrSx = gyrSy = gyrSz = 0;
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    NOTIF_CH_ID, "Motion Logger", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }

        return new NotificationCompat.Builder(this, NOTIF_CH_ID)
                .setContentTitle("Motion Logger")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private static String safe(String v, String fallback) {
        if (v == null) return fallback;
        v = v.trim();
        return v.isEmpty() ? fallback : v;
    }
}
