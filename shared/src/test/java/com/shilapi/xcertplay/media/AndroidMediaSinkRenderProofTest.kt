package com.shilapi.xcertplay.media

import android.view.Surface
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AndroidMediaSinkRenderProofTest {
    @Test fun mirrorRendererKeepsDiagnosticsWithoutReportingTheHostPictureAsRendered() {
        val sink = AndroidMediaSink(surface = null)
        val messages = mutableListOf<String>()
        sink.setVideoDiagnosticHandler(110, messages::add)
        val mirror = sink.javaClass.getDeclaredMethod("newVideoDecoder", Int::class.javaPrimitiveType,
            Surface::class.java, String::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(sink, 110, null, " stream=110 mirror=test", false) as Closeable
        try {
            reporter(mirror)("first frame rendered")
            assertFalse("A mirror is not the host picture", messages.contains("first frame rendered"))
            assertTrue("Keep mirror diagnostics", messages.any { it.contains("first frame rendered") })
        } finally { mirror.close(); sink.close(); assertTrue(sink.awaitVideoReleased(5_000)) }
    }

    @Test fun lateRendererReportCannotBeDeliveredToTheReplacementStreamsObserver() {
        val sink = AndroidMediaSink(surface = null)
        val previous = mutableListOf<String>()
        val current = mutableListOf<String>()
        try {
            sink.setVideoDiagnosticHandler(110, previous::add)
            val old = decoder(sink)
            val queuedReport = reporter(old)
            sink.setVideoDiagnosticHandler(110, current::add)
            val replacement = decoder(sink)
            queuedReport("first frame rendered")
            assertTrue("A retired renderer cannot prove the replacement displayed", current.isEmpty())
            assertNotSame("A new stream needs a renderer bound to its observer", old, replacement)
            reporter(replacement)("first frame rendered")
            assertEquals(listOf("first frame rendered"), current)
        } finally { sink.close(); assertTrue(sink.awaitVideoReleased(5_000)) }
    }

    private fun decoder(sink: AndroidMediaSink) = sink.javaClass
        .getDeclaredMethod("videoDecoder", Int::class.javaPrimitiveType).apply { isAccessible = true }
        .invoke(sink, 110) as Closeable

    @Suppress("UNCHECKED_CAST")
    private fun reporter(decoder: Any): (String) -> Unit = decoder.javaClass
        .getDeclaredField("report").apply { isAccessible = true }.get(decoder) as (String) -> Unit
}
