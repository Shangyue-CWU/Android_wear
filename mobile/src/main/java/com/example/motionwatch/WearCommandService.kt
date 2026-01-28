package com.example.motionwatch

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WearCommandService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        val path = event.path
        val payload = event.data?.toString(Charsets.UTF_8) ?: ""

        Log.d(TAG, "onMessageReceived path=$path payload=$payload")

        when (path) {

            PATH_START -> {
                val map = parsePayload(payload)
                val label = map["label"] ?: "UNKNOWN"
                val sessionId = map["sessionId"] ?: "unknown"
                val startEpochMs = System.currentTimeMillis()

                Log.d(TAG, "START cmd label=$label session=$sessionId")

                // 1) Start phone logging service
                val svc = Intent(this, SensorLoggerService::class.java).apply {
                    action = ACTION_START
                    putExtra("label", label)
                    putExtra("sessionId", sessionId)
                    putExtra("startEpochMs", startEpochMs)
                }
                startForegroundService(svc)

                // 2) Broadcast UI command so CollectFragment updates like Start was tapped
                val ui = Intent(ACTION_UI_CMD).apply {
                    putExtra("cmd", "start")
                    putExtra("label", label)
                    putExtra("sessionId", sessionId)
                    putExtra("startEpochMs", startEpochMs)
                }
                sendBroadcast(ui)

                Log.d(TAG, "UI_CMD start broadcast sent")
            }

            PATH_STOP -> {
                Log.d(TAG, "STOP cmd received")

                // 1) Stop phone logging service
                val svc = Intent(this, SensorLoggerService::class.java).apply {
                    action = ACTION_STOP
                }
                startService(svc)

                // 2) Broadcast UI command so CollectFragment updates like Stop was tapped
                val ui = Intent(ACTION_UI_CMD).apply {
                    putExtra("cmd", "stop")
                }
                sendBroadcast(ui)

                Log.d(TAG, "UI_CMD stop broadcast sent")
            }

            else -> {
                Log.w(TAG, "Unknown path: $path")
            }
        }
    }

    /**
     * Payload format:
     * "sessionId=abc123;label=RUNNING"
     */
    private fun parsePayload(payload: String): Map<String, String> {
        if (payload.isBlank()) return emptyMap()

        return payload.split(";")
            .mapNotNull { part ->
                val idx = part.indexOf("=")
                if (idx <= 0) null
                else part.substring(0, idx) to part.substring(idx + 1)
            }
            .toMap()
    }

    companion object {
        private const val TAG = "WearCommand"

        // service actions
        const val ACTION_START = "com.example.motionwatch.action.START"
        const val ACTION_STOP  = "com.example.motionwatch.action.STOP"

        // message paths
        const val PATH_START = "/cmd/start"
        const val PATH_STOP  = "/cmd/stop"

        // NEW: UI broadcast action (CollectFragment listens to this)
        const val ACTION_UI_CMD = "com.example.motionwatch.UI_CMD"
    }
}
