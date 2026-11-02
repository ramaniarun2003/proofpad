package com.mathnotes.solver.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [CropMapping]. No Android/OpenCV on the classpath here,
 * which is exactly why the mapping math lives in its own file.
 */
class CropMappingTest {

    private fun assertNear(label: String, expected: Int, actual: Int, tol: Int = 2) {
        assertTrue(
            "$label: expected ~$expected but was $actual",
            kotlin.math.abs(expected - actual) <= tol,
        )
    }

    /**
     * Known case: landscape sensor 4000x3000 rotated 90° to a 3000x4000 upright image,
     * shown in a 1080x1920 portrait PreviewView under FILL_CENTER.
     *
     * FILL scale s = max(1080/3000, 1920/4000) = 0.48; the image is 1440px wide drawn in
     * a 1080px view, so it overflows horizontally by 180px each side (offX = -180, offY = 0).
     * The 90%-wide x 35%-tall centered guide (left .05, top .325) with a 5% margin maps to
     * roughly (386, 1230)..(2614, 2770) in upright pixels — worked out by hand below.
     */
    @Test
    fun guideBox_rot90_fillCenter_withMargin() {
        val r = CropMapping.guideBoxToImageRect(
            viewW = 1080f, viewH = 1920f,
            rawW = 4000, rawH = 3000, rotationDegrees = 90,
            guideLeftFrac = 0.05f, guideTopFrac = 0.325f,
            guideWidthFrac = 0.90f, guideHeightFrac = 0.35f,
            marginFrac = 0.05f,
            scaleType = CropScaleType.FILL_CENTER,
        )
        assertNear("left", 386, r.left)
        assertNear("top", 1230, r.top)
        assertNear("right", 2614, r.right)
        assertNear("bottom", 2770, r.bottom)
        // Stays inside the upright 3000x4000 frame.
        assertTrue(r.left >= 0 && r.top >= 0 && r.right <= 3000 && r.bottom <= 4000)
    }

    /**
     * Rotation must actually change the result: the same inputs with no rotation produce
     * a different rectangle (upright frame is now 4000x3000, so FILL overflows vertically).
     */
    @Test
    fun guideBox_rotationChangesMapping() {
        fun rectFor(deg: Int) = CropMapping.guideBoxToImageRect(
            viewW = 1080f, viewH = 1920f,
            rawW = 4000, rawH = 3000, rotationDegrees = deg,
            guideLeftFrac = 0.05f, guideTopFrac = 0.325f,
            guideWidthFrac = 0.90f, guideHeightFrac = 0.35f,
            marginFrac = 0f,
        )
        assertTrue("rotation should change the mapped rect", rectFor(0) != rectFor(90))
    }

    /** FIT_CENTER with matched aspect ratios: the guide fractions map straight through. */
    @Test
    fun guideBox_fitCenter_matchedAspect() {
        val r = CropMapping.guideBoxToImageRect(
            viewW = 1000f, viewH = 2000f,
            rawW = 1000, rawH = 2000, rotationDegrees = 0,
            guideLeftFrac = 0.10f, guideTopFrac = 0.20f,
            guideWidthFrac = 0.80f, guideHeightFrac = 0.40f,
            marginFrac = 0f,
            scaleType = CropScaleType.FIT_CENTER,
        )
        assertEquals(100, r.left)
        assertEquals(400, r.top)
        assertEquals(900, r.right)
        assertEquals(1200, r.bottom)
    }

    /** Normalized gallery rect resolves to pixels and clamps to the frame. */
    @Test
    fun normalizedRect_clampsToFrame() {
        val r = CropMapping.normalizedToImageRect(
            leftFrac = 0.1f, topFrac = 0.1f, rightFrac = 1.2f, bottomFrac = 0.5f,
            uprW = 2000, uprH = 1000, marginFrac = 0f,
        )
        assertEquals(200, r.left)
        assertEquals(100, r.top)
        assertEquals(2000, r.right)   // clamped from 1.2 -> 1.0
        assertEquals(500, r.bottom)
    }
}
