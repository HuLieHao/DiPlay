package com.shilapi.xcertplay.clock

internal class PhoneTimeSyncGate {
    private var lastAttempt: Long? = null
    @Synchronized fun claim(elapsedMs: Long): Boolean {
        if (lastAttempt?.let { elapsedMs - it in 0 until 60_000 } == true) return false
        lastAttempt = elapsedMs
        return true
    }
    companion object {
        fun vendorSupported(model: String, hardware: String, sdk: Int) =
            model.equals("FF-5000", true) && hardware.equals("ac8227l", true) && sdk == 27
        fun rootScript(unixMillis: Long): String {
            require(unixMillis in 1_577_836_800_000L until 4_102_444_800_000L)
            // Transaction 2 is IAlarmManager.setTime(long), verified in this API 27 firmware.
            // Only validated numeric UTC milliseconds reach the privileged service command.
            return "exec /system/bin/service call alarm 2 i64 $unixMillis\n"
        }
    }
}
