package com.shilapi.xcertplay

import android.view.View
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ScrollView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PreparationLayoutTest {
    @Test fun shortAndRegularViewportsKeepWaitingContentAndActionsReachable() {
        // Attach without onCreate: avoid starting sensors, networking or CarPlay sessions.
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val method = CarPlayHostActivity::class.java.getDeclaredMethod("buildContentView")
        method.isAccessible = true
        val root = method.invoke(activity) as android.widget.FrameLayout
        val waiting = CarPlayHostActivity::class.java.getDeclaredField("connectionPanel")
            .apply { isAccessible = true }.get(activity) as ConnectionWaitingView
        val viewport = waiting.bodyScroll
        val panel = viewport.getChildAt(0) as LinearLayout
        val density = activity.resources.displayMetrics.density
        fun layout(heightDp: Int) {
            val width = (640 * density).toInt()
            val height = (heightDp * density).toInt()
            repeat(3) {
                // This detached view has no ViewRoot to schedule the next traversal.
                fun invalidateMeasurement(view: View) {
                    view.forceLayout()
                    if (view is android.view.ViewGroup) {
                        for (index in 0 until view.childCount) invalidateMeasurement(view.getChildAt(index))
                    }
                }
                invalidateMeasurement(root)
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
            }
            assertTrue("Body must not overlap the actions at height=$heightDp", viewport.bottom <= waiting.footer.top)
            assertTrue("Actions must fit at height=$heightDp", waiting.footer.bottom <= waiting.height - waiting.paddingBottom)
            viewport.scrollTo(0, 0)
            assertEquals(0, viewport.scrollY)
            assertTrue("top=${panel.top} height=$heightDp", panel.top >= 0)
            assertEquals(1f, panel.scaleY)
            viewport.scrollTo(0, panel.height)
            assertTrue("The final diagnostics must be reachable at height=$heightDp",
                viewport.scrollY + viewport.height >= panel.bottom)
        }
        for (height in listOf(200, 240, 320, 400, 479, 480, 720)) layout(height)
        assertTrue(waiting.stage.textSize / activity.resources.displayMetrics.scaledDensity >= 18f)
    }
}
