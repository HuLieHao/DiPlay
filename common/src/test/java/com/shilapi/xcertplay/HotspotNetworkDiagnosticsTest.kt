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
            assertFalse(report.contains("not-for-export"))
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
            assertFalse(report.contains("secret-one"))
            assertFalse(report.contains("secret-two"))
            assertFalse(report.contains("192.168.43.2"))
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
            assertTrue(report.contains("unavailable, empty, or timed out"))
            assertTrue(probe.commands.any { it.contains("logcat -d") })
            assertTrue(probe.commands.any { it.contains("ip route show") })
            assertFalse(report.contains("secret-three"))
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

    @Test fun systemNetworkNamesAreNotSavedThroughNetworkInfoExtraFields() {
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
            assertFalse(saved.contains("Alice-iPhone"))
            assertFalse(saved.contains("private-account"))
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
                    (1..299).joinToString("\n") { "old-event-$it" } + "\nlatest-event-marker" else "ok"
                override fun close() {}
            }
            assertEquals(HotspotNetworkDiagnostics.Outcome.SAVED, HotspotNetworkDiagnostics.collect(context, probe))
            val saved = HotspotNetworkDiagnostics.report(context)
            assertTrue(saved.contains("latest-event-marker"))
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
        `when`(client.shell(anyString(), anyInt())).thenAnswer {
            if (released.await(3, TimeUnit.SECONDS)) null else "busy shell exceeded its deadline"
        }
        type.getDeclaredField("adb").apply { isAccessible = true }.set(probe, client)
        try {
            val started = System.nanoTime()
            val result = probe.shell("read-only-test", 100)
            assertNull("A busy shell must be cancelled, not wait for an idle timeout", result)
            assertTrue("Command exceeded its wall-clock budget", System.nanoTime() - started < 1_500_000_000L)
        } finally { released.countDown(); probe.close() }
    }

    @Test fun byteClippedLogCannotExportASensitiveValueWithoutItsFieldPrefix() {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.filesDir, "hotspot-network-diagnostics")
        try {
            val probe = Probe(LocalAdb.Access.READY, log = "unlabelled-private-value\ncomplete-network-event")
            assertEquals(HotspotNetworkDiagnostics.Outcome.SAVED, HotspotNetworkDiagnostics.collect(context, probe))
            val saved = File(folder, "latest.txt").readText()
            assertFalse(saved.contains("unlabelled-private-value"))
            assertTrue(saved.contains("complete-network-event"))
        } finally { folder.deleteRecursively() }
    }

}
