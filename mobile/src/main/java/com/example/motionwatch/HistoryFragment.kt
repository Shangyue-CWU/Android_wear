package com.example.motionwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AbsListView
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

    private lateinit var adapter: android.widget.ArrayAdapter<String>

    private val files = mutableListOf<File>()
    private val displayNames = mutableListOf<String>()

    private val fileReceivedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadLogs()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        listView = view.findViewById(R.id.list_logs)
        tvHint = view.findViewById(R.id.tv_hint)

        // ✅ Use custom row layout with padding + multi-line text
        adapter = android.widget.ArrayAdapter(
            requireContext(),
            R.layout.item_history_file,
            displayNames
        )
        listView.adapter = adapter

        // Avoid odd focus/selection behavior on some devices
        listView.setItemsCanFocus(false)

        // ✅ Enable contextual multi-selection mode
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE_MODAL
        listView.setMultiChoiceModeListener(historyMultiChoiceListener)

        // Normal click: preview only when NOT selecting
        listView.setOnItemClickListener { _, _, position, _ ->
            if (listView.checkedItemCount == 0) {
                val f = files.getOrNull(position) ?: return@setOnItemClickListener
                showPreviewDialog(f)
            }
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

    private fun loadLogs() {
        files.clear()
        displayNames.clear()

        val base = requireContext().getExternalFilesDir(null) ?: requireContext().filesDir
        val phoneDir = File(base, "logs")
        val watchDir = File(base, "logs/watch")

        val all = mutableListOf<File>()
        if (phoneDir.exists()) all += phoneDir.listFiles()?.toList().orEmpty()
        if (watchDir.exists()) all += watchDir.listFiles()?.toList().orEmpty()

        val csvs = all
            .filter { it.isFile && it.name.lowercase(Locale.US).endsWith(".csv") }
            .sortedByDescending { it.lastModified() }

        files.addAll(csvs)

        for (f in csvs) {
            val source = if (f.absolutePath.contains("${File.separator}watch${File.separator}")) "WATCH" else "PHONE"
            // Put size on the next line to improve readability
            displayNames.add("[$source] ${f.name}\n${formatSize(f.length())}")
        }

        adapter.notifyDataSetChanged()
        clearAllSelections()

        tvHint.text = if (files.isEmpty()) {
            "No logs yet.\nStart/Stop from watch to create logs.\nSynced watch CSVs will appear here."
        } else {
            "Tap to preview • Long-press to select • Batch delete supported"
        }
    }

    // ----------------------------
    // Multi-select contextual menu
    // ----------------------------
    private val historyMultiChoiceListener = object : AbsListView.MultiChoiceModeListener {

        override fun onCreateActionMode(mode: android.view.ActionMode, menu: Menu): Boolean {
            menu.add(0, MENU_SELECT_ALL, 0, "Select All")
            menu.add(0, MENU_CLEAR, 1, "Clear")
            menu.add(0, MENU_SHARE, 2, "Share")
            menu.add(0, MENU_DELETE, 3, "Delete")

            updateCabTitle(mode)
            updateCabMenu(menu)
            return true
        }

        override fun onPrepareActionMode(mode: android.view.ActionMode, menu: Menu): Boolean {
            updateCabTitle(mode)
            updateCabMenu(menu)
            return true
        }

        override fun onActionItemClicked(mode: android.view.ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                MENU_SELECT_ALL -> {
                    selectAll()
                    updateCabTitle(mode)
                    updateCabMenu(mode.menu)
                    true
                }
                MENU_CLEAR -> {
                    clearAllSelections()
                    if (listView.checkedItemCount == 0) mode.finish()
                    true
                }
                MENU_SHARE -> {
                    val selected = getSelectedFiles()
                    if (selected.isEmpty()) return true
                    shareCsvFiles(selected)
                    true
                }
                MENU_DELETE -> {
                    val selected = getSelectedFiles()
                    if (selected.isEmpty()) return true
                    confirmDelete(selected) {
                        mode.finish()
                        loadLogs()
                    }
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: android.view.ActionMode) {
            clearAllSelections()
        }

        override fun onItemCheckedStateChanged(
            mode: android.view.ActionMode,
            position: Int,
            id: Long,
            checked: Boolean
        ) {
            updateCabTitle(mode)
            updateCabMenu(mode.menu)
        }

        private fun updateCabTitle(mode: android.view.ActionMode) {
            val n = listView.checkedItemCount
            mode.title = if (n > 0) "$n selected" else "Select files"
        }

        private fun updateCabMenu(menu: Menu) {
            val n = listView.checkedItemCount
            menu.findItem(MENU_SHARE)?.isEnabled = n > 0
            menu.findItem(MENU_DELETE)?.isEnabled = n > 0
            menu.findItem(MENU_CLEAR)?.isEnabled = n > 0
            menu.findItem(MENU_SELECT_ALL)?.isEnabled = files.isNotEmpty()
        }
    }

    private fun selectAll() {
        for (i in 0 until adapter.count) {
            listView.setItemChecked(i, true)
        }
    }

    private fun clearAllSelections() {
        for (i in 0 until adapter.count) {
            listView.setItemChecked(i, false)
        }
    }

    private fun getSelectedFiles(): List<File> {
        val selected = mutableListOf<File>()
        val checked = listView.checkedItemPositions
        for (i in 0 until checked.size()) {
            val position = checked.keyAt(i)
            if (checked.valueAt(i)) {
                files.getOrNull(position)?.let { selected.add(it) }
            }
        }
        return selected
    }

    private fun confirmDelete(selected: List<File>, onDone: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete files")
            .setMessage("Delete ${selected.size} CSV file(s)? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                var deleted = 0
                selected.forEach { f ->
                    try {
                        if (f.exists() && f.isFile && f.delete()) deleted++
                    } catch (_: Exception) { }
                }
                Toast.makeText(requireContext(), "Deleted $deleted file(s)", Toast.LENGTH_SHORT).show()
                onDone()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPreviewDialog(file: File) {
        val text = readFirstLines(file, 50)
        AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setMessage(text)
            .setPositiveButton("OK", null)
            .setNeutralButton("Share") { _, _ -> shareCsvFiles(listOf(file)) }
            .show()
    }

    private fun shareCsvFiles(filesToShare: List<File>) {
        try {
            val uris: ArrayList<Uri> = ArrayList()
            filesToShare.forEach { file ->
                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.provider",
                    file
                )
                uris.add(uri)
            }

            val share = Intent().apply {
                action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
                type = "text/csv"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0])
                else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
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

    companion object {
        private const val MENU_SELECT_ALL = 1001
        private const val MENU_CLEAR = 1002
        private const val MENU_SHARE = 1003
        private const val MENU_DELETE = 1004
    }
}
