package com.handshake.browser.net

import java.util.Locale

internal data class GatewayEvent(
    val timestampMillis: Long,
    val stage: String,
    val host: String,
    val status: Int,
    val reason: String,
)

internal object GatewayEventLog {
    private const val MAX_EVENTS = 25
    private val events = ArrayDeque<GatewayEvent>()

    @Synchronized
    fun record(stage: String, host: String, status: Int, reason: String) {
        events.addLast(
            GatewayEvent(
                timestampMillis = System.currentTimeMillis(),
                stage = stage.sanitizeToken(),
                host = host.sanitizeHost(),
                status = status,
                reason = reason.sanitizeReason(),
            ),
        )
        while (events.size > MAX_EVENTS) {
            events.removeFirst()
        }
    }

    @Synchronized
    fun snapshot(): List<GatewayEvent> = events.toList()

    @Synchronized
    fun snapshotText(): String {
        if (events.isEmpty()) {
            return "none"
        }
        return events.joinToString(separator = "\n") { event ->
            "${event.timestampMillis} ${event.stage} ${event.host} ${event.status} ${event.reason}"
        }
    }

    @Synchronized
    fun clear() {
        events.clear()
    }

    private fun String.sanitizeHost(): String {
        return trim()
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .trim()
            .trimEnd('.')
            .lowercase(Locale.US)
            .filter { it.isLetterOrDigit() || it == '-' || it == '.' || it == ':' || it == '[' || it == ']' }
            .take(253)
            .ifBlank { "unknown" }
    }

    private fun String.sanitizeToken(): String {
        return trim()
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .trim()
            .replace(Regex("\\s+"), "_")
            .lowercase(Locale.US)
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .take(40)
            .ifBlank { "unknown" }
    }

    private fun String.sanitizeReason(): String {
        return trim()
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .trim()
            .replace(Regex("\\s+"), "_")
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .take(80)
            .ifBlank { "unknown" }
    }
}
