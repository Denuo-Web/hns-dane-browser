package com.denuoweb.hnsdane.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserStoreBoundsTest {
    @Test
    fun historyParsingBoundsCountAndFields() {
        val array = JSONArray()
        repeat(300) { index ->
            array.put(
                JSONObject()
                    .put("url", "https://example.com/$index")
                    .put("title", "t".repeat(1_000)),
            )
        }
        array.put(JSONObject().put("url", "https://example.com/" + "x".repeat(20_000)))

        val entries = BrowserHistoryStore.parseEntries(array.toString())

        assertEquals(250, entries.size)
        assertTrue(entries.all { it.title.length <= 512 && it.url.length <= 16 * 1024 })
        assertTrue(
            BrowserHistoryStore.parseEntries(
                JSONArray().put(JSONObject().put("url", "https://example.com/" + "x".repeat(20_000))).toString(),
            ).isEmpty(),
        )
    }

    @Test
    fun downloadParsingBoundsCountAndFields() {
        val array = JSONArray()
        repeat(120) { index ->
            array.put(
                JSONObject()
                    .put("downloadId", index)
                    .put("contentUri", "")
                    .put("url", "https://example.com/$index")
                    .put("fileName", "f".repeat(1_000))
                    .put("mimeType", "m".repeat(1_000)),
            )
        }

        val records = BrowserDownloadStore.parseRecords(array.toString())

        assertEquals(100, records.size)
        assertTrue(records.all {
            it.url.length <= 16 * 1024 && it.fileName.length <= 255 && it.mimeType.length <= 255
        })
        assertTrue(
            BrowserDownloadStore.parseRecords(
                JSONArray().put(
                    JSONObject()
                        .put("downloadId", 1)
                        .put("url", "https://example.com/" + "x".repeat(20_000)),
                ).toString(),
            ).isEmpty(),
        )
    }
}
