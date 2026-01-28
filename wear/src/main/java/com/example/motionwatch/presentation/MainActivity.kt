package com.example.motionwatch.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.example.motionwatch.presentation.theme.MotionWatchTheme
import com.google.android.gms.wearable.Wearable
import java.util.UUID

class MainActivity : ComponentActivity() {

    private var gx by mutableStateOf(0f)
    private var gy by mutableStateOf(0f)
    private var gz by mutableStateOf(0f)

    private var activeSessionId: String? = null

    // Keep receiver reference so we can unregister (good practice)
    private var gyroReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)

        // Listen for live gyro updates from SensorService
        val filter = IntentFilter(ACTION_GYRO_UPDATE)
        gyroReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                gx = intent?.getFloatExtra("gx", 0f) ?: 0f
                gy = intent?.getFloatExtra("gy", 0f) ?: 0f
                gz = intent?.getFloatExtra("gz", 0f) ?: 0f
            }
        }
        registerReceiver(gyroReceiver, filter)

        setContent {
            WearApp(
                gx = gx,
                gy = gy,
                gz = gz,
                startLogging = { sport -> startLoggingBoth(sport) },
                stopLogging = { stopLoggingBoth() }
            )
        }

        // Quick connectivity check (watch → phone)
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                Log.d(TAG_TEST, "Connected nodes: ${nodes.map { it.displayName + ":" + it.id }}")
            }
            .addOnFailureListener { e ->
                Log.e(TAG_TEST, "connectedNodes failed", e)
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            gyroReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) { }
    }

    /**
     * Start logging on WATCH + send START command to PHONE.
     */
    private fun startLoggingBoth(sport: String) {
        val sessionId = UUID.randomUUID().toString().replace("-", "").take(12)
        activeSessionId = sessionId

        // 1) Start WATCH logging
        val watchIntent = Intent(this, SensorService::class.java).apply {
            putExtra("sport", sport)
            putExtra("sessionId", sessionId)
        }
        // For Wear OS, startForegroundService is correct
        startForegroundService(watchIntent)
        Log.d(TAG_CMD, "Started watch logging sport=$sport session=$sessionId")

        // 2) Send PHONE command
        val payload = "sessionId=$sessionId;label=$sport"
        sendCommandToPhone("/cmd/start", payload)
    }

    /**
     * Stop logging on WATCH + send STOP command to PHONE.
     */
    private fun stopLoggingBoth() {
        val sessionId = activeSessionId ?: "unknown"

        // 1) Stop WATCH logging (your SensorService.onDestroy will sync files)
        stopService(Intent(this, SensorService::class.java))
        Log.d(TAG_CMD, "Stopped watch logging session=$sessionId")

        // 2) Send PHONE command
        sendCommandToPhone("/cmd/stop", "sessionId=$sessionId")

        activeSessionId = null
    }

    /**
     * Wear Message API (watch → phone)
     */
    private fun sendCommandToPhone(path: String, payload: String) {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    Log.e(TAG_CMD, "No connected phone nodes; cannot send $path")
                    return@addOnSuccessListener
                }

                // Use the first connected node (typical single-phone scenario)
                val nodeId = nodes[0].id

                Wearable.getMessageClient(this)
                    .sendMessage(nodeId, path, payload.toByteArray(Charsets.UTF_8))
                    .addOnSuccessListener {
                        Log.d(TAG_CMD, "Sent $path to $nodeId payload=$payload")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG_CMD, "Failed to send $path to $nodeId", e)
                    }
            }
            .addOnFailureListener { e ->
                Log.e(TAG_CMD, "connectedNodes failed for $path", e)
            }
    }

    companion object {
        private const val TAG_CMD = "WearCmd"
        private const val TAG_TEST = "WearTest"
        const val ACTION_GYRO_UPDATE = "GYRO_UPDATE"
    }
}

@Composable
fun WearApp(
    gx: Float,
    gy: Float,
    gz: Float,
    startLogging: (String) -> Unit,
    stopLogging: () -> Unit
) {
    MotionWatchTheme {
        var isLogging by remember { mutableStateOf(false) }
        var sportCategory by remember { mutableStateOf("RUNNING") }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
        ) {
            TimeText(modifier = Modifier.align(Alignment.TopCenter))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Greeting(
                    isLogging = isLogging,
                    gx = gx,
                    gy = gy,
                    gz = gz
                )

                Spacer(modifier = Modifier.height(15.dp))

                // Sport selector
                Button(
                    onClick = {
                        // lock category changes while logging (optional)
                        if (isLogging) return@Button

                        sportCategory = when (sportCategory) {
                            "RUNNING" -> "WALKING"
                            "WALKING" -> "CYCLING"
                            "CYCLING" -> "GYM"
                            "GYM" -> "HIKING"
                            else -> "RUNNING"
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 1.dp)
                        .height(24.dp)
                ) {
                    Text(text = sportCategory, style = MaterialTheme.typography.title3)
                }

                Spacer(modifier = Modifier.height(15.dp))

                // Start/Stop toggle
                Button(
                    onClick = {
                        if (!isLogging) {
                            startLogging(sportCategory)
                            isLogging = true
                        } else {
                            stopLogging()
                            isLogging = false
                        }
                    }
                ) {
                    Text(if (!isLogging) "Start" else "Stop")
                }
            }
        }
    }
}

@Composable
fun Greeting(
    isLogging: Boolean,
    gx: Float,
    gy: Float,
    gz: Float
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (isLogging) "Status: Logging…" else "Status: Not Logging",
            color = if (isLogging) MaterialTheme.colors.primary else MaterialTheme.colors.secondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Gyro: %.2f, %.2f, %.2f".format(gx, gy, gz),
            color = MaterialTheme.colors.primary,
            textAlign = TextAlign.Center
        )
    }
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true)
@Composable
fun DefaultPreview() {
    WearApp(
        gx = 0f,
        gy = 0f,
        gz = 0f,
        startLogging = {},
        stopLogging = {}
    )
}
