package com.shilapi.xcertplay

import android.os.Build
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class Ff5000VolumeSettingsTest {
    @Test fun automaticFeedbackOnlyOwnsMusicWhenMediaRouteIsAutomaticOrThree() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("diplay_volume", 0)
        prefs.edit().clear().commit()
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "FF-5000")
        ReflectionHelpers.setStaticField(Build::class.java, "HARDWARE", "ac8227l")
        try {
            for (media in listOf(14, 5)) {
                AirPlayPersistence.saveMediaAudioChannel(context, media)
                assertFalse("custom stream $media must stay with firmware by default", CarPlayVolumeSettings.enabled(context))
            }
            for (media in listOf(0, 3)) {
                AirPlayPersistence.saveMediaAudioChannel(context, media)
                assertTrue(CarPlayVolumeSettings.enabled(context))
            }
            AirPlayPersistence.saveMediaAudioChannel(context, 14)
            CarPlayVolumeSettings.save(context, true)
            assertTrue(CarPlayVolumeSettings.enabled(context))
        } finally {
            AirPlayPersistence.saveMediaAudioChannel(context, 0)
            prefs.edit().clear().commit()
        }
    }
    @Test fun customFeedbackDefaultsOnForBoardAndExplicitPreferencesAlwaysWin() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("diplay_volume", 0)
        prefs.edit().clear().commit()
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "FF-5000")
        ReflectionHelpers.setStaticField(Build::class.java, "HARDWARE", "ac8227l")
        assertTrue(CarPlayVolumeSettings.enabled(context))
        CarPlayVolumeSettings.save(context, false)
        assertFalse(CarPlayVolumeSettings.enabled(context))
        CarPlayVolumeSettings.save(context, true)
        assertTrue(CarPlayVolumeSettings.enabled(context))
        prefs.edit().clear().commit()
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "other")
        assertFalse(CarPlayVolumeSettings.enabled(context))
    }
}
