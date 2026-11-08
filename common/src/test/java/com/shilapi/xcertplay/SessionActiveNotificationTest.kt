package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import android.content.Intent
import android.os.Looper
import android.widget.TextView
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class SessionActiveNotificationTest {
    @Test fun stoppingDoesNotDisplayConnectingOrAllowAnotherStart() {
        val home = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        val text = TextView(home)
        val connect = android.widget.Button(home)
        ReflectionHelpers.setField(home, "status", text)
        ReflectionHelpers.setField(home, "connectButton", connect)
        ReflectionHelpers.setField(home, "setupError", null)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopping", true)
        CarPlayBackgroundSession.active = false
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(home, "refreshStatus")
            assertEquals("Disconnecting CarPlay…", text.text.toString())
            assertFalse(connect.isEnabled)
        } finally {
            ReflectionHelpers.setField(CarPlayBackgroundSession, "stopping", false)
            CarPlayBackgroundSession.clear()
        }
    }

    @Test fun resumedManagementUpdatesWithoutWaitingForThePollingTick() {
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.allowAutomaticConnection()
        val lifecycle = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent().putExtra("page", "home")).create().start().resume()
        val home = lifecycle.get()
        val text = TextView(home)
        ReflectionHelpers.setField(home, "status", text)
        ReflectionHelpers.setField(home, "setupError", null)
        shadowOf(Looper.getMainLooper()).idle()
        try {
            CarPlayBackgroundSession.active = true
            shadowOf(Looper.getMainLooper()).idle() // No clock advance: the 1 s tick cannot cause this.
            assertEquals(home.getString(R.string.carplay_connected), text.text.toString())
            lifecycle.pause()
            CarPlayBackgroundSession.active = false
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(home.getString(R.string.carplay_connected), text.text.toString())
        } finally {
            lifecycle.stop().destroy()
            CarPlayBackgroundSession.clear()
        }
    }
    @Test fun transitionsNotifyOnceAndPausedObserverIsRemoved() {
        CarPlayBackgroundSession.clear()
        val owner = Any()
        val changes = mutableListOf<Boolean>()
        val callback: () -> Unit = { changes += CarPlayBackgroundSession.active }
        ReflectionHelpers.callInstanceMethod<Unit>(CarPlayBackgroundSession, "observeActive",
            ReflectionHelpers.ClassParameter(Any::class.java, owner),
            ReflectionHelpers.ClassParameter(kotlin.jvm.functions.Function0::class.java, callback))
        try {
            CarPlayBackgroundSession.active = true
            CarPlayBackgroundSession.active = true
            CarPlayBackgroundSession.clear()
            assertEquals(listOf(true, false), changes)
        } finally {
            ReflectionHelpers.callInstanceMethod<Unit>(CarPlayBackgroundSession, "removeActiveObserver",
                ReflectionHelpers.ClassParameter(Any::class.java, owner))
        }
        CarPlayBackgroundSession.active = true
        assertEquals(listOf(true, false), changes)
        CarPlayBackgroundSession.clear()
    }
}
