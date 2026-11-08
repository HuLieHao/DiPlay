package com.shilapi.xcertplay.clock

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** iAP2 device calendar time, never the AirPlay media clock. UTC seconds use the Unix epoch. */
data class IphoneTimeUpdate(val unixMillis: Long, val offsetMinutes: Int) {
    companion object {
        const val MESSAGE_ID = 0x4e0b
        fun decode(frame: Iap2Frame): IphoneTimeUpdate {
            require(frame.messageId == MESSAGE_ID) { "not DeviceTimeUpdate" }
            val body = Iap2BodyReader.of(frame)
            for (id in 0..2) require(body.all(id).size == 1) { "missing/duplicate time field $id" }
            val seconds = body.u64(0)
            // Reject unsigned overflow and wrong epochs before multiplying. 2020 through 2099.
            require(seconds in 1_577_836_800L until 4_102_444_800L) { "invalid phone UTC seconds=$seconds" }
            val zone = body.i16(1)
            val dst = body.i8(2)
            require(zone in -720..840 && dst in -120..120 && zone + dst in -720..840) { "invalid phone timezone" }
            return IphoneTimeUpdate(seconds * 1000, zone + dst)
        }
    }
}

/** A bounded, process-local anchor. A system date jump cannot move this clock. */
data class PhoneClockAnchor(val update: IphoneTimeUpdate, val receivedElapsedMs: Long) {
    fun at(elapsedMs: Long): IphoneTimeUpdate? {
        val age = elapsedMs - receivedElapsedMs
        if (age !in 0..86_400_000L) return null
        return update.copy(unixMillis = update.unixMillis + age)
    }
}
