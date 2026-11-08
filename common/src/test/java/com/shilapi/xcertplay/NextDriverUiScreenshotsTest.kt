package com.shilapi.xcertplay

import android.app.AlertDialog
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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NextDriverUiScreenshotsTest {
    @Test fun captureDiagnosticsAtLargeFonts() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup().visible()
        val activity = controller.get()
        try {
            for ((name, width, tag) in listOf(Triple("compact-capture", 320, "zh-CN"), Triple("full-capture", 1000, "zh-CN"), Triple("arabic-capture", 320, "ar-SA"))) {
                val config = Configuration(activity.resources.configuration).apply {
                    fontScale = 1.5f; screenWidthDp = width
                    setLocale(Locale.forLanguageTag(tag)); setLayoutDirection(Locale.forLanguageTag(tag))
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                org.robolectric.util.ReflectionHelpers.setField(activity, "settingsSectionFilter", setOf(SettingsSection.DIAGNOSTICS))
                val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                method("allSettingsSections", LinearLayout::class.java).invoke(activity, root)
                assertTrue(views(root).filterIsInstance<android.widget.Button>().any { it.text == activity.getString(R.string.settings_capture_stop) })
                assertFalse(views(root).filterIsInstance<android.widget.Button>().any { it.text == activity.getString(R.string.settings_hotspot_capture) })
                capture(root, width, name)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun boardMfiSettingsRemainReadableAtLargeFonts() {
        org.robolectric.shadows.ShadowBuild.setModel("FF_5000")
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup().visible()
        val activity = controller.get()
        activity.applicationInfo.flags = activity.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        AirPlayPersistence.saveMfiTarget(activity, com.shilapi.xcertplay.orchestration.MfiTarget.I2C)
        try {
            for ((name, width, tag) in listOf(Triple("compact", 320, "zh-CN"), Triple("full", 1000, "zh-CN"), Triple("arabic", 320, "ar-SA"))) {
                val config = Configuration(activity.resources.configuration).apply {
                    fontScale = 1.5f
                    screenWidthDp = width
                    setLocale(Locale.forLanguageTag(tag))
                    setLayoutDirection(Locale.forLanguageTag(tag))
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                org.robolectric.util.ReflectionHelpers.setField(activity, "settingsSectionFilter", setOf(SettingsSection.CONNECTION_SETUP))
                val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                method("allSettingsSections", LinearLayout::class.java).invoke(activity, root)
                capture(root, width, "$name-board-mfi")
                val target = views(root).filterIsInstance<android.widget.Button>().single { it.text.contains(activity.getString(R.string.settings_board_mfi)) }
                assertTrue("Authentication row must fit its label", target.layout.height <= target.height - target.compoundPaddingTop - target.compoundPaddingBottom)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun clockHomeAndExportRemainUsableAtLargeFonts() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).setup().visible().get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        for ((name, width, tag) in listOf(Triple("compact-en", 320, "en-US"), Triple("full-en", 1000, "en-US"), Triple("compact-ar", 320, "ar-SA"))) {
            val locale = Locale.forLanguageTag(tag)
            val config = Configuration(activity.resources.configuration).apply {
                fontScale = 1.5f
                screenWidthDp = width
                setLocale(locale)
                setLayoutDirection(locale)
            }
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            val clock = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            method("clockSettings", LinearLayout::class.java).invoke(activity, clock)
            assertTrue(views(clock).filterIsInstance<TextView>().any { it.text.contains(activity.getString(R.string.settings_clock_size)) })
            assertEquals("Clock settings should only contain the size choice", 1, views(clock).filterIsInstance<android.widget.Button>().count { it !is android.widget.CompoundButton })
            assertTrue("Clock settings have no display switch", views(clock).filterIsInstance<android.widget.Switch>().none())
            capture(clock, width, "$name-clock-settings")
            for (label in views(clock).filterIsInstance<android.widget.Button>()) {
                assertTrue("Clock row clips: ${label.text}", label.layout.height <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom)
            }
            val home = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            method("home", LinearLayout::class.java).invoke(activity, home)
            val primary = views(home).filterIsInstance<TextView>().single { it.text == activity.getString(R.string.connect_phone) }
            assertTrue(primary.layoutParams.height >= (48 * activity.resources.displayMetrics.density).toInt())
            capture(home, width, "$name-home")
            method("chooseDiagnosticSelection", kotlin.jvm.functions.Function0::class.java).invoke(activity, {})
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val selection = ShadowAlertDialog.getLatestAlertDialog()
            val list = selection.listView
            assertEquals(5, list.adapter.count)
            assertEquals(activity.getString(R.string.settings_archive_session), list.adapter.getItem(4))
            capture(selection.window!!.decorView, width, "$name-export-selection", 640)
            selection.dismiss()
            method("showDiagnosticReport", String::class.java).invoke(activity,
                "Preview fixture — selected session: session-01\nManifest: 2 original files\nSHA-256: see manifest.json\nOriginal log preview (bounded)\nConnection started\nFirst frame received")
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val preview = ShadowAlertDialog.getLatestAlertDialog()
            capture(preview.window!!.decorView, width, "$name-export-preview", 640)
            preview.dismiss()
        }
        activity.finish()
    }

    @Test fun clockOverlayHasBoundedPhysicalEdgesInBothAppearances() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().visible().get()
        try {
            for ((name, width, localeTag) in listOf(Triple("compact", 640, "en-US"), Triple("full", 1000, "en-US"), Triple("arabic", 640, "ar-SA"))) {
                val locale = Locale.forLanguageTag(localeTag)
                val config = Configuration(activity.resources.configuration).apply {
                    fontScale = 1.5f
                    setLocale(locale)
                    setLayoutDirection(locale)
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                for (night in listOf(false, true)) {
                    val root = android.widget.FrameLayout(activity).apply { setBackgroundColor(if (night) android.graphics.Color.rgb(40, 52, 68) else android.graphics.Color.rgb(232, 234, 238)) }
                    root.addView(TextView(activity).apply { text = "Video viewport fixture"; setTextColor(android.graphics.Color.WHITE) })
                    val clock = CarPlayClockOverlay(activity, android.os.Handler(android.os.Looper.getMainLooper())) { 1_800_000_020_000L }
                    clock.setVideoContentBounds(android.graphics.RectF(0f, 0f, width.toFloat(), 360f))
                    root.addView(clock)
                    activity.setContentView(root)
                    CarPlayClockSettings.save(activity, 24)
                    clock.update(true, night)
                    capture(root, width, "$name-clock-${if (night) "night-left" else "day-right"}", 360)
                    println("clock=$name night=$night text=${clock.text} width=${clock.width} height=${clock.height} baseline=${clock.baseline} padding=${clock.compoundPaddingLeft},${clock.compoundPaddingTop},${clock.compoundPaddingRight},${clock.compoundPaddingBottom} layoutWidth=${clock.layout.width} layoutHeight=${clock.layout.height} textWidth=${clock.paint.measureText(clock.text.toString())} font=${clock.paint.fontMetrics} scrollX=${clock.scrollX}")
                    assertFalse(clock.text.isNullOrBlank())
                    val ink = Bitmap.createBitmap(clock.width, clock.height, Bitmap.Config.ARGB_8888)
                    try {
                        clock.draw(Canvas(ink))
                        var visibleTextPixels = 0
                        for (y in 0 until ink.height) for (x in 0 until ink.width) {
                            if (ink.getPixel(x, y) == clock.currentTextColor) visibleTextPixels++
                        }
                        assertTrue("Clock must draw legible text: ${clock.text}", visibleTextPixels > 0)
                        assertEquals(android.graphics.Color.TRANSPARENT, ink.getPixel(0, 0))
                        assertEquals("The fixed black plate must be removed", android.graphics.Color.TRANSPARENT,
                            ink.getPixel(0, ink.height - 1))
                    } finally { ink.recycle() }
                    assertTrue(clock.left >= 0 && clock.right <= root.width)
                    assertTrue(clock.top >= 0 && clock.bottom <= root.height)
                }
            }
        } finally { activity.finish() }
    }

    @Test fun recentsLabelsDistinguishManagementAndProjectionAndRetainDebugName() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.applicationInfo.nonLocalizedLabel = "BuickPlay-debug"
        AppTaskIdentity.apply(activity, false)
        assertEquals("BuickPlay-debug · ${activity.getString(R.string.task_connection_management)}", org.robolectric.util.ReflectionHelpers.getField<android.app.ActivityManager.TaskDescription>(activity, "mTaskDescription").label)
        AppTaskIdentity.apply(activity, true)
        assertEquals("BuickPlay-debug · CarPlay", org.robolectric.util.ReflectionHelpers.getField<android.app.ActivityManager.TaskDescription>(activity, "mTaskDescription").label)
        assertEquals("BuickPlay-debug", activity.applicationInfo.loadLabel(activity.packageManager))
        activity.finish()
    }

    @Test fun vehicleNightOptionsRemainReadableAtLargeFonts() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup().visible()
        val activity = controller.get()
        try {
            AirPlayPersistence.saveCarPlayNightMode(activity, CarPlayNightMode.VEHICLE)
            for ((name, width, tag) in listOf(Triple("compact", 320, "zh-CN"), Triple("full", 1000, "zh-CN"), Triple("arabic", 320, "ar-SA"))) {
                val locale = Locale.forLanguageTag(tag)
                val config = Configuration(activity.resources.configuration).apply {
                    fontScale = 1.5f
                    screenWidthDp = width
                    setLocale(locale)
                    setLayoutDirection(locale)
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                method("displaySettings", LinearLayout::class.java).invoke(activity, root)
                capture(root, width, "$name-vehicle-night")
                val mode = views(root).filterIsInstance<android.widget.Button>().first {
                    it.text.startsWith(activity.getString(R.string.carplay_night_mode) + " · ")
                }
                assertTrue(mode.layout.height <= mode.height - mode.compoundPaddingTop - mode.compoundPaddingBottom)
            }
        } finally { controller.pause().stop().destroy() }
    }

    private fun method(name: String, type: Class<*>) = DiPlayActivity::class.java.getDeclaredMethod(name, type).apply { isAccessible = true }
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
    private fun capture(view: View, widthDp: Int, filename: String, heightDp: Int = 0) {
        val width = (widthDp * view.resources.displayMetrics.density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((heightDp * view.resources.displayMetrics.density).toInt(), if (heightDp == 0) View.MeasureSpec.UNSPECIFIED else View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, view.measuredHeight)
        assertTrue(view.height > 0)
        val bitmap = Bitmap.createBitmap(width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val directory = File("build/reports/next-driver-ui").apply { mkdirs() }
            FileOutputStream(File(directory, "$filename.png")).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
