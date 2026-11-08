package com.shilapi.xcertplay.network

import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class WirelessStartupDiagnosticsTest {
    @Test fun slowSnapshotReportsCaptureWindowRatherThanPretendingItIsInstant() {
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        val captured = CountDownLatch(1)
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val diagnostics = WirelessStartupDiagnostics(
            sample = { clock.set(2_000_000_000); "interfaceState=up" },
            log = { logs.add(it); if (it.contains("interfaceState=up")) captured.countDown() },
            nowNs = clock::get,
        )
        try {
            diagnostics.start()
            assertTrue(captured.await(2, TimeUnit.SECONDS))
            assertTrue(logs.toString(), logs.any { it.contains("captureDurationMs=2000") && it.contains("captureStartAtMs=0") })
        } finally { diagnostics.close() }
    }

    @Test fun sessionEstablishmentDoesNotClaimPictureReadyAndFirstFrameTimeIsStable() {
        var clock = 0L
        val logs = mutableListOf<String>()
        val diagnostics = WirelessStartupDiagnostics({ "" }, logs::add, nowNs = { clock })
        clock = 2_000_000_000L
        diagnostics.sessionActive()
        assertTrue(diagnostics.summary().contains("waitingFor=first_rendered_frame"))
        clock = 3_000_000_000L
        diagnostics.frameRendered()
        clock = 9_000_000_000L
        diagnostics.frameRendered()
        val summary = diagnostics.summary()
        assertTrue(summary, summary.contains("sessionActiveAtMs=2000"))
        assertTrue(summary, summary.contains("firstFrameAtMs=3000"))
        assertTrue(summary, summary.contains("firstFrameAfterSessionMs=1000"))
        assertTrue(summary, summary.contains("waitingFor=none"))
        diagnostics.close()
        assertEquals(1, logs.count { it.contains("observation=ended") })
    }

    @Test fun environmentSnapshotDoesNotSilentlyLoseLinesAfterKernelCounters() {
        val sampled = CountDownLatch(8)
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val diagnostics = WirelessStartupDiagnostics(
            sample = { (1..8).joinToString("\n") { "evidenceLine=$it" } },
            log = { logs.add(it); if (it.startsWith("wireless snapshot ")) sampled.countDown() },
        )
        try {
            diagnostics.start()
            assertTrue("Environment evidence after the fourth line must be retained", sampled.await(2, TimeUnit.SECONDS))
            assertTrue(logs.any { it.endsWith("evidenceLine=8") })
        } finally { diagnostics.close() }
    }

    @Test fun authenticationAndWifiHandoffHaveIndependentFirstEventTimings() {
        var clock = 0L
        val diagnostics = WirelessStartupDiagnostics({ "" }, {}, nowNs = { clock })
        clock = 100_000_000L
        diagnostics.controlProgress("iap2 authentication accepted")
        clock = 300_000_000L
        diagnostics.controlProgress("iap2 tx=0x5703 accessory-wifi-configuration")
        clock = 400_000_000L
        diagnostics.controlProgress("iap2 tx=0x4301 carplay-start-session")
        clock = 1_300_000_000L
        diagnostics.controlProgress("iap2 tx=0x5703 post-transport accessory-wifi-configuration")
        diagnostics.connectionAccepted()
        val summary = diagnostics.summary()
        assertTrue(summary, summary.contains("authenticatedAtMs=100"))
        assertTrue(summary, summary.contains("firstWifiConfigAtMs=300"))
        assertTrue(summary, summary.contains("firstTcpAfterWifiConfigMs=1000"))
        assertTrue(summary, summary.contains("firstTcpAfterStartMs=900"))
        diagnostics.close()
    }

    @Test fun observerExportsSummaryAndAllFourSnapshotLinesSeparately() {
        val sampled = CountDownLatch(4)
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val diagnostics = WirelessStartupDiagnostics(
            sample = { "interfaceState=up\nreceiveCounters ifaceRx=sampled lastCounter=1\n" +
                "receiveCounters udp4=sampled lastCounter=2\nreceiveCounters udp6=sampled lastCounter=3" },
            log = {
                logs.add(it)
                if (it.startsWith("wireless snapshot ")) sampled.countDown()
            },
        )
        diagnostics.start()
        try {
            assertTrue(sampled.await(2, TimeUnit.SECONDS))
            assertTrue(logs.any { it.startsWith("wireless startup") && !it.contains("interfaceState") })
            assertTrue(logs.any { it.endsWith("lastCounter=3") })
            assertTrue(logs.all { !it.contains('\n') && !it.contains('\r') })
        } finally { diagnostics.close() }
    }

    @Test fun startupTimersMeasureTheFirstRequestAndFirstAcceptedTcpWithoutChangingStages() {
        var clock = 1_000_000_000L
        val diagnostics = WirelessStartupDiagnostics({ "" }, {}, nowNs = { clock })
        assertTrue(diagnostics.summary().contains("startRequestAgeMs=none firstTcpAfterStartMs=none"))
        diagnostics.controlProgress("iap2 tx=0x4301 carplay-start-session")
        clock += 20_000_000_000L
        // A repeated request must not hide how long the original handoff has been stalled.
        diagnostics.controlProgress("iap2 tx=0x4301 carplay-start-session")
        assertTrue(diagnostics.summary().contains("startRequestAgeMs=20000 firstTcpAfterStartMs=none"))
        diagnostics.connectionAccepted()
        clock += 2_000_000_000L
        diagnostics.connectionAccepted()
        assertTrue(diagnostics.summary().contains("startRequestAgeMs=22000 firstTcpAfterStartMs=20000"))
        diagnostics.close()
    }

    @Test fun closedObserverCannotBeStartedAndCannotFailTeardown() {
        var samples = 0
        val diagnostics = WirelessStartupDiagnostics({ samples++; "" }, { throw IllegalStateException("observer failed") })
        diagnostics.close()
        diagnostics.start()
        assertEquals(0, samples)
    }

    @Test fun distinguishesSilentWifiHandoffFromAcceptedTcpAndActiveSession() {
        val diagnostics = WirelessStartupDiagnostics({ "" }, {})
        assertTrue(diagnostics.summary().contains("waitingFor=Bluetooth_iAP2_authentication"))
        diagnostics.controlProgress("iap2 authentication accepted")
        assertTrue(diagnostics.summary().contains("waitingFor=WiFi_configuration_or_start_request"))
        diagnostics.controlProgress("iap2 tx=0x5703 accessory-wifi-configuration")
        diagnostics.controlProgress("iap2 tx=0x4301 carplay-start-session")
        diagnostics.controlProgress("iap2 tx=0x5703 post-transport accessory-wifi-configuration")
        assertTrue(diagnostics.summary().contains("wifiConfigs=2 startRequests=1 tcpAccepted=0"))
        assertTrue(diagnostics.summary().contains("waitingFor=WiFi_discovery_or_AirPlay_TCP"))
        diagnostics.connectionAccepted()
        assertTrue(diagnostics.summary().contains("waitingFor=AirPlay_protocol"))
        diagnostics.sessionActive()
        assertTrue(diagnostics.summary().contains("sessionActive=true waitingFor=first_rendered_frame"))
        diagnostics.close()
    }

    @Test fun observerFailureAndCloseDoNotAffectTheConnectionOrLeakErrorDetails() {
        val sampled = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val diagnostics = WirelessStartupDiagnostics(
            sample = { throw SecurityException("Jane's phone password=secret") },
            log = {
                logs.add(it)
                if (it.contains("sampling=unavailable")) sampled.countDown()
                if (it.contains("observation=ended")) ended.countDown()
            },
        )
        diagnostics.start()
        try {
            assertTrue(sampled.await(2, TimeUnit.SECONDS))
            diagnostics.close()
            diagnostics.close()
            assertTrue(ended.await(2, TimeUnit.SECONDS))
            assertEquals(1, logs.count { it.contains("observation=ended") })
            assertTrue(logs.any { it.contains("failureClass=SecurityException") })
            assertFalse(logs.any { it.contains("Jane") || it.contains("secret") })
        } finally { diagnostics.close() }
    }

    @Test fun addressInventoryCountsAlternativeFamiliesWithoutExportingAddresses() {
        val v6 = InetAddress.getByName("fe80::1234")
        val scoped = Inet6Address.getByAddress(null, v6.address, 7)
        val result = WirelessInterfaceDiagnostics.addressSummary(listOf(
            InetAddress.getByName("192.168.49.1"), scoped,
            InetAddress.getByName("127.0.0.1"), InetAddress.getByName("0.0.0.0"),
            InetAddress.getByName("224.0.0.251"), InetAddress.getByName("169.254.1.2"),
        ))
        assertEquals("ipv4Usable=1 ipv6LinkLocal=1 ipv6Scoped=1", result)
        assertFalse(result.contains("192.168") || result.contains("fe80"))
    }
}
