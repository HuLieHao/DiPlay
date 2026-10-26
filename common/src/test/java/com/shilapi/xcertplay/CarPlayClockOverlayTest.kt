package com.shilapi.xcertplay

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarPlayClockOverlayTest {
    @Test fun clockIsOptInClampedAndInvalidTimeIsExplicit() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val overlay = CarPlayClockOverlay(activity, Handler(Looper.getMainLooper())) { 0L }
        assertEquals("off", CarPlayClockSettings.position(activity))
        CarPlayClockSettings.save(activity, "bad", 300)
        assertEquals("off", CarPlayClockSettings.position(activity))
        assertEquals(24, CarPlayClockSettings.size(activity))
        CarPlayClockSettings.save(activity, "right", 20)
        overlay.update(true, false)
        assertTrue(overlay.text.toString().contains("—"))
        assertFalse(overlay.isClickable)
        assertFalse(overlay.isFocusable)
        overlay.update(false, false)
        assertEquals(View.GONE, overlay.visibility)
        activity.finish()
    }

    @Test fun visibleClockAlignsToMinuteAndStopsWhenHiddenOrDetached() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var now = 1_800_000_020_000L
        var reads = 0
        val overlay = CarPlayClockOverlay(activity, Handler(Looper.getMainLooper())) { reads++; now }
        val root = FrameLayout(activity)
        root.addView(overlay)
        activity.setContentView(root)
        CarPlayClockSettings.save(activity, "left", 16)
        overlay.update(true, false)
        val initial = reads
        now += 39_999
        shadowOf(Looper.getMainLooper()).idleFor(39_999, TimeUnit.MILLISECONDS)
        assertEquals(initial, reads)
        now++
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
        assertTrue(reads > initial)
        overlay.update(false, true)
        val hidden = reads
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.MINUTES)
        assertEquals(hidden, reads)
        overlay.update(true, true)
        assertEquals(16f, overlay.textSize / activity.resources.displayMetrics.scaledDensity, .01f)
        root.removeView(overlay)
        val detached = reads
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.MINUTES)
        assertEquals(detached, reads)
        activity.finish()
    }

    @Test fun settingsApplyLiveAndHiddenClockDoesNotObservePreferences() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val overlay = CarPlayClockOverlay(activity, Handler(Looper.getMainLooper())) { 1_800_000_000_000L }
        val root = FrameLayout(activity)
        root.addView(overlay)
        activity.setContentView(root)
        CarPlayClockSettings.save(activity, "left", 16)
        overlay.update(true, false)
        CarPlayClockSettings.save(activity, "right", 24)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(24f, overlay.textSize / activity.resources.displayMetrics.scaledDensity, .01f)
        val params = overlay.layoutParams as FrameLayout.LayoutParams
        assertEquals(android.view.Gravity.BOTTOM or android.view.Gravity.RIGHT, params.gravity)
        val dayColor = overlay.currentTextColor
        overlay.update(true, true)
        assertNotEquals(dayColor, overlay.currentTextColor)
        overlay.update(false, true)
        CarPlayClockSettings.save(activity, "left", 16)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(24f, overlay.textSize / activity.resources.displayMetrics.scaledDensity, .01f)
        activity.finish()
    }

    @Test fun touchesPassThroughAndPositionDoesNotResizeVideo() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        var touches = 0
        val video = View(activity).apply { setOnTouchListener { _, _ -> touches++; true } }
        val overlay = CarPlayClockOverlay(activity, Handler(Looper.getMainLooper())) { 1_800_000_000_000L }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay)
        activity.setContentView(root)
        CarPlayClockSettings.save(activity, "right", 24)
        overlay.update(true, false)
        root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 800, 480)
        overlay.update(true, true)
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, overlay.x + 1, overlay.y + 1, 0)
        assertTrue(root.dispatchTouchEvent(event))
        event.recycle()
        assertEquals(1, touches)
        assertEquals(800, video.width)
        assertEquals(480, video.height)
        assertTrue(overlay.x >= 0 && overlay.x + overlay.width <= 800)
        activity.finish()
    }
}
