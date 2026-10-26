package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PhoneBluetoothReceiverTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val selected = "12:34:56:78:9A:BC"

    @Before fun reset() {
        android.provider.Settings.Global.putInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 1)
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, false)
        app.getSharedPreferences("diplay_phone_wake", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("diplay_phone_wake_diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.allowAutomaticConnection()
        // Saved in lowercase on purpose: the receiver must match the (always uppercase) device
        // address case-insensitively; Robolectric refuses to build a lowercase remote device.
        DiPlayPreferences.savePhone(app, selected.lowercase(), "iPhone")
    }

    @org.junit.After fun allowFollowingTests() { CarPlayBackgroundSession.allowAutomaticConnection() }

    private fun event(address: String): Intent {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
        return Intent(BluetoothDevice.ACTION_ACL_CONNECTED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
    }

    @Test fun elapsedTimestampFromAnotherBootCannotSuppressThePhoneWake() {
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, true)
        app.getSharedPreferences("diplay_phone_wake", Context.MODE_PRIVATE).edit()
            .putLong("last_launch_elapsed", android.os.SystemClock.elapsedRealtime())
            .putInt("last_launch_boot", 41).commit()
        android.provider.Settings.Global.putInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 42)
        var launches = 0
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launches++ }
        }
        PhoneBluetoothReceiver().onReceive(context, event(selected))
        assertEquals(1, launches)
    }

    @Test fun manualStopSuppressesWakeUntilExplicitConnect() {
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, true)
        var launches = 0
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launches++ }
        }
        CarPlayBackgroundSession.stop()
        PhoneBluetoothReceiver().onReceive(context, event(selected))
        assertEquals(0, launches)
        CarPlayBackgroundSession.allowAutomaticConnection()
        PhoneBluetoothReceiver().onReceive(context, event(selected))
        assertEquals(1, launches)
    }

    @Test fun onlySelectedPhoneWithOptInLaunchesOnce() {
        val launches = mutableListOf<Intent>()
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launches += intent }
        }
        val receiver = PhoneBluetoothReceiver()
        receiver.onReceive(context, event(selected))
        assertTrue(launches.isEmpty())
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, true)
        receiver.onReceive(context, event("AA:BB:CC:DD:EE:FF"))
        assertTrue(launches.isEmpty())
        receiver.onReceive(context, event(selected))
        receiver.onReceive(context, event(selected))
        assertEquals(1, launches.size)
        assertEquals(DiPlayActivity::class.java.name, launches.single().component?.className)
        assertTrue(launches.single().getBooleanExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT, false))
        assertTrue(PhoneWakeDiagnostics.report(app).contains("result=startActivity-returned"))
    }
}
