package com.diego.kiki.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SwipeInterpolationTest {

    @Test
    fun `vertical swipe has correct count and endpoints`() {
        val points = interpolateSwipe(0.5f, 0.8f, 0.5f, 0.2f, steps = 12)
        assertEquals(13, points.size)
        assertEquals(0.5f, points.first().first, 0.0001f)
        assertEquals(0.8f, points.first().second, 0.0001f)
        assertEquals(0.5f, points.last().first, 0.0001f)
        assertEquals(0.2f, points.last().second, 0.0001f)
    }

    @Test
    fun `horizontal swipe moves monotonically`() {
        val points = interpolateSwipe(0.1f, 0.5f, 0.9f, 0.5f, steps = 6)
        assertEquals(7, points.size)
        val xs = points.map { it.first }
        assertTrue(xs.zipWithNext().all { (a, b) -> b > a })
        assertEquals(0.1f, xs.first(), 0.0001f)
        assertEquals(0.9f, xs.last(), 0.0001f)
    }

    @Test
    fun `midpoint of a diagonal swipe is centered`() {
        val points = interpolateSwipe(0f, 0f, 1f, 1f, steps = 2)
        val mid = points[1]
        assertEquals(0.5f, mid.first, 0.0001f)
        assertEquals(0.5f, mid.second, 0.0001f)
    }

    @Test
    fun `single step interpolates linearly between endpoints`() {
        val points = interpolateSwipe(0.2f, 0.9f, 0.8f, 0.1f, steps = 1)
        assertEquals(2, points.size)
        assertEquals(0.2f, points[0].first, 0.0001f)
        assertEquals(0.9f, points[0].second, 0.0001f)
        assertEquals(0.8f, points[1].first, 0.0001f)
        assertEquals(0.1f, points[1].second, 0.0001f)
    }

    @Test
    fun `pin distance uses pixel scale`() {
        val pins = GesturePins(0f, 0f, 1f, 0f)
        assertEquals(500f, pinDistancePx(pins, widthPx = 500f, heightPx = 1000f), 0.001f)

        val diagonal = GesturePins(0f, 0f, 0.3f, 0.4f)
        assertEquals(500f, pinDistancePx(diagonal, widthPx = 1000f, heightPx = 1000f), 0.001f)
    }

    @Test
    fun `near-tap placement is below minimum distance`() {
        val pins = GesturePins(0.5f, 0.5f, 0.5f, 0.5f)
        assertTrue(pinDistancePx(pins, 1080f, 2000f) < AutoScroller.MIN_PIN_DISTANCE_PX)
        val slightlyMoved = GesturePins(0.5f, 0.5f, 0.5f, 0.53f)
        assertTrue(
            abs(pinDistancePx(slightlyMoved, 1080f, 2000f) - 60f) < 0.01f
        )
    }
}
