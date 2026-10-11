package com.diego.kiki.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeSmartStopTest {

    @Test
    fun `growing capture resets the stalled counter`() {
        assertEquals(0, SwipeSmartStop.nextStalled(3, captureGrew = true))
    }

    @Test
    fun `stalled counter increments on stagnant swipes`() {
        assertEquals(1, SwipeSmartStop.nextStalled(0, captureGrew = false))
        assertEquals(2, SwipeSmartStop.nextStalled(1, captureGrew = false))
    }

    @Test
    fun `stops at the limit not before`() {
        assertFalse(SwipeSmartStop.shouldStop(4))
        assertTrue(SwipeSmartStop.shouldStop(5))
        assertTrue(SwipeSmartStop.shouldStop(6))
    }

    @Test
    fun `equal count is not growth`() {
        // Same capture count as before counts as stagnant (count <= lastCount)
        assertEquals(2, SwipeSmartStop.nextStalled(1, captureGrew = false))
    }
}
