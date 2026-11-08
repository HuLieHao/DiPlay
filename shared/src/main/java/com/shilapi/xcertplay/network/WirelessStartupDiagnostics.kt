package com.shilapi.xcertplay.network

import java.io.Closeable
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Observes startup without changing connection deadlines, address selection or retry behavior. */
internal class WirelessStartupDiagnostics(
    private val sample: () -> String,
    private val log: (String) -> Unit,
    private val intervalMillis: Long = 10_000,
    private val nowNs: () -> Long = System::nanoTime,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val startedNanos = nowNs()
    private var authenticatedAtNs: Long? = null
    private var firstWifiConfigNs: Long? = null
    private var firstTcpAfterWifiConfigMs: Long? = null
    private var authenticated = false
    private var wifiConfigs = 0
    private var startRequests = 0
    private var tcpAccepted = 0
    private var sessionActive = false
    private var sessionActiveAtNs: Long? = null
    private var firstFrameAtNs: Long? = null
    private var firstStartRequestNs: Long? = null
    private var firstTcpAfterStartMs: Long? = null
    private data class Snapshot(val text: String, val startNs: Long, val endNs: Long)
    @Volatile private var lastSnapshot: Snapshot? = null
    private val worker = Thread(::observe, "diplay-wireless-diagnostics").apply { isDaemon = true }

    init { require(intervalMillis > 0) }

    @Synchronized fun start() {
        if (!closed.get() && worker.state == Thread.State.NEW) worker.start()
    }

    @Synchronized fun controlProgress(message: String) {
        when (message) {
            "iap2 authentication accepted" -> {
                authenticated = true
                if (authenticatedAtNs == null) authenticatedAtNs = nowNs()
            }
            "iap2 tx=0x5703 accessory-wifi-configuration",
            "iap2 tx=0x5703 post-transport accessory-wifi-configuration" -> {
                wifiConfigs++
                if (firstWifiConfigNs == null) firstWifiConfigNs = nowNs()
            }
            "iap2 tx=0x4301 carplay-start-session" -> {
                startRequests++
                if (firstStartRequestNs == null) firstStartRequestNs = nowNs()
            }
        }
    }

    @Synchronized fun connectionAccepted() {
        tcpAccepted++
        if (firstTcpAfterWifiConfigMs == null) {
            firstWifiConfigNs?.let { firstTcpAfterWifiConfigMs = elapsedMillis(it) }
        }
        if (firstTcpAfterStartMs == null) {
            firstStartRequestNs?.let { firstTcpAfterStartMs = elapsedMillis(it) }
        }
    }
    @Synchronized fun sessionActive() {
        sessionActive = true
        if (sessionActiveAtNs == null) sessionActiveAtNs = nowNs()
    }

    @Synchronized fun frameRendered() {
        if (firstFrameAtNs == null) firstFrameAtNs = nowNs()
    }

    @Synchronized fun summary(): String {
        val waitingFor = when {
            firstFrameAtNs != null -> "none"
            sessionActive -> "first_rendered_frame"
            tcpAccepted > 0 -> "AirPlay_protocol"
            startRequests > 0 -> "WiFi_discovery_or_AirPlay_TCP"
            authenticated -> "WiFi_configuration_or_start_request"
            else -> "Bluetooth_iAP2_authentication"
        }
        return "wireless startup elapsedMs=${elapsedMillis(startedNanos)} " +
            "authenticated=$authenticated wifiConfigs=$wifiConfigs startRequests=$startRequests " +
            "tcpAccepted=$tcpAccepted sessionActive=$sessionActive waitingFor=$waitingFor " +
            "startRequestAgeMs=${firstStartRequestNs?.let(::elapsedMillis) ?: "none"} " +
            "firstTcpAfterStartMs=${firstTcpAfterStartMs ?: "none"} " +
            "authenticatedAtMs=${authenticatedAtNs?.let { (it - startedNanos).coerceAtLeast(0) / 1_000_000 } ?: "none"} " +
            "firstWifiConfigAtMs=${firstWifiConfigNs?.let { (it - startedNanos).coerceAtLeast(0) / 1_000_000 } ?: "none"} " +
            "firstTcpAfterWifiConfigMs=${firstTcpAfterWifiConfigMs ?: "none"} " +
            "sessionActiveAtMs=${sessionActiveAtNs?.let { (it - startedNanos).coerceAtLeast(0) / 1_000_000 } ?: "none"} " +
            "firstFrameAtMs=${firstFrameAtNs?.let { (it - startedNanos).coerceAtLeast(0) / 1_000_000 } ?: "none"} " +
            "firstFrameAfterSessionMs=${firstFrameAtNs?.let { frame -> sessionActiveAtNs?.let { (frame - it).coerceAtLeast(0) / 1_000_000 } } ?: "none"} " +
            "timingOrigin=after_hotspot_address_selection"
    }

    private fun elapsedMillis(since: Long): Long = (nowNs() - since).coerceAtLeast(0) / 1_000_000

    private fun observe() {
        try {
            while (!closed.get()) {
                val captureStart = nowNs()
                val snapshot = try { sample() } catch (error: Exception) {
                    "sampling=unavailable failureClass=${error.javaClass.simpleName}"
                }
                if (closed.get()) return
                val capture = Snapshot(snapshot, captureStart, nowNs())
                lastSnapshot = capture
                emit(summary())
                emitSnapshot(capture)
                Thread.sleep(intervalMillis)
            }
        } catch (_: InterruptedException) {
            // Teardown interrupts sleep or an in-flight Android callback wait.
        }
    }

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.interrupt()
        emit("${summary()} observation=ended")
        emitSnapshot(lastSnapshot, cached = true)
    }

    private fun emitSnapshot(snapshot: Snapshot?, cached: Boolean = false) {
        if (snapshot == null) {
            emit("wireless snapshot available=false reason=no_completed_capture")
            return
        }
        emit("wireless capture captureStartAtMs=${(snapshot.startNs - startedNanos).coerceAtLeast(0) / 1_000_000} " +
            "captureEndAtMs=${(snapshot.endNs - startedNanos).coerceAtLeast(0) / 1_000_000} " +
            "captureDurationMs=${(snapshot.endNs - snapshot.startNs).coerceAtLeast(0) / 1_000_000} " +
            "cached=$cached observationWindow=true not_atomic=true")
        // Keep each source separate for the legacy mirror and retain the original in the archive.
        val lines = snapshot.text.lineSequence().filter { it.isNotBlank() }.toList()
        lines.take(32).forEach { line ->
            emit("wireless snapshot${if (cached) " cached=true ageMs=${elapsedMillis(snapshot.startNs)}" else ""} $line")
        }
        if (lines.size > 32) emit("wireless snapshot truncated=true omittedLines=${lines.size - 32}")
    }

    private fun emit(message: String) {
        try { log(message) } catch (_: RuntimeException) {
            // An observer cannot fail startup or teardown.
        }
    }
}

/** Read-only interface evidence; address counts remain available for summary consumers. */
internal object WirelessInterfaceDiagnostics {
    fun snapshot(interfaceName: String?): String {
        if (interfaceName == null) return "interfaceState=unknown"
        return try {
            val network = NetworkInterface.getByName(interfaceName)
                ?: return "interfaceState=missing"
            "interfaceState=${if (network.isUp) "up" else "down"} multicast=${network.supportsMulticast()} " +
                addressSummary(Collections.list(network.inetAddresses)) +
                " iface=$interfaceName mtu=${network.mtu} addresses=" +
                network.interfaceAddresses.joinToString(",") { "${it.address.hostAddress}/${it.networkPrefixLength}" }
        } catch (error: Exception) {
            "interfaceState=unavailable failureClass=${error.javaClass.simpleName}"
        }
    }

    fun addressSummary(addresses: List<InetAddress>): String =
        "ipv4Usable=${addresses.count { it is Inet4Address && !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress && !it.isMulticastAddress }} " +
            "ipv6LinkLocal=${addresses.count { it is Inet6Address && it.isLinkLocalAddress }} " +
            "ipv6Scoped=${addresses.count { it is Inet6Address && it.isLinkLocalAddress && it.scopeId > 0 }}"
}
