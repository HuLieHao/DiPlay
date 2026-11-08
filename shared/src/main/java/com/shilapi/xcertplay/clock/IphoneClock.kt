package com.shilapi.xcertplay.clock

import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArraySet

/** Kept only in memory: a reboot never reuses an elapsedRealtime anchor from the previous boot. */
object IphoneClock {
    private val store = PhoneClockStore()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    fun now(): IphoneTimeUpdate? = store.at(SystemClock.elapsedRealtime())
    internal fun newOwner(): Long = store.newOwner()
    internal fun isOwner(token: Long): Boolean = store.isOwner(token)
    internal fun invalidate(token: Long) = store.invalidate(token)
    internal fun publish(token: Long, value: PhoneClockAnchor) {
        if (!store.publish(token, value)) return
        listeners.forEach { runCatching(it) }
    }
    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
}
