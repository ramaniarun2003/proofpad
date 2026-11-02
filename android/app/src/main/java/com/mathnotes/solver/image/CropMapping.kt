package com.mathnotes.solver.image

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure geometry for mapping the on-screen framing guide to pixel coordinates in
 * the captured image. Deliberately free of Android / OpenCV types so it can run
 * in a plain JVM unit test (see app/src/test/.../CropMappingTest.kt) — the real
 * pipeline needs OpenCV native libs, but this math does not.
 */

/** How the camera preview is fitted into the PreviewView. CameraX defaults to FILL_CENTER. */
enum class CropScaleType { FILL_CENTER, FIT_CENTER }

/** A rectangle in integer pixel coordinates of the upright image. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * How the crop was specified. Resolved to a [PixelRect] once the image's upright
 * pixel size is known (in [Preprocessor]).
 */
sealed interface CropSpec {
    /**
     * Camera path: the guide box drawn on the PreviewView, expressed as fractions
     * of the view, plus the view's pixel size and the preview scale type. Because
     * FILL_CENTER crops the preview, the visible region is only a sub-rectangle of
     * the full sensor frame, which this accounts for.
     */
    data class GuideBox(
        val viewWidth: Float,
        val viewHeight: Float,
        val leftFrac: Float,
        val topFrac: Float,
        val widthFrac: Float,
        val heightFrac: Float,
        val marginFrac: Float = 0.05f,
        val scaleType: CropScaleType = CropScaleType.FILL_CENTER,
    ) : CropSpec

    /**
     * Gallery path: a rectangle the user dragged over the whole picked image,
     * already in fractions of the upright image (0..1). No preview mapping needed.
     */
    data class NormalizedRect(
        val leftFrac: Float,
        val topFrac: Float,
        val rightFrac: Float,
        val bottomFrac: Float,
        val marginFrac: Float = 0.05f,
    ) : CropSpec
}

object CropMapping {

    /**
     * Map the framing guide to a pixel rectangle in the *upright* image.
     *
     * @param viewW,viewH      PreviewView size in px.
     * @param rawW,rawH        decoded JPEG size in px, BEFORE EXIF rotation is applied.
     * @param rotationDegrees  EXIF rotation to upright: 0/90/180/270. For 90/270 the
     *                         upright image swaps width/height, which shifts the FILL
     *                         crop — this is the "accounting for EXIF rotation" part.
     * @return rectangle in upright-image pixels, clamped to the image and never empty.
     */
    fun guideBoxToImageRect(
        viewW: Float,
        viewH: Float,
        rawW: Int,
        rawH: Int,
        rotationDegrees: Int,
        guideLeftFrac: Float,
        guideTopFrac: Float,
        guideWidthFrac: Float,
        guideHeightFrac: Float,
        marginFrac: Float = 0f,
        scaleType: CropScaleType = CropScaleType.FILL_CENTER,
    ): PixelRect {
        // Dimensions of the image as the preview and the decoded upright bitmap see it.
        val swap = rotationDegrees == 90 || rotationDegrees == 270
        val uprW = (if (swap) rawH else rawW).toFloat()
        val uprH = (if (swap) rawW else rawH).toFloat()

        // Uniform scale that maps the upright image into the view.
        val sx = viewW / uprW
        val sy = viewH / uprH
        val s = when (scaleType) {
            CropScaleType.FILL_CENTER -> max(sx, sy)   // fill view, overflow one axis
            CropScaleType.FIT_CENTER -> min(sx, sy)    // fit view, letterbox one axis
        }
        // Where the scaled image's top-left sits in view space (negative under FILL).
        val offX = (viewW - uprW * s) / 2f
        val offY = (viewH - uprH * s) / 2f

        // Guide rectangle in view pixels.
        val vx0 = guideLeftFrac * viewW
        val vy0 = guideTopFrac * viewH
        val vx1 = (guideLeftFrac + guideWidthFrac) * viewW
        val vy1 = (guideTopFrac + guideHeightFrac) * viewH

        // Invert the view transform to land in upright-image pixels.
        var ix0 = (vx0 - offX) / s
        var iy0 = (vy0 - offY) / s
        var ix1 = (vx1 - offX) / s
        var iy1 = (vy1 - offY) / s

        // Grow by a margin so a slightly misframed expression is not clipped.
        val mw = (ix1 - ix0) * marginFrac
        val mh = (iy1 - iy0) * marginFrac
        ix0 -= mw; ix1 += mw; iy0 -= mh; iy1 += mh

        return clampRect(ix0, iy0, ix1, iy1, uprW, uprH)
    }

    /** Resolve a normalized (0..1) rectangle to upright-image pixels, with margin. */
    fun normalizedToImageRect(
        leftFrac: Float,
        topFrac: Float,
        rightFrac: Float,
        bottomFrac: Float,
        uprW: Int,
        uprH: Int,
        marginFrac: Float = 0f,
    ): PixelRect {
        var ix0 = leftFrac * uprW
        var iy0 = topFrac * uprH
        var ix1 = rightFrac * uprW
        var iy1 = bottomFrac * uprH
        val mw = (ix1 - ix0) * marginFrac
        val mh = (iy1 - iy0) * marginFrac
        ix0 -= mw; ix1 += mw; iy0 -= mh; iy1 += mh
        return clampRect(ix0, iy0, ix1, iy1, uprW.toFloat(), uprH.toFloat())
    }

    private fun clampRect(x0: Float, y0: Float, x1: Float, y1: Float, w: Float, h: Float): PixelRect {
        val l = x0.coerceIn(0f, w)
        val t = y0.coerceIn(0f, h)
        val r = x1.coerceIn(0f, w)
        val b = y1.coerceIn(0f, h)
        // Round, then guarantee at least a 1px, in-bounds rectangle.
        var li = l.roundToInt()
        var ti = t.roundToInt()
        var ri = r.roundToInt()
        var bi = b.roundToInt()
        if (ri <= li) { li = li.coerceAtMost(w.toInt() - 1).coerceAtLeast(0); ri = li + 1 }
        if (bi <= ti) { ti = ti.coerceAtMost(h.toInt() - 1).coerceAtLeast(0); bi = ti + 1 }
        return PixelRect(li, ti, ri, bi)
    }
}
