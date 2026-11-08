package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.setup.DiLinkGeneration
import com.shilapi.xcertplay.setup.SetupGuide
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class CarSetupDefaultsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun prefs(name: String = "xcertplay_airplay") = app.getSharedPreferences(name, Context.MODE_PRIVATE)
    @Before fun setup() {
        listOf("xcertplay_airplay", "diplay", "diplay_volume", "diplay_car_hotspot").forEach { prefs(it).edit().clear().commit() }
        ShadowBuild.setModel("FF-5000")
        ShadowBuild.setHardware("ac8227l")
    }

    @Test fun freshGuideAndHostReadsUseCarDefaultsWithoutCompletingSetup() {
        assertFalse(SetupGuide.seen(app))
        assertEquals(3, prefs().getInt("media_audio_channel", -1))
        assertEquals(11, prefs().getInt("navigation_audio_channel", -1))
        assertEquals(3, AirPlayPersistence.loadMediaAudioChannel(app))
        assertEquals(11, AirPlayPersistence.loadNavigationAudioChannel(app))
        assertTrue(AirPlayPersistence.loadWirelessEnabled(app))
        assertEquals(CarPlayNightMode.VEHICLE, AirPlayPersistence.loadCarPlayNightMode(app))
        assertTrue(CarPlayVolumeSettings.enabled(app))
        assertTrue(com.shilapi.xcertplay.network.CarHotspotSettings.enabled(app))
        assertFalse(prefs("diplay").contains("phone_address"))
        assertFalse(prefs().contains("display_fps"))
        assertFalse(prefs().contains("display_width_physical_mm"))
    }

    @Test @Config(shadows = [Bootstrap::class])
    fun directProjectionLaunchInitializesDefaultsBeforeConnectionSettingsAreRead() {
        CarPlayBackgroundSession.allowAutomaticConnection()
        val host = org.robolectric.Robolectric.buildActivity(CarPlayHostActivity::class.java).create()
        try {
            assertEquals(3, prefs().getInt("media_audio_channel", -1))
            assertEquals(11, prefs().getInt("navigation_audio_channel", -1))
            assertTrue(CarPlayHostActivity::class.java.getDeclaredField("wirelessEnabled")
                .apply { isAccessible = true }.getBoolean(host.get()))
            assertFalse(SetupGuide.seen(app))
        } finally {
            host.destroy()
        }
    }

    @Test fun carConfirmationCannotReplacePresetWithBydNavigationStream() {
        assertFalse(SetupGuide.applyFirstSetupDefaults(app, DiLinkGeneration.DILINK_3, false))
        assertEquals(11, AirPlayPersistence.loadNavigationAudioChannel(app))
    }

    @Test fun explicitFalseCustomValuesAndCompletedGuideSurviveEveryRead() {
        prefs().edit().putInt("media_audio_channel", 5).putInt("navigation_audio_channel", 7)
            .putBoolean("wireless_enabled", false).putString("carplay_night_mode", "day").commit()
        prefs("diplay_volume").edit().putBoolean("enabled", false).commit()
        prefs("diplay_car_hotspot").edit().putBoolean("auto_enable", false).commit()
        SetupGuide.markSeen(app)
        assertTrue(SetupGuide.seen(app))
        assertEquals(5, AirPlayPersistence.loadMediaAudioChannel(app))
        assertEquals(7, AirPlayPersistence.loadNavigationAudioChannel(app))
        assertFalse(AirPlayPersistence.loadWirelessEnabled(app))
        assertEquals(CarPlayNightMode.DAY, AirPlayPersistence.loadCarPlayNightMode(app))
        assertFalse(CarPlayVolumeSettings.enabled(app))
        assertFalse(com.shilapi.xcertplay.network.CarHotspotSettings.enabled(app))
    }

    @Test fun driverEditsAfterFirstReadAndLegacyNavigationChoiceWin() {
        assertEquals(3, AirPlayPersistence.loadMediaAudioChannel(app))
        AirPlayPersistence.saveMediaAudioChannel(app, 0)
        AirPlayPersistence.saveNavigationAudioChannel(app, 0)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        assertEquals(0, AirPlayPersistence.loadMediaAudioChannel(app))
        assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(app))
        assertFalse(AirPlayPersistence.loadWirelessEnabled(app))
        prefs().edit().clear().putInt("navigation_stream_type", 14).commit()
        assertEquals(14, AirPlayPersistence.loadNavigationAudioChannel(app))
    }

    @Test fun otherModelHardwareOrApiRetainsExistingAutomaticAudioDefaults() {
        ShadowBuild.setModel("Other")
        assertEquals(0, AirPlayPersistence.loadMediaAudioChannel(app))
        ShadowBuild.setModel("FF-5000")
        ShadowBuild.setHardware("Other")
        assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(app))
    }

    @org.robolectric.annotation.Implements(DiPlayBootstrap::class, isInAndroidSdk = false)
    internal class Bootstrap {
        @org.robolectric.annotation.Implementation
        fun ensure(context: Context, target: com.shilapi.xcertplay.orchestration.MfiTarget) = Unit
        @org.robolectric.annotation.Implementation
        fun deviceId(identity: com.shilapi.xcertplay.airplay.AirPlayIdentity) = "02:00:00:00:00:01"
    }

    @Test @Config(sdk = [28]) fun otherApiRetainsAutomaticAudioDefaults() {
        assertEquals(0, AirPlayPersistence.loadMediaAudioChannel(app))
        assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(app))
    }
}
