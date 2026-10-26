package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.media.AudioManager
import com.shilapi.xcertplay.media.NavigationPlaybackSnapshot

/** An unknown guidance route stays with firmware; never change music instead of guidance. */
internal fun carPlayVolumeStream(playback: NavigationPlaybackSnapshot, calling: Boolean): Int? = when {
    calling || playback.priorityVoiceActive -> null
    !playback.active -> AudioManager.STREAM_MUSIC
    else -> playback.legacyStreamType?.takeIf { it in 1..20 && it != AudioManager.STREAM_MUSIC }
}

internal object CarPlayVolumeSettings {
    fun enabled(context: Context) = context.getSharedPreferences("diplay_volume", 0).getBoolean("enabled", false)
    fun save(context: Context, enabled: Boolean) { context.getSharedPreferences("diplay_volume", 0).edit().putBoolean("enabled", enabled).apply() }
}

/** In-window only: no overlay permission, no touch target and no handler survives a hidden host. */
internal class CarPlayVolumeOverlay(context: Context, private val handler: Handler) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f
    private var navigation = false
    private val hide = Runnable { visibility = GONE }
    init {
        visibility = GONE
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = FrameLayout.LayoutParams(dp(WIDTH_DP).coerceAtMost(resources.displayMetrics.widthPixels), dp(HEIGHT_DP), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(TOP_DP) }
    }
    fun show(stream: Int, current: Int, maximum: Int) {
        navigation = stream != AudioManager.STREAM_MUSIC
        progress = if (maximum > 0) current.toFloat() / maximum else 0f
        visibility = VISIBLE
        invalidate()
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, VISIBLE_MS)
    }
    fun stop() { handler.removeCallbacks(hide); visibility = GONE }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        canvas.save(); canvas.scale(width.toFloat() / WIDTH_DP, d)
        paint.color = 0xDA202428.toInt()
        canvas.drawRoundRect(0f, 0f, WIDTH_DP.toFloat(), HEIGHT_DP.toFloat(), 16f, 16f, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f
        if (navigation) {
            val path = android.graphics.Path().apply { moveTo(27f, 13f); lineTo(17f, 35f); lineTo(27f, 29f); lineTo(37f, 35f); close() }
            canvas.drawPath(path, paint)
        } else {
            canvas.drawRect(16f, 21f, 22f, 29f, paint)
            val path = android.graphics.Path().apply { moveTo(22f, 21f); lineTo(30f, 15f); lineTo(30f, 35f); lineTo(22f, 29f) }
            canvas.drawPath(path, paint); canvas.drawArc(25f, 16f, 39f, 34f, -65f, 130f, false, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = 0xFF646A70.toInt(); canvas.drawRoundRect(52f, 22f, 260f, 28f, 3f, 3f, paint)
        paint.color = Color.WHITE; canvas.drawRoundRect(52f, 22f, 52f + 208f * progress.coerceIn(0f, 1f), 28f, 3f, 3f, paint)
        canvas.restore()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { const val WIDTH_DP = 280; const val HEIGHT_DP = 50; const val TOP_DP = 24; const val VISIBLE_MS = 1500L }
}

/** Same paired-key policy as the accessibility route; a failed initial write leaves firmware in charge. */
internal class CarPlayVolumeKeyRouter(
    private val stream: () -> Int?,
    private val minimum: (Int) -> Int,
    private val maximum: (Int) -> Int,
    private val read: (Int) -> Int,
    private val write: (Int, Int, Int) -> Unit,
    private val displayed: (Int, Int, Int) -> Unit,
) {
    private val keys = NavigationWheelKeyPolicy()
    private val streams = mutableMapOf<Triple<Int, Int, Long>, Int>()
    fun owns(key: Int, device: Int, downTime: Long) = keys.owns(key, device, downTime)
    fun onKey(action: Int, key: Int, device: Int, downTime: Long, repeat: Int): Boolean {
        val press = Triple(device, key, downTime)
        val handled = keys.onKey(
            action, key, device, downTime, repeat, stream() != null,
        ) adjust@{ delta ->
            try {
                val selected = stream() ?: return@adjust false
                val original = streams[press]
                if (original != null && selected != original) return@adjust false
                val max = maximum(selected)
                val target = navigationWheelTarget(read(selected), minimum(selected), max, delta) ?: return@adjust false
                if (stream() != selected) return@adjust false
                write(selected, target, 0) // Never request the OEM/system volume window for a handled key.
                (read(selected) == target).also {
                    if (it) {
                        streams[press] = selected
                        displayed(selected, target, max)
                    }
                }
            } catch (_: Exception) { false }
        }
        if (action == 1) streams.remove(press)
        return handled
    }
}

/** Only a resumed host registers. Accessibility routing still works with no host/overlay. */
internal object CarPlayVolumeDisplay {
    var show: ((Int, Int, Int) -> Unit)? = null
}
