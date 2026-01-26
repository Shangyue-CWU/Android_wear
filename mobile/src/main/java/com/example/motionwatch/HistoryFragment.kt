package com.example.motionwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.Locale

class HistoryFragment : Fragment(R.layout.fragment_history) {

    private lateinit var listView: ListView
    private lateinit var tvHint: TextView

    private lateinit var adapter: ArrayAdapter<String>

    private val files = mutableListOf<File>()
    private val displayNames = mutableListOf<String>()

    private val fileReceivedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // watch file arrived -> refresh
            loadLogs()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        listView = view.findViewById(R.id.list_logs)
        tvHint = view.findViewById(R.id.tv_hint)

        adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, displayNames)
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val f = files[position]
            showPreviewDialog(f)
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val f = files[position]
            shareCsv(f)
            true
        }

        loadLogs()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(FileReceiverService.ACTION_FILE_RECEIVED)
        if (Build.VERSION.SDK_INT >= 33) {
            requireContext().registerReceiver(fileReceivedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            requireContext().registerReceiver(fileReceivedReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            requireContext().unregisterReceiver(fileReceivedReceiver)
        } catch (_: Exception) { }
    }

    /**
     * Loads CSVs from:
     *  - <externalFiles>/logs/        (phone)
     *  - <externalFiles>/logs/watch/  (watch synced)
     * Fallback to internal filesDir if external is null.
     */
    private fun loadLogs() {
        files.clear()
        displayNames.clear()

        val base = requireContext().getExternalFilesDir(null) ?: requireContext().filesDir
        val phoneDir = File(base, "logs")
        val watchDir = File(base, "logs/watch")

        val all = mutableListOf<File>()
        if (phoneDir.exists()) {
            all += phoneDir.listFiles()?.toList().orEmpty()
        }
        if (watchDir.exists()) {
            all += watchDir.listFiles()?.toList().orEmpty()
        }

        val csvs = all
            .filter { it.isFile && it.name.lowercase(Locale.US).endsWith(".csv") }
            .sortedByDescending { it.lastModified() }

        files.addAll(csvs)

        for (f in csvs) {
            val source = if (f.absolutePath.contains("${File.separator}watch${File.separator}")) "WATCH" else "PHONE"
            displayNames.add("[$source] ${f.name}  (${formatSize(f.length())})")
        }

        adapter.notifyDataSetChanged()

        tvHint.text = if (files.isEmpty()) {
            "No logs yet.\nStart/Stop from watch to create logs.\nSynced watch CSVs will appear here."
        } else {
            "Tap to preview • Long-press to share/export"
        }
    }

    private fun showPreviewDialog(file: File) {
        val text = readFirstLines(file, 50)
        AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setMessage(text)
            .setPositiveButton("OK", null)
            .setNeutralButton("Share") { _, _ -> shareCsv(file) }
            .show()
    }

    private fun shareCsv(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.provider",
                file
            )

            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Share CSV"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun readFirstLines(file: File, maxLines: Int): String {
        val sb = StringBuilder()
        try {
            BufferedReader(InputStreamReader(FileInputStream(file))).use { br ->
                var line: String?
                var count = 0
                while (count < maxLines) {
                    line = br.readLine() ?: break
                    sb.append(line).append('\n')
                    count++
                }
                if (count == maxLines) sb.append("...\n")
            }
        } catch (e: Exception) {
            return "Failed to read file: ${e.message}"
        }
        return sb.toString()
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1 -> String.format(Locale.US, "%.2f MB", mb)
            kb >= 1 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }
}
