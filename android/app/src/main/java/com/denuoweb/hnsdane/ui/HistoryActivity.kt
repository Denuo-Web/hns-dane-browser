package com.denuoweb.hnsdane.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.denuoweb.hnsdane.R

class HistoryActivity : ComponentActivity() {
    private lateinit var listContainer: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = preferenceSummary("")
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        setSecondaryScreen(getString(R.string.screen_history)) {
            addView(screenSection(getString(R.string.section_browsing_history)) {
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_saved_pages),
                    summaryView = status,
                ))
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_clear_history),
                    summary = getString(R.string.row_clear_history_summary),
                    actionLabel = getString(R.string.action_clear),
                    destructive = true,
                ) {
                    confirmClearHistory()
                })
            })
            addView(screenSection(getString(R.string.section_recent_pages)) {
                addView(listContainer)
            })
        }

        refreshHistory()
    }

    private fun refreshHistory() {
        listContainer.removeAllViews()
        val entries = BrowserHistoryStore.entries(this)
        status.text = if (entries.isEmpty()) {
            getString(R.string.history_empty_status)
        } else {
            resources.getQuantityString(R.plurals.history_recent_pages_status, entries.size, entries.size)
        }

        if (entries.isEmpty()) {
            listContainer.addScreenRow(preferenceRow(
                title = getString(R.string.history_empty_title),
                summary = getString(R.string.history_empty_summary),
            ))
        } else {
            entries.forEach { entry ->
                listContainer.addScreenRow(historyRow(entry))
            }
        }
    }

    private fun historyRow(entry: BrowserHistoryEntry): LinearLayout =
        preferenceRow(
            title = entry.title.ifBlank { entry.url },
            summary = "${formatTime(entry.visitedAtMillis)}\n${entry.url}",
            actionLabel = getString(R.string.action_open),
            summaryMaxLines = 3,
        ) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_LOAD_URL, entry.url)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }

    private fun confirmClearHistory() {
        val count = BrowserHistoryStore.entries(this).size
        if (count == 0) {
            Toast.makeText(this, getString(R.string.history_already_empty), Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.history_clear_title)
            .setMessage(R.string.history_clear_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_clear) { _, _ ->
                val cleared = BrowserHistoryStore.clear(this)
                Toast.makeText(
                    this,
                    resources.getQuantityString(R.plurals.history_cleared_items, cleared, cleared),
                    Toast.LENGTH_SHORT,
                ).show()
                refreshHistory()
            }
            .show()
    }

    private fun formatTime(visitedAtMillis: Long): String =
        if (visitedAtMillis <= 0L) {
            getString(R.string.history_unknown_time)
        } else {
            DateFormat.format("yyyy-MM-dd HH:mm", visitedAtMillis).toString()
        }
}
