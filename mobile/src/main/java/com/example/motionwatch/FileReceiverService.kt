package com.example.motionwatch

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.File
import java.io.FileOutputStream

class FileReceiverService : WearableListenerService() {

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val path = channel.path ?: ""
        Log.d(TAG, "Channel opened. path=$path")

        // Accept both:
        // 1) Legacy: "/sync_file"
        // 2) New: "/file/<sessionId>/<filename>.csv"
        val accepted = (path == LEGACY_PATH) || path.startsWith(NEW_PREFIX)
        if (!accepted) {
            Log.d(TAG, "Ignoring channel (unexpected path): $path")
            return
        }

        Wearable.getChannelClient(this)
            .getInputStream(channel)
            .addOnSuccessListener { inputStream ->
                try {
                    val outDir = getWatchLogsDir()
                    if (!outDir.exists()) outDir.mkdirs()

                    val outName = deriveFileNameFromPath(path)
                    val outFile = File(outDir, outName)

                    inputStream.use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    Log.d(TAG, "File saved: ${outFile.absolutePath}")

                    // Tell UI to refresh file list
                    sendBroadcast(Intent(ACTION_FILE_RECEIVED).apply {
                        putExtra("path", outFile.absolutePath)
                        putExtra("filename", outFile.name)
                    })

                } catch (e: Exception) {
                    Log.e(TAG, "Error saving file", e)
                } finally {
                    // Always close the channel
                    Wearable.getChannelClient(this).close(channel)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to open channel input stream", e)
                Wearable.getChannelClient(this).close(channel)
            }
    }

    private fun getWatchLogsDir(): File {
        // Prefer app-specific external storage for easier export later.
        val base = getExternalFilesDir(null) ?: filesDir
        return File(base, "logs/watch")
    }

    private fun deriveFileNameFromPath(path: String): String {
        // Legacy path: /sync_file -> generate a name
        if (path == LEGACY_PATH) {
            return "watch_${System.currentTimeMillis()}.csv"
        }

        // New path: /file/<sessionId>/<filename>
        // Example: /file/abcd1234/SESSION_abcd1234_WATCH_Walking_20260125_101500.csv
        // Take the last segment as filename.
        val segs = path.split("/").filter { it.isNotBlank() }
        val last = segs.lastOrNull() ?: "watch_${System.currentTimeMillis()}.csv"

        // Ensure ".csv"
        return if (last.endsWith(".csv", ignoreCase = true)) last else "$last.csv"
    }

    companion object {
        private const val TAG = "PhoneSync"
        private const val LEGACY_PATH = "/sync_file"
        private const val NEW_PREFIX = "/file/"
        const val ACTION_FILE_RECEIVED = "com.example.motionwatch.FILE_RECEIVED"
    }
}
