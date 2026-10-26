package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AutomaticConnectionLifecycleTest {
    private lateinit var activity: DiPlayActivity
    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.allowAutomaticConnection()
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
    }
    @After fun cleanup() {
        ReflectionHelpers.getField<Handler>(activity, "handler").removeCallbacksAndMessages(null)
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.allowAutomaticConnection()
    }
    private fun enqueue() = ReflectionHelpers.callInstanceMethod<Unit>(activity, "postAutomaticConnection",
        from(Boolean::class.javaPrimitiveType, false))

    @Test fun duplicateWakeRequestsOpenOnlyOneProjection() {
        enqueue()
        enqueue()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun hiddenManagementPageStopsItsRefresh() {
        val handler = ReflectionHelpers.getField<Handler>(activity, "handler")
        val tick = ReflectionHelpers.getField<Runnable>(activity, "tick")
        handler.postDelayed(tick, 1000L)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onPause")
        assertFalse(handler.hasCallbacks(tick))
    }

    @Test fun queuedConnectCannotUndoManualDisconnect() {
        enqueue()
        CarPlayBackgroundSession.stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(CarPlayBackgroundSession.automaticConnectionTicket())
    }

    @Test fun queuedConnectCannotTearDownASessionStartedInTheMeantime() {
        enqueue()
        var stops = 0
        val stop: ((() -> Unit) -> Unit) = { stops++ }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, stops)
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun newerExplicitAttemptInvalidatesOldAutomaticRequest() {
        enqueue()
        CarPlayBackgroundSession.allowAutomaticConnection()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
