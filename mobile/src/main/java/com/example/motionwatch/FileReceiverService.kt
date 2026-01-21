package com.example.motionwatch   // ← Make sure this matches your MOBILE app package!

import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.File

class FileReceiverService : WearableListenerService() {

    override fun onChannelOpened(channel: ChannelClient.Channel) {

        // Only handle file sync channels
        if (channel.path != "/sync_file") return

        Log.d("PhoneSync", "Channel opened from watch. Path=/sync_file")

        // Request input stream from the channel
        Wearable.getChannelClient(this)
            .getInputStream(channel)
            .addOnSuccessListener { inputStream ->

                Log.d("PhoneSync", "Receiving file from watch...")

                // Save CSV inside mobile app internal storage
                val folder = File(filesDir, "synced_logs")
                if (!folder.exists()) folder.mkdirs()

                val outFile = File(folder, "wear_${System.currentTimeMillis()}.csv")

                try {
                    inputStream.use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    Log.d(
                        "PhoneSync",
                        "File saved successfully: ${outFile.absolutePath}"
                    )

                } catch (e: Exception) {
                    Log.e("PhoneSync", "Error saving file", e)
                }

                // Close the channel when done
                Wearable.getChannelClient(this).close(channel)
            }
            .addOnFailureListener { e ->
                Log.e("PhoneSync", "Failed to open channel input stream", e)
            }
    }
}
