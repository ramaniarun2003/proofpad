package com.mathnotes.solver.ui.onboarding

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The black-and-white marbling of a composition-book cover, drawn procedurally: four octaves of
 * gradient noise (fBm) starting at [CYCLES_PER_DP], cut at a threshold into two flat colours. It
 * is the same recipe as the mockup's SVG filter (feTurbulence 0.085, 4 octaves, hard threshold).
 *
 * Pure arithmetic, no Android types, so it can be run off the main thread and unit tested.
 */
internal object Marble {
    const val BLACK = 0xFF141414.toInt()
    const val WHITE = 0xFFF4F1EA.toInt()

    private const val CYCLES_PER_DP = 0.085f
    private const val OCTAVES = 4
    /** Just above the middle of the noise, so black has slightly the larger share, as on the mockup. */
    private const val THRESHOLD = 0.02f

    /**
     * [width] x [height] ARGB pixels, row by row. [density] is device pixels per dp, so the blotches
     * are the same physical size on every screen. The same [seed] always gives the same cover.
     */
    fun pixels(width: Int, height: Int, density: Float, seed: Long = 3L): IntArray {
        val random = Random(seed)
        // Perlin's lattice: a shuffled permutation (doubled, so two lookups never run off the end)
        // and a unit gradient for each of its 256 values.
        val perm = IntArray(512)
        val order = IntArray(256) { it }
        for (i in 255 downTo 1) {
            val j = random.nextInt(i + 1)
            val t = order[i]; order[i] = order[j]; order[j] = t
        }
        for (i in 0 until 512) perm[i] = order[i and 255]
        val gradX = FloatArray(256)
        val gradY = FloatArray(256)
        for (i in 0 until 256) {
            val angle = random.nextFloat() * 2f * PI.toFloat()
            gradX[i] = cos(angle); gradY[i] = sin(angle)
        }

        val out = IntArray(width * height)
        val row = FloatArray(width)
        for (y in 0 until height) {
            row.fill(0f)
            var frequency = CYCLES_PER_DP / density
            var amplitude = 1f
            for (octave in 0 until OCTAVES) {
                // Each octave reads a different part of the lattice so they do not line up.
                val fy = y * frequency + octave * 17.3f
                val y0 = fy.toInt()
                val ty = fy - y0
                val sy = ty * ty * (3f - 2f * ty)
                val py0 = perm[y0 and 255]
                val py1 = perm[(y0 + 1) and 255]
                for (x in 0 until width) {
                    val fx = x * frequency + octave * 31.7f
                    val x0 = fx.toInt()
                    val tx = fx - x0
                    val sx = tx * tx * (3f - 2f * tx)
                    val xi = x0 and 255
                    val g00 = perm[xi + py0]; val g10 = perm[xi + 1 + py0]
                    val g01 = perm[xi + py1]; val g11 = perm[xi + 1 + py1]
                    val n00 = gradX[g00] * tx + gradY[g00] * ty
                    val n10 = gradX[g10] * (tx - 1f) + gradY[g10] * ty
                    val n01 = gradX[g01] * tx + gradY[g01] * (ty - 1f)
                    val n11 = gradX[g11] * (tx - 1f) + gradY[g11] * (ty - 1f)
                    val top = n00 + sx * (n10 - n00)
                    val bottom = n01 + sx * (n11 - n01)
                    row[x] += amplitude * (top + sy * (bottom - top))
                }
                frequency *= 2f
                amplitude *= 0.5f
            }
            val base = y * width
            for (x in 0 until width) out[base + x] = if (row[x] > THRESHOLD) WHITE else BLACK
        }
        return out
    }
}
