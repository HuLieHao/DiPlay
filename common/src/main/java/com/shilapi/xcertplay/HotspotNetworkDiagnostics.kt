package com.shilapi.xcertplay

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.network.CarHotspotStatus
import java.io.Closeable
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import android.util.Log
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit, read-only capture. Never runs on connection/startup or silently requests ADB approval. */
internal object HotspotNetworkDiagnostics {
    enum class Outcome { SAVED, BUSY, FAILED }
    interface Probe : Closeable {
        fun basic(): List<String>
        fun connect(): LocalAdb.Access
        fun shell(command: String, timeoutMillis: Int): String?
    }
    private val running = AtomicBoolean()
    private val storageLock = Any()
    private const val MAX_CAPTURE_CHARS = 640 * 1024
    private const val MAX_SECTION_CHARS = 32 * 1024
    private const val REMOTE_SECTION_BYTES = 128 * 1024
    private const val EXIT_MARKER = "__BUICK_DIAG_EXIT__="
    private const val ADB_BUDGET_MILLIS = 20_000L
    private const val DIRECTORY = "hotspot-network-diagnostics"
    private val files = listOf("latest.txt", "previous-1.txt", "previous-2.txt")

    // Fixed read-only commands: no user input, su, configuration writes, service restart, or packet capture.
    private val commands = listOf(
        "privileges" to "id; getenforce; getprop ro.debuggable; getprop ro.secure; getprop service.adb.tcp.port",
        "hotspot-processes" to "ps -A | grep -E 'hostapd|wificond|netd|dnsmasq|wifi@|wifi.hostapd'",
        "hotspot-file-metadata" to "ls -ld /system/bin/hostapd /vendor/bin/hostapd /system/bin/hostapd_cli /data/misc/wifi /data/vendor/wifi /data/misc/wifi/hostapd.conf /data/vendor/wifi/hostapd/hostapd.conf",
        "hotspot-config-selected-fields" to "for f in /data/misc/wifi/hostapd.conf /data/vendor/wifi/hostapd/hostapd.conf; do echo configPath=\"\$f\"; grep -E '^(interface|driver|ssid|hw_mode|channel|country_code|ieee80211[nac]+|wmm_enabled|interworking|internet|access_network_type|vendor_elements)=' \"\$f\"; done",
        "addresses" to "ip -details addr show",
        "routes" to "ip route show table all; ip -6 route show table all; ip rule show; ip -6 rule show",
        "neighbours" to "ip neigh show; ip -6 neigh show; cat /proc/net/arp",
        "dhcp-leases" to "for f in /data/misc/dhcp/dnsmasq.leases /data/misc/dhcp/dnsmasq.leases6 /data/misc/wifi/dnsmasq.leases /data/vendor/dhcp/dnsmasq.leases; do echo leasePath=\"\$f\"; cat \"\$f\"; done",
        "recent-network-log" to "logcat -d -t 1000 -v threadtime -s SoftApManager WifiStateMachine WifiClientModeImpl WifiService WificondControl wificond hostapd dnsmasq DhcpClient IpClient Tethering ConnectivityService NetworkMonitor netd",
        "wifi-status" to "cmd wifi status",
        "radio-and-stations" to "iw dev; iw dev ap0 station dump; iw dev wlan0 station dump; iw dev wlan1 station dump",
        "forwarding" to "cat /proc/sys/net/ipv4/ip_forward; cat /proc/sys/net/ipv6/conf/all/forwarding; iptables -t nat -S; ip6tables -t nat -S",
        "tethering" to "dumpsys tethering",
        "connectivity" to "dumpsys connectivity",
        "wifi-hotspot-details" to "dumpsys wifi | grep -i -A 12 -B 4 -E 'SoftAp|tether|hostapd|frequency|channel|client|dhcp|upstream'",
        "wifi" to "dumpsys wifi",
    )

    fun collect(context: Context, probe: Probe = AndroidProbe(context.applicationContext)): Outcome {
        if (!running.compareAndSet(false, true)) { runCatching { probe.close() }; return Outcome.BUSY }
        val timer = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "hotspot-diagnostic-deadline").apply { isDaemon = true }
        }
        val started = SystemClock.elapsedRealtime()
        val captureId = java.util.UUID.randomUUID().toString()
        val archive = DiagnosticArchive.forLogs(File(context.filesDir, "logs"))
        val correlation = archive.correlation()
        val text = StringBuilder()
        fun event(message: String) { Log.i("BuickPlayNetworkDiag", "captureId=$captureId $message") }
        fun section(title: String, raw: String, metadata: String = "") {
            if (text.length >= MAX_CAPTURE_CHARS) return
            val tail = title == "recent-network-log"
            val lines = raw.lineSequence().toList()
            val selected = if (tail) lines.takeLast(1000) else lines.take(1000)
            val joined = selected.joinToString("\n")
            val remaining = (MAX_CAPTURE_CHARS - text.length - title.length - metadata.length - 150).coerceAtLeast(0)
            val limit = remaining.coerceAtMost(MAX_SECTION_CHARS)
            val content = if (tail) joined.takeLast(limit) else joined.take(limit)
            text.appendLine("--- $title ---")
            if (metadata.isNotEmpty()) text.appendLine(metadata)
            if (lines.size > 1000 || joined.length > limit || raw.toByteArray(Charsets.UTF_8).size >= REMOTE_SECTION_BYTES)
                text.appendLine(if (tail) "[truncated; keeping newest available events; first line may be partial]" else "[truncated; showing available prefix]")
            text.appendLine(content)
        }
        return try {
            event("capture_started")
            section("capture", "captureId=$captureId $correlation captureStartedAt=${DiagnosticBuildInfo.timestamp()} capturedAtEpochMs=${System.currentTimeMillis()} elapsedRealtimeMs=$started\n" +
                DiagnosticBuildInfo.report(context) +
                "Android API=${Build.VERSION.SDK_INT} board=${Build.BOARD} hardware=${Build.HARDWARE}\n" +
                "sessionActive=${CarPlayBackgroundSession.active} sessionPresent=${CarPlayBackgroundSession.hasSession()}\n" +
                connectionState(context) + "\n" +
                "Captured text retained without redaction or credential/payload filtering; output size limits apply.\n" +
                "Read-only receiver snapshot; iPhone internet, routes and actual beacon IEs are NOT measured.")
            val basicAt = SystemClock.elapsedRealtime()
            val basic = runCatching { probe.basic().joinToString("\n") }
            section("app-network", basic.getOrElse { "failure=${it.javaClass.simpleName}: ${it.message}" },
                "status=${if (basic.isSuccess) "completed" else "failed"} durationMs=${SystemClock.elapsedRealtime() - basicAt}")
            val deadline = SystemClock.elapsedRealtime() + ADB_BUDGET_MILLIS
            val captureExpired = AtomicBoolean()
            val cancellation = timer.schedule({
                captureExpired.set(true)
                runCatching { probe.close() }
            }, ADB_BUDGET_MILLIS, TimeUnit.MILLISECONDS)
            val connectAt = SystemClock.elapsedRealtime()
            val access = runCatching { probe.connect() }.getOrDefault(LocalAdb.Access.UNREACHABLE)
            section("local-adb", "access=$access approvalRequested=false durationMs=${SystemClock.elapsedRealtime() - connectAt} budgetMs=$ADB_BUDGET_MILLIS" +
                if (captureExpired.get() || SystemClock.elapsedRealtime() >= deadline) " reason=capture_deadline" else "")
            val counts = linkedMapOf<String, Int>()
            for ((name, command) in commands) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (access != LocalAdb.Access.READY || remaining <= 0 || captureExpired.get()) {
                    val reason = if (remaining <= 0 || captureExpired.get()) "capture_deadline" else "adb_$access"
                    section(name, "command=$command", "status=not_sampled reason=$reason")
                    counts["not_sampled"] = (counts["not_sampled"] ?: 0) + 1
                    continue
                }
                val budget = remaining.coerceAtMost(2_000).toInt()
                val commandAt = SystemClock.elapsedRealtime()
                // Marker is inside the bounded stream: if a prefix limit removes it, exit status
                // is explicitly unknown instead of reporting the pipeline's misleading exit=0.
                val wrapped = "{ $command; code=\$?; printf '\\n${EXIT_MARKER}%s\\n' \"\$code\"; } 2>&1 | ${if (name == "recent-network-log") "tail" else "head"} -c $REMOTE_SECTION_BYTES"
                val result = runCatching { probe.shell(wrapped, budget) }
                val raw = result.getOrNull()
                val exit = raw?.let { Regex("(?m)^${EXIT_MARKER}([0-9]+)\\s*$").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                val timeoutReason = when {
                    captureExpired.get() || SystemClock.elapsedRealtime() >= deadline -> "capture_deadline"
                    result.exceptionOrNull() is SocketTimeoutException -> "command_timeout"
                    else -> null
                }
                val status = when {
                    timeoutReason != null -> "timed_out"
                    result.isFailure -> "failed"
                    raw == null -> "link_failed"
                    exit == null -> "incomplete_output"
                    exit != 0 -> "exit_nonzero"
                    else -> "completed"
                }
                counts[status] = (counts[status] ?: 0) + 1
                val duration = SystemClock.elapsedRealtime() - commandAt
                val metadata = "status=$status" + (timeoutReason?.let { " reason=$it" } ?: "") +
                    " exitCode=${exit ?: "unknown"} durationMs=$duration timeoutMs=$budget"
                event("section=$name $metadata")
                val output = raw?.replace(Regex("(?m)^${EXIT_MARKER}[0-9]+\\s*$"), "")
                    ?: result.exceptionOrNull()?.let { "failure=${it.javaClass.simpleName}: ${it.message}" }
                    ?: "ADB link failed or was cancelled; no output returned"
                section(name, output, "$metadata\ncommand=$command")
            }
            cancellation.cancel(false)
            section("capture-result", "captureId=$captureId captureCompletedAt=${DiagnosticBuildInfo.timestamp()} durationMs=${SystemClock.elapsedRealtime() - started}\n" +
                "commandSummary=$counts\n" + connectionState(context))
            section("interpretation", "Selected configuration fields are not proof of actual broadcast contents.\n" +
                "Station Wi-Fi/DHCP describes the receiver upstream, NOT the iPhone DHCP assignment.\n" +
                "Config/lease probes use known candidate paths only; unavailable files, permissions or tools do not prove absent capability.\n" +
                "Compare captures while connecting and running. Record iPhone internet tests separately. Original identifiers are retained.")
            save(context, text.toString())
            archive.capture(text.toString(), captureId, correlation.substringAfter("session=").substringBefore(" "))
            event("capture_saved durationMs=${SystemClock.elapsedRealtime() - started}")
            Outcome.SAVED
        } catch (error: Exception) {
            event("capture_failed failure=${error.javaClass.simpleName}: ${error.message}")
            Outcome.FAILED
        } finally { runCatching { probe.close() }; timer.shutdownNow(); running.set(false) }
    }

    private fun connectionState(context: Context): String =
        CarPlayBackgroundSession.snapshot()?.controller?.connectionDiagnosticSnapshot()
            ?: "transport=${if (AirPlayPersistence.loadWirelessEnabled(context)) "WIRELESS" else "WIRED"} controller=absent stage=not_running"

    private fun save(context: Context, text: String) = synchronized(storageLock) {
        val folder = File(context.filesDir, DIRECTORY).apply { check(mkdirs() || isDirectory) }
        val latest = AtomicFile(File(folder, files.first()))
        if (File(folder, files.first()).exists() || File(folder, files.first() + ".bak").exists()) latest.openRead().close()
        for (i in files.lastIndex downTo 1) {
            val previous = File(folder, files[i - 1])
            if (previous.isFile) previous.copyTo(File(folder, files[i]), overwrite = true)
        }
        val file = AtomicFile(File(folder, files.first()))
        val stream = file.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    /** Export reads saved evidence only, without network calls or permission prompts. */
    fun report(context: Context): String = synchronized(storageLock) {
        buildString {
            appendLine("--- Saved hotspot network captures (receiver-side only) ---")
            var found = false
            for (name in files) {
                val file = File(context.filesDir, "$DIRECTORY/$name")
                if (!file.isFile && !File(file.path + ".bak").isFile) continue
                found = true
                appendLine("--- $name ---")
                runCatching { AtomicFile(file).openRead().bufferedReader().use { it.readTextBounded() } }.getOrNull()
                    ?.let(::appendLine)
            }
            if (!found) appendLine("No capture saved. Use Settings > Diagnostics > Capture hotspot network diagnostics.")
        }
    }

    private fun java.io.Reader.readTextBounded(): String {
        val chars = CharArray(MAX_CAPTURE_CHARS)
        var total = 0
        while (total < chars.size) { val size = read(chars, total, chars.size - total); if (size <= 0) break; total += size }
        return String(chars, 0, total)
    }

    private class AndroidProbe(private val app: Context) : Probe {
        private val clientLock = Any()
        private var adb: LocalAdb? = null
        private var commandGeneration = 0
        private val commandTimer = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "hotspot-diagnostic-command-deadline").apply { isDaemon = true }
        }
        private val closed = AtomicBoolean()
        override fun basic(): List<String> = buildList {
            add("hotspotEnabled=${CarHotspotStatus.isEnabled(app)}")
            runCatching {
                val wifi = app.getSystemService(WifiManager::class.java)
                add("wifiEnabled=${wifi?.isWifiEnabled}")
                val connectivity = app.getSystemService(ConnectivityManager::class.java)
                val active = connectivity?.activeNetwork
                connectivity?.allNetworks?.take(12)?.forEachIndexed { index, network ->
                    val caps = connectivity.getNetworkCapabilities(network)
                    val link = connectivity.getLinkProperties(network)
                    add("networkIndex=$index active=${network == active} iface=${link?.interfaceName} " +
                        "wifi=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)} " +
                        "cellular=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)} " +
                        "internetCapability=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
                        "validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
                        "dnsCount=${link?.dnsServers?.size} routes=${link?.routes?.size} " +
                        "defaultRoute=${link?.routes?.any { it.isDefaultRoute }}")
                    add("networkIndex=$index capabilities=$caps")
                    add("networkIndex=$index linkProperties=$link")
                }
            }.onFailure { add("networkDetailsUnavailable=${it.javaClass.simpleName}") }
            runCatching {
                val wifi = app.getSystemService(WifiManager::class.java)
                @Suppress("DEPRECATION") val station = wifi?.connectionInfo
                add("stationWifi=$station (receiver upstream, not hotspot radio)")
                add("stationFrequencyMHz=${station?.frequency} linkSpeedMbps=${station?.linkSpeed} rssiDbm=${station?.rssi} networkId=${station?.networkId}")
                @Suppress("DEPRECATION") val dhcp = wifi?.dhcpInfo
                add("stationDhcp=$dhcp (receiver upstream, not iPhone lease)")
                val hotspot = CarPlayBackgroundSession.hotspotReady
                add("sessionHotspot=${hotspot?.let { "backend=${it.backend} ssid=${it.ssid} band=${it.band} channel=${it.channel} bssid=${it.bssid} address=${it.address}" } ?: "not_observed"}")
                val config = wifi?.javaClass?.getMethod("getWifiApConfiguration")?.invoke(wifi) as? android.net.wifi.WifiConfiguration
                add("legacyHotspotConfig=${config?.let { "ssid=${it.SSID} channel=${runCatching { it.javaClass.getField("apChannel").get(it) }.getOrNull()} band=${runCatching { it.javaClass.getField("apBand").get(it) }.getOrNull()}" } ?: "unavailable"}")
            }.onFailure { add("wifiRadioDetailsUnavailable=${it.javaClass.simpleName}: ${it.message}") }
            runCatching {
                add("arpTable=" + File("/proc/net/arp").readText().take(8192))
            }.onFailure { add("arpTableUnavailable=${it.javaClass.simpleName}: ${it.message}") }
            runCatching {
                Collections.list(NetworkInterface.getNetworkInterfaces()).take(16).forEach { network ->
                    val addresses = Collections.list(network.inetAddresses)
                    add("iface=${network.name} up=${network.isUp} loopback=${network.isLoopback} " +
                        "ipv4Count=${addresses.count { it is Inet4Address }} ipv6Count=${addresses.count { it is Inet6Address }} " +
                        "addresses=${network.interfaceAddresses} mtu=${network.mtu} multicast=${network.supportsMulticast()} " +
                        "mac=${runCatching { network.hardwareAddress?.joinToString(":") { "%02x".format(it.toInt() and 0xff) } }.getOrNull()}")
                }
            }.onFailure { add("interfacesUnavailable=${it.javaClass.simpleName}") }
        }
        private fun client(): LocalAdb? = synchronized(clientLock) {
            if (closed.get()) null else adb ?: LocalAdb(AdbKeys.load(app)).also { adb = it }
        }
        override fun connect() = client()?.connect(mayAsk = false) ?: LocalAdb.Access.UNREACHABLE
        override fun shell(command: String, timeoutMillis: Int): String? {
            val (current, generation) = synchronized(clientLock) {
                val current = client() ?: return null
                current to ++commandGeneration
            }
            val timedOut = AtomicBoolean()
            val timeout = commandTimer.schedule({
                synchronized(clientLock) {
                    if (commandGeneration == generation && adb === current) {
                        // soTimeout alone only limits idle reads. Close even a continuously busy stream.
                        timedOut.set(true)
                        current.cancelPendingOperations()
                        adb = null // A later command may reconnect without requesting authorization.
                    }
                }
            }, timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            return try {
                val result = current.shellForDiagnostics(command, timeoutMillis)
                if (timedOut.get()) throw SocketTimeoutException("command wall-clock deadline reached")
                result
            }
            finally {
                synchronized(clientLock) { if (commandGeneration == generation) commandGeneration++ }
                timeout.cancel(false)
            }
        }
        override fun close() = synchronized(clientLock) {
            closed.set(true); commandGeneration++; adb?.cancelPendingOperations(); commandTimer.shutdownNow(); Unit
        }
    }
}
