package com.example.motionwatch.presentation

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.motionwatch.R
import com.google.android.gms.wearable.Wearable
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class SensorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accSensor: Sensor? = null
    private var gyroSensor: Sensor? = null

    // ✅ single merged writer
    private var writer: BufferedWriter? = null

    private val channelId = "sensor_logging_channel"
    private val notifId = 101

    // received from MainActivity
    private var sportCategory: String = "UNKNOWN"

    // per-run session id (used for syncing + filenames)
    private var sessionId: String = ""

    // log folder
    private lateinit var logsDir: File

    // ✅ track the current file so we ONLY sync this run
    private var currentLogFile: File? = null

    // flush every N samples to reduce data loss
    private val flushEvery = 50
    private var linesSinceFlush = 0

    // simple counters for debugging
    private val accCount = AtomicLong(0)
    private val gyroCount = AtomicLong(0)

    // ✅ IMPORTANT: safe explicit sampling period to avoid 0us (FASTEST) crash
    // 10,000 us = 10 ms ≈ 100 Hz
    private val samplingPeriodUs = 10_000

    // ✅ Latest samples so each row contains both ACC and GYRO
    private var lastAx = Float.NaN
    private var lastAy = Float.NaN
    private var lastAz = Float.NaN
    private var lastGx = Float.NaN
    private var lastGy = Float.NaN
    private var lastGz = Float.NaN

    // ✅ Filename timestamp formatter: YYYY-MM-DD_HH-MM-SS (local time)
    private val fileTsFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)

    private fun formatFileTimestamp(epochMillis: Long): String {
        return Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(fileTsFormatter)
    }

    private fun sanitizeForFilename(s: String): String {
        return s.replace(Regex("[^A-Za-z0-9_-]"), "_")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        logsDir = File(filesDir, "motion_logs")
        if (!logsDir.exists()) logsDir.mkdirs()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand()")

        // Read category (label)
        sportCategory = intent?.getStringExtra("sport") ?: "UNKNOWN"

        // Create a fresh session id per start
        sessionId = intent?.getStringExtra("sessionId")
            ?: UUID.randomUUID().toString().replace("-", "").take(12)

        // 1) START FOREGROUND IMMEDIATELY (critical on Wear/Android 12+)
        startInForeground(sportCategory, sessionId)

        // reset latest samples
        resetLatestSamples()
        linesSinceFlush = 0
        accCount.set(0)
        gyroCount.set(0)

        // 2) Now do file I/O
        try {
            initializeWriter(sportCategory, sessionId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize writer", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // 3) Now register sensors (uses safe samplingPeriodUs)
        registerSensors()

        Log.d(TAG, "Logging started. sport=$sportCategory session=$sessionId file=${currentLogFile?.name}")
        return START_STICKY
    }

    // ----------------------------
    // Foreground notification
    // ----------------------------
    @SuppressLint("ForegroundServiceType")
    private fun startInForeground(sport: String, session: String) {
        createNotificationChannelIfNeeded()

        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("MotionWatch")
            .setContentText("Logging… ($sport)  [$session]")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        startForeground(notifId, notif)
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                channelId,
                "Motion Logging",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }

    // ----------------------------
    // CSV writer (merged file)
    // ----------------------------
    private fun initializeWriter(label: String, session: String) {
        val startMs = System.currentTimeMillis()

        val safeLabel = sanitizeForFilename(label)
        val safeSession = sanitizeForFilename(session)
        val tsHuman = formatFileTimestamp(startMs)

        // ✅ One merged file
        val logFile = File(logsDir, "SESSION_${safeSession}_WATCH_${safeLabel}_${tsHuman}.csv")
        currentLogFile = logFile

        writer = BufferedWriter(FileWriter(logFile, false)).apply {
            write("# epoch_ms,event_ts_ns,AX,AY,AZ,GX,GY,GZ,label,sessionID\n")
            flush()
        }

        Log.d(TAG, "Created file: ${logFile.name}")
    }

    // ----------------------------
    // Sensor registration
    // ----------------------------
    private fun registerSensors() {
        Log.d(TAG, "Registering sensors @ ${samplingPeriodUs}us (~${1000000.0 / samplingPeriodUs} Hz)")

        if (accSensor == null) {
            Log.e(TAG, "Accelerometer not available on this watch")
        } else {
            sensorManager.registerListener(this, accSensor, samplingPeriodUs)
            Log.d(TAG, "Accelerometer registered")
        }

        if (gyroSensor == null) {
            Log.e(TAG, "Gyroscope not available on this watch")
        } else {
            sensorManager.registerListener(this, gyroSensor, samplingPeriodUs)
            Log.d(TAG, "Gyroscope registered")
        }
    }

    // ----------------------------
    // Sensor callback (merged rows)
    // ----------------------------
    override fun onSensorChanged(event: SensorEvent) {
        val w = writer ?: return

        val epochMs = System.currentTimeMillis()
        val eventTsNs = event.timestamp

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastAx = event.values[0]
                lastAy = event.values[1]
                lastAz = event.values[2]
                accCount.incrementAndGet()
            }

            Sensor.TYPE_GYROSCOPE -> {
                lastGx = event.values[0]
                lastGy = event.values[1]
                lastGz = event.values[2]
                gyroCount.incrementAndGet()

                // If you still want a gyro-only UI preview, keep broadcasting it
                val ui = Intent(ACTION_GYRO_UPDATE).apply {
                    putExtra("gx", lastGx)
                    putExtra("gy", lastGy)
                    putExtra("gz", lastGz)
                }
                sendBroadcast(ui)
            }

            else -> return
        }

        // Write merged row on every ACC or GYRO event
        try {
            w.write(
                "$epochMs,$eventTsNs," +
                        "$lastAx,$lastAy,$lastAz," +
                        "$lastGx,$lastGy,$lastGz," +
                        "$sportCategory,$sessionId\n"
            )
            linesSinceFlush++
            if (linesSinceFlush >= flushEvery) {
                w.flush()
                linesSinceFlush = 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Write failed", e)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun resetLatestSamples() {
        lastAx = Float.NaN
        lastAy = Float.NaN
        lastAz = Float.NaN
        lastGx = Float.NaN
        lastGy = Float.NaN
        lastGz = Float.NaN
    }

    // ----------------------------
    // Sync file to phone
    // ----------------------------
    private fun sendFileToPhone(file: File, session: String) {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    Log.e(TAG_SYNC, "No connected phone nodes; cannot send ${file.name}")
                    return@addOnSuccessListener
                }

                val nodeId = nodes[0].id
                val path = "/file/$session/${file.name}"
                Log.d(TAG_SYNC, "Sending to node=$nodeId path=$path")

                Wearable.getChannelClient(this)
                    .openChannel(nodeId, path)
                    .addOnSuccessListener { channel ->
                        Wearable.getChannelClient(this)
                            .getOutputStream(channel)
                            .addOnSuccessListener { stream ->
                                try {
                                    stream.use { out ->
                                        file.inputStream().use { input ->
                                            input.copyTo(out)
                                        }
                                    }
                                    Log.d(TAG_SYNC, "Sent OK: ${file.name}")

                                    // ✅ Recommended: delete after successful send to prevent re-sync forever
                                    val deleted = try { file.delete() } catch (_: Exception) { false }
                                    Log.d(TAG_SYNC, "Delete after send: ${file.name} -> $deleted")

                                } catch (e: Exception) {
                                    Log.e(TAG_SYNC, "Send failed: ${file.name}", e)
                                } finally {
                                    Wearable.getChannelClient(this).close(channel)
                                }
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG_SYNC, "getOutputStream failed", e)
                                Wearable.getChannelClient(this).close(channel)
                            }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG_SYNC, "openChannel failed", e)
                    }
            }
            .addOnFailureListener { e ->
                Log.e(TAG_SYNC, "connectedNodes failed", e)
            }
    }

    // ----------------------------
    // Cleanup
    // ----------------------------
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy() acc=${accCount.get()} gyro=${gyroCount.get()} file=${currentLogFile?.name}")

        try { sensorManager.unregisterListener(this) } catch (_: Exception) { }

        try { writer?.flush(); writer?.close() } catch (_: Exception) { }
        writer = null

        // ✅ Only sync this run's file (NOT the whole folder)
        currentLogFile?.let { file ->
            if (file.exists() && file.isFile) {
                sendFileToPhone(file, sessionId.ifBlank { "unknown" })
            }
        }

        stopForeground(true)
    }

    companion object {
        private const val TAG = "SensorService"
        private const val TAG_SYNC = "WearSync"
        const val ACTION_GYRO_UPDATE = "GYRO_UPDATE"
    }
}
