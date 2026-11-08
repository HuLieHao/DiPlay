package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AudioRendererWindowTest {
    @Test fun firstHandledPacketPeaksBelongToFirstWindowAndNextWindowContinuesAtItsEnd() {
        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val reports = LinkedBlockingQueue<String>()
        val workerStarted = CountDownLatch(1)
        val packetQueued = CountDownLatch(1)
        val renderer = type.declaredConstructors.single { it.parameterCount == 13 }.apply { isAccessible = true }
            .newInstance(AudioFormat(AudioCodecKind.LPCM, 48_000, 1, 96, "media"),
                false, false, 0, 0, AudioFocusCoordinator(null, false, false), 0, 300,
                { text: String -> if (text.startsWith("Audio: starting")) {
                    workerStarted.countDown()
                    check(packetQueued.await(3, TimeUnit.SECONDS))
                }
                if (text.contains("windowStartNs=")) reports.offer(text)
                Unit },
                0L, false, null, false)
        fun call(name: String) = type.getDeclaredMethod(name).apply { isAccessible = true }.invoke(renderer)
        fun submit(sample: Int) = type.getDeclaredMethod("submit", ByteArray::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(renderer, ByteArray(204) { 1 }, sample)
        fun number(line: String, key: String) = Regex("(?:^| )$key=(\\d+)").find(line)!!.groupValues[1].toLong()
        val thread = type.getDeclaredField("thread").apply { isAccessible = true }.get(renderer) as Thread
        try {
            // Queue before the worker starts handling its first packet.
            call("start")
            assertTrue(workerStarted.await(3, TimeUnit.SECONDS))
            submit(1)
            packetQueued.countDown()
            val first = reports.poll(8, TimeUnit.SECONDS) ?: error("first timing window missing")
            val start = number(first, "windowStartNs")
            val end = number(first, "windowEndNs")
            assertTrue(first, number(first, "queueSamples") > 0)
            for (key in listOf("maxQueueAtNs", "maxWriteAtNs")) {
                val peak = number(first, key)
                assertTrue("$key outside first window: $first", peak in start..end)
            }
            submit(2)
            val second = reports.poll(7, TimeUnit.SECONDS) ?: error("second timing window missing")
            assertEquals(end, number(second, "windowStartNs"))
            assertTrue(second, number(second, "queueSamples") > 0)
            assertTrue(second, number(second, "maxQueueAtNs") in end..number(second, "windowEndNs"))
        } finally {
            packetQueued.countDown()
            call("close")
            thread.join(3000)
            assertFalse("renderer leaked", thread.isAlive)
        }
    }
}
