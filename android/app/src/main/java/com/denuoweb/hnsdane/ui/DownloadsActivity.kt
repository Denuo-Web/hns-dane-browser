package com.denuoweb.hnsdane.ui

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.denuoweb.hnsdane.R

class DownloadsActivity : ComponentActivity() {
    private lateinit var listContainer: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = preferenceSummary("")
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        setSecondaryScreen(getString(R.string.screen_downloads)) {
            addView(screenSection(getString(R.string.section_download_records)) {
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_app_downloads),
                    summaryView = status,
                ))
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_open_system_downloads),
                    summary = getString(R.string.row_open_system_downloads_summary),
                    actionLabel = getString(R.string.action_open),
                ) {
                    openSystemDownloads()
                })
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_clear_download_records),
                    summary = getString(R.string.row_clear_download_records_summary),
                    actionLabel = getString(R.string.action_clear),
                    destructive = true,
                ) {
                    confirmClearDownloadRecords()
                })
            })
            addView(screenSection(getString(R.string.section_recent_downloads)) {
                addView(listContainer)
            })
        }

        refreshDownloads()
    }

    private fun refreshDownloads() {
        listContainer.removeAllViews()
        val records = BrowserDownloadStore.records(this)
        status.text = if (records.isEmpty()) {
            getString(R.string.downloads_empty_status)
        } else {
            resources.getQuantityString(R.plurals.downloads_app_downloads_status, records.size, records.size)
        }

        if (records.isEmpty()) {
            listContainer.addScreenRow(preferenceRow(
                title = getString(R.string.downloads_empty_title),
                summary = getString(R.string.downloads_empty_summary),
            ))
        } else {
            records.forEach { record ->
                listContainer.addScreenRow(downloadRow(record))
            }
        }
    }

    private fun downloadRow(record: BrowserDownloadRecord): LinearLayout =
        preferenceRow(
            title = record.fileName,
            summary = getString(
                R.string.downloads_row_summary,
                formatTime(record.queuedAtMillis),
                downloadStatus(record),
                record.url,
            ),
            summaryMaxLines = 4,
        )

    private fun downloadStatus(record: BrowserDownloadRecord): String {
        if (record.contentUri.isNotBlank()) {
            return mediaStoreDownloadStatus(record.contentUri)
        }
        val downloadId = record.downloadId
        val manager = getSystemService(DownloadManager::class.java)
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
            ?: return getString(R.string.downloads_status_unknown)
        cursor.use {
            if (!it.moveToFirst()) {
                return getString(R.string.downloads_status_not_listed)
            }
            return when (it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_PENDING -> getString(R.string.downloads_status_pending)
                DownloadManager.STATUS_PAUSED -> getString(R.string.downloads_status_paused)
                DownloadManager.STATUS_RUNNING -> progressText(it)
                DownloadManager.STATUS_SUCCESSFUL -> getString(R.string.downloads_status_complete)
                DownloadManager.STATUS_FAILED -> getString(
                    R.string.downloads_status_failed,
                    it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                )
                else -> getString(R.string.downloads_status_unknown)
            }
        }
    }

    private fun mediaStoreDownloadStatus(contentUri: String): String {
        val uri = runCatching { Uri.parse(contentUri) }.getOrNull()
            ?: return getString(R.string.downloads_saved_record_invalid)
        return try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                getString(R.string.downloads_saved_to_downloads)
            } ?: getString(R.string.downloads_saved_file_unavailable)
        } catch (_: Exception) {
            getString(R.string.downloads_saved_file_unavailable)
        }
    }

    private fun progressText(cursor: android.database.Cursor): String {
        val downloaded = cursor.getLong(
            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
        )
        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        return if (total > 0L) {
            getString(R.string.downloads_progress_with_total, downloaded.toString(), total.toString())
        } else {
            getString(R.string.downloads_progress_without_total, downloaded.toString())
        }
    }

    private fun openSystemDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.downloads_no_system_app), Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClearDownloadRecords() {
        val count = BrowserDownloadStore.records(this).size
        if (count == 0) {
            Toast.makeText(this, getString(R.string.downloads_records_already_empty), Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.downloads_clear_title)
            .setMessage(R.string.downloads_clear_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_clear) { _, _ ->
                val cleared = BrowserDownloadStore.clear(this)
                Toast.makeText(
                    this,
                    resources.getQuantityString(R.plurals.downloads_cleared_records, cleared, cleared),
                    Toast.LENGTH_SHORT,
                ).show()
                refreshDownloads()
            }
            .show()
    }

    private fun formatTime(queuedAtMillis: Long): String =
        if (queuedAtMillis <= 0L) {
            getString(R.string.history_unknown_time)
        } else {
            DateFormat.format("yyyy-MM-dd HH:mm", queuedAtMillis).toString()
        }
}
