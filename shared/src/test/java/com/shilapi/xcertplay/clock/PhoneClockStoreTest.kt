package com.shilapi.xcertplay.clock

import org.junit.Assert.*
import org.junit.Test

class PhoneClockStoreTest {
    @Test fun oldControllerCannotOverwriteNewPhonesTimeOrPublishAfterClose() {
        val store = PhoneClockStore()
        val old = store.newOwner()
        val first = PhoneClockAnchor(IphoneTimeUpdate(1_762_923_477_000, 480), 100)
        assertTrue(store.publish(old, first))
        val current = store.newOwner()
        val second = PhoneClockAnchor(IphoneTimeUpdate(1_762_923_478_000, -300), 200)
        assertTrue(store.publish(current, second))
        assertFalse(store.publish(old, first))
        store.invalidate(old)
        assertEquals(second.update, store.at(200))
        store.invalidate(current)
        assertFalse(store.publish(current, first))
        // A disconnect retains the last valid clock for its bounded lifetime.
        assertEquals(second.update, store.at(200))
    }
}
