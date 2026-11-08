package com.shilapi.xcertplay.clock

/** Serializes ownership across controllers; closing an old controller cannot revoke a new one. */
internal class PhoneClockStore {
    private var owner = 0L
    private var anchor: PhoneClockAnchor? = null
    @Synchronized fun newOwner(): Long = ++owner
    @Synchronized fun isOwner(token: Long): Boolean = token == owner
    @Synchronized fun invalidate(token: Long) { if (owner == token) ++owner }
    @Synchronized fun publish(token: Long, value: PhoneClockAnchor): Boolean {
        if (owner != token) return false
        anchor = value
        return true
    }
    @Synchronized fun at(elapsedMs: Long): IphoneTimeUpdate? = anchor?.at(elapsedMs)
}
