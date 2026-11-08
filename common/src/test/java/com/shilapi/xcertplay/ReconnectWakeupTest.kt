package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Looper
import com.shilapi.xcertplay.network.WirelessStartupFailure
import java.time.Duration
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReconnectWakeupTest {
    private lateinit var host: CarPlayHostActivity
    private val phone = "12:34:56:78:9A:BC"

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.allowAutomaticConnection()
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        shadowOf(host.application).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        DiPlayPreferences.savePhone(host, phone.lowercase(), "iPhone")
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", host)
        ReflectionHelpers.setField(host, "wirelessEnabled", true)
    }

    @After fun cleanup() {
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.allowAutomaticConnection()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(40))
    }

    private fun schedule(wireless: Boolean = true) {
        ReflectionHelpers.setField(host, "wirelessEnabled", wireless)
        ReflectionHelpers.setField(host, "reconnectAttempts", 4)
        ReflectionHelpers.callInstanceMethod<Unit>(host, "reconnectAfterLoss",
            ReflectionHelpers.ClassParameter(String::class.java, "Bluetooth connection timeout"),
            ReflectionHelpers.ClassParameter(WirelessStartupFailure::class.java, null),
            ReflectionHelpers.ClassParameter(String::class.java, "Bluetooth connection timeout"))
    }

    private fun pending() = ReflectionHelpers.getField<Boolean>(host, "reconnectScheduled")
    private fun displayReady() {
        val type = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val constructor = type.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        constructor.isAccessible = true
        ReflectionHelpers.setField(host, "activeDisplaySize", constructor.newInstance(1024, 600))
    }
    private fun event(address: String = phone) {
        val intent = Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE,
            BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address))
        ReflectionHelpers.getField<BroadcastReceiver>(host, "bluetoothOnReceiver").onReceive(host, intent)
    }

    private fun armWait() = ReflectionHelpers.callInstanceMethod<Unit>(host, "armWirelessWaitTimeout")

    @Test fun fiveMinutesWithoutConnectionStopsAndReturnsToManagement() {
        armWait()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(4))
        armWait() // another attempt must not grant another five minutes
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(59))
        assertFalse(host.isFinishing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(host.isFinishing)
        assertNull(CarPlayBackgroundSession.automaticConnectionTicket())
        assertEquals(DiPlayActivity::class.java.name, shadowOf(host).nextStartedActivity?.component?.className)
        event()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(CarPlayBackgroundSession.automaticConnectionTicket())
    }

    @Test fun successfulConnectionIsNotStoppedByOldWaitTimer() {
        armWait()
        CarPlayBackgroundSession.active = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        assertFalse(host.isFinishing)
        assertNotNull(CarPlayBackgroundSession.automaticConnectionTicket())
        CarPlayBackgroundSession.active = false
        armWait()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(4))
        assertFalse(host.isFinishing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertTrue(host.isFinishing)
    }

    @Test fun oldHostTimerCannotStopANewOwner() {
        armWait()
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", Any())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        assertFalse(host.isFinishing)
        assertNotNull(CarPlayBackgroundSession.automaticConnectionTicket())
    }

    @Test fun replacementHostInheritsTheOriginalDeadline() {
        armWait()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(4))
        val replacement = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(replacement, "wirelessEnabled", true)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", replacement)
        ReflectionHelpers.callInstanceMethod<Unit>(replacement, "armWirelessWaitTimeout")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertFalse(host.isFinishing)
        assertTrue(replacement.isFinishing)
        assertNull(CarPlayBackgroundSession.automaticConnectionTicket())
    }

    @Test fun manualNewConnectionGetsAFreshFiveMinuteWindow() {
        armWait()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(4))
        CarPlayBackgroundSession.stop()
        CarPlayBackgroundSession.allowAutomaticConnection()
        val replacement = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(replacement, "wirelessEnabled", true)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", replacement)
        ReflectionHelpers.callInstanceMethod<Unit>(replacement, "armWirelessWaitTimeout")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(4))
        assertFalse(replacement.isFinishing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertTrue(replacement.isFinishing)
    }

    @Test fun wiredConnectionDoesNotGetWirelessWaitLimit() {
        ReflectionHelpers.setField(host, "wirelessEnabled", false)
        armWait()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(6))
        assertFalse(host.isFinishing)
        assertNotNull(CarPlayBackgroundSession.automaticConnectionTicket())
    }

    @Test fun wirelessFallbackWaitIsAtMostTenSeconds() {
        schedule()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(9999))
        assertTrue(pending())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertFalse(pending())
    }

    @Test fun wiredFallbackStillWaitsThirtySeconds() {
        schedule(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertTrue(pending())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertFalse(pending())
    }

    @Test fun selectedPhoneWakesPendingRetryWithoutWaitingForTimer() {
        schedule()
        event(); event()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(pending())
        assertNull(ReflectionHelpers.getField<Runnable?>(host, "pendingReconnect"))
    }

    @Test fun anotherPhoneDoesNotWakeRetry() {
        schedule()
        event("AA:BB:CC:DD:EE:FF")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(pending())
    }

    @Test fun manualDisconnectDoesNotWakeRetry() {
        schedule()
        CarPlayBackgroundSession.stop()
        event()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(pending())
        assertNull(CarPlayBackgroundSession.automaticConnectionTicket())
    }

    @Test fun oldOwnerCannotWakeRetry() {
        schedule()
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", Any())
        event()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(pending())
    }

    @Test fun connectedSessionIsNotRestartedByAclEvent() {
        schedule()
        CarPlayBackgroundSession.active = true
        event()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(pending())
    }

    @Test fun aRecoveredControlLoopIsNotTornDownByAnEarlyRetry() {
        displayReady()
        schedule()
        ReflectionHelpers.setField(host, "controlRunningCount", 1)
        ReflectionHelpers.setField(host, "lossSinceControlRunning", false)
        event()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(pending())
        assertEquals(0, ReflectionHelpers.getField<Int>(host, "restartGeneration"))
        assertFalse(ReflectionHelpers.getField<Boolean>(host, "handshakeResetInProgress"))
    }

    @Test fun duplicateEventsRebuildTheStackOnlyOnce() {
        displayReady()
        schedule()
        event(); event()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, ReflectionHelpers.getField<Int>(host, "restartGeneration"))
        assertFalse(pending())
    }
}
