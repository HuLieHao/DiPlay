package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

/** Connection observations only; buttons delegate to the existing host connection actions. */
internal class ConnectionWaitingView(
    context: Context,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRecovery: () -> Unit,
) : LinearLayout(context) {
    val stage = label(22f, true).apply {
        text = context.getString(R.string.getting_carplay_ready)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val currentTime = label(20f, true).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.END
    }
    val totalTime = label(16f)
    val gestureHint = label(13f)
    val bodyScroll = ScrollView(context).apply { isFillViewport = false }
    val stepsCard = column()
    val networkCard = column()
    val footer = LinearLayout(context)
    val retryButton = action(R.string.retry_carplay_connection, onRetry).apply { visibility = View.GONE }
    val recoveryButton = action(R.string.reset_carplay_wi_fi, onRecovery).apply { visibility = View.GONE }
    val backButton = action(R.string.back_to_diplay, onBack)
    val detailsToggle = action(R.string.waiting_details_show) { toggleDetails() }
    val detailsText = label(14f)

    private val brand = label(18f, true).apply { text = context.getString(R.string.app_name) }
    private val icon = ImageView(context).apply { setImageResource(R.drawable.ic_carplay) }
    private val headline = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val header = column()
    private val sections = LinearLayout(context)
    private val stepHeading = label(16f, true).apply { text = context.getString(R.string.waiting_steps_title) }
    private val networkHeading = label(16f, true).apply { text = context.getString(R.string.waiting_network_title) }
    private val hotspot = label(16f)
    private val latestProgress = label(14f)
    private val tcp = label(16f)
    private val internet = label(14f).apply { text = context.getString(R.string.waiting_internet_unknown) }
    private val error = label(14f).apply {
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = View.GONE
    }
    val help = label(14f).apply { visibility = View.GONE }
    val instructions = label(13f)
    private val detailsGroup = column().apply { visibility = View.GONE }
    private var palette = DiPlayPalette.LIGHT
    private var progress: ConnectionWaitingProgress? = null
    private var wireless = true
    private var expanded = false
    private var wide: Boolean? = null
    private var compact: Boolean? = null
    private val rows = WaitingPhase.entries.mapIndexed { index, phase -> PhaseRow(index, phase) }

    init {
        orientation = VERTICAL
        isClickable = true
        setPadding(dp(PADDING), dp(PADDING), dp(PADDING), dp(PADDING))
        val brandRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        brandRow.addView(icon, LayoutParams(dp(ICON), dp(ICON)))
        brandRow.addView(brand, LayoutParams(-2, -2).apply { marginStart = dp(GAP) })
        header.addView(brandRow)
        headline.addView(stage, LayoutParams(0, -2, 1f).apply { marginEnd = dp(GAP) })
        headline.addView(currentTime, LayoutParams(-2, -2))
        header.addView(headline, LayoutParams(-1, -2).apply { topMargin = dp(GAP) })
        header.addView(totalTime, LayoutParams(-1, -2).apply { topMargin = dp(SMALL_GAP) })
        addView(header, LayoutParams(-1, -2).apply { bottomMargin = dp(GAP) })

        stepsCard.addView(stepHeading, LayoutParams(-1, -2).apply { bottomMargin = dp(SMALL_GAP) })
        rows.forEach { stepsCard.addView(it.view, LayoutParams(-1, -2)) }
        for (view in listOf(networkHeading, hotspot, tcp, latestProgress, internet, error, help, detailsToggle)) {
            networkCard.addView(view, LayoutParams(-1, -2).apply { bottomMargin = dp(SMALL_GAP) })
        }
        detailsGroup.addView(detailsText)
        detailsGroup.addView(instructions, LayoutParams(-1, -2).apply { topMargin = dp(GAP) })
        detailsGroup.addView(gestureHint, LayoutParams(-1, -2).apply { topMargin = dp(SMALL_GAP) })
        networkCard.addView(detailsGroup)
        sections.addView(stepsCard)
        sections.addView(networkCard)
        bodyScroll.addView(sections, FrameLayout.LayoutParams(-1, -2))
        addView(bodyScroll, LayoutParams(-1, 0, 1f))
        for (button in listOf(recoveryButton, retryButton, backButton)) footer.addView(button)
        addView(footer, LayoutParams(-1, -2).apply { topMargin = dp(GAP) })

        applyPalette(palette)
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            setPadding(dp(PADDING) + safe.left, dp(PADDING) + safe.top,
                dp(PADDING) + safe.right, dp(PADDING) + safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(this)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val height = MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
        val stageLines = if (width < dp(WIDE_MIN_WIDTH)) 4 else 2
        if (stage.maxLines != stageLines) stage.maxLines = stageLines
        if (currentTime.maxWidth != width / 2) currentTime.maxWidth = width / 2
        val landscape = width >= dp(WIDE_MIN_WIDTH) && width > height
        if (wide != landscape) {
            wide = landscape
            sections.orientation = if (landscape) HORIZONTAL else VERTICAL
            stepsCard.layoutParams = if (landscape) LayoutParams(0, -2, 1.2f) else LayoutParams(-1, -2)
            networkCard.layoutParams = (if (landscape) LayoutParams(0, -2, 1f) else LayoutParams(-1, -2)).apply {
                if (landscape) marginStart = dp(GAP) else topMargin = dp(GAP)
            }
        }
        val short = height < dp(COMPACT_HEIGHT)
        val minimal = height < dp(MINIMAL_HEIGHT)
        val hideBrand = minimal || (landscape && short)
        (brand.parent as View).visibility = if (hideBrand) View.GONE else View.VISIBLE
        totalTime.visibility = if (minimal) View.GONE else View.VISIBLE
        val headlineParams = headline.layoutParams as LayoutParams
        val headlineGap = if (hideBrand) 0 else dp(GAP)
        if (headlineParams.topMargin != headlineGap) {
            headlineParams.topMargin = headlineGap
            headline.layoutParams = headlineParams
        }
        if (compact != short) {
            compact = short
            stage.textSize = if (short) 18f else 22f
            currentTime.textSize = if (short) 16f else 20f
            totalTime.textSize = if (short) 14f else 16f
            rows.forEach { row ->
                row.view.minimumHeight = dp(if (short) COMPACT_ROW_HEIGHT else ROW_HEIGHT)
                val vertical = dp(if (short) COMPACT_ROW_PADDING else SMALL_GAP)
                row.view.setPadding(dp(SMALL_GAP), vertical, dp(SMALL_GAP), vertical)
            }
        }
        // Reserve the action area before the scrolling content, including at a large font scale.
        val buttons = listOf(recoveryButton, retryButton, backButton).filter { it.visibility != View.GONE }
        val stacked = width < dp(buttons.size * MIN_BUTTON_WIDTH)
        footer.orientation = if (stacked) VERTICAL else HORIZONTAL
        val buttonHeight = dp(maxOf(BUTTON_HEIGHT, (18 * resources.configuration.fontScale * 2 + 12).roundToInt()))
        buttons.forEachIndexed { index, button ->
            val next = (if (stacked) LayoutParams(-1, buttonHeight) else LayoutParams(0, buttonHeight, 1f)).apply {
                if (index > 0) {
                    if (stacked) topMargin = dp(SMALL_GAP) else marginStart = dp(GAP)
                }
            }
            val old = button.layoutParams as? LayoutParams
            if (old == null || old.width != next.width || old.height != next.height || old.weight != next.weight ||
                old.topMargin != next.topMargin || old.marginStart != next.marginStart) button.layoutParams = next
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun update(progress: ConnectionWaitingProgress, wireless: Boolean) {
        this.progress = progress
        this.wireless = wireless
        val state = progress.snapshot()
        currentTime.show(context.getString(R.string.waiting_current_time, state.stageSeconds))
        totalTime.show(context.getString(R.string.waiting_total_attempt, state.totalSeconds, state.attempt))
        if (state.retrySeconds != null) currentTime.show(context.getString(R.string.waiting_retry_countdown, state.retrySeconds))
        if (state.stopped) currentTime.show(context.getString(R.string.waiting_stopped))
        rows.zip(state.phases).forEach { (row, phase) -> row.bind(phase) }
        hotspot.show(context.getString(if (!wireless) R.string.waiting_wired_network else
            if (state.hotspot != null) R.string.waiting_hotspot_ready else R.string.waiting_hotspot_unknown))
        latestProgress.show(context.getString(R.string.waiting_since_progress, state.sinceProgressSeconds))
        tcp.show(context.getString(if (state.tcpAccepted) R.string.waiting_tcp_connected else R.string.waiting_tcp_unconfirmed))
        tcp.setTextColor(if (state.tcpAccepted) palette.success else palette.secondaryText)
        error.visibility = if (state.failure == null) View.GONE else View.VISIBLE
        state.failure?.let { error.show(context.getString(R.string.waiting_last_error, it)) }
        if (expanded) detailsText.show(progress.text(resources, wireless))
        instructions.show(context.getString(if (wireless) R.string.keep_your_iphone_nearby_with_bluetooth_and_wi_fi_on_allow
            else R.string.use_a_usb_data_cable_and_unlock_your_iphone_allow_trust_an))
    }

    fun applyPalette(colors: DiPlayPalette) {
        palette = colors
        setBackgroundColor(colors.background)
        for (card in listOf(stepsCard, networkCard)) {
            card.setPadding(dp(CARD_PADDING), dp(CARD_PADDING), dp(CARD_PADDING), dp(CARD_PADDING))
            card.background = shape(colors.surface, colors.outline)
        }
        for (text in listOf(brand, stage, stepHeading, networkHeading, hotspot)) text.setTextColor(colors.primaryText)
        currentTime.setTextColor(colors.accent)
        for (text in listOf(totalTime, latestProgress, internet, help, instructions, gestureHint, detailsText)) text.setTextColor(colors.secondaryText)
        error.setTextColor(colors.warning)
        backButton.style(DiPlayButtonKind.SECONDARY, colors, overlay = false)
        retryButton.style(DiPlayButtonKind.PRIMARY, colors, overlay = false)
        recoveryButton.style(DiPlayButtonKind.PRIMARY, colors, overlay = false)
        detailsToggle.style(DiPlayButtonKind.SECONDARY, colors, overlay = false)
        rows.forEach { it.last = null }
        progress?.let { update(it, wireless) }
    }

    private fun toggleDetails() {
        expanded = !expanded
        detailsGroup.visibility = if (expanded) View.VISIBLE else View.GONE
        detailsToggle.text = context.getString(if (expanded) R.string.waiting_details_hide else R.string.waiting_details_show)
        progress?.let { update(it, wireless) }
    }

    private inner class PhaseRow(index: Int, phase: WaitingPhase) {
        val view = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ROW_HEIGHT)
            setPadding(dp(SMALL_GAP), dp(SMALL_GAP), dp(SMALL_GAP), dp(SMALL_GAP))
        }
        private val mark = label(18f, true).apply { gravity = Gravity.CENTER; text = (index + 1).toString() }
        private val title = label(16f, true).apply { text = context.getString(phase.title) }
        private val duration = label(14f).apply { gravity = Gravity.END }
        var last: WaitingPhaseState? = null

        init {
            view.addView(mark, LayoutParams(dp(ICON), -2).apply { marginEnd = dp(SMALL_GAP) })
            view.addView(title, LayoutParams(0, -2, 1f).apply { marginEnd = dp(SMALL_GAP) })
            view.addView(duration, LayoutParams(-2, -2))
        }

        fun bind(phase: WaitingPhaseProgress) {
            val state = phase.state
            if (last != state) {
                last = state
                val color = when (state) {
                    WaitingPhaseState.DONE -> palette.success
                    WaitingPhaseState.CURRENT -> palette.accent
                    WaitingPhaseState.INTERRUPTED -> palette.warning
                    WaitingPhaseState.PENDING -> palette.secondaryText
                }
                mark.show(when (state) {
                    WaitingPhaseState.DONE -> "✓"
                    WaitingPhaseState.CURRENT -> "●"
                    WaitingPhaseState.INTERRUPTED -> "!"
                    WaitingPhaseState.PENDING -> (phase.phase.ordinal + 1).toString()
                })
                mark.setTextColor(color)
                title.setTextColor(if (state == WaitingPhaseState.PENDING) palette.secondaryText else palette.primaryText)
                duration.setTextColor(color)
                view.background = if (state == WaitingPhaseState.CURRENT) shape(palette.railSelected, palette.railSelectedOutline)
                    else shape(Color.TRANSPARENT, Color.TRANSPARENT)
            }
            duration.show(when {
                state == WaitingPhaseState.PENDING -> context.getString(R.string.waiting_pending)
                phase.elapsedMillis == null -> context.getString(R.string.waiting_done)
                phase.elapsedMillis < 1_000 -> context.getString(R.string.waiting_under_second)
                else -> context.getString(R.string.waiting_duration, phase.elapsedMillis / 1_000.0)
            })
            val status = context.getString(when (state) {
                WaitingPhaseState.DONE -> R.string.waiting_done
                WaitingPhaseState.CURRENT -> R.string.waiting_in_progress
                WaitingPhaseState.INTERRUPTED -> R.string.waiting_interrupted
                WaitingPhaseState.PENDING -> R.string.waiting_pending
            })
            view.contentDescription = "${title.text} · $status · ${duration.text}"
        }
    }

    private fun label(size: Float, bold: Boolean = false) = TextView(context).apply {
        textSize = size
        gravity = Gravity.START
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun column() = LinearLayout(context).apply { orientation = VERTICAL }
    private fun action(title: Int, click: () -> Unit) = DiPlayButton(context).apply {
        text = context.getString(title)
        textSize = 16f
        minHeight = dp(BUTTON_HEIGHT)
        setOnClickListener { click() }
    }
    private fun TextView.show(value: CharSequence) { if (text.toString() != value.toString()) text = value }
    private fun shape(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(CARD_RADIUS).toFloat()
        setStroke(dp(1), stroke)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val PADDING = 16
        const val CARD_PADDING = 12
        const val CARD_RADIUS = 12
        const val GAP = 12
        const val SMALL_GAP = 6
        const val ICON = 28
        const val ROW_HEIGHT = 48
        const val COMPACT_ROW_HEIGHT = 28
        const val COMPACT_ROW_PADDING = 2
        const val BUTTON_HEIGHT = 52
        const val MIN_BUTTON_WIDTH = 120
        const val WIDE_MIN_WIDTH = 560
        const val COMPACT_HEIGHT = 400
        const val MINIMAL_HEIGHT = 260
    }
}
