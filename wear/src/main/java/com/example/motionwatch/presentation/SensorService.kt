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
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class SensorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accSensor: Sensor? = null
    private var gyroSensor: Sensor? = null

    private var accWriter: BufferedWriter? = null
    private var gyroWriter: BufferedWriter? = null

    private val channelId = "sensor_logging_channel"
    private val notifId = 101

    // received from MainActivity
    private var sportCategory: String = "UNKNOWN"

    // per-run session id (used for syncing + filenames)
    private var sessionId: String = ""

    // log folder
    private lateinit var logsDir: File

    // flush every N samples to reduce data loss
    private val flushEvery = 50
    private var accLinesSinceFlush = 0
    private var gyroLinesSinceFlush = 0

    // simple counters for debugging
    private val accCount = AtomicLong(0)
    private val gyroCount = AtomicLong(0)

    // ✅ IMPORTANT: safe explicit sampling period to avoid 0us (FASTEST) crash
    // 10,000 us = 10 ms ≈ 100 Hz
    private val samplingPeriodUs = 10_000

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

//        sessionId = UUID.randomUUID().toString().replace("-", "").take(12)

        // 1) START FOREGROUND IMMEDIATELY (critical on Wear/Android 12+)
        startInForeground(sportCategory, sessionId)

        // 2) Now do file I/O
        try {
            initializeWriters(sportCategory, sessionId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize writers", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // 3) Now register sensors (uses safe samplingPeriodUs)
        registerSensors()

        Log.d(TAG, "Logging started. sport=$sportCategory session=$sessionId")
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
    // CSV writers
    // ----------------------------
    private fun initializeWriters(label: String, session: String) {
        val ts = System.currentTimeMillis()
        val safeLabel = label.replace(Regex("[^A-Za-z0-9_-]"), "_")

        val accFile = File(logsDir, "SESSION_${session}_WATCH_${safeLabel}_ACC_${ts}.csv")
        val gyroFile = File(logsDir, "SESSION_${session}_WATCH_${safeLabel}_GYRO_${ts}.csv")

        accWriter = BufferedWriter(FileWriter(accFile, false)).apply {
            write("# epoch_ms,ax,ay,az,label,session\n")
            flush()
        }
        gyroWriter = BufferedWriter(FileWriter(gyroFile, false)).apply {
            write("# epoch_ms,gx,gy,gz,label,session\n")
            flush()
        }

        Log.d(TAG, "Created files: ${accFile.name}, ${gyroFile.name}")
    }

    // ----------------------------
    // Sensor registration
    // ----------------------------
    private fun registerSensors() {
        // ✅ Avoid SENSOR_DELAY_FASTEST to prevent "0 microseconds" permission crash
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
    // Sensor callback
    // ----------------------------
    override fun onSensorChanged(event: SensorEvent) {
        val t = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val w = accWriter ?: return
                try {
                    w.write("$t,${event.values[0]},${event.values[1]},${event.values[2]},$sportCategory,$sessionId\n")
                    accLinesSinceFlush++
                    accCount.incrementAndGet()
                    if (accLinesSinceFlush >= flushEvery) {
                        w.flush()
                        accLinesSinceFlush = 0
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ACC write failed", e)
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                val w = gyroWriter ?: return
                try {
                    val gx = event.values[0]
                    val gy = event.values[1]
                    val gz = event.values[2]

                    w.write("$t,$gx,$gy,$gz,$sportCategory,$sessionId\n")
                    gyroLinesSinceFlush++
                    gyroCount.incrementAndGet()
                    if (gyroLinesSinceFlush >= flushEvery) {
                        w.flush()
                        gyroLinesSinceFlush = 0
                    }

                    // Live UI update (MainActivity listens for this)
                    val ui = Intent(ACTION_GYRO_UPDATE).apply {
                        putExtra("gx", gx)
                        putExtra("gy", gy)
                        putExtra("gz", gz)
                    }
                    sendBroadcast(ui)

                } catch (e: Exception) {
                    Log.e(TAG, "GYRO write failed", e)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ----------------------------
    // Sync files to phone
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
        Log.d(TAG, "onDestroy() acc=${accCount.get()} gyro=${gyroCount.get()}")

        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) { }

        try { accWriter?.flush(); accWriter?.close() } catch (_: Exception) { }
        try { gyroWriter?.flush(); gyroWriter?.close() } catch (_: Exception) { }

        // Sync all CSVs generated in this folder
        logsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".csv", ignoreCase = true) }
            ?.forEach { file ->
                sendFileToPhone(file, sessionId.ifBlank { "unknown" })
            }

        stopForeground(true)
    }

    companion object {
        private const val TAG = "SensorService"
        private const val TAG_SYNC = "WearSync"
        const val ACTION_GYRO_UPDATE = "GYRO_UPDATE"
    }
}
