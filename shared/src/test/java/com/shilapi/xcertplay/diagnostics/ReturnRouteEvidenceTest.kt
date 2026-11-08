package com.shilapi.xcertplay.diagnostics

import org.junit.Assert.*
import org.junit.Test

class ReturnRouteEvidenceTest {
    @Test fun buildsBothFamiliesWithSourceInterfaceAndExplicitUidAndMarkContexts() {
        val queries = ReturnRouteEvidence.queries(
            "192.168.43.2 dev wlan0 lladdr aa:bb:cc:dd:ee:ff REACHABLE",
            "fe80::2 dev wlan0 lladdr aa:bb:cc:dd:ee:ff STALE",
            "3: wlan0 inet 192.168.43.1/24 scope global wlan0\n3: wlan0 inet6 fe80::1/64 scope link",
            "100: from all fwmark 0x10063/0x1ffff lookup 100\n", "", 10123)
        val lines = queries.map { it.joinToString(" ") }
        assertTrue(lines.contains("ip -4 route get 192.168.43.2"))
        assertTrue(lines.contains("ip -6 route get fe80::2 oif wlan0"))
        assertTrue(lines.any { it.contains("from 192.168.43.1 oif wlan0 mark 0 uid 10123") })
        assertTrue(lines.any { it.contains("from fe80::1 oif wlan0 mark 0 uid 10123") })
        assertTrue(lines.any { it.contains("mark 0x10063") })
    }

    @Test fun rejectsUntrustedAddressesInterfacesAndMarksAndBoundsQueries() {
        val queries = ReturnRouteEvidence.queries(
            "1.2.3.4;reboot dev ap0 REACHABLE\n1.2.3.4 dev ap0;reboot REACHABLE\n" +
                (1..100).joinToString("\n") { "192.168.43.$it dev ap0 REACHABLE" }, "",
            "3: ap0 inet 192.168.43.254/24 scope global", "fwmark 0x1;reboot lookup 1", "", 10123)
        assertTrue(queries.isNotEmpty())
        assertTrue(queries.size <= ReturnRouteEvidence.MAX_QUERIES)
        assertFalse(queries.flatMap { it.toList() }.any { it.contains(';') })
    }

    @Test fun prioritizesHotspotAndUsesConfiguredIngressMarksAsHypotheses() {
        val marks = ReturnRouteEvidence.ingressMarks("-A PREROUTING -i ap0 -j MARK --set-xmark 0x30063/0xffefffff\n-A PREROUTING -j MARK --set-mark 0x1;reboot")
        assertTrue(marks.contains("fwmark 0x30063"))
        assertFalse(marks.contains("reboot"))
        val peers = (1..10).joinToString("\n") { "10.0.0.$it dev eth0 REACHABLE" }
        val queries = ReturnRouteEvidence.queries(peers + "\n192.168.43.2 dev ap0 REACHABLE", "", "", marks, "", 10123)
        assertTrue(queries.first().contains("192.168.43.2"))
        assertTrue(queries.any { it.contains("0x30063") })
    }

    @Test fun reachableFallbackOutranksTwoOldStaleNeighbours() {
        val queries = ReturnRouteEvidence.queries(
            "192.168.43.2 dev ap0 STALE\n192.168.43.3 dev ap0 STALE\n192.168.43.9 dev ap0 REACHABLE",
            "", "", "", "", 10123)
        assertEquals("192.168.43.9", queries.first()[4])
    }

    @Test fun explicitCurrentPeerOutranksTwoOldPeersAndIsQueriedWithoutNeighbourEntry() {
        val plan = ReturnRouteEvidence.plan(
            "192.168.43.2 dev ap0 REACHABLE\n192.168.43.3 dev ap0 REACHABLE\n192.168.43.9 dev ap0 STALE",
            "", "", "", "", 10123, "ap0", listOf("192.168.43.9", "192.168.43.10"))
        assertEquals(listOf("192.168.43.9", "192.168.43.10"), plan.queries.map { it[4] }.distinct())
        assertTrue(plan.records.any { it.contains("192.168.43.2") && it.contains("peer_limit") })
        assertTrue(plan.records.any { it.contains("192.168.43.9 dev ap0 STALE") })
    }

    @Test fun unknownIdentityUsesCurrentInterfaceAndStateWithoutClaimingPhoneIdentity() {
        val plan = ReturnRouteEvidence.plan(
            "10.0.0.2 dev ap0 REACHABLE\n192.168.1.2 dev wlan0 STALE\n192.168.1.3 dev wlan0 PROBE",
            "fe80::2 dev wlan0 DELAY", "", "", "", 10123, "wlan0", emptyList())
        assertEquals("192.168.1.3", plan.queries.first()[4])
        assertTrue(plan.records.any { it.contains("target_identity=unknown") })
        assertTrue(plan.queries.any { it.toList() == listOf("ip", "-6", "route", "get", "fe80::2", "oif", "wlan0") })
        assertTrue(plan.records.any { it.contains("fallback") && it.contains("PROBE") })
    }

    @Test fun invalidHintsNeverReachArgvAndEveryExcludedCandidateHasARecord() {
        val raw = "192.168.43.1 dev ap0 FAILED\n192.168.43.2 dev ap0 STALE\n192.168.43.3 dev ap0 REACHABLE\n192.168.43.3 dev ap0 REACHABLE\nno-valid-neighbour"
        val plan = ReturnRouteEvidence.plan(raw, "", "", "", "", 10123, "ap0", listOf("phone.local", "1.2.3.4;reboot"))
        assertFalse(plan.queries.flatMap { it.toList() }.any { it.contains(';') || it.contains("phone.local") })
        assertTrue(plan.records.any { it.contains("target_identity=unknown") })
        for (line in raw.lines()) assertTrue(line, plan.records.any { it.contains(line) })
        assertTrue(plan.records.any { it.contains("reason=duplicate") })
        assertTrue(plan.records.any { it.contains("reason=peer_limit") })
        assertTrue(plan.records.any { it.contains("reason=invalid_neighbour") })
        assertTrue(plan.records.any { it.contains("selected=2") && it.contains("truncated=1") && it.contains("rejected=4") })
    }

    @Test fun invalidExplicitInterfaceDoesNotBecomeACommandArgument() {
        val plan = ReturnRouteEvidence.plan("", "", "", "", "", 10123, "ap0;reboot", listOf("192.168.43.9"))
        assertTrue(plan.queries.isEmpty())
        assertTrue(plan.records.any { it.contains("reason=invalid_target_interface") })
    }

    @Test fun unavailableCommandsDoNotTurnErrorsIntoRouteDestinations() {
        assertTrue(ReturnRouteEvidence.queries("ip: unknown command", "Permission denied", "", "", "", 10123).isEmpty())
    }
}
