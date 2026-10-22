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
    private const val MAX_CAPTURE_CHARS = 48 * 1024
    private const val MAX_SECTION_CHARS = 4096
    private const val ADB_BUDGET_MILLIS = 20_000L
    private const val DIRECTORY = "hotspot-network-diagnostics"
    private val files = listOf("latest.txt", "previous-1.txt", "previous-2.txt")
    private val extraSecret = Regex("(?i)(pre.?shared.?key|\\bpsk\\b|wep.?key|imsi|imei|subscriber|authorization|cookie|serialnumber|identity|username|user_name|fqdn|\\bextra(?:info)?\\s*[:=])")

    // Fixed read-only commands: no user input, su, configuration writes, service restart, or packet capture.
    private val commands = listOf(
        "privileges" to "id; getenforce; getprop ro.debuggable; getprop ro.secure; getprop service.adb.tcp.port",
        "hotspot-processes" to "ps -A | grep -E 'hostapd|wificond|netd|dnsmasq|wifi@|wifi.hostapd'",
        "hotspot-file-metadata" to "ls -ld /system/bin/hostapd /vendor/bin/hostapd /system/bin/hostapd_cli /data/misc/wifi /data/vendor/wifi /data/misc/wifi/hostapd.conf /data/vendor/wifi/hostapd/hostapd.conf",
        "hotspot-config-selected-fields" to "grep -E '^(interface|driver|hw_mode|channel|interworking|internet|access_network_type)=' /data/misc/wifi/hostapd.conf /data/vendor/wifi/hostapd/hostapd.conf; if grep -q '^vendor_elements=' /data/misc/wifi/hostapd.conf /data/vendor/wifi/hostapd/hostapd.conf; then echo configuredVendorElements=present; else echo configuredVendorElements=unavailable_or_absent; fi; if grep -qE '^vendor_elements=.*00a04000' /data/misc/wifi/hostapd.conf /data/vendor/wifi/hostapd/hostapd.conf; then echo configuredAppleDeviceElement=present; else echo configuredAppleDeviceElement=unavailable_or_absent; fi",
        "addresses" to "ip -o addr show",
        "routes" to "ip route show table all; ip -6 route show table all; ip rule show",
        "neighbours" to "ip neigh show",
        "recent-network-log" to "logcat -d -t 300 -v threadtime -s SoftApManager WifiStateMachine WificondControl wificond hostapd dnsmasq Tethering ConnectivityService",
        "tethering" to "dumpsys tethering",
        "connectivity" to "dumpsys connectivity",
        "wifi" to "dumpsys wifi",
    )

    fun collect(context: Context, probe: Probe = AndroidProbe(context.applicationContext)): Outcome {
        if (!running.compareAndSet(false, true)) { runCatching { probe.close() }; return Outcome.BUSY }
        val timer = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "hotspot-diagnostic-deadline").apply { isDaemon = true }
        }
        val text = StringBuilder()
        fun section(title: String, raw: String) {
            if (text.length >= MAX_CAPTURE_CHARS) return
            val tail = title == "recent-network-log"
            // Remote byte-tail may start inside a sensitive line, after its identifying key.
            // Discard its first line unconditionally: LocalAdb trims whitespace, so byte length
            // cannot reliably tell us whether the remote limit was hit.
            val source = if (tail) raw.lineSequence().drop(1) else raw.lineSequence()
            val safeLines = source.mapNotNull(::sanitize).toList()
            val selected = if (tail) safeLines.takeLast(200) else safeLines.take(200)
            val joined = selected.joinToString("\n")
            val remaining = (MAX_CAPTURE_CHARS - text.length - title.length - 100).coerceAtLeast(0)
            val limit = remaining.coerceAtMost(MAX_SECTION_CHARS)
            val content = if (tail) joined.takeLast(limit) else joined.take(limit)
            text.appendLine("--- $title ---")
            if (tail && raw.isNotBlank()) text.appendLine("[first available log line omitted: may be partial after byte truncation]")
            if (safeLines.size > 200 || joined.length > limit || raw.toByteArray(Charsets.UTF_8).size >= 32768)
                text.appendLine(if (tail) "[truncated; keeping newest available events]" else "[truncated; showing available prefix]")
            text.appendLine(content)
        }
        return try {
            section("capture", "capturedAtEpochMs=${System.currentTimeMillis()} elapsedRealtimeMs=${SystemClock.elapsedRealtime()}\n" +
                "Android API=${Build.VERSION.SDK_INT} board=${Build.BOARD} hardware=${Build.HARDWARE}\n" +
                "sessionActive=${CarPlayBackgroundSession.active} sessionPresent=${CarPlayBackgroundSession.hasSession()}\n" +
                "Read-only receiver snapshot; iPhone internet, routes and actual beacon IEs are NOT measured.")
            section("app-network", runCatching { probe.basic().joinToString("\n") }
                .getOrElse { "unavailable failureClass=${it.javaClass.simpleName}" })
            val deadline = SystemClock.elapsedRealtime() + ADB_BUDGET_MILLIS
            val cancellation = timer.schedule({ runCatching { probe.close() } }, ADB_BUDGET_MILLIS, TimeUnit.MILLISECONDS)
            val access = runCatching { probe.connect() }.getOrDefault(LocalAdb.Access.UNREACHABLE)
            section("local-adb", "access=$access approvalRequested=false")
            if (access == LocalAdb.Access.READY) {
                for ((name, command) in commands) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) { section("local-adb", "collection deadline reached; remaining sections not sampled"); break }
                    val result = runCatching {
                        probe.shell("{ $command; } 2>&1 | ${if (name == "recent-network-log") "tail" else "head"} -c 32768", remaining.coerceAtMost(2_000).toInt())
                    }.getOrNull()
                    section(name, result?.takeIf { it.isNotBlank() } ?: "unavailable, empty, or timed out; not evidence of absent capability")
                }
            } else section("local-adb", "System details not sampled. Basic snapshot retained; no permission or ADB configuration changed.")
            cancellation.cancel(false)
            section("interpretation", "Selected configuration fields are not proof of actual broadcast contents.\n" +
                "Config probes use known candidate paths only; missing files or permission denial do not prove unsupported hardware.\n" +
                "Compare captures while Wi-Fi-only, CarPlay connecting, and CarPlay running; phone internet tests must be recorded separately.")
            save(context, text.toString())
            Outcome.SAVED
        } catch (_: Exception) { Outcome.FAILED }
        finally { runCatching { probe.close() }; timer.shutdownNow(); running.set(false) }
    }

    private fun sanitize(line: String): String? =
        if (extraSecret.containsMatchIn(line)) null else DiagnosticRedactor.redact(line.replace(Regex("[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f]"), "?"))

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
                    ?.lineSequence()?.forEach { sanitize(it)?.let(::appendLine) }
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
                }
            }.onFailure { add("networkDetailsUnavailable=${it.javaClass.simpleName}") }
            runCatching {
                Collections.list(NetworkInterface.getNetworkInterfaces()).take(16).forEach { network ->
                    val addresses = Collections.list(network.inetAddresses)
                    add("iface=${network.name} up=${network.isUp} loopback=${network.isLoopback} " +
                        "ipv4Count=${addresses.count { it is Inet4Address }} ipv6Count=${addresses.count { it is Inet6Address }}")
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
            val timeout = commandTimer.schedule({
                synchronized(clientLock) {
                    if (commandGeneration == generation && adb === current) {
                        // soTimeout alone only limits idle reads. Close even a continuously busy stream.
                        current.cancelPendingOperations()
                        adb = null // A later command may reconnect without requesting authorization.
                    }
                }
            }, timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            return try { current.shell(command, timeoutMillis) }
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
