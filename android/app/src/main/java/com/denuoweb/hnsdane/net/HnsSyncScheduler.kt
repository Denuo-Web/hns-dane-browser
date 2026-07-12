package com.denuoweb.hnsdane.net

import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class HnsSyncSnapshot(
    val statusJson: String,
    val updatedAtMillis: Long,
)

class HnsSyncScheduler(
    private val dataDir: File,
    private val bridge: HnsSyncBridge = NativeBridge,
    private val network: () -> String = { DEFAULT_NETWORK },
    private val idleIntervalMs: Long = DEFAULT_IDLE_INTERVAL_MS,
    private val activeIntervalMs: Long = DEFAULT_ACTIVE_INTERVAL_MS,
    private val retryIntervalMs: Long = DEFAULT_RETRY_INTERVAL_MS,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val singleFlight: HnsSyncSingleFlight = ProcessHnsSyncSingleFlight,
) : Closeable {
    private val running = AtomicBoolean(false)
    private var future: ScheduledFuture<*>? = null

    @Volatile
    var lastSnapshot: HnsSyncSnapshot? = null
        private set

    fun start(onSnapshot: (HnsSyncSnapshot) -> Unit) {
        if (!running.compareAndSet(false, true)) {
            return
        }

        scheduleNext(0, onSnapshot)
    }

    internal fun tick(onSnapshot: (HnsSyncSnapshot) -> Unit) {
        if (!running.get()) {
            return
        }

        val snapshot = runOnceIfIdle(onSnapshot)
        if (running.get()) {
            scheduleNext(snapshot?.let(::nextDelayMs) ?: activeIntervalMs, onSnapshot)
        }
    }

    internal fun runOnce(onSnapshot: (HnsSyncSnapshot) -> Unit): HnsSyncSnapshot =
        checkNotNull(runOnceIfIdle(onSnapshot)) { "another HNS sync operation is already running" }

    private fun runOnceIfIdle(onSnapshot: (HnsSyncSnapshot) -> Unit): HnsSyncSnapshot? =
        singleFlight.tryRun {
            val snapshot = HnsSyncSnapshot(
                statusJson = bridge.syncOnce(dataDir.absolutePath, network()),
                updatedAtMillis = clock(),
            )
            lastSnapshot = snapshot
            onSnapshot(snapshot)
            snapshot
        }

    internal fun nextDelayMs(snapshot: HnsSyncSnapshot): Long {
        val progress = HnsSyncProgress.fromJson(snapshot.statusJson)
        return when {
            progress.shouldRetrySoon -> retryIntervalMs
            progress.shouldContinueSoon -> activeIntervalMs
            else -> idleIntervalMs
        }
    }

    private fun scheduleNext(delayMs: Long, onSnapshot: (HnsSyncSnapshot) -> Unit) {
        future = executor.schedule(
            { tick(onSnapshot) },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
    }

    override fun close() {
        running.set(false)
        future?.cancel(true)
        executor.shutdownNow()
    }

    companion object {
        const val DEFAULT_ACTIVE_INTERVAL_MS: Long = 1_000
        const val DEFAULT_RETRY_INTERVAL_MS: Long = 10_000
        const val DEFAULT_IDLE_INTERVAL_MS: Long = 10 * 60 * 1_000
        private const val DEFAULT_NETWORK = "mainnet"
    }
}

class HnsSyncSingleFlight {
    private val running = AtomicBoolean(false)

    fun <T> tryRun(operation: () -> T): T? {
        if (!running.compareAndSet(false, true)) {
            return null
        }
        return try {
            operation()
        } finally {
            running.set(false)
        }
    }

    fun isRunning(): Boolean = running.get()
}

internal val ProcessHnsSyncSingleFlight = HnsSyncSingleFlight()
