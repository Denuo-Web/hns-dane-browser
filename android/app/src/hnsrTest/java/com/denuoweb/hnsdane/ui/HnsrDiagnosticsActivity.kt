package com.denuoweb.hnsdane.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.denuoweb.hnsdane.R
import com.denuoweb.hnsdane.net.NativeBridge
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

class HnsrDiagnosticsActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var networkStatus: TextView
    private lateinit var evidence: TextView
    private lateinit var rawReport: TextView
    private lateinit var connectivity: ConnectivityManager
    private val generation = AtomicInteger(0)
    private var callbackRegistered = false
    private var completedProbes = 0
    private var successfulProbes = 0
    private var lastFingerprint: String? = null
    private lateinit var bootstrap: String

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scheduleProbe("available", network)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            scheduleProbe("capabilities", network, networkCapabilities)
        }

        override fun onLost(network: Network) {
            scheduleProbe("lost:${network}", null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        bootstrap = intent.getStringExtra(EXTRA_BOOTSTRAP)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: DEFAULT_BOOTSTRAP

        setSecondaryScreen(title = getString(R.string.hnsr_diagnostics_title)) {
            addView(screenSection(getString(R.string.hnsr_phase_one_section)) {
                status = preferenceSummary(
                    text = getString(R.string.hnsr_running, bootstrap),
                    selectable = true,
                    maxLines = Int.MAX_VALUE,
                    bold = true,
                )
                addScreenRow(preferenceRow(
                    title = getString(R.string.hnsr_native_status),
                    summaryView = status,
                ))
            })
            addView(screenSection(getString(R.string.hnsr_network_section)) {
                networkStatus = preferenceSummary(
                    text = "Waiting for Android default-network state…",
                    selectable = true,
                    maxLines = Int.MAX_VALUE,
                    bold = true,
                )
                addScreenRow(preferenceRow(
                    title = getString(R.string.hnsr_network_status),
                    summaryView = networkStatus,
                ))
            })
            addView(screenSection(getString(R.string.hnsr_evidence_section)) {
                evidence = reportText("Waiting for the native HNSR response…", boldFieldValues = true)
                addView(evidence)
            })
            addView(screenSection(getString(R.string.hnsr_raw_section)) {
                rawReport = reportText("", monospace = true)
                addView(rawReport)
            })
        }
    }

    override fun onStart() {
        super.onStart()

        if (!callbackRegistered) {
            connectivity.registerDefaultNetworkCallback(networkCallback)
            callbackRegistered = true
        }

        scheduleProbe("activity-start", connectivity.activeNetwork)
    }

    override fun onStop() {
        generation.incrementAndGet()

        if (callbackRegistered) {
            connectivity.unregisterNetworkCallback(networkCallback)
            callbackRegistered = false
        }

        lastFingerprint = null
        super.onStop()
    }

    private fun scheduleProbe(
        reason: String,
        network: Network?,
        suppliedCapabilities: NetworkCapabilities? = null,
    ) {
        val capabilities = suppliedCapabilities
            ?: network?.let(connectivity::getNetworkCapabilities)
        val snapshot = NetworkSnapshot.from(network, capabilities)
        val fingerprint = snapshot.fingerprint

        synchronized(this) {
            if (reason == "capabilities" && lastFingerprint == fingerprint) return
            lastFingerprint = fingerprint
        }

        val probeGeneration = generation.incrementAndGet()
        runOnUiThread {
            if (!isDestroyed) {
                status.text = getString(R.string.hnsr_running, bootstrap)
                networkStatus.text = snapshot.describe(
                    probeGeneration = probeGeneration,
                    completedProbes = completedProbes,
                    successfulProbes = successfulProbes,
                    reason = reason,
                )
            }
        }

        Thread({
            val report = NativeBridge.hnsrProbe(bootstrap = bootstrap)
            val display = formatReport(report, snapshot, probeGeneration)
            runOnUiThread {
                if (!isDestroyed && generation.get() == probeGeneration) {
                    completedProbes += 1

                    if (display.passed) successfulProbes += 1

                    status.text = display.status
                    evidence.text = display.evidence
                    rawReport.text = report
                    networkStatus.text = snapshot.describe(
                        probeGeneration = probeGeneration,
                        completedProbes = completedProbes,
                        successfulProbes = successfulProbes,
                        reason = reason,
                    )
                }
            }
        }, "hnsr-native-probe-${probeGeneration}").start()
    }

    private fun formatReport(
        report: String,
        snapshot: NetworkSnapshot,
        probeGeneration: Int,
    ): ProbeDisplay = runCatching {
        val root = JSONObject(report)
        if (root.optString("result") != "pass") {
            return@runCatching ProbeDisplay(
                passed = false,
                status = "ERROR — native HNSR probe failed closed",
                evidence = "Transport: native Rust\n" +
                    "Android generation: ${probeGeneration}\n" +
                    "Android network: ${snapshot.label}\n" +
                    "Network: ${root.optString("network", "unknown")}\n" +
                    "Bootstrap: ${root.optString("bootstrap", "unknown")}\n" +
                    "Error: ${root.optString("error", "unknown failure")}",
            )
        }
        val outer = root.getJSONObject("outer")
        val hnsr = root.getJSONObject("hnsr")
        val route = root.getJSONObject("route")
        val opcodes = hnsr.getJSONArray("observedOpcodes")
        ProbeDisplay(
            passed = true,
            status = "PASS — native Rust verified an HNSR route after network generation ${probeGeneration}",
            evidence = buildString {
                appendLine("Transport: native Rust Handshake P2P")
                appendLine("Android network: ${snapshot.label}")
                appendLine("Android generation: ${probeGeneration}")
                appendLine("Network: ${root.getString("network")}")
                appendLine("Version/verack: ${outer.getBoolean("versionVerack")}")
                appendLine("HNSR service: ${outer.getBoolean("rendezvousService")}")
                appendLine("Private packet: ${hnsr.getString("packetType")}")
                appendLine("Discovery contacts: ${hnsr.getInt("findNodeContacts")}")
                appendLine("Observed: ${opcodes.join(", ")}")
                appendLine("Sampled routes: ${hnsr.getInt("sampledRecords")}")
                appendLine("Exact routes: ${hnsr.getInt("exactRecords")}")
                appendLine("Route sequence: ${route.getLong("sequence")}")
                appendLine("Relay tickets: ${route.getInt("relayTickets")}")
                appendLine("Delegation signature: verified")
                appendLine("Relay + endpoint signatures: verified")
                appendLine("Route signature: verified")
                append("Network binding: verified")
            },
        )
    }.getOrElse { error ->
        ProbeDisplay(
            passed = false,
            status = "ERROR — invalid native report",
            evidence = "Transport: native Rust\nParser error: ${error.message.orEmpty()}",
        )
    }

    private data class ProbeDisplay(
        val passed: Boolean,
        val status: String,
        val evidence: String,
    )

    private data class NetworkSnapshot(
        val handle: String,
        val transports: String,
        val validated: Boolean,
        val metered: Boolean,
    ) {
        val fingerprint: String
            get() = "$handle|$transports|$validated|$metered"

        val label: String
            get() = "$transports ($handle)"

        fun describe(
            probeGeneration: Int,
            completedProbes: Int,
            successfulProbes: Int,
            reason: String,
        ): String = buildString {
            appendLine("Default: $label")
            appendLine("Validated: $validated")
            appendLine("Metered: $metered")
            appendLine("Probe generation: $probeGeneration")
            appendLine("Completed reprobes: $completedProbes")
            appendLine("Successful reprobes: $successfulProbes")
            append("Trigger: $reason")
        }

        companion object {
            fun from(network: Network?, capabilities: NetworkCapabilities?): NetworkSnapshot {
                val transports = buildList {
                    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                        add("Wi-Fi")
                    }
                    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
                        add("cellular")
                    }
                    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) {
                        add("Ethernet")
                    }
                    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                        add("VPN")
                    }
                }.ifEmpty { listOf("unavailable") }.joinToString(" + ")

                return NetworkSnapshot(
                    handle = network?.toString() ?: "no-default-network",
                    transports = transports,
                    validated = capabilities?.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                    ) == true,
                    metered = capabilities?.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
                    ) != true,
                )
            }
        }
    }

    companion object {
        const val EXTRA_BOOTSTRAP = "bootstrap"
        private const val DEFAULT_BOOTSTRAP = "127.0.0.1:24038"
    }
}
