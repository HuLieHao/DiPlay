package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
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

/** Always-visible clock: authenticated iPhone time when available, system time otherwise. */
internal object CarPlayClockSettings {
    val sizes = listOf(16, 20, 24)
    private fun prefs(context: Context) = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
    fun size(context: Context) = prefs(context).getInt("carplay_clock_size_sp", 20).coerceIn(16, 24)
    fun save(context: Context, size: Int = size(context)) {
        prefs(context).edit().putInt("carplay_clock_size_sp", size.coerceIn(16, 24)).apply()
    }

}

internal class CarPlayClockOverlay(
    context: Context,
    private val handler: Handler,
    private val systemTime: () -> Long = { com.shilapi.xcertplay.clock.IphoneClock.now()?.unixMillis ?: System.currentTimeMillis() },
) : TextView(context) {
    private val phoneRefresh = Runnable { refresh() }
    private val phoneTimeChanged: () -> Unit = { handler.removeCallbacks(phoneRefresh); handler.post(phoneRefresh) }
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var contentBounds = RectF()
    private var running = false
    private var registered = false
    private var observing = false
    private var visiblePage = false
    private var nightMode = false
    private var hiddenReason = "not_shown"
    private var sampledWallMs: Long? = null
    private val preferences = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
    private val preferenceChanged = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (visiblePage && key == "carplay_clock_size_sp") update(true, nightMode)
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = systemTime()
            sampledWallMs = now
            text = if (now < VALID_SYSTEM_TIME_MS) context.getString(R.string.settings_clock_unknown)
                else SimpleDateFormat("HH:mm", Locale.ROOT).apply {
                    com.shilapi.xcertplay.clock.IphoneClock.now()?.let {
                        timeZone = java.util.SimpleTimeZone(it.offsetMinutes * 60_000, "iPhone")
                    }
                }.format(Date(now))
            publishDiagnostic()
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
        setBackgroundColor(Color.TRANSPARENT)
    }
    fun update(visible: Boolean, night: Boolean, reason: String = "page_hidden") {
        hiddenReason = reason
        visiblePage = visible
        nightMode = night
        if (visible && !observing) {
            preferences.registerOnSharedPreferenceChangeListener(preferenceChanged)
            com.shilapi.xcertplay.clock.IphoneClock.addListener(phoneTimeChanged)
            observing = true
        } else if (!visible && observing) {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceChanged)
            com.shilapi.xcertplay.clock.IphoneClock.removeListener(phoneTimeChanged)
            observing = false
        }
        running = visible
        visibility = if (running) View.VISIBLE else View.GONE
        // Follow the same host day/night result sent to CarPlay; do not infer video colors.
        setTextColor(if (night) Color.WHITE else Color.BLACK)
        invalidate()
        placeInDock()
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
        publishDiagnostic()
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
        hiddenReason = "detached"
        publishDiagnostic()
        com.shilapi.xcertplay.clock.IphoneClock.removeListener(phoneTimeChanged)
        handler.removeCallbacks(phoneRefresh)
        super.onDetachedFromWindow()
    }
    /** Bounds are the visible video in this overlay's parent coordinates, excluding letterboxing. */
    fun setVideoContentBounds(bounds: RectF) {
        contentBounds = RectF(bounds)
        placeInDock()
        publishDiagnostic()
    }
    private fun placeInDock() {
        // Calibrated from a 1960 x 858 landscape CarPlay raster: sidebar 118, native
        // native glyph box measured26px high on-device; nominal Android font32 matches it.
        // The earlier31px estimate came from a scaled screenshot. Menu center59 above bottom.
        // No native widget bounds are exposed.
        // Scale with video height, cap the sidebar to the visible width for narrow layouts.
        val scale = contentBounds.height().coerceAtLeast(0f) / 858f
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
            leftMargin = contentBounds.left.toInt()
            topMargin = top.toInt()
        }
    }
    /** One transparent clock, colored from the same day/night result sent to CarPlay. */
    override fun onDraw(canvas: Canvas) {
        clockPaint.set(paint)
        clockPaint.textAlign = Paint.Align.CENTER
        clockPaint.clearShadowLayer()
        clockPaint.color = currentTextColor
        val baselineOffset = -(clockPaint.ascent() + clockPaint.descent()) / 2f
        canvas.drawText(text.toString(), width / 2f, height / 2f + baselineOffset, clockPaint)
    }
    private fun publishDiagnostic() {
        val params = layoutParams as? FrameLayout.LayoutParams
        val bounds = params?.let { "${it.leftMargin},${it.topMargin},${it.width},${it.height}" } ?: "unmeasured"
        val attached = isAttachedToWindow && hiddenReason != "detached"
        val drawable = running && visibility == View.VISIBLE && attached &&
            (params?.width ?: 0) > 0 && (params?.height ?: 0) > 0
        val reason = when {
            !running -> hiddenReason
            !attached -> "not_attached"
            !drawable -> "empty_bounds"
            else -> "visible"
        }
        latestDiagnostic = "CarPlay clock (latest observation in this process): visible=$drawable reason=$reason " +
            "requestedVisible=$visiblePage attached=$attached bounds=$bounds " +
            "videoBounds=${contentBounds.left},${contentBounds.top},${contentBounds.right},${contentBounds.bottom} " +
            "sampledWallMs=${sampledWallMs ?: "unknown"} clockValid=${sampledWallMs?.let { it >= VALID_SYSTEM_TIME_MS } ?: "unknown"} " +
            "source=${if (com.shilapi.xcertplay.clock.IphoneClock.now() != null) "iPhone" else "system"} " +
            "validity=validated_range night=$nightMode " +
            "textColor=${String.format(Locale.ROOT, "#%08X", currentTextColor)} textSizePx=$textSize " +
            "sizePreferenceSp=${CarPlayClockSettings.size(context)}"
    }
    companion object {
        @Volatile private var latestDiagnostic = "CarPlay clock: visible=false reason=not_created"
        fun diagnosticReport(): String = latestDiagnostic
        const val MINUTE_MS = 60_000L
        const val VALID_SYSTEM_TIME_MS = 1_577_836_800_000L // 2020; don't display a reset Android date as valid.
    }
}
