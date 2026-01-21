package com.example.motionwatch.presentation

import android.app.*
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
import com.google.android.gms.wearable.Wearable
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

class SensorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accWriter: BufferedWriter? = null
    private var gyroWriter: BufferedWriter? = null

    private val CHANNEL_ID = "sensor_logging_channel"
    private val NOTIF_ID = 101

    // sport category received from MainActivity
    private var sportCategory: String = "UNKNOWN"

    override fun onCreate() {
        super.onCreate()
        Log.d("SensorService", "Service created")

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Create log folder in internal storage
        val folder = File(filesDir, "motion_logs")
        if (!folder.exists()) folder.mkdirs()

        // Auto-sync any old unsent files (optional)
        folder.listFiles()?.forEach { file ->
            sendFileToPhone(file)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("SensorService", "Service started")

        // -----------------------------------------------
        // NEW: Read the sport category from the intent
        // -----------------------------------------------
        sportCategory = intent?.getStringExtra("sport") ?: "UNKNOWN"
        Log.d("SensorService", "Sport category = $sportCategory")

        // initialize writers now that we know category
        initializeWriters()

        startForegroundService()
        registerSensors()

        return START_STICKY
    }

    // ---------------------------------------------------------
    // NEW: Create CSV files with sport category in filename
    // ---------------------------------------------------------
    private fun initializeWriters() {
        val folder = File(filesDir, "motion_logs")
        if (!folder.exists()) folder.mkdirs()

        val timestamp = System.currentTimeMillis()

        // ACC file
        val accFile = File(folder, "${sportCategory}_ACC_${timestamp}.csv")
        accWriter = BufferedWriter(FileWriter(accFile))
        accWriter?.write("timestamp,ax,ay,az\n")

        // GYRO file
        val gyroFile = File(folder, "${sportCategory}_GYRO_${timestamp}.csv")
        gyroWriter = BufferedWriter(FileWriter(gyroFile))
        gyroWriter?.write("timestamp,gx,gy,gz\n")

        Log.d("SensorService", "Created files: ${accFile.name}, ${gyroFile.name}")
    }

    // ------------------------------------------------------------
    // SENSOR LOGGING
    // ------------------------------------------------------------
    private fun registerSensors() {
        val acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        acc?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            Log.d("SensorService", "Accelerometer registered")
        }

        gyro?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            Log.d("SensorService", "Gyroscope registered")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val t = System.currentTimeMillis()

        when (event.sensor.type) {

            Sensor.TYPE_ACCELEROMETER -> {
                accWriter?.write("$t, ${event.values[0]}, ${event.values[1]}, ${event.values[2]}\n")
            }

            Sensor.TYPE_GYROSCOPE -> {
                gyroWriter?.write("$t, ${event.values[0]}, ${event.values[1]}, ${event.values[2]}\n")

                // Live UI update
                val intent = Intent("GYRO_UPDATE")
                intent.putExtra("gx", event.values[0])
                intent.putExtra("gy", event.values[1])
                intent.putExtra("gz", event.values[2])
                sendBroadcast(intent)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ------------------------------------------------------------
    // BACKGROUND SERVICE
    // ------------------------------------------------------------
    private fun startForegroundService() {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Motion Logging Running")
            .setContentText("Collecting accelerometer & gyroscope data…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sensor Logging Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    // ------------------------------------------------------------
    // AUTO SYNC CSV FILE TO PHONE
    // ------------------------------------------------------------
    private fun sendFileToPhone(file: File) {

        Wearable.getNodeClient(this)
            .connectedNodes
            .addOnSuccessListener { nodes ->

                if (nodes.isEmpty()) {
                    Log.e("WearSync", "No connected phone nodes")
                    return@addOnSuccessListener
                }

                val nodeId = nodes[0].id
                Log.d("WearSync", "Sending file to node $nodeId → ${file.name}")

                Wearable.getChannelClient(this)
                    .openChannel(nodeId, "/sync_file")
                    .addOnSuccessListener { channel ->

                        Wearable.getChannelClient(this)
                            .getOutputStream(channel)
                            .addOnSuccessListener { stream ->

                                stream.use { out ->
                                    file.inputStream().use { input ->
                                        input.copyTo(out)
                                    }
                                }

                                Wearable.getChannelClient(this).close(channel)
                                Log.d("WearSync", "File sent successfully: ${file.name}")
                            }
                    }
            }
    }

    // ------------------------------------------------------------
    // CLEANUP + AUTO SYNC ON STOP
    // ------------------------------------------------------------
    override fun onDestroy() {
        super.onDestroy()
        Log.d("SensorService", "Service destroyed")

        sensorManager.unregisterListener(this)

        accWriter?.close()
        gyroWriter?.close()

        val folder = File(filesDir, "motion_logs")
        folder.listFiles()?.forEach { file ->
            sendFileToPhone(file)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
