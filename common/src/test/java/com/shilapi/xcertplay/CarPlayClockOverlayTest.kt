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
    @Test fun repeatedShowHideAndDetachDoesNotAccumulateMinuteCallbacks() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val handler = Handler(Looper.getMainLooper())
        var reads = 0
        val root = FrameLayout(activity)
        activity.setContentView(root)
        CarPlayClockSettings.save(activity, "left", 20)
        repeat(24) {
            val overlay = CarPlayClockOverlay(activity, handler) { reads++; 1_800_000_000_000L }
            overlay.setVideoContentBounds(android.graphics.RectF(0f, 0f, 1960f, 858f))
            root.addView(overlay)
            overlay.update(true, false)
            overlay.update(true, true)
            val before = reads
            shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS)
            assertEquals(before + 1, reads)
            overlay.update(false, true)
            root.removeView(overlay)
            val detached = reads
            CarPlayClockSettings.save(activity, "right", 24)
            shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.MINUTES)
            assertEquals(detached, reads)
        }
        controller.pause().stop().destroy()
    }

    @Test fun clockAlwaysUsesTwentyFourHours() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val oldZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            val overlay = CarPlayClockOverlay(activity, Handler(Looper.getMainLooper())) { 1_800_018_000_000L } // 13:00 UTC
            overlay.setVideoContentBounds(android.graphics.RectF(0f, 0f, 1960f, 858f))
            CarPlayClockSettings.save(activity, "left", 20)
            android.provider.Settings.System.putString(activity.contentResolver, android.provider.Settings.System.TIME_12_24, "24")
            overlay.update(true, false)
            assertEquals("13:00", overlay.text.toString())
            android.provider.Settings.System.putString(activity.contentResolver, android.provider.Settings.System.TIME_12_24, "12")
            overlay.update(true, false)
            assertEquals("13:00", overlay.text.toString())
            overlay.update(false, false)
        } finally { java.util.TimeZone.setDefault(oldZone); activity.finish() }
    }

    @Test fun dockClockUsesVisibleVideoBoundsIndependentOfAndroidDensity() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val overlay = CarPlayClockOverlay(activity, Handler(Looper.getMainLooper()))
        overlay.setVideoContentBounds(android.graphics.RectF(0f, 0f, 1960f, 858f))
        CarPlayClockSettings.save(activity, "left", 20)
        overlay.update(true, false)
        overlay.setVideoContentBounds(android.graphics.RectF(20f, 100f, 1980f, 958f))
        val params = overlay.layoutParams as FrameLayout.LayoutParams
        assertEquals(118, params.width)
        assertEquals(20, params.leftMargin)
        assertEquals(812, params.topMargin)
        assertEquals(32f, overlay.textSize, .01f)
        assertEquals(android.graphics.Color.WHITE, overlay.currentTextColor)
        assertEquals(android.graphics.Color.BLACK, overlay.shadowColor)
        assertEquals(2f, overlay.shadowRadius, .01f)
        val originalScaledDensity = activity.resources.displayMetrics.scaledDensity
        activity.resources.displayMetrics.scaledDensity = 3f
        overlay.update(true, false)
        assertEquals(32f, overlay.textSize, .01f)
        overlay.setVideoContentBounds(android.graphics.RectF(40f, 200f, 1020f, 629f))
        assertEquals(16f, overlay.textSize, .01f)
        assertEquals(1f, overlay.shadowRadius, .01f)
        CarPlayClockSettings.save(activity, "right", 24)
        overlay.update(true, false)
        val right = overlay.layoutParams as FrameLayout.LayoutParams
        assertEquals(961, right.leftMargin)
        assertTrue(overlay.textSize in 16f..19.2f)
        assertTrue(overlay.paint.measureText("00:00") <= right.width + .01f)
        overlay.update(false, false)
        activity.resources.displayMetrics.scaledDensity = originalScaledDensity
        activity.finish()
    }

    @Test fun clockIsOptInClampedAndInvalidTimeIsExplicit() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val overlay = CarPlayClockOverlay(activity, Handler(Looper.getMainLooper())) { 0L }
        overlay.setVideoContentBounds(android.graphics.RectF(0f, 0f, 1960f, 858f))
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
        overlay.setVideoContentBounds(android.graphics.RectF(0f, 0f, 1960f, 858f))
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
        assertEquals(25.6f, overlay.textSize, .01f)
        root.removeView(overlay)
        val detached = reads
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.MINUTES)
        assertEquals(detached, reads)
        activity.finish()
    }

    @Test fun settingsApplyLiveAndHiddenClockDoesNotObservePreferences() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val overlay = CarPlayClockOverlay(activity, Handler(Looper.getMainLooper())) { 1_800_000_000_000L }
        overlay.setVideoContentBounds(android.graphics.RectF(0f, 0f, 1960f, 858f))
        val root = FrameLayout(activity)
        root.addView(overlay)
        activity.setContentView(root)
        CarPlayClockSettings.save(activity, "left", 16)
        overlay.update(true, false)
        CarPlayClockSettings.save(activity, "right", 24)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(overlay.textSize in 32f..38.4f)
        val params = overlay.layoutParams as FrameLayout.LayoutParams
        assertEquals(android.view.Gravity.TOP or android.view.Gravity.LEFT, params.gravity)
        val dayColor = overlay.currentTextColor
        overlay.update(true, true)
        assertEquals(dayColor, overlay.currentTextColor)
        assertEquals(android.graphics.Color.WHITE, overlay.currentTextColor)
        overlay.update(false, true)
        CarPlayClockSettings.save(activity, "left", 16)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(overlay.textSize in 32f..38.4f)
        activity.finish()
    }

    @Test fun touchesPassThroughAndPositionDoesNotResizeVideo() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        var touches = 0
        val video = View(activity).apply { setOnTouchListener { _, _ -> touches++; true } }
        val overlay = CarPlayClockOverlay(activity, Handler(Looper.getMainLooper())) { 1_800_000_000_000L }
        overlay.setVideoContentBounds(android.graphics.RectF(0f, 0f, 800f, 480f))
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
