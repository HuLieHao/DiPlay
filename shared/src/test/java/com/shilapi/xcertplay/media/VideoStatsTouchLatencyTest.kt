package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class VideoStatsTouchLatencyTest {
    @Test fun aTouchSendThatFinishesAfterStreamReplacementIsIgnored() {
        var now = 1_000_000_000L
        VideoStats(nanoTime = { now })
        val sendingGeneration = TouchLatencyProbe.currentGeneration()
        val current = VideoStats(nanoTime = { now })
        TouchLatencyProbe.onTouchSent(now, 9_000_000, sendingGeneration)
        now += 40_000_000
        current.onReceived(100)
        now += 5_000_000_000
        val report = current.logIfDue()!!
        assertTrue(report, report.contains("touch2frame avg=-1ms max=0ms n=0 touchSendMax=0ms"))
    }

    @Test fun anUnmatchedTouchIsReportedEvenWhenNoFrameArrives() {
        var now = 1_000_000_000L
        val stats = VideoStats(nanoTime = { now })
        TouchLatencyProbe.onTouchSent(now, 1_000_000)
        now += 5_000_000_000
        val report = stats.logIfDue()!!
        assertTrue(report, report.contains("touch2frame avg=-1ms max=0ms n=0"))
        assertTrue(report, report.contains("touchExpired=1"))
        now += 5_000_000_000
        assertNull(stats.logIfDue())
    }

    @Test fun newMainVideoStreamDoesNotUseThePreviousConnectionsPendingTouch() {
        var now = 1_000_000_000L
        VideoStats(nanoTime = { now })
        TouchLatencyProbe.onTouchSent(now, 3_000_000)
        now += 188_497_000_000L
        val current = VideoStats(nanoTime = { now })
        current.onReceived(100)
        now += 5_000_000_000L
        val report = current.logIfDue()!!
        assertTrue(report, report.contains("touch2frame avg=-1ms max=0ms n=0 touchSendMax=0ms"))
    }

    @Test fun unmatchedTouchExpiresWithoutBecomingAMinutesLongLatency() {
        var now = 1_000_000_000L
        val stats = VideoStats(nanoTime = { now })
        TouchLatencyProbe.onTouchSent(now, 1_000_000)
        now += 188_497_000_000L
        stats.onReceived(100)
        val report = stats.logIfDue()!!
        assertTrue(report, report.contains("touch2frame avg=-1ms max=0ms n=0"))
        assertTrue(report, report.contains("touchExpired=1"))
    }

    @Test fun oldAndAuxiliaryStreamsCannotConsumeTheCurrentStreamsTouchSample() {
        var now = 1_000_000_000L
        val old = VideoStats(nanoTime = { now })
        val current = VideoStats(nanoTime = { now })
        val auxiliary = VideoStats(" stream=111", nanoTime = { now })
        now += 5_000_000_000L
        TouchLatencyProbe.onTouchSent(now, 6_000_000)
        auxiliary.onReceived(100)
        auxiliary.logIfDue()
        now += 40_000_000L
        old.onReceived(100)
        old.logIfDue()
        current.onReceived(100)
        val report = current.logIfDue()!!
        assertTrue(report, report.contains("touch2frame avg=40ms max=40ms n=1 touchSendMax=6ms"))
    }

    @Test fun validTouchStillReportsItsActualLatency() {
        var now = 1_000_000_000L
        val stats = VideoStats(nanoTime = { now })
        TouchLatencyProbe.onTouchSent(now, 2_000_000)
        now += 75_000_000L
        stats.onReceived(100)
        now += 5_000_000_000L
        val report = stats.logIfDue()!!
        assertTrue(report, report.contains("touch2frame avg=75ms max=75ms n=1 touchSendMax=2ms"))
    }

    @Test fun aNewTouchReplacesAnExpiredUnmatchedTouch() {
        var now = 1_000_000_000L
        val stats = VideoStats(nanoTime = { now })
        TouchLatencyProbe.onTouchSent(now, 1_000_000)
        now += 10_000_000_000L
        TouchLatencyProbe.onTouchSent(now, 2_000_000)
        now += 50_000_000L
        stats.onReceived(100)
        val report = stats.logIfDue()!!
        assertTrue(report, report.contains("touch2frame avg=50ms max=50ms n=1"))
        assertTrue(report, report.contains("touchExpired=1"))
    }
}
