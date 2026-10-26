package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.RectF
import android.util.TypedValue
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

/** Local head-unit system clock. Never represents an iPhone clock or changes the video viewport. */
internal object CarPlayClockSettings {
    val positions = listOf("off", "left", "right")
    val sizes = listOf(16, 20, 24)
    private fun prefs(context: Context) = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
    fun position(context: Context) = prefs(context).getString("carplay_clock_position", "off")
        ?.takeIf { it in positions } ?: "off"
    fun size(context: Context) = prefs(context).getInt("carplay_clock_size_sp", 20).coerceIn(16, 24)
    fun save(context: Context, position: String = position(context), size: Int = size(context)) {
        prefs(context).edit().putString("carplay_clock_position", position.takeIf { it in positions } ?: "off")
            .putInt("carplay_clock_size_sp", size.coerceIn(16, 24)).apply()
    }
}

internal class CarPlayClockOverlay(
    context: Context,
    private val handler: Handler,
    private val systemTime: () -> Long = System::currentTimeMillis,
) : TextView(context) {
    private var contentBounds = RectF()
    private var running = false
    private var registered = false
    private var observing = false
    private var visiblePage = false
    private var nightMode = false
    private val preferences = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
    private val preferenceChanged = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (visiblePage && (key == "carplay_clock_position" || key == "carplay_clock_size_sp")) update(true, nightMode)
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = systemTime()
            text = if (now < VALID_SYSTEM_TIME_MS) context.getString(R.string.settings_clock_unknown)
                else SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(now))
            handler.postDelayed(this, MINUTE_MS - Math.floorMod(now, MINUTE_MS))
        }
    }
    private val timeChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refresh() }
    }
    init {
        visibility = View.GONE
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = null
        includeFontPadding = false
        setPadding(0, 0, 0, 0)
    }
    fun update(visible: Boolean, night: Boolean) {
        visiblePage = visible
        nightMode = night
        if (visible && !observing) {
            preferences.registerOnSharedPreferenceChangeListener(preferenceChanged)
            observing = true
        } else if (!visible && observing) {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceChanged)
            observing = false
        }
        val position = CarPlayClockSettings.position(context)
        running = visible && position != "off"
        visibility = if (running) View.VISIBLE else View.GONE
        // CarPlay can change its sidebar independently of the host day/night setting.
        setTextColor(Color.YELLOW)
        placeInDock(position)
        if (running && isAttachedToWindow && !registered) {
            context.registerReceiver(timeChanged, IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
            })
            registered = true
        }
        if (!running) unregister()
        refresh()
    }
    private fun refresh() {
        handler.removeCallbacks(tick)
        if (running) tick.run()
    }
    private fun unregister() {
        if (registered) context.unregisterReceiver(timeChanged)
        registered = false
    }
    override fun onDetachedFromWindow() {
        running = false
        visiblePage = false
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceChanged)
        observing = false
        handler.removeCallbacks(tick)
        unregister()
        super.onDetachedFromWindow()
    }
    /** Bounds are the visible video in this overlay's parent coordinates, excluding letterboxing. */
    fun setVideoContentBounds(bounds: RectF) {
        contentBounds = RectF(bounds)
        placeInDock(CarPlayClockSettings.position(context))
    }
    private fun placeInDock(position: String) {
        // Calibrated from a 1960 x 858 landscape CarPlay raster: sidebar 118, native
        // native glyph box measured26px high on-device; nominal Android font32 matches it.
        // The earlier31px estimate came from a scaled screenshot. Menu center59 above bottom.
        // No native widget bounds are exposed.
        // Scale with video height, cap the sidebar to the visible width for narrow layouts.
        val scale = contentBounds.height().coerceAtLeast(0f) / 858f
        // Bright yellow with a dark halo stays distinct from both light and dark sidebars.
        setShadowLayer(3f * scale, 0f, 0f, Color.BLACK)
        val dockWidth = (118f * scale).coerceAtMost(contentBounds.width().coerceAtLeast(0f))
        val clockHeight = (56f * scale).coerceAtMost(contentBounds.height().coerceAtLeast(0f))
        val top = (contentBounds.bottom - 146f * scale)
            .coerceAtLeast(contentBounds.top)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 32f * scale * CarPlayClockSettings.size(context) / 20f)
        // Preserve all five HH:mm characters even for the largest preference or narrow video.
        val timeWidth = paint.measureText("00:00")
        val availableWidth = dockWidth.toInt().toFloat()
        if (timeWidth > availableWidth && timeWidth > 0f) {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize * availableWidth / timeWidth)
        }
        layoutParams = FrameLayout.LayoutParams(dockWidth.toInt(), clockHeight.toInt(), Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = (if (position == "right") contentBounds.right - dockWidth else contentBounds.left).toInt()
            topMargin = top.toInt()
        }
    }
    companion object {
        const val MINUTE_MS = 60_000L
        const val VALID_SYSTEM_TIME_MS = 1_577_836_800_000L // 2020; don't display a reset Android date as valid.
    }
}
