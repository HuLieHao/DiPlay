package com.shilapi.xcertplay.orchestration

import android.os.Looper
import com.shilapi.xcertplay.ConnectionMilestone
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ConnectionMilestoneDiagnosticTest {
    @Test fun milestoneDiagnosticsRetainTheSpecificEventAndMonotonicTimeAlongsideUiStatus() {
        val logs = mutableListOf<String>()
        val statuses = mutableListOf<CarPlayStatus>()
        val controller = CarPlayController(RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
                identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1",
                    carPlayUsbInterfaceNumber = 0)),
            AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(),
            object : AirPlaySessionListener { override fun onDebugLog(message: String) { logs += message } },
            object : AirPlayMediaHandler {}, statuses::add)
        try {
            val status = CarPlayStatus.ConnectionProgress(ConnectionMilestone.AIRPLAY_PAIR_VERIFY)
            controller.javaClass.getDeclaredMethod("onStatus", CarPlayStatus::class.java, Int::class.javaObjectType)
                .apply { isAccessible = true }.invoke(controller, status, null)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(status), statuses)
            assertTrue(logs.joinToString("\n"), logs.any {
                it.startsWith("CONNECTION_DIAGNOSTIC ") && it.contains("milestone=AIRPLAY_PAIR_VERIFY") &&
                    Regex("atNs=\\d+").containsMatchIn(it)
            })
        } finally { controller.close(); assertTrue(controller.awaitClosed(5_000)) }
    }
}
