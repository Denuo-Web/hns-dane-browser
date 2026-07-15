package com.denuoweb.hnsdane.net

import com.denuoweb.hnsdane.core.HnsPageResolverPolicy
import com.denuoweb.hnsdane.core.HnsPageSecurityPath
import com.denuoweb.hnsdane.core.HnsPageTlsPolicy
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal interface KotlinFallbackProxyServer {
    fun start(): Boolean

    fun boundPort(): Int?

    fun close()
}

internal fun interface KotlinFallbackProxyServerFactory {
    fun create(
        config: RustBrowserProxyConfig,
        authorization: LoopbackProxyAuthorization,
        onStatus: (String, Int, HnsPageTlsPolicy?, HnsPageResolverPolicy?, HnsPageSecurityPath?, String?) -> Unit,
    ): KotlinFallbackProxyServer
}

internal object DefaultKotlinFallbackProxyServerFactory : KotlinFallbackProxyServerFactory {
    override fun create(
        config: RustBrowserProxyConfig,
        authorization: LoopbackProxyAuthorization,
        onStatus: (String, Int, HnsPageTlsPolicy?, HnsPageResolverPolicy?, HnsPageSecurityPath?, String?) -> Unit,
    ): KotlinFallbackProxyServer {
        val server = LoopbackProxyServer(
            port = 0,
            dataDir = File(config.dataDir),
            strictHnsMode = { config.strictHnsMode },
            dohResolverUrl = { config.dohResolverUrl },
            statelessDaneCertificates = { config.statelessDaneCertificates },
            handshakeNetwork = { config.network },
            enforceHnsHostScope = true,
            scopedHnsHost = { config.scopeHost },
            proxyAuthorization = authorization,
            onHnsStatus = onStatus,
        )
        return object : KotlinFallbackProxyServer {
            override fun start(): Boolean = server.start()

            override fun boundPort(): Int? = server.boundPort()

            override fun close() = server.close()
        }
    }
}

internal class DefaultLocalBrowserProxyFactory(
    private val rustFactory: LocalBrowserProxyFactory = LocalBrowserProxyFactory(RustBrowserProxy::start),
    private val fallbackFactory: LocalBrowserProxyFactory = LocalBrowserProxyFactory(KotlinFallbackBrowserProxy::start),
) : LocalBrowserProxyFactory {
    override fun start(config: RustBrowserProxyConfig): LocalBrowserProxy? =
        runCatching { rustFactory.start(config) }.getOrNull()
            ?: runCatching { fallbackFactory.start(config) }.getOrNull()
}

internal class KotlinFallbackBrowserProxy private constructor(
    override val endpoint: LocalBrowserProxyEndpoint,
    override val scopeHost: String,
    private val server: KotlinFallbackProxyServer,
    private val statuses: KotlinFallbackStatusMailbox,
) : LocalBrowserProxy {
    private val lifecycleLock = Any()
    private var stopRequested = false
    private var destroyed = false

    override fun matchesLocalCertificate(host: String, certificateDer: ByteArray): Boolean {
        synchronized(lifecycleLock) {
            if (stopRequested || destroyed) return false
        }
        val matches = HnsLocalCertificateRegistry.hasPinnedCertificateDer(host, certificateDer)
        return synchronized(lifecycleLock) {
            !stopRequested && !destroyed && matches
        }
    }

    override fun takeMainFrameStatus(host: String): LocalBrowserProxyStatus? {
        synchronized(lifecycleLock) {
            if (stopRequested || destroyed) return null
        }
        val status = statuses.take(host)
        return synchronized(lifecycleLock) {
            status.takeIf { !stopRequested && !destroyed }
        }
    }

    override fun discardMainFrameStatus(host: String) {
        synchronized(lifecycleLock) {
            if (stopRequested || destroyed) return
        }
        statuses.discard(host)
    }

    override fun requestStop() {
        val shouldStop = synchronized(lifecycleLock) {
            if (stopRequested || destroyed) {
                false
            } else {
                stopRequested = true
                true
            }
        }
        if (shouldStop) {
            statuses.deactivate()
            server.close()
        }
    }

    override fun joinAndDestroy() {
        requestStop()
        synchronized(lifecycleLock) {
            destroyed = true
        }
    }

    override fun toString(): String =
        "KotlinFallbackBrowserProxy(scope=[REDACTED], endpoint=$endpoint)"

    companion object {
        private val nextGeneration = AtomicLong(0)

        fun start(
            config: RustBrowserProxyConfig,
            serverFactory: KotlinFallbackProxyServerFactory = DefaultKotlinFallbackProxyServerFactory,
        ): KotlinFallbackBrowserProxy? {
            val scopeHost = canonicalBrowserProxyHost(config.scopeHost) ?: return null
            val normalizedConfig = if (scopeHost == config.scopeHost) {
                config
            } else {
                RustBrowserProxyConfig(
                    dataDir = config.dataDir,
                    network = config.network,
                    scopeHost = scopeHost,
                    strictHnsMode = config.strictHnsMode,
                    dohResolverUrl = config.dohResolverUrl,
                    statelessDaneCertificates = config.statelessDaneCertificates,
                )
            }
            val authorization = LoopbackProxyAuthorization.create()
            val statuses = KotlinFallbackStatusMailbox()
            val server = runCatching {
                serverFactory.create(normalizedConfig, authorization, statuses::record)
            }.getOrNull() ?: return null
            val started = runCatching { server.start() }.getOrDefault(false)
            val port = server.boundPort()
            if (!started || port !in 1..65535) {
                runCatching { server.close() }
                return null
            }
            val generation = nextFallbackGeneration()
            return KotlinFallbackBrowserProxy(
                endpoint = LocalBrowserProxyEndpoint(
                    nativeHandle = 0,
                    port = requireNotNull(port),
                    instanceId = LocalProxyInstanceId(UUID.randomUUID().toString(), generation),
                    authorization = authorization,
                ),
                scopeHost = scopeHost,
                server = server,
                statuses = statuses,
            )
        }

        private fun nextFallbackGeneration(): Long {
            while (true) {
                val current = nextGeneration.get()
                val next = if (current == Long.MAX_VALUE) 1L else current + 1L
                if (nextGeneration.compareAndSet(current, next)) return next
            }
        }
    }
}

private class KotlinFallbackStatusMailbox {
    private class Pending(
        val status: LocalBrowserProxyStatus,
        val traceBytes: Int,
    )

    private var active = true
    private var nextSequence = 0L
    private var retainedTraceBytes = 0
    private val latestByHost = LinkedHashMap<String, Pending>()

    @Synchronized
    fun record(
        host: String,
        statusCode: Int,
        tlsPolicy: HnsPageTlsPolicy?,
        resolverPolicy: HnsPageResolverPolicy?,
        securityPath: HnsPageSecurityPath?,
        traceJson: String?,
    ) {
        if (!active || statusCode !in 100..599) return
        val canonicalHost = canonicalBrowserProxyHost(host) ?: return
        val retainedTrace = traceJson?.takeIf {
            it.toByteArray(StandardCharsets.UTF_8).size <= MAX_RETAINED_TRACE_BYTES
        }
        val traceBytes = retainedTrace?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0
        if (nextSequence == Long.MAX_VALUE) {
            nextSequence = 0
            retainedTraceBytes = 0
            latestByHost.clear()
        }
        nextSequence += 1
        latestByHost.remove(canonicalHost)?.let { previous ->
            retainedTraceBytes = (retainedTraceBytes - previous.traceBytes).coerceAtLeast(0)
        }
        while (
            latestByHost.size >= MAX_RETAINED_HOSTS ||
            retainedTraceBytes + traceBytes > MAX_RETAINED_TRACE_BYTES
        ) {
            val oldestHost = latestByHost.entries.firstOrNull()?.key ?: break
            latestByHost.remove(oldestHost)?.let { removed ->
                retainedTraceBytes = (retainedTraceBytes - removed.traceBytes).coerceAtLeast(0)
            }
        }
        val status = LocalBrowserProxyStatus(
            sequence = nextSequence,
            statusCode = statusCode,
            tlsPolicy = tlsPolicy,
            resolverPolicy = resolverPolicy,
            securityPath = securityPath,
            resolutionTraceJson = retainedTrace,
        )
        latestByHost[canonicalHost] = Pending(status, traceBytes)
        retainedTraceBytes += traceBytes
    }

    @Synchronized
    fun take(host: String): LocalBrowserProxyStatus? {
        if (!active) return null
        val canonicalHost = canonicalBrowserProxyHost(host) ?: return null
        return latestByHost.remove(canonicalHost)?.also { pending ->
            retainedTraceBytes = (retainedTraceBytes - pending.traceBytes).coerceAtLeast(0)
        }?.status
    }

    @Synchronized
    fun discard(host: String) {
        val canonicalHost = canonicalBrowserProxyHost(host) ?: return
        latestByHost.remove(canonicalHost)?.let { pending ->
            retainedTraceBytes = (retainedTraceBytes - pending.traceBytes).coerceAtLeast(0)
        }
    }

    @Synchronized
    fun deactivate() {
        active = false
        retainedTraceBytes = 0
        latestByHost.clear()
    }

    private companion object {
        const val MAX_RETAINED_HOSTS = 8
        const val MAX_RETAINED_TRACE_BYTES = 64 * 1024
    }
}
