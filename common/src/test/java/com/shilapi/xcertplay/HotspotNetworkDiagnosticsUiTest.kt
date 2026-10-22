package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HotspotNetworkDiagnosticsUiTest {
    @Test fun diagnosticCaptureAndExportAreAccessibleAtLargeFontInCompactFullAndRtl() {
        for ((name, qualifiers) in listOf(
            "compact-zh" to "zh-rCN-w320dp-h900dp-mdpi",
            "full-zh" to "zh-rCN-w1024dp-h900dp-mdpi",
            "compact-ar" to "ar-rSA-w320dp-h900dp-mdpi",
        )) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            RuntimeEnvironment.setFontScale(1.3f)
            val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
                Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java).putExtra("page", "settings"))
                .setup()
            val activity = controller.get()
            try {
                ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.DIAGNOSTICS)
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val width = activity.resources.displayMetrics.widthPixels
                val height = activity.resources.displayMetrics.heightPixels
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                val texts = descendants(root).filterIsInstance<TextView>().toList()
                val collect = texts.single { it.text == activity.getString(R.string.settings_hotspot_capture) }
                assertTrue(collect.isEnabled)
                assertTrue(texts.any { it.text == activity.getString(R.string.save_diagnostic_report) })
                assertTrue(texts.any { it.text == activity.getString(R.string.settings_hotspot_capture_description) })
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    root.draw(Canvas(bitmap))
                    val folder = File("build/reports/hotspot-network-ui").apply { mkdirs() }
                    File(folder, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            } finally { controller.pause().stop().destroy() }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
