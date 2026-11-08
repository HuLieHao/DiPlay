package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class WirelessStartupRetryBudgetTest {
    @Test fun availabilityStallsUseBoundedBackoffWithoutFirstTcpGraceCredit() {
        val budget = WirelessStartupRetryBudget()
        val reason = com.shilapi.xcertplay.network.WirelessStartupFailure.AVAILABILITY_TIMEOUT
        assertTrue(reason.retryable)
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L), List(5) {
            budget.nextDelayMillis(true, reason)
        })
        assertNull(budget.nextDelayMillis(true, reason))
    }

    @Test fun stopsAfterFiveRetriesWithExistingBackoff() {
        val budget = WirelessStartupRetryBudget()
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L), List(5) { budget.nextDelayMillis() })
        assertNull(budget.nextDelayMillis())
        assertEquals(5, budget.retries)
        budget.manualRetry()
        assertEquals(2_000L, budget.nextDelayMillis())
    }

    @Test fun profileFirstTcpBorrowsGraceFromCappedBackoff() {
        val budget = WirelessStartupRetryBudget()
        assertEquals(listOf(0L, 2_000L, 2_000L, 2_000L, 2_000L), List(5) {
            budget.nextDelayMillis(true, com.shilapi.xcertplay.network.WirelessStartupFailure.FIRST_TCP_TIMEOUT)
        })
        assertNull(budget.nextDelayMillis(true, com.shilapi.xcertplay.network.WirelessStartupFailure.FIRST_TCP_TIMEOUT))
        budget.manualRetry()
        assertEquals(2_000L, budget.nextDelayMillis(true, com.shilapi.xcertplay.network.WirelessStartupFailure.SESSION_TIMEOUT))
        assertEquals(4_000L, budget.nextDelayMillis(false, com.shilapi.xcertplay.network.WirelessStartupFailure.FIRST_TCP_TIMEOUT))
    }

    @Test fun onlyContinuousVideoSessionResetsBudget() {
        val budget = WirelessStartupRetryBudget()
        budget.nextDelayMillis()
        val one = Any()
        assertFalse(budget.resetIfStable(one, 120_000))
        assertTrue(budget.firstFrame(one, 100))
        assertFalse(budget.firstFrame(one, 200))
        assertFalse(budget.resetIfStable(one, 60_099))
        budget.disconnected()
        assertFalse(budget.resetIfStable(one, 60_100))
        val two = Any()
        assertTrue(budget.firstFrame(two, 70_000))
        assertFalse(budget.resetIfStable(one, 130_000))
        assertTrue(budget.resetIfStable(two, 130_000))
        assertEquals(0, budget.retries)
    }
}
