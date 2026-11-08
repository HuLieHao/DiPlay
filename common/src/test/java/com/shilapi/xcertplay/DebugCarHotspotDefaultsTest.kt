package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class DebugCarHotspotDefaultsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
    private fun context(packageId: String = "com.shihab.diplay.hudtest", asset: String? = "ssid=TestCar\npassphrase=test-secret\n"): Context {
        val assets = Mockito.mock(AssetManager::class.java)
        Mockito.`when`(assets.open("debug-car-hotspot.properties")).thenAnswer {
            asset?.byteInputStream() ?: throw java.io.FileNotFoundException()
        }
        return object : ContextWrapper(app) {
            override fun getPackageName() = packageId
            override fun getAssets() = assets
            override fun getApplicationInfo() = ApplicationInfo(app.applicationInfo).apply { flags = flags or ApplicationInfo.FLAG_DEBUGGABLE }
        }
    }
    @Before fun setup() {
        prefs.edit().clear().commit()
        ShadowBuild.setModel("FF-5000")
        ShadowBuild.setHardware("ac8227l")
    }
    @Test fun carDebugReplacesPreviousPhoneSettingsOnceAndKeepsLaterEdits() {
        prefs.edit().putString("manual_hotspot_ssid", "OldPhone").putString("wireless_hotspot_mode", "WIFI_DIRECT").commit()
        val car = context()
        assertEquals("TestCar", AirPlayPersistence.loadManualHotspotSsid(car))
        assertEquals("test-secret", AirPlayPersistence.loadManualHotspotPassphrase(car))
        assertEquals(com.shilapi.xcertplay.orchestration.WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(car))
        AirPlayPersistence.saveManualHotspotSsid(car, "EditedByDriver")
        assertEquals("EditedByDriver", AirPlayPersistence.loadManualHotspotSsid(car))
    }
    @Test fun otherPackagesAndDevicesKeepTheirSettings() {
        assertEquals("", AirPlayPersistence.loadManualHotspotSsid(context("com.shihab.diplay")))
        ShadowBuild.setModel("Phone")
        assertEquals("", AirPlayPersistence.loadManualHotspotSsid(context()))
    }
    @Test fun absentOrInvalidAssetLeavesPreferencesUntouchedAndAllowsLaterProvisioning() {
        assertEquals("", AirPlayPersistence.loadManualHotspotSsid(context(asset = null)))
        assertEquals("", AirPlayPersistence.loadManualHotspotSsid(context(asset = "ssid=Car\npassphrase=short\n")))
        assertEquals("TestCar", AirPlayPersistence.loadManualHotspotSsid(context()))
    }
}
