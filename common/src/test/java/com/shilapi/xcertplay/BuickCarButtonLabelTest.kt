package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class BuickCarButtonLabelTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun clearPreferences() { prefs.edit().clear().apply() }

    @Test fun newInstallAndBlankLabelsUseBuick() {
        assertEquals("别克", AirPlayPersistence.loadOemLabel(context))
        AirPlayPersistence.saveOemLabel(context, " ")
        assertEquals("别克", AirPlayPersistence.loadOemLabel(context))
    }

    @Test fun savedBydLabelIsPreserved() {
        AirPlayPersistence.saveOemLabel(context, "BYD")
        assertEquals("BYD", AirPlayPersistence.loadOemLabel(context))
    }

    @Test fun customLabelIsPreserved() {
        AirPlayPersistence.saveOemLabel(context, "我的凯越")
        assertEquals("我的凯越", AirPlayPersistence.loadOemLabel(context))
    }

    @Test fun carIdentityUsesBuickPlayDefaultsAndPreservesSavedValues() {
        assertEquals("BuickPlay", AirPlayPersistence.loadManufacturer(context))
        assertEquals("BuickPlay", AirPlayPersistence.loadModel(context))
        AirPlayPersistence.saveManufacturer(context, "DiPlay")
        AirPlayPersistence.saveModel(context, "DiPlay")
        assertEquals("DiPlay", AirPlayPersistence.loadManufacturer(context))
        assertEquals("DiPlay", AirPlayPersistence.loadModel(context))
        AirPlayPersistence.saveManufacturer(context, "Buick")
        AirPlayPersistence.saveModel(context, "Kaiyue")
        assertEquals("Buick", AirPlayPersistence.loadManufacturer(context))
        assertEquals("Kaiyue", AirPlayPersistence.loadModel(context))
    }
}
