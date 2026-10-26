package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Compact/full and RTL screenshots of the optional media-key setting at 1.5x text size. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarPlayVolumeSettingsScreenshotsTest {
    @Test fun speakerAndNavigationBarsRenderWithoutCaptionsAndPassThroughTouches() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        for ((name, widthDp, language) in listOf(Triple("compact", 320, "zh-CN"), Triple("full", 960, "en-US"), Triple("rtl", 320, "ar-SA"))) {
            val config = Configuration(activity.resources.configuration).apply {
                fontScale = 1.5f; val locale = Locale.forLanguageTag(language); setLocale(locale); setLayoutDirection(locale)
            }
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            val root = android.widget.FrameLayout(activity).apply { setBackgroundColor(android.graphics.Color.rgb(60, 68, 76)) }
            var touches = 0
            root.addView(View(activity).apply { setOnTouchListener { _, _ -> touches++; true } }, android.widget.FrameLayout.LayoutParams(-1, -1))
            val overlay = CarPlayVolumeOverlay(activity, android.os.Handler(android.os.Looper.getMainLooper()))
            root.addView(overlay); activity.setContentView(root)
            val density = activity.resources.displayMetrics.density
            val width = (widthDp * density).toInt(); val height = (160 * density).toInt()
            for (stream in listOf(3, 5)) {
                overlay.show(stream, 4, 10)
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                val event = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, width / 2f, 40 * density, 0)
                root.dispatchTouchEvent(event); event.recycle()
                assertTrue(touches > 0)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                val directory = File("build/reports/carplay-volume-ui").apply { mkdirs() }
                FileOutputStream(File(directory, "$name-bar-$stream.png")).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle(); overlay.stop()
            }
        }
        activity.finish()
    }
    @Test fun volumeControlsRenderAtLargeFontsInThreeLayouts() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        CarPlayVolumeSettings.save(activity, true)
        try {
            for ((filename, widthDp, language) in listOf(
                Triple("compact-zh", 320, "zh-CN"),
                Triple("full-en", 960, "en-US"),
                Triple("compact-ar-rtl", 320, "ar-SA"),
            )) {
                val locale = Locale.forLanguageTag(language)
                val config = Configuration(activity.resources.configuration).apply {
                    fontScale = 1.5f
                    setLocale(locale)
                    setLayoutDirection(locale)
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                val row = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                DiPlayActivity::class.java.getDeclaredMethod("navigationWheelControls", LinearLayout::class.java)
                    .apply { isAccessible = true }.invoke(activity, row)
                assertTrue(allViews(row).filterIsInstance<TextView>().any {
                    it.text.toString() == activity.getString(R.string.settings_carplay_volume_bar)
                })
                val width = (widthDp * activity.resources.displayMetrics.density).toInt()
                row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                row.layout(0, 0, width, row.measuredHeight)
                val bitmap = Bitmap.createBitmap(width, row.measuredHeight, Bitmap.Config.ARGB_8888)
                try {
                    row.draw(Canvas(bitmap))
                    val directory = File("build/reports/carplay-volume-ui").apply { mkdirs() }
                    FileOutputStream(File(directory, "$filename.png")).use { output ->
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            activity.finish()
        }
    }

    private fun allViews(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(allViews(view.getChildAt(i)))
    }
}
