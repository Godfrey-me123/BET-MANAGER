package com.example.data.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteLimitsAndWinTest {

    @Test
    fun `parses min and max from one line`() {
        val (min, max) = parseSiteStakeLimits(listOf("Min 10.00 Max 100,000"))
        assertEquals(10.0, min!!, 0.001)
        assertEquals(100000.0, max!!, 0.001)
    }

    @Test
    fun `parses labelled limits from separate texts`() {
        val (min, max) = parseSiteStakeLimits(listOf("Balance 5,000", "Min bet: 50", "Max bet: 20000"))
        assertEquals(50.0, min!!, 0.001)
        assertEquals(20000.0, max!!, 0.001)
    }

    @Test
    fun `no limits on screen gives nulls`() {
        val (min, max) = parseSiteStakeLimits(listOf("Bet", "Auto", "2.00"))
        assertNull(min)
        assertNull(max)
    }

    @Test
    fun `inverted limits are ignored`() {
        val (min, max) = parseSiteStakeLimits(listOf("Min 500 Max 5"))
        assertNull(min)
        assertNull(max)
    }

    @Test
    fun `game side auto cash out win needs armed flag and crash at or above target`() {
        assertTrue(isGameSideCashOutWin(true, 2.50, 2.00))
        assertTrue(isGameSideCashOutWin(true, 2.00, 2.00))
        assertFalse(isGameSideCashOutWin(true, 1.40, 2.00))
        assertFalse(isGameSideCashOutWin(false, 9.00, 2.00))
    }

    @Test
    fun `parses rejection messages`() {
        assertEquals(10.0, parseSiteStakeLimits(listOf("Minimum bet is 10.00")).first!!, 0.001)
        assertEquals(50.0, parseSiteStakeLimits(listOf("Bet amount must be at least 50")).first!!, 0.001)
        assertEquals(20000.0, parseSiteStakeLimits(listOf("Stake cannot exceed 20,000")).second!!, 0.001)
        assertEquals(5000.0, parseSiteStakeLimits(listOf("Maximum bet 5000")).second!!, 0.001)
        assertNull(parseSiteStakeLimits(listOf("Max win 1,000,000")).second)
        assertNull(parseSiteStakeLimits(listOf("Max 100x")).second)
    }
}
