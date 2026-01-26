package com.example.motionwatch

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment

class CollectFragment : Fragment(R.layout.fragment_collect) {

    private lateinit var tvStatus: TextView
    private lateinit var tvAccX: TextView
    private lateinit var tvAccY: TextView
    private lateinit var tvAccZ: TextView
    private lateinit var tvGyroX: TextView
    private lateinit var tvGyroY: TextView
    private lateinit var tvGyroZ: TextView
    private lateinit var tvSummary: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnLabel: Button

    private var isLogging = false

    private val labels = arrayOf("Running", "Sitting", "Standing", "Walking")
    private var labelIdx = 3
    private var currentLabel = labels[labelIdx]

    // Track current session (watch-started or phone-started)
    private var currentSessionId: String = "unknown"

    // UI duration/stat tracking (for fallback / immediate UI feedback)
    private var startElapsedMs = 0L
    private var accCount = 0L
    private var gyroCount = 0L
    private var accSumX = 0.0
    private var accSumY = 0.0
    private var accSumZ = 0.0
    private var gyroSumX = 0.0
    private var gyroSumY = 0.0
    private var gyroSumZ = 0.0

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted && Build.VERSION.SDK_INT >= 33) {
                Toast.makeText(
                    requireContext(),
                    "Notifications permission recommended",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    // ---------------------------------------------------------
    // ✅ Receive updates from SensorLoggerService (watch OR phone)
    // ---------------------------------------------------------
    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            when (intent.action) {

                SensorLoggerService.ACTION_LOG_STATE -> {
                    val started = intent.getBooleanExtra("started", false)
                    val label = intent.getStringExtra("label") ?: "UNKNOWN"
                    val sessionId = intent.getStringExtra("sessionId") ?: "unknown"

                    currentSessionId = sessionId
                    currentLabel = label.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    updateLabelButton()

                    if (started) {
                        // Start UI state
                        isLogging = true
                        btnToggle.text = "Stop"
                        tvStatus.text = "Logging… ($currentLabel)\nSession: $currentSessionId"

                        resetStats()
                        startElapsedMs = SystemClock.elapsedRealtime()
                        tvSummary.text = "Summary: logging…"
                    } else {
                        // Stopped state (LOG_DONE will usually follow with summary)
                        isLogging = false
                        btnToggle.text = "Start"
                        tvStatus.text = "Stopped ($currentLabel)\nSession: $currentSessionId"
                    }
                }

                SensorLoggerService.ACTION_LIVE_SAMPLE -> {
                    if (!isLogging) return

                    val sensor = intent.getStringExtra("sensor") ?: return
                    val x = intent.getFloatExtra("x", 0f)
                    val y = intent.getFloatExtra("y", 0f)
                    val z = intent.getFloatExtra("z", 0f)

                    if (sensor == "ACC") {
                        tvAccX.text = String.format("X: %.3f m/s²", x)
                        tvAccY.text = String.format("Y: %.3f m/s²", y)
                        tvAccZ.text = String.format("Z: %.3f m/s²", z)

                        accSumX += x; accSumY += y; accSumZ += z
                        accCount++
                    } else if (sensor == "GYRO") {
                        tvGyroX.text = String.format("X: %.3f rad/s", x)
                        tvGyroY.text = String.format("Y: %.3f rad/s", y)
                        tvGyroZ.text = String.format("Z: %.3f rad/s", z)

                        gyroSumX += x; gyroSumY += y; gyroSumZ += z
                        gyroCount++
                    }
                }

                SensorLoggerService.ACTION_LOG_DONE -> {
                    val durationMs = intent.getLongExtra("durationMs", 0L)
                    val durationStr = String.format("%.2f s", durationMs / 1000.0)

                    val accAvgX = intent.getDoubleExtra("accAvgX", 0.0)
                    val accAvgY = intent.getDoubleExtra("accAvgY", 0.0)
                    val accAvgZ = intent.getDoubleExtra("accAvgZ", 0.0)

                    val gyrAvgX = intent.getDoubleExtra("gyrAvgX", 0.0)
                    val gyrAvgY = intent.getDoubleExtra("gyrAvgY", 0.0)
                    val gyrAvgZ = intent.getDoubleExtra("gyrAvgZ", 0.0)

                    tvSummary.text =
                        "Summary\n" +
                                "Duration: $durationStr\n" +
                                "ACC avg (X,Y,Z)= (%.3f, %.3f, %.3f) m/s²\n".format(accAvgX, accAvgY, accAvgZ) +
                                "GYRO avg (X,Y,Z)= (%.3f, %.3f, %.3f) rad/s".format(gyrAvgX, gyrAvgY, gyrAvgZ)

                    isLogging = false
                    btnToggle.text = "Start"
                    tvStatus.text = "Stopped ($currentLabel)\nSession: $currentSessionId"
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tvStatus = view.findViewById(R.id.tvStatus)
        tvAccX = view.findViewById(R.id.tvAccX)
        tvAccY = view.findViewById(R.id.tvAccY)
        tvAccZ = view.findViewById(R.id.tvAccZ)
        tvGyroX = view.findViewById(R.id.tvGyroX)
        tvGyroY = view.findViewById(R.id.tvGyroY)
        tvGyroZ = view.findViewById(R.id.tvGyroZ)
        tvSummary = view.findViewById(R.id.tvSummary)
        btnToggle = view.findViewById(R.id.btnToggle)
        btnLabel = view.findViewById(R.id.btnLabel)

        if (Build.VERSION.SDK_INT >= 33) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        updateLabelButton()
        tvStatus.text = "Stopped"
        tvSummary.text = "Summary: (not started)"

        btnLabel.setOnClickListener {
            if (isLogging) {
                Toast.makeText(requireContext(), "Stop logging to change label.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            labelIdx = (labelIdx + 1) % labels.size
            currentLabel = labels[labelIdx]
            updateLabelButton()
        }

        // Phone button starts/stops phone logging (still supported)
        btnToggle.setOnClickListener {
            if (!isLogging) startLoggingFromPhoneTap()
            else stopLoggingFromPhoneTap()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(SensorLoggerService.ACTION_LOG_STATE)
            addAction(SensorLoggerService.ACTION_LIVE_SAMPLE)
            addAction(SensorLoggerService.ACTION_LOG_DONE)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            requireContext().registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(logReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try { requireContext().unregisterReceiver(logReceiver) } catch (_: Exception) {}
    }

    private fun updateLabelButton() {
        btnLabel.text = "Label: $currentLabel"
    }

    private fun resetStats() {
        accCount = 0
        gyroCount = 0
        accSumX = 0.0
        accSumY = 0.0
        accSumZ = 0.0
        gyroSumX = 0.0
        gyroSumY = 0.0
        gyroSumZ = 0.0
    }

    // ----------------------------
    // Phone tap: start service
    // ----------------------------
    private fun startLoggingFromPhoneTap() {
        val sessionId = "PHONE_ONLY_${System.currentTimeMillis()}"
        currentSessionId = sessionId

        val intent = Intent(requireContext(), SensorLoggerService::class.java).apply {
            action = SensorLoggerService.ACTION_START
            putExtra("sessionId", sessionId)
            putExtra("label", currentLabel)
            putExtra("startEpochMs", System.currentTimeMillis())
        }

        try {
            requireContext().startForegroundService(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error starting service: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        // UI will update via ACTION_LOG_STATE broadcast from service
    }

    private fun stopLoggingFromPhoneTap() {
        val stop = Intent(requireContext(), SensorLoggerService::class.java).apply {
            action = SensorLoggerService.ACTION_STOP
        }
        requireContext().startService(stop)

        // UI will update via ACTION_LOG_STATE + ACTION_LOG_DONE
    }
}
