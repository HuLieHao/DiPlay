package com.shilapi.xcertplay

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class CarPlayVolumeRoutingTest {
    @Test fun pairedKeysWriteOnlyChosenStreamAtStepOneWithoutSystemUiAndKeepUpDuringCall() {
        var stream: Int? = 5
        val volumes = mutableMapOf(3 to 8, 5 to 4)
        val writes = mutableListOf<Triple<Int, Int, Int>>()
        val route = CarPlayVolumeKeyRouter({ stream }, { 0 }, { 10 }, { volumes.getValue(it) },
            { s, v, flags -> writes.add(Triple(s, v, flags)); volumes[s] = v }, { _, _, _ -> })
        assertTrue(route.onKey(0, 24, 1, 100, 0))
        assertEquals(5, volumes[5]); assertEquals(8, volumes[3])
        stream = null // call/Siri starts between DOWN and UP
        assertTrue(route.onKey(0, 24, 1, 100, 1))
        assertTrue(route.onKey(1, 24, 1, 100, 0))
        assertEquals(listOf(Triple(5, 5, 0)), writes)
        assertFalse(route.onKey(0, 25, 1, 200, 0))
        stream = 3
        assertTrue(route.onKey(0, 25, 1, 300, 0))
        assertEquals(7, volumes[3]); assertEquals(5, volumes[5])
    }
    @Test fun heldGuidanceKeyNeverStartsChangingMusicWhenGuidanceEnds() {
        var stream: Int? = 5
        val changed = mutableListOf<Int>()
        var volume = 4
        val route = CarPlayVolumeKeyRouter({ stream }, { 0 }, { 10 }, { volume },
            { s, v, _ -> changed.add(s); volume = v }, { _, _, _ -> })
        assertTrue(route.onKey(0, 24, 1, 100, 0))
        stream = 3
        assertTrue(route.onKey(0, 24, 1, 100, 1))
        assertTrue(route.onKey(1, 24, 1, 100, 0))
        assertEquals(listOf(5), changed)
        assertTrue(route.onKey(0, 24, 1, 200, 0))
        assertEquals(listOf(5, 3), changed)
    }
    @Test fun unsupportedReadbackPassesInitialPairAndClampIsConsumedWithoutExtraStep() {
        var current = 10
        var accept = false
        val route = CarPlayVolumeKeyRouter({ 5 }, { 0 }, { 10 }, { current },
            { _, value, _ -> if (accept) current = value }, { _, _, _ -> })
        assertFalse(route.onKey(0, 25, 1, 100, 0))
        assertFalse(route.onKey(1, 25, 1, 100, 0))
        accept = true
        assertTrue(route.onKey(0, 24, 1, 200, 0))
        assertEquals(10, current)
        assertTrue(route.onKey(1, 24, 1, 200, 0))
        assertFalse(route.onKey(0, 85, 1, 300, 0))
    }
    @Test fun overlayTimesOutAndHidingCancelsCallbacksWithoutTouchTargets() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val overlay = CarPlayVolumeOverlay(activity, Handler(Looper.getMainLooper()))
        assertFalse(overlay.isClickable); assertFalse(overlay.isFocusable)
        overlay.show(5, 4, 10)
        assertEquals(View.VISIBLE, overlay.visibility)
        shadowOf(Looper.getMainLooper()).idleFor(1500, TimeUnit.MILLISECONDS)
        assertEquals(View.GONE, overlay.visibility)
        overlay.show(3, 8, 10); overlay.stop()
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(View.GONE, overlay.visibility)
        activity.finish()
    }
}
