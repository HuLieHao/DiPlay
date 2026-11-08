package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class WirelessAddressPreferenceTest {
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("address_test", 0)
    @Test fun generatedHotspotIdentityDoesNotDiscardRetryOnEveryRestart() {
        for (backend in listOf("WIFI_P2P", "LOCAL_ONLY_HOTSPOT")) {
            val before = WirelessAddressPreference.environment(backend, "p2p0", "random1", "mac1", "phone")
            val after = WirelessAddressPreference.environment(backend, "p2p1", "random2", "mac2", "phone")
            assertEquals(before, after)
            val state = WirelessAddressPreference()
            state.timedOut(state.begin(before, prefs), "IPv6")
            assertEquals("IPv6", state.begin(after, prefs).family)
        }
        assertNotEquals(
            WirelessAddressPreference.environment("MANUAL_HOTSPOT", "ap0", "car1", "mac", "phone"),
            WirelessAddressPreference.environment("MANUAL_HOTSPOT", "ap0", "car2", "mac", "phone"))
    }

    @Test fun actualAutomaticallySelectedPhonesHaveSeparateScopes() {
        val firstPhone = WirelessAddressPreference.environment("MANUAL", "ap0", "buickPlay", "ap-mac", "AA:11")
        val secondPhone = WirelessAddressPreference.environment("MANUAL", "ap0", "buickPlay", "ap-mac", "BB:22")
        assertNotEquals(firstPhone, secondPhone)
        val state = WirelessAddressPreference()
        state.confirm(state.begin(firstPhone, prefs), "IPv6", prefs)
        assertNull(state.begin(secondPhone, prefs).family)
        assertEquals("IPv6", state.begin(firstPhone, prefs).family)
    }

    @Test fun timeoutIsTransientAndOnlyFirstFrameConfirmsPreference() {
        prefs.edit().clear().putString("hotspot_address_family", "IPv6").commit()
        val state = WirelessAddressPreference()
        val first = state.begin("car", prefs)
        assertNull(first.family) // legacy timeout-written value is not evidence of success
        assertTrue(state.timedOut(first, "IPv6"))
        val retry = state.begin("car", prefs)
        assertEquals("IPv6", retry.family)
        assertNull(prefs.getString("successful_family", null))
        assertTrue(state.confirm(retry, "IPv6", prefs))
        assertEquals("IPv6", WirelessAddressPreference().begin("car", prefs).family)
        assertNull(WirelessAddressPreference().begin("other-car", prefs).family)
    }
    @Test fun oldCallbacksCannotChangeNewAttemptOrPersistAfterStop() {
        val state = WirelessAddressPreference()
        val old = state.begin("car", prefs)
        val current = state.begin("car", prefs)
        assertFalse(state.timedOut(old, "IPv6"))
        assertFalse(state.confirm(old, "IPv6", prefs))
        state.clear()
        assertFalse(state.confirm(current, "IPv4", prefs))
        assertNull(state.begin("car", prefs).family)
    }
    @Test fun environmentChangeDropsRetryAndSuccessfulActualFamilyWins() {
        val state = WirelessAddressPreference()
        val first = state.begin("car", prefs)
        state.timedOut(first, "IPv6")
        val other = state.begin("other", prefs)
        assertNull(other.family)
        assertTrue(state.confirm(other, "IPv4", prefs))
        assertFalse(state.confirm(other, "IPv6", prefs))
        assertEquals("IPv4", state.begin("other", prefs).family)
        assertNull(state.begin("car", prefs).family)
    }
}
