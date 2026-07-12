package com.denuoweb.hnsdane.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class BrowserDownloadRecord(
    val downloadId: Long,
    val contentUri: String,
    val url: String,
    val fileName: String,
    val mimeType: String,
    val queuedAtMillis: Long,
)

internal object BrowserDownloadStore {
    private const val PREFS = "browser_downloads"
    private const val KEY_RECORDS = "records_json"
    private const val MAX_RECORDS = 100
    private const val MAX_URL_CHARS = 16 * 1024
    private const val MAX_CONTENT_URI_CHARS = 2 * 1024
    private const val MAX_FILE_NAME_CHARS = 255
    private const val MAX_MIME_TYPE_CHARS = 255
    private const val MAX_SERIALIZED_CHARS = 3 * 1024 * 1024

    @Synchronized
    fun records(context: Context): List<BrowserDownloadRecord> =
        parseRecords(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_RECORDS, null),
        )

    @Synchronized
    fun record(
        context: Context,
        downloadId: Long,
        url: String,
        fileName: String,
        mimeType: String?,
        queuedAtMillis: Long = System.currentTimeMillis(),
    ) {
        if (downloadId < 0L) return
        val normalizedUrl = normalizeUrl(url) ?: return
        val updated = listOf(
            BrowserDownloadRecord(
                downloadId = downloadId,
                contentUri = "",
                url = normalizedUrl,
                fileName = normalizeFileName(fileName),
                mimeType = normalizeMimeType(mimeType),
                queuedAtMillis = queuedAtMillis,
            ),
        ) + records(context).filterNot { it.downloadId == downloadId && downloadId >= 0L }

        save(context, updated.take(MAX_RECORDS))
    }

    @Synchronized
    fun recordSavedFile(
        context: Context,
        contentUri: String,
        url: String,
        fileName: String,
        mimeType: String?,
        queuedAtMillis: Long = System.currentTimeMillis(),
    ) {
        val normalizedContentUri = contentUri.trim().takeIf {
            it.isNotEmpty() && it.length <= MAX_CONTENT_URI_CHARS
        } ?: return
        val normalizedUrl = normalizeUrl(url) ?: return
        val updated = listOf(
            BrowserDownloadRecord(
                downloadId = -1L,
                contentUri = normalizedContentUri,
                url = normalizedUrl,
                fileName = normalizeFileName(fileName),
                mimeType = normalizeMimeType(mimeType),
                queuedAtMillis = queuedAtMillis,
            ),
        ) + records(context).filterNot { it.contentUri == normalizedContentUri }

        save(context, updated.take(MAX_RECORDS))
    }

    @Synchronized
    fun clear(context: Context): Int {
        val count = records(context).size
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_RECORDS)
            .apply()
        return count
    }

    internal fun parseRecords(json: String?): List<BrowserDownloadRecord> {
        if (json.isNullOrBlank() || json.length > MAX_SERIALIZED_CHARS) {
            return emptyList()
        }

        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until minOf(array.length(), MAX_RECORDS)).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val downloadId = item.optLong("downloadId", -1L)
            val contentUri = item.optString("contentUri").trim()
            val url = normalizeUrl(item.optString("url"))
            val validContentUri = contentUri.length <= MAX_CONTENT_URI_CHARS
            if ((downloadId < 0L && contentUri.isBlank()) || !validContentUri || url == null) {
                null
            } else {
                BrowserDownloadRecord(
                    downloadId = downloadId,
                    contentUri = contentUri,
                    url = url,
                    fileName = normalizeFileName(item.optString("fileName")),
                    mimeType = normalizeMimeType(item.optString("mimeType")),
                    queuedAtMillis = item.optLong("queuedAtMillis", 0L),
                )
            }
        }
    }

    private fun normalizeUrl(url: String): String? =
        url.trim().takeIf { it.isNotEmpty() && it.length <= MAX_URL_CHARS }

    private fun normalizeFileName(fileName: String): String =
        fileName.trim().take(MAX_FILE_NAME_CHARS).ifBlank { "download" }

    private fun normalizeMimeType(mimeType: String?): String =
        mimeType.orEmpty().trim().take(MAX_MIME_TYPE_CHARS)

    private fun save(context: Context, records: List<BrowserDownloadRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("downloadId", record.downloadId)
                    .put("contentUri", record.contentUri)
                    .put("url", record.url)
                    .put("fileName", record.fileName)
                    .put("mimeType", record.mimeType)
                    .put("queuedAtMillis", record.queuedAtMillis),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORDS, array.toString())
            .apply()
    }
}
