package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.adb.LocalAdb
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.mockito.Mockito.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class HotspotNetworkDiagnosticsTest {
    @Test fun savedHotspotSnapshotSurvivesUntilDiagnosticExport() {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.filesDir, "hotspot-network-diagnostics").apply { mkdirs() }
        try {
            File(folder, "latest.txt").writeText("captureMarker=hotspot-snapshot\npassword=not-for-export\n")
            val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
            val report = activity.javaClass.getDeclaredMethod("diagnosticReportText", Context::class.java)
                .apply { isAccessible = true }.invoke(activity, context) as String
            assertTrue("Saved network evidence must be in the exported diagnostic report", report.contains("captureMarker=hotspot-snapshot"))
            assertTrue(report.contains("password=not-for-export"))
        } finally { folder.deleteRecursively() }
    }
    private class Probe(private val access: LocalAdb.Access, private val marker: String = "sample", private val log: String? = null) : HotspotNetworkDiagnostics.Probe {
        val commands = mutableListOf<String>()
        var closed = false
        override fun basic() = listOf("captureMarker=$marker", "password=secret-one", "preSharedKey=secret-two", "peer=192.168.43.2")
        override fun connect() = access
        override fun shell(command: String, timeoutMillis: Int): String? {
            assertTrue(timeoutMillis in 1..2000)
            commands += command
            if (log != null && command.contains("logcat -d")) return log
            return if (command.contains("dumpsys wifi")) null else "readOnlyEvidence=true\npsk=secret-three"
        }
        override fun close() { closed = true }
    }

    @Test fun unavailableAdbStillSavesBasicEvidenceWithoutExecutingCommands() {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.filesDir, "hotspot-network-diagnostics")
        try {
            val probe = Probe(LocalAdb.Access.NOT_APPROVED)
            assertEquals(HotspotNetworkDiagnostics.Outcome.SAVED, HotspotNetworkDiagnostics.collect(context, probe))
            assertTrue(probe.commands.isEmpty())
            assertTrue(probe.closed)
            val report = HotspotNetworkDiagnostics.report(context)
            assertTrue(report.contains("captureMarker=sample"))
            assertTrue(report.contains("access=NOT_APPROVED"))
            assertTrue(report.contains("approvalRequested=false"))
            assertTrue(report.contains("password=secret-one"))
            assertTrue(report.contains("preSharedKey=secret-two"))
            assertTrue(report.contains("192.168.43.2"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun authorizedCaptureKeepsPartialResultsWhenASystemCommandIsUnavailable() {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.filesDir, "hotspot-network-diagnostics")
        try {
            val probe = Probe(LocalAdb.Access.READY)
            assertEquals(HotspotNetworkDiagnostics.Outcome.SAVED, HotspotNetworkDiagnostics.collect(context, probe))
            val report = HotspotNetworkDiagnostics.report(context)
            assertTrue(report.contains("readOnlyEvidence=true"))
            assertTrue(report.contains("status=link_failed"))
            assertTrue(report.contains("status=incomplete_output exitCode=unknown"))
            assertTrue(probe.commands.any { it.contains("logcat -d") })
            assertTrue(probe.commands.any { it.contains("ip route show") })
            assertTrue(report.contains("psk=secret-three"))
            assertTrue(probe.closed)
        } finally { folder.deleteRecursively() }
    }

    @Test fun onlyTheLatestThreeCapturesAreRetainedAndExportDoesNotRecapture() {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.filesDir, "hotspot-network-diagnostics")
        try {
            for (marker in listOf("discarded-oldest", "second", "third", "newest"))
                assertEquals(HotspotNetworkDiagnostics.Outcome.SAVED,
                    HotspotNetworkDiagnostics.collect(context, Probe(LocalAdb.Access.UNREACHABLE, marker)))
            val report = HotspotNetworkDiagnostics.report(context)
            assertFalse(report.contains("discarded-oldest"))
            assertTrue(report.contains("captureMarker=second"))
            assertTrue(report.contains("captureMarker=third"))
            assertTrue(report.contains("captureMarker=newest"))
            assertEquals(3, folder.listFiles()!!.count { it.name.endsWith(".txt") })
        } finally { folder.deleteRecursively() }
    }

    @Test fun concurrentCaptureIsRejectedWithoutReplacingSavedEvidence() {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.filesDir, "hotspot-network-diagnostics")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val job = thread {
            HotspotNetworkDiagnostics.collect(context, object : HotspotNetworkDiagnostics.Probe {
                override fun basic(): List<String> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return listOf("captureMarker=first") }
                override fun connect() = LocalAdb.Access.UNREACHABLE
                override fun shell(command: String, timeoutMillis: Int): String? = error("No ADB")
                override fun close() {}
            })
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val other = Probe(LocalAdb.Access.READY, "must-not-overwrite")
            assertEquals(HotspotNetworkDiagnostics.Outcome.BUSY, HotspotNetworkDiagnostics.collect(context, other))
            assertTrue(other.commands.isEmpty())
            release.countDown(); job.join(5_000)
            assertFalse(job.isAlive)
            assertFalse(HotspotNetworkDiagnostics.report(context).contains("must-not-overwrite"))
        } finally { release.countDown(); job.join(5_000); folder.deleteRecursively() }
    }

    @Test fun systemNetworkNamesAndDeviceInformationRemainAvailableForDiagnosis() {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.filesDir, "hotspot-network-diagnostics")
        try {
            val probe = object : HotspotNetworkDiagnostics.Probe {
                override fun basic() = listOf("NetworkInfo[type: WIFI[], extra: Alice-iPhone, state: CONNECTED]", "anonymous_identity=private-account")
                override fun connect() = LocalAdb.Access.UNREACHABLE
                override fun shell(command: String, timeoutMillis: Int): String? = error("No ADB")
                override fun close() {}
            }
            assertEquals(HotspotNetworkDiagnostics.Outcome.SAVED, HotspotNetworkDiagnostics.collect(context, probe))
            val saved = File(folder, "latest.txt").readText()
            assertTrue(saved.contains("Alice-iPhone"))
            assertTrue(saved.contains("private-account"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun longSystemLogKeepsTheNewestEventAndLabelsTruncation() {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.filesDir, "hotspot-network-diagnostics")
        try {
            val probe = object : HotspotNetworkDiagnostics.Probe {
                override fun basic() = emptyList<String>()
                override fun connect() = LocalAdb.Access.READY
                override fun shell(command: String, timeoutMillis: Int) = if (command.contains("logcat -d"))
                    (1..1500).joinToString("\n") { "old-event-$it" } + "\nlatest-event-marker" else "ok"
                override fun close() {}
            }
            assertEquals(HotspotNetworkDiagnostics.Outcome.SAVED, HotspotNetworkDiagnostics.collect(context, probe))
            val saved = HotspotNetworkDiagnostics.report(context)
            assertTrue(saved.contains("latest-event-marker"))
            assertTrue("Log command must survive newest-event truncation", saved.contains("command=logcat -d -t 1000"))
            assertTrue(saved.contains("truncated"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun continuouslyBusyShellIsCancelledAtTheCommandDeadline() {
        val context = RuntimeEnvironment.getApplication()
        val type = HotspotNetworkDiagnostics::class.java.declaredClasses.single { it.simpleName == "AndroidProbe" }
        val probe = type.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
            .newInstance(context) as HotspotNetworkDiagnostics.Probe
        val client = mock(LocalAdb::class.java)
        val released = CountDownLatch(1)
        doAnswer { released.countDown(); null }.`when`(client).cancelPendingOperations()
        `when`(client.shellForDiagnostics(anyString(), anyInt())).thenAnswer {
            if (released.await(3, TimeUnit.SECONDS)) null else "busy shell exceeded its deadline"
        }
        type.getDeclaredField("adb").apply { isAccessible = true }.set(probe, client)
        try {
            val started = System.nanoTime()
            val result = try { probe.shell("read-only-test", 100) } catch (_: java.net.SocketTimeoutException) { null }
            assertNull("A busy shell must be cancelled, not wait for an idle timeout", result)
            assertTrue("Command exceeded its wall-clock budget", System.nanoTime() - started < 1_500_000_000L)
        } finally { released.countDown(); probe.close() }
    }

    @Test fun byteClippedLogRetainsFirstAvailableLineAndOriginalNetworkIdentifiers() {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.filesDir, "hotspot-network-diagnostics")
        try {
            val probe = Probe(LocalAdb.Access.READY, log = "unlabelled-private-value\nclient=AA:BB:CC:DD:EE:FF ip=192.168.43.2 ssid=Passenger WiFi\ncomplete-network-event")
            assertEquals(HotspotNetworkDiagnostics.Outcome.SAVED, HotspotNetworkDiagnostics.collect(context, probe))
            val saved = File(folder, "latest.txt").readText()
            assertTrue(saved.contains("unlabelled-private-value"))
            assertTrue(saved.contains("client=AA:BB:CC:DD:EE:FF ip=192.168.43.2 ssid=Passenger WiFi"))
            assertTrue(saved.contains("complete-network-event"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun captureReportsCommandExitFailureTimingAndUnavailableSections() {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.filesDir, "hotspot-network-diagnostics")
        try {
            val probe = object : HotspotNetworkDiagnostics.Probe {
                override fun basic() = listOf("iface=ap0 address=192.168.43.1/24", "ssid=BuickPlay", "client=AA:BB:CC:DD:EE:FF")
                override fun connect() = LocalAdb.Access.READY
                override fun shell(command: String, timeoutMillis: Int): String? = when {
                    command.contains("ip route show") -> "default via 192.168.43.1 dev ap0\n__BUICK_DIAG_EXIT__=0"
                    command.contains("ip neigh show") -> "permission denied\n__BUICK_DIAG_EXIT__=1"
                    command.contains("dumpsys wifi") -> throw java.net.SocketTimeoutException("wall-clock deadline")
                    else -> "evidence\n__BUICK_DIAG_EXIT__=0"
                }
                override fun close() {}
            }
            assertEquals(HotspotNetworkDiagnostics.Outcome.SAVED, HotspotNetworkDiagnostics.collect(context, probe))
            val report = HotspotNetworkDiagnostics.report(context)
            assertTrue(report.contains("default via 192.168.43.1 dev ap0"))
            assertTrue(report.contains("ssid=BuickPlay"))
            assertTrue(report.contains("client=AA:BB:CC:DD:EE:FF"))
            assertTrue(report.contains("status=exit_nonzero exitCode=1"))
            assertTrue(report.contains("status=timed_out"))
            assertTrue(Regex("durationMs=\\d+").containsMatchIn(report))
            assertTrue(report.contains("captureCompletedAt="))
            assertTrue(report.contains("commandSummary"))
            assertTrue(report.contains("transport="))
        } finally { folder.deleteRecursively() }
    }


    @Test fun socketReadTimeoutSurvivesTheAdbLayerBeforeCancellationTimerFires() {
        val context = RuntimeEnvironment.getApplication()
        val type = HotspotNetworkDiagnostics::class.java.declaredClasses.single { it.simpleName == "AndroidProbe" }
        val probe = type.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
            .newInstance(context) as HotspotNetworkDiagnostics.Probe
        val client = LocalAdb(com.shilapi.xcertplay.adb.AdbKeys.load(context))
        // Inject only the socket I/O boundary; execute the real LocalAdb shell/catch path.
        val socket = java.net.Socket()
        val input = object : java.io.InputStream() {
            override fun read(): Int = throw java.net.SocketTimeoutException("synthetic idle read timeout")
        }
        for ((name, value) in listOf("socket" to socket, "input" to input, "output" to java.io.ByteArrayOutputStream()))
            LocalAdb::class.java.getDeclaredField(name).apply { isAccessible = true }.set(client, value)
        type.getDeclaredField("adb").apply { isAccessible = true }.set(probe, client)
        try {
            val failure = runCatching { probe.shell("read-only-test", 2_000) }.exceptionOrNull()
            assertTrue("Socket timeout must not become a null/link failure: $failure", failure is java.net.SocketTimeoutException)
            assertEquals("synthetic idle read timeout", failure?.message)
        } finally { probe.close(); socket.close() }
    }

    @Test fun totalCaptureBudgetIsReportedAsTimeoutInsteadOfLinkFailure() {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.filesDir, "hotspot-network-diagnostics")
        try {
            val probe = object : HotspotNetworkDiagnostics.Probe {
                override fun basic() = emptyList<String>()
                override fun connect() = LocalAdb.Access.READY
                override fun shell(command: String, timeoutMillis: Int): String? {
                    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(21))
                    return null // Closing the ADB socket at the capture deadline releases its read.
                }
                override fun close() {}
            }
            assertEquals(HotspotNetworkDiagnostics.Outcome.SAVED, HotspotNetworkDiagnostics.collect(context, probe))
            val report = HotspotNetworkDiagnostics.report(context)
            assertTrue(report, report.contains("status=timed_out reason=capture_deadline"))
            assertFalse(report.contains("status=link_failed"))
            assertTrue(report.contains("status=not_sampled reason=capture_deadline"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun captureAndExportRetainCredentialAndPayloadTextVerbatim() {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.filesDir, "hotspot-network-diagnostics")
        val lines = listOf("password=synthetic", "wpa_passphrase=synthetic", "wpa_psk=synthetic",
            "mPassphrase=synthetic", "{\"password\":\"synthetic\"}", "TRACE payload=synthetic", "PHONE body=synthetic")
        try {
            val probe = Probe(LocalAdb.Access.READY, log = lines.joinToString("\n"))
            assertEquals(HotspotNetworkDiagnostics.Outcome.SAVED, HotspotNetworkDiagnostics.collect(context, probe))
            val saved = File(folder, "latest.txt").readText()
            val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
            val report = activity.javaClass.getDeclaredMethod("diagnosticReportText", Context::class.java)
                .apply { isAccessible = true }.invoke(activity, context) as String
            for (line in lines) {
                assertTrue("Missing stored line: $line", saved.contains(line))
                assertTrue("Missing exported line: $line", report.contains(line))
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun diagnosticReportIdentifiesBuildAndGenerationTime() {
        val context = RuntimeEnvironment.getApplication()
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        val report = activity.javaClass.getDeclaredMethod("diagnosticReportText", Context::class.java)
            .apply { isAccessible = true }.invoke(activity, context) as String
        assertTrue(report.contains("versionCode="))
        assertTrue(report.contains("sourceRevision="))
        assertTrue(report.contains("reportGeneratedAt="))
        assertTrue(report.contains("timeZone="))
    }

}
