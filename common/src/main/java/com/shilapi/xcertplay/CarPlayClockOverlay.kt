package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.text.DateFormat
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
                else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now))
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
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(PADDING_DP), dp(PADDING_DP / 2), dp(PADDING_DP), dp(PADDING_DP / 2))
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
        textSize = CarPlayClockSettings.size(context).toFloat()
        setTextColor(if (night) Color.rgb(232, 234, 238) else Color.rgb(24, 28, 32))
        background = GradientDrawable().apply {
            setColor(if (night) 0xC0202428.toInt() else 0xDDEDF0F4.toInt())
            cornerRadius = dp(PADDING_DP).toFloat()
        }
        // Fixed physical edges: CarPlay does not mirror with the Android app locale. Leave space for
        // the side dock and bottom home/navigation controls; physical acceptance still depends on iOS.
        layoutParams = FrameLayout.LayoutParams(-2, -2,
            Gravity.BOTTOM or if (position == "left") Gravity.LEFT else Gravity.RIGHT).apply {
            leftMargin = dp(SIDE_CLEARANCE_DP)
            rightMargin = dp(SIDE_CLEARANCE_DP)
            bottomMargin = dp(BOTTOM_CLEARANCE_DP)
        }
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
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        const val SIDE_CLEARANCE_DP = 96
        const val BOTTOM_CLEARANCE_DP = 72
        const val PADDING_DP = 8
        const val MINUTE_MS = 60_000L
        const val VALID_SYSTEM_TIME_MS = 1_577_836_800_000L // 2020; don't display a reset Android date as valid.
    }
}
