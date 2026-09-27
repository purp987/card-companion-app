package com.cardprice.app.data.scan

import kotlin.math.abs

/**
 * Notices when the card in view is shaken, moved a lot or swapped, so a stuck scan can start over.
 * Each camera frame is reduced to a coarse brightness grid; frames that differ a lot from the one
 * before count as "moving", and a few of those close together are a big move. Brightness is
 * compared relative to each frame's average, so the camera adjusting its exposure doesn't count.
 */
class MotionDetector(
    private val moveThreshold: Int = MOVE_THRESHOLD,
    private val movingFrames: Int = MOVING_FRAMES,
    private val windowMs: Long = WINDOW_MS,
    private val cooldownMs: Long = COOLDOWN_MS,
) {
    private var previous: IntArray? = null
    private val moves = ArrayDeque<Long>()
    private var lastFired = Long.MIN_VALUE / 2

    /** Feeds one frame's grid (all frames the same size); true when this frame completes a big move. */
    fun onFrame(grid: IntArray, now: Long): Boolean {
        val before = previous
        previous = grid
        if (before == null || before.size != grid.size || grid.isEmpty()) return false
        if (difference(before, grid) >= moveThreshold) moves.addLast(now)
        while (moves.isNotEmpty() && now - moves.first() > windowMs) moves.removeFirst()
        if (moves.size < movingFrames || now - lastFired < cooldownMs) return false
        lastFired = now
        moves.clear()
        return true
    }

    companion object {
        /** Average change per grid cell (0–255 brightness) that counts as movement; sensor noise is ~2–5. */
        const val MOVE_THRESHOLD = 18
        const val MOVING_FRAMES = 3
        const val WINDOW_MS = 1000L
        /** After a big move, give the new view time to settle before another can count. */
        const val COOLDOWN_MS = 1500L

        /** Mean change per cell, ignoring an overall brightness shift. */
        fun difference(a: IntArray, b: IntArray): Int {
            val shift = (b.sum() - a.sum()) / a.size
            var total = 0L
            for (i in a.indices) total += abs(b[i] - a[i] - shift)
            return (total / a.size).toInt()
        }

        /**
         * Averages the luminance plane of a camera frame into a [cols]×[rows] grid, sampling every few
         * pixels so it's cheap enough to run on every frame.
         */
        fun grid(
            luma: java.nio.ByteBuffer,
            width: Int,
            height: Int,
            rowStride: Int,
            pixelStride: Int,
            cols: Int = 16,
            rows: Int = 12,
        ): IntArray {
            val out = IntArray(cols * rows)
            val step = 4
            for (gy in 0 until rows) {
                val y0 = gy * height / rows
                val y1 = (gy + 1) * height / rows
                for (gx in 0 until cols) {
                    val x0 = gx * width / cols
                    val x1 = (gx + 1) * width / cols
                    var sum = 0L
                    var n = 0
                    var y = y0
                    while (y < y1) {
                        var x = x0
                        while (x < x1) {
                            sum += luma.get(y * rowStride + x * pixelStride).toInt() and 0xFF
                            n++
                            x += step
                        }
                        y += step
                    }
                    out[gy * cols + gx] = if (n == 0) 0 else (sum / n).toInt()
                }
            }
            return out
        }
    }
}
