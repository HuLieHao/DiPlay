package com.shilapi.xcertplay

import android.os.Binder
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 33])
class VehicleIlluminationTest {
    @Test fun readsOnlyTransactionFiveAndTreatsZeroAsValidOff() {
        for (state in listOf(0, 1)) {
            val service = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    assertEquals(5, code)
                    assertEquals(0, flags)
                    data.enforceInterface("com.ts.can.carinfo.ICarInfoService")
                    assertEquals(0, data.dataAvail())
                    reply!!.writeNoException()
                    reply.writeInt(state)
                    return true
                }
            }
            assertEquals(state == 1, readVehicleIllumination(service))
        }
    }
    @Test fun unavailableServiceRetriesWithoutFloodingAndStopCancelsRetries() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        var binds = 0
        val context = object : android.content.ContextWrapper(app) {
            override fun getApplicationContext() = this
            override fun bindService(intent: android.content.Intent, connection: android.content.ServiceConnection, flags: Int): Boolean {
                assertEquals("com.ts.MainUI", intent.component!!.packageName)
                binds++
                return false
            }
            override fun unbindService(connection: android.content.ServiceConnection) {}
        }
        val states = mutableListOf<Boolean?>()
        val logs = mutableListOf<String>()
        val source = VehicleIllumination(context, logs::add)
        source.start(states::add)
        assertEquals(1, binds)
        assertEquals(listOf<Boolean?>(null), states)
        val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        looper.idleFor(java.time.Duration.ofSeconds(9))
        assertEquals(1, binds)
        looper.idleFor(java.time.Duration.ofSeconds(1))
        assertEquals(2, binds)
        assertEquals(1, logs.size)
        source.stop()
        looper.idleFor(java.time.Duration.ofSeconds(30))
        assertEquals(2, binds)
    }

    @Test fun lateServiceConnectionAfterStopIsIgnored() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        lateinit var connection: android.content.ServiceConnection
        var unbinds = 0
        val context = object : android.content.ContextWrapper(app) {
            override fun getApplicationContext() = this
            override fun bindService(intent: android.content.Intent, conn: android.content.ServiceConnection, flags: Int): Boolean {
                connection = conn
                return true
            }
            override fun unbindService(conn: android.content.ServiceConnection) { unbinds++ }
        }
        val states = mutableListOf<Boolean?>()
        val source = VehicleIllumination(context) {}
        source.start(states::add)
        source.stop()
        val count = states.size
        connection.onServiceConnected(android.content.ComponentName("com.ts.MainUI", "service"), Binder())
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(30))
        assertEquals(1, unbinds)
        assertEquals(count, states.size)
    }

    @Test fun realCarInfoBroadcastUpdatesBothDirectionsAndCancelsPendingBindTimeout() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        lateinit var receiver: android.content.BroadcastReceiver
        lateinit var serviceConnection: android.content.ServiceConnection
        var binds = 0
        var unregisters = 0
        val context = object : android.content.ContextWrapper(app) {
            override fun getApplicationContext() = this
            override fun registerReceiver(value: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?): android.content.Intent? {
                assertTrue(filter!!.hasAction("broadcast_send_carinfo"))
                receiver = value!!
                return null
            }
            override fun registerReceiver(value: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?, flags: Int): android.content.Intent? {
                assertEquals(android.content.Context.RECEIVER_EXPORTED, flags)
                return registerReceiver(value, filter)
            }
            override fun unregisterReceiver(value: android.content.BroadcastReceiver?) { unregisters++ }
            override fun bindService(intent: android.content.Intent, conn: android.content.ServiceConnection, flags: Int): Boolean {
                binds++
                serviceConnection = conn
                return true // Initial service callback is delayed; event may arrive first.
            }
            override fun unbindService(conn: android.content.ServiceConnection) {}
        }
        val states = mutableListOf<Boolean?>()
        val source = VehicleIllumination(context) {}
        source.start(states::add)
        fun event(value: Int) = android.content.Intent("broadcast_send_carinfo").putExtra("ILL_LIGHT", value)
        receiver.onReceive(context, event(1))
        assertEquals(true, states.last())
        serviceConnection.onServiceConnected(android.content.ComponentName("com.ts.MainUI", "service"), Binder())
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(60))
        assertEquals(true, states.last()) // No pending bind timeout may replace a valid event.
        assertEquals(1, binds)
        receiver.onReceive(context, event(0))
        assertEquals(false, states.last())
        val count = states.size
        receiver.onReceive(context, event(7))
        receiver.onReceive(context, android.content.Intent("broadcast_send_carinfo"))
        receiver.onReceive(context, android.content.Intent("BROADCAST_ADAS_LIGHT_STATE").putExtra("ILL_LIGHT", 1))
        assertEquals(count, states.size)
        serviceConnection.onServiceDisconnected(android.content.ComponentName("com.ts.MainUI", "service"))
        assertNull("Service death must clear the event state", states.last())
        val oldReceiver = receiver
        source.stop()
        assertEquals(1, unregisters)
        source.start(states::add)
        val resumedCount = states.size
        oldReceiver.onReceive(context, event(1))
        assertEquals(resumedCount, states.size)
        source.stop()
    }

    @Test fun unsupportedTransactionDoesNotBecomeLightsOff() {
        assertThrows(IllegalStateException::class.java) { readVehicleIllumination(Binder()) }
    }
}
