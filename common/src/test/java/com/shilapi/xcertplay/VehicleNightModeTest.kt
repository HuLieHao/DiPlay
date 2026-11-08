package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class VehicleNightModeTest {
    @Test fun vehicleUnknownUsesDayEvenAtNightAndRecoversWithoutSchedulePolling() {
        var callback: ((Boolean?) -> Unit)? = null
        var stopped = false
        val source = object : CarPlayNightModeController.VehicleSource {
            override fun start(onState: (Boolean?) -> Unit) { callback = onState }
            override fun stop() { stopped = true }
        }
        val tasks = mutableSetOf<Runnable>()
        val controller = CarPlayNightModeController(
            object : CarPlayNightModeController.LightSource {
                override val available = false
                override fun start(onLux: (Float) -> Unit) = false
                override fun stop() {}
            },
            object : CarPlayNightModeController.Scheduler {
                override fun postDelayed(task: Runnable, delayMillis: Long) { tasks.add(task) }
                override fun remove(task: Runnable) { tasks.remove(task) }
            }, false, {},
            object : NightTimeSource { override fun snapshot() = NightTimeSnapshot(23 * 60, 60000) },
            vehicle = source,
        )
        controller.configure(CarPlayNightMode.VEHICLE, false)
        controller.resume(false)
        assertFalse(controller.night)
        assertTrue(tasks.isEmpty())
        assertTrue(controller.diagnosticState().contains("vehicleState=unknown"))
        assertTrue(controller.diagnosticState().contains("scheduleNight=true"))
        callback!!(false)
        assertFalse(controller.night)
        assertTrue(controller.diagnosticState().contains("vehicleState=off"))
        assertTrue(controller.diagnosticState().contains("fallback=false"))
        assertTrue(controller.diagnosticState().contains("clockColor=black"))
        assertTrue(tasks.isEmpty())
        callback!!(true)
        assertTrue(controller.night)
        callback!!(null)
        assertFalse(controller.night)
        assertTrue(tasks.isEmpty())
        callback!!(false)
        assertFalse(controller.night)
        controller.pause()
        assertTrue(stopped)
        assertTrue(tasks.isEmpty())
        callback!!(true)
        assertFalse(controller.night)
    }
}
