package com.shilapi.xcertplay.media

import android.os.Build
import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class Ff5000AudioRoutingTest {
    private fun profile(model: String = "FF-5000", hardware: String = "ac8227l") {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", model)
        ReflectionHelpers.setStaticField(Build::class.java, "HARDWARE", hardware)
    }
    private fun route(channel: AudioChannel, media: Int = 0, navigation: Int = 0, speakerphone: Boolean = false): Int {
        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val renderer = type.declaredConstructors.single { it.parameterCount == 13 }.apply { isAccessible = true }
            .newInstance(AudioFormat(AudioCodecKind.LPCM, 48000, 1, 96, "default"), false, false,
                media, navigation, AudioFocusCoordinator(null, false, false), 0, 300,
                { _: String -> }, 0L, speakerphone, null, false)
        try {
            return type.getDeclaredMethod("channelOverride", AudioChannel::class.java).apply { isAccessible = true }
                .invoke(renderer, channel) as Int
        } finally { (renderer as java.io.Closeable).close() }
    }
    @Test @Config(shadows = [RejectGisTrack::class]) fun rejectedVendorStreamUsesUsageTrackWithoutAdvertisingGisOwnership() = verifyTrack(null)
    @Test fun initializedGisTrackAdvertisesAcceptedLegacyRoute() = verifyTrack(11)

    private fun verifyTrack(expectedLegacy: Int?) {
        profile()
        val reports = mutableListOf<String>()
        val sink = AndroidMediaSink(navigationChannel = 11, onAudioDiagnostic = { reports.add(it) })
        val renderer = AndroidMediaSink::class.java.getDeclaredMethod("audioRenderer",
            AudioStreamId::class.java, AudioFormat::class.java).apply { isAccessible = true }
            .invoke(sink, AudioStreamId(96, "default"), AudioFormat(AudioCodecKind.LPCM, 48000, 1, 96, "default"))
        val type = renderer.javaClass
        try {
            type.getDeclaredMethod("createTrack").apply { isAccessible = true }.invoke(renderer)
            val track = type.getDeclaredField("track").apply { isAccessible = true }.get(renderer) as android.media.AudioTrack
            assertEquals(android.media.AudioTrack.STATE_INITIALIZED, track.state)
            assertEquals(expectedLegacy, type.getDeclaredField("actualLegacyStreamType").apply { isAccessible = true }.get(renderer))
            type.getDeclaredMethod("reportNavigationPlayback", android.media.AudioTrack::class.java)
                .apply { isAccessible = true }.invoke(renderer, track)
            assertTrue(NavigationPlayback.snapshot().active)
            assertEquals(expectedLegacy, NavigationPlayback.snapshot().legacyStreamType)
            assertTrue(reports.joinToString(), reports.any {
                "requestedLegacy=11 actualLegacy=${expectedLegacy ?: "unknown"}" in it && "sessionId=" in it &&
                    (expectedLegacy != null || "fallback=usage" in it)
            })
        } finally { sink.close() }
    }
    @Test fun alertsKeepUsageByDefaultAndExplicitNavigationPreferenceWins() {
        profile()
        for ((configured, expected) in listOf(0 to null, 5 to 5)) {
            val reports = mutableListOf<String>()
            val sink = AndroidMediaSink(navigationChannel = configured, onAudioDiagnostic = { reports.add(it) })
            val renderer = AndroidMediaSink::class.java.getDeclaredMethod("audioRenderer",
                AudioStreamId::class.java, AudioFormat::class.java).apply { isAccessible = true }
                .invoke(sink, AudioStreamId(96, "alert"), AudioFormat(AudioCodecKind.LPCM, 48000, 1, 96, "alert"))
            try {
                val type = renderer.javaClass
                type.getDeclaredMethod("createTrack").apply { isAccessible = true }.invoke(renderer)
                assertEquals(expected, type.getDeclaredField("actualLegacyStreamType").apply { isAccessible = true }.get(renderer))
                assertTrue(reports.joinToString(), reports.any {
                    "audioType=alert" in it && "configuredLegacy=$configured" in it &&
                        "requestedLegacy=${configured}" in it && "actualLegacy=${expected ?: "unknown"}" in it
                })
            } finally { sink.close() }
        }
    }
    @Test fun automaticRoutesUseIndependentMusicAndGisOnlyOnVerifiedBoard() {
        profile()
        assertEquals(3, route(AudioChannel.MEDIA))
        assertEquals(11, route(AudioChannel.NAVIGATION))
        assertEquals(0, route(AudioChannel.PHONE))
        assertEquals(0, route(AudioChannel.ASSISTANT))
    }
    @Test fun speakerphoneCallsKeepAutomaticUsageInsteadOfMusicProfile() {
        profile()
        assertEquals(0, route(AudioChannel.MEDIA, speakerphone = true))
        assertEquals(5, route(AudioChannel.MEDIA, media = 5, speakerphone = true))
    }
    @Test fun explicitRoutesWinAndOtherBoardsRemainAutomatic() {
        profile()
        assertEquals(5, route(AudioChannel.NAVIGATION, navigation = 5))
        assertEquals(14, route(AudioChannel.MEDIA, media = 14))
        for ((model, hardware) in listOf("other" to "ac8227l", "FF-5000" to "other", "FF_5000" to "ac8227l")) {
            profile(model, hardware)
            assertEquals(0, route(AudioChannel.MEDIA))
            assertEquals(0, route(AudioChannel.NAVIGATION))
        }
    }
    @Test @Config(sdk = [28]) fun otherFirmwareApiRemainsAutomatic() {
        profile()
        assertEquals(0, route(AudioChannel.MEDIA))
        assertEquals(0, route(AudioChannel.NAVIGATION))
    }
    @org.robolectric.annotation.Implements(android.media.AudioTrack::class)
    // Simulates a vendor returning an uninitialized legacy candidate; usage fallback initializes.
    class RejectGisTrack : org.robolectric.shadows.ShadowAudioTrack() {
        @org.robolectric.annotation.RealObject private lateinit var real: android.media.AudioTrack
        @org.robolectric.annotation.Implementation
        protected fun getState(): Int {
            if (rejected == null) rejected = real
            return if (rejected === real) android.media.AudioTrack.STATE_UNINITIALIZED
                else android.media.AudioTrack.STATE_INITIALIZED
        }
        companion object { private var rejected: android.media.AudioTrack? = null }
    }

}
