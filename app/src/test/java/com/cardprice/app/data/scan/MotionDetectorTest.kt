package com.cardprice.app.data.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.random.Random

class MotionDetectorTest {
    /** A card-like scene: a bright rectangle at [x] on a dark background, plus a little sensor noise. */
    private fun scene(x: Int, noise: Random = Random(1), brightness: Int = 0) = IntArray(16 * 12) { i ->
        val cx = i % 16
        val cy = i / 16
        val base = if (cx in x until x + 5 && cy in 2 until 10) 200 else 40
        (base + brightness + noise.nextInt(-3, 4)).coerceIn(0, 255)
    }

    @Test
    fun steadyCardWithNoiseIsNotMovement() {
        val d = MotionDetector()
        val noise = Random(7)
        val fired = (0 until 60).any { d.onFrame(scene(5, noise), it * 33L) }
        assertFalse(fired)
    }

    @Test
    fun exposureChangeIsNotMovement() {
        val d = MotionDetector()
        val fired = (0 until 30).any { t -> d.onFrame(scene(5, brightness = if (t % 2 == 0) 0 else 40), t * 33L) }
        assertFalse(fired)
    }

    @Test
    fun shakingTheCardIsABigMove() {
        val d = MotionDetector()
        val positions = listOf(5, 5, 9, 2, 10, 3, 8)
        val firedAt = positions.indices.firstOrNull { d.onFrame(scene(positions[it]), it * 33L) }
        assertEquals(4, firedAt) // the third moving frame
    }

    @Test
    fun oneJoltIsNotEnough() {
        val d = MotionDetector()
        val positions = listOf(5, 5, 9, 9, 9, 9, 9, 9)
        assertFalse(positions.indices.any { d.onFrame(scene(positions[it]), it * 33L) })
    }

    @Test
    fun keepsQuietWhileTheViewSettles() {
        val d = MotionDetector()
        var fires = 0
        // Two seconds of constant shaking at ~30 fps: once, then again only after the cooldown.
        for (t in 0 until 60) if (d.onFrame(scene(if (t % 2 == 0) 2 else 9), t * 33L)) fires++
        assertEquals(2, fires)
    }

    @Test
    fun movesSpreadOutOverTimeDontAddUp() {
        val d = MotionDetector()
        // A jolt every 600 ms: never three within a second.
        val frames = listOf(0L to 5, 600L to 9, 1200L to 3, 1800L to 9, 2400L to 3)
        assertFalse(frames.any { (t, x) -> d.onFrame(scene(x), t) })
    }

    @Test
    fun gridAveragesTheLuminancePlane() {
        // 32×24 frame, left half black, right half white, with row padding.
        val width = 32
        val height = 24
        val stride = 40
        val buffer = ByteBuffer.allocate(stride * height)
        for (y in 0 until height) for (x in 0 until width) buffer.put(y * stride + x, (if (x < 16) 0 else 255).toByte())
        val grid = MotionDetector.grid(buffer, width, height, stride, 1, cols = 4, rows = 2)
        assertEquals(listOf(0, 0, 255, 255, 0, 0, 255, 255), grid.toList())
        assertTrue(MotionDetector.difference(grid, grid) == 0)
    }
}
