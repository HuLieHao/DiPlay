package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ScrollView
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.res.Configuration
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.network.WirelessStartupFailure
import java.time.Duration
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

/** The waiting screen must distinguish authentication, network waiting and a stopped retry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], qualifiers = "zh-rCN", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ConnectionWaitingUiTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var root: View

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.applicationInfo.flags = activity.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.setField(activity, "wirelessEnabled", true)
        ReflectionHelpers.setField(activity, "isActivityStarted", true)
        root = ReflectionHelpers.callInstanceMethod(activity, "buildContentView")
    }

    @After fun cleanup() {
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            ReflectionHelpers.getField<ExecutorService>(activity, name).shutdownNow()
        }
        CarPlayBackgroundSession.clear()
    }

    @Test fun authenticationIsNamedInsteadOfGenericGettingReady() {
        report(CarPlayStatus.RunningWireless)
        assertTrue(text().contains("iAP2"))
        assertTrue(text().contains("认证"))
    }

    @Test fun hotspotAddressAndBandAreShownWithoutClaimingPhoneInternetWorks() {
        report(CarPlayStatus.HotspotReady("BuickPlay", "2.4GHz", 6, "", "192.168.43.1", "manual"))
        assertFalse(text().contains("192.168.43.1"))
        assertFalse(text().contains("2.4GHz"))
        assertTrue(text().contains("手机互联网：未检测"))
        screen().detailsToggle.performClick()
        assertTrue(text().contains("2.4GHz"))
        assertTrue(text().contains("192.168.43.1"))
    }

    @Test fun handshakeSubstageIsVisibleAndDuplicateCallbacksDoNotResetTheProgressClock() {
        report(CarPlayStatus.EstablishingAirPlay)
        report(CarPlayStatus.ConnectionProgress(ConnectionMilestone.AIRPLAY_PAIR_VERIFY))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        report(CarPlayStatus.ConnectionProgress(ConnectionMilestone.AIRPLAY_PAIR_VERIFY))
        assertTrue(text(), text().contains("正在处理 CarPlay 配对验证"))
        assertTrue(text(), text().contains("距最近进展：4 秒"))
        screen().detailsToggle.performClick()
        assertTrue(text(), text().contains("本次事件记录"))
        assertTrue(text(), text().contains("iPhone 确认弹窗：无法检测"))
        assertEquals(0, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    @Test fun elapsedTimeAdvancesAndCompletedStageKeepsItsOwnDuration() {
        report(CarPlayStatus.ConnectingBluetooth)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        report(CarPlayStatus.RunningWireless)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(text(), text().contains("总耗时：5 秒"))
        assertTrue(text(), text().contains("当前阶段：2 秒"))
        screen().detailsToggle.performClick()
        assertTrue(text(), text().contains("蓝牙连接：3 秒"))
    }

    @Test fun nonRetryableFailureRemainsVisibleAndDoesNotPretendToBeRetrying() {
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", activity)
        report(CarPlayStatus.Failed("Diagnostic permission denied", startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION))
        assertTrue(text(), text().contains("自动重试已停止"))
        assertTrue(text(), text().contains("Diagnostic permission denied"))
        assertTrue(screen().stage.text.toString().contains(activity.getString(R.string.hotspot_network_not_ready)))
        assertEquals(View.VISIBLE, screen().help.visibility)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    @Test fun requiredWifiResetStopsWaitingInsteadOfCountingAnInactiveAttempt() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        report(CarPlayStatus.Failed("Previous Wi-Fi Direct connection needs a reset", wifiResetRequired = true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertTrue(text(), text().contains("自动重试已停止"))
        assertTrue(text(), text().contains("总耗时：3 秒"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    @Test fun staleStatusCannotReplaceTheCurrentStage() {
        report(CarPlayStatus.RunningWireless)
        val before = text()
        report(CarPlayStatus.StartingHotspot, generation = -1)
        assertEquals(before, text())
    }

    @Test fun waitingTimerStopsUpdatingWhenTheCarPlayPictureIsShown() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        ReflectionHelpers.getField<MutableSet<Int>>(activity, "activeScreenStreamTypes").add(110)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "updateDebugOverlays")
        val before = screen().totalTime.text.toString()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(View.GONE, ReflectionHelpers.getField<View>(activity, "connectionPanel").visibility)
        assertEquals(before, screen().totalTime.text.toString())
    }

    @Test fun landscapeSeparatesStepsFromNetwork() {
        layoutScreen(1024, 600)
        val steps = descendants(root).filterIsInstance<TextView>().firstOrNull { it.text == "连接步骤" }
        val network = descendants(root).filterIsInstance<TextView>().firstOrNull { it.text == "网络状态" }
        assertNotNull("The connection sequence must have its own section", steps)
        assertNotNull("Network facts must have their own section", network)
        val a = location(steps!!); val b = location(network!!)
        assertTrue("Landscape sections must sit side by side", b[0] > a[0])
    }

    @Test fun addressesAreExpandedOnDemandWithoutChangingTheController() {
        report(CarPlayStatus.HotspotReady("BuickPlay", "2.4GHz", 6, "", "192.168.43.1", "manual"))
        assertFalse("The overview must not be dominated by addresses", text().contains("192.168.43.1"))
        val details = descendants(root).filterIsInstance<TextView>().single { it.text == "连接详情" }
        details.performClick()
        assertTrue(text(), text().contains("192.168.43.1"))
        assertEquals(0, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    @Test fun returnActionRemainsVisibleOnShortLandscapeScreens() {
        layoutScreen(600, 360)
        val back = descendants(root).filterIsInstance<TextView>().single { it.text == "返回 BuickPlay" }
        val position = location(back)
        assertTrue("Return action must be visible without scrolling", position[1] >= 0 && position[1] + back.height <= 360)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [29], qualifiers = "zh-rCN-w815dp-h356dp-mdpi")
    fun shortLandscapeShowsAllStepsWithoutScrollingAtNormalFontSize() {
        report(CarPlayStatus.EstablishingAirPlay)
        val density = activity.resources.displayMetrics.density
        layoutScreen((815 * density).toInt(), (356 * density).toInt())
        val lastStep = descendants(root).single { it.contentDescription?.startsWith("显示画面 ·") == true }
        val stepPosition = location(lastStep)
        val bodyPosition = location(screen().bodyScroll)
        assertTrue("The complete sequence must fit on a phone in landscape: last=${stepPosition[1]}+${lastStep.height}, " +
            "body=${bodyPosition[1]}+${screen().bodyScroll.height}, font=${activity.resources.configuration.fontScale}",
            stepPosition[1] + lastStep.height <= bodyPosition[1] + screen().bodyScroll.height)
    }

    private fun location(view: View): IntArray {
        var current = view
        var x = 0; var y = 0
        while (true) {
            x += current.left; y += current.top
            val parent = current.parent as? View ?: break
            x -= parent.scrollX; y -= parent.scrollY
            current = parent
        }
        return intArrayOf(x, y)
    }

    private fun layoutScreen(width: Int, height: Int) {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            descendants(root).forEach { it.forceLayout() }
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
        }
    }

    private fun screen() = ReflectionHelpers.getField<ConnectionWaitingView>(activity, "waitingScreen")

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [29], qualifiers = "zh-rCN-w600dp-h360dp-mdpi")
    fun compactLargeFontWaitingScreenKeepsDetailsReadable() = screenshot("compact-zh", 600, 360)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [29], qualifiers = "zh-rCN-w1024dp-h600dp-mdpi")
    fun headUnitLargeFontWaitingScreenKeepsDetailsReadable() = screenshot("headunit-zh", 1024, 600)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [29], qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
    fun portraitWaitingScreenStacksSectionsAboveFixedActions() = screenshot("portrait-zh", 400, 800)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [29], qualifiers = "zh-rCN-w1024dp-h600dp-mdpi")
    fun darkWaitingScreenRetainsReadableStageAndNetworkColors() {
        ReflectionHelpers.setField(activity, "appNight", true)
        ReflectionHelpers.setField(activity, "darkMode", false)
        ReflectionHelpers.getField<() -> Unit>(activity, "paintWaitingScreen").invoke()
        screenshot("headunit-dark", 1024, 600)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = [29], qualifiers = "ar-ldrtl-w1024dp-h600dp-mdpi")
    fun arabicLargeFontWaitingScreenKeepsDetailsReadable() = screenshot("headunit-ar", 1024, 600)

    private fun screenshot(name: String, width: Int, height: Int) {
        val configuration = Configuration(activity.resources.configuration).apply { fontScale = 1.3f }
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
        root.layoutDirection = configuration.layoutDirection
        report(CarPlayStatus.StartingHotspot)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        report(CarPlayStatus.HotspotReady("BuickPlay", "2.4GHz", 6, "", "192.168.43.1", "manual"))
        report(CarPlayStatus.ConnectingBluetooth)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        report(CarPlayStatus.RunningWireless)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        report(CarPlayStatus.WaitingForAirPlay)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        report(CarPlayStatus.EstablishingAirPlay)
        report(CarPlayStatus.ConnectionProgress(ConnectionMilestone.AIRPLAY_PAIR_VERIFY))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
        layoutScreen(width, height)
        val screen = screen()
        assertTrue("The current connection detail must be readable at a large font size",
            (0 until screen.stage.layout.lineCount).all { screen.stage.layout.getEllipsisCount(it) == 0 })
        val viewport = screen.bodyScroll
        assertTrue(viewport.getChildAt(0).top >= 0)
        assertEquals(1f, viewport.getChildAt(0).scaleY)
        assertTrue(screen.footer.bottom <= height - screen.paddingBottom)
        assertTrue(screen.bodyScroll.bottom <= screen.footer.top)
        if (configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL && width >= 600) {
            assertTrue("RTL sections must follow the reading direction", screen.networkCard.left < screen.stepsCard.left)
        }
        if (width < 560) assertTrue(screen.networkCard.top >= screen.stepsCard.bottom)
        val directory = File("build/reports/connection-waiting").apply { mkdirs() }
        fun capture(suffix: String) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File(directory, "$name-$suffix.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
        capture("top")
        screen.detailsToggle.performClick()
        layoutScreen(width, height)
        val details = screen.detailsText
        assertNotNull(details.layout)
        assertTrue(details.layout.lineCount >= 6)
        assertEquals(details.text.length, details.layout.getLineEnd(details.layout.lineCount - 1))
        viewport.scrollTo(0, viewport.getChildAt(0).height)
        assertTrue(viewport.scrollY + viewport.height >= viewport.getChildAt(0).height)
        assertTrue(screen.footer.bottom <= height - screen.paddingBottom)
        capture("bottom")
    }

    private fun report(status: CarPlayStatus, generation: Int = 0) {
        ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(activity, "createStatusReporter",
            from(Int::class.javaPrimitiveType, generation))(status)
    }

    private fun text(): String = descendants(root).filterIsInstance<TextView>()
        .filter { view ->
            generateSequence(view as View?) { it.parent as? View }.all { it.visibility == View.VISIBLE }
        }.joinToString("\n") { it.text }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
