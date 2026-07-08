package com.denuoweb.hnsdane.net

import android.content.Context
import com.denuoweb.hnsdane.R
import java.text.NumberFormat
import java.util.Locale

data class HnsSyncProgress(
    val status: String,
    val bestHeight: Long?,
    val bestPeerHeight: Long?,
    val attempted: Long?,
    val successful: Long?,
    val accepted: Long?,
    val failed: Long?,
    val peerCount: Long?,
    val peerGroups: Long?,
    val estimatedTipHeight: Long?,
) {
    val targetHeight: Long?
        get() = bestPeerHeight ?: estimatedTipHeight

    val isBehind: Boolean
        get() {
            val best = bestHeight ?: return false
            val target = targetHeight ?: return false
            return target > best
        }

    val isBehindKnownPeer: Boolean
        get() {
            val best = bestHeight ?: return false
            val peer = bestPeerHeight ?: return false
            return peer > best
        }

    val shouldContinueSoon: Boolean
        get() = isBehindKnownPeer || hasUnknownTargetProgress || status == "syncing"

    val shouldRetrySoon: Boolean
        get() = status in RETRY_STATUSES || needsPeerDiscovery

    val hasUnknownTargetProgress: Boolean
        get() = bestHeight != null &&
            bestHeight > 0L &&
            bestPeerHeight == null &&
            ((accepted ?: 0L) > 0L || status == "syncing")

    val needsPeerDiscovery: Boolean
        get() = status == "idle" && (peerCount ?: 0L) == 0L

    fun progressPermille(): Int? {
        val best = bestHeight ?: return null
        val target = targetHeight ?: return null
        if (target <= 0L) return null
        return ((best.coerceIn(0L, target) * 1000L) / target).toInt()
    }

    fun summary(): String {
        val formattedBest = bestHeight?.formatHeight() ?: "unknown"
        val target = targetHeight
        val targetPart = when {
            isBehind && target != null -> "target ${target.formatHeight()}"
            bestPeerHeight != null -> "bestPeerHeight ${bestPeerHeight.formatHeight()}"
            estimatedTipHeight != null -> "target ${estimatedTipHeight.formatHeight()}"
            else -> "target unknown"
        }
        val acceptedPart = accepted
            ?.takeIf { it > 0L }
            ?.let { " • accepted +${it.formatHeight()}" }
            .orEmpty()
        val peerPart = peerCount
            ?.takeIf { it > 0L }
            ?.let { " • peers ${it.formatHeight()}" }
            .orEmpty()
        return "${status.ifBlank { "idle" }} • bestHeight $formattedBest • $targetPart$acceptedPart$peerPart"
    }

    fun summary(context: Context): String {
        val formattedBest = bestHeight?.formatHeight(context) ?: context.getString(R.string.common_unknown)
        val target = targetHeight
        val targetPart = when {
            isBehind && target != null -> context.getString(R.string.sync_progress_target, target.formatHeight(context))
            bestPeerHeight != null -> context.getString(
                R.string.sync_progress_best_peer_height,
                bestPeerHeight.formatHeight(context),
            )
            estimatedTipHeight != null -> context.getString(
                R.string.sync_progress_target,
                estimatedTipHeight.formatHeight(context),
            )
            else -> context.getString(R.string.sync_progress_target_unknown)
        }
        val acceptedPart = accepted
            ?.takeIf { it > 0L }
            ?.let { " • ${context.getString(R.string.sync_progress_accepted, it.formatHeight(context))}" }
            .orEmpty()
        val peerPart = peerCount
            ?.takeIf { it > 0L }
            ?.let { " • ${context.getString(R.string.sync_progress_peers, it.formatHeight(context))}" }
            .orEmpty()
        return context.getString(
            R.string.sync_progress_summary,
            statusLabel(context),
            formattedBest,
            targetPart,
            acceptedPart,
            peerPart,
        )
    }

    private fun Long.formatHeight(): String =
        NumberFormat.getIntegerInstance(Locale.US).format(this)

    private fun Long.formatHeight(context: Context): String =
        NumberFormat.getIntegerInstance(context.resources.configuration.locales[0] ?: Locale.getDefault()).format(this)

    private fun statusLabel(context: Context): String =
        when (status.ifBlank { "idle" }) {
            "idle" -> context.getString(R.string.sync_status_idle)
            "syncing" -> context.getString(R.string.sync_status_syncing)
            "up_to_date" -> context.getString(R.string.sync_status_up_to_date)
            "error" -> context.getString(R.string.sync_status_error)
            "seed_failed" -> context.getString(R.string.sync_status_seed_failed)
            "peer_failed" -> context.getString(R.string.sync_status_peer_failed)
            else -> status.replace('_', ' ')
        }

    companion object {
        private val RETRY_STATUSES = setOf("error", "peer_failed", "seed_failed")

        fun fromJson(statusJson: String?): HnsSyncProgress {
            if (statusJson.isNullOrBlank()) {
                return HnsSyncProgress(
                    status = "idle",
                    bestHeight = null,
                    bestPeerHeight = null,
                    attempted = null,
                    successful = null,
                    accepted = null,
                    failed = null,
                    peerCount = null,
                    peerGroups = null,
                    estimatedTipHeight = null,
                )
            }
            return HnsSyncProgress(
                status = stringField(statusJson, "status") ?: "idle",
                bestHeight = longField(statusJson, "bestHeight"),
                bestPeerHeight = longField(statusJson, "bestPeerHeight"),
                attempted = longField(statusJson, "attempted"),
                successful = longField(statusJson, "successful"),
                accepted = longField(statusJson, "accepted"),
                failed = longField(statusJson, "failed"),
                peerCount = longField(statusJson, "peerCount"),
                peerGroups = longField(statusJson, "peerGroups"),
                estimatedTipHeight = longField(statusJson, "estimatedTipHeight"),
            )
        }

        private fun stringField(json: String, name: String): String? {
            val pattern = """"$name"\s*:\s*"([^"]*)"""".toRegex()
            return pattern.find(json)?.groupValues?.getOrNull(1)
        }

        private fun longField(json: String, name: String): Long? {
            val pattern = """"$name"\s*:\s*(null|-?\d+)""".toRegex()
            val value = pattern.find(json)?.groupValues?.getOrNull(1) ?: return null
            return value.takeUnless { it == "null" }?.toLongOrNull()
        }
    }
}
