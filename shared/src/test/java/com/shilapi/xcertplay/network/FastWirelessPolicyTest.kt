package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class FastWirelessPolicyTest {
    @Test fun onlyVerifiedProfileEnablesPolicy() {
        assertTrue(WirelessStartupPolicy.fastWireless("FF-5000", "ac8227l", 27))
        assertFalse(WirelessStartupPolicy.fastWireless("FF_5000", "ac8227l", 27))
        assertFalse(WirelessStartupPolicy.fastWireless("other", "ac8227l", 27))
        assertFalse(WirelessStartupPolicy.fastWireless("FF-5000", "other", 27))
        assertFalse(WirelessStartupPolicy.fastWireless("FF-5000", "ac8227l", 28))
    }

    @Test fun ipv6SnapshotFallbackCannotEscapeProfileButValidIpv4FallbackCan() {
        val v6 = InetAddress.getByName("fe80::1")
        val v4 = InetAddress.getByName("192.168.43.1")
        assertNull(HotspotAddressPolicy.select(listOf(v6), "IPv6", true, v6))
        assertNull(HotspotAddressPolicy.select(emptyList(), "IPv4", true, InetAddress.getByName("127.0.0.1")))
        assertEquals(v4, HotspotAddressPolicy.select(listOf(v6), "IPv6", true, v4))
        assertEquals(v6, HotspotAddressPolicy.select(emptyList(), "IPv6", false, v6))
    }

    @Test fun profileIgnoresSavedIpv6AndFiltersEveryBonjourAddress() {
        val v4 = InetAddress.getByName("192.168.43.1")
        val v6 = InetAddress.getByName("fe80::1")
        val loop = InetAddress.getByName("127.0.0.1")
        assertEquals(v4, HotspotAddressPolicy.select(listOf(v6, v4), "IPv6", true))
        assertNull(HotspotAddressPolicy.select(listOf(v6, loop), "IPv6", true))
        assertEquals(listOf(v4), HotspotAddressPolicy.advertisedAddresses(listOf(v6, v4, loop), true))
        assertEquals(listOf(v6, v4, loop), HotspotAddressPolicy.advertisedAddresses(listOf(v6, v4, loop), false))
        assertEquals(v6, HotspotAddressPolicy.select(listOf(v6, v4), "IPv6", false))
    }
}
