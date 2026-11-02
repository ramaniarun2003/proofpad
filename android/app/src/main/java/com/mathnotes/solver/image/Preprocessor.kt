package com.mathnotes.solver.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * On-device cleanup before upload. The pipeline, in order:
 *   1. decode upright (EXIF) → 2. crop to the framing guide → 3. grayscale →
 *   (optional) safe perspective warp → mild denoise → CLAHE → shadow flatten →
 *   cap long side to 1600px → grayscale JPEG.
 *
 * Deliberately *gentle*: Mathpix reads grayscale photos of handwriting well, and
 * the old hard adaptive threshold was erasing thin pencil strokes, so binarization
 * is now opt-in (useAdaptiveThreshold) only for A/B comparison. Page detection is
 * also opt-in: the warp is applied only when a confidently page-like quadrilateral
 * is found, never as a whole-frame fallback.
 *
 * Output: a grayscale JPEG under filesDir/images, ready for /recognize.
 */
object Preprocessor {
    private const val TAG = "Preprocessor"
    private const val MAX_SIDE = 1600
    private const val JPEG_QUALITY = 85

    private val loaded: Boolean by lazy { OpenCVLoader.initLocal() }

    /**
     * @param crop                 how to crop; null means use the whole (upright) image.
     * @param debug                write each stage to filesDir/debug/<ts>_<stage>.jpg.
     * @param useAdaptiveThreshold binarize at the end (old behaviour) instead of gentle grayscale.
     */
    fun process(
        context: Context,
        input: File,
        crop: CropSpec? = null,
        debug: Boolean = false,
        useAdaptiveThreshold: Boolean = false,
    ): File {
        val ts = System.currentTimeMillis().toString()
        val upright = decodeUpright(input)
        val srcW = upright.width
        val srcH = upright.height
        if (debug) saveDebug(context, ts, "0raw", upright)

        // 1. Crop to the guide box (+ margin) before anything else runs.
        val rect = resolveRect(crop, srcW, srcH)
        val cropped = Bitmap.createBitmap(upright, rect.left, rect.top, rect.width, rect.height)
        if (cropped !== upright) upright.recycle()
        if (debug) saveDebug(context, ts, "1crop", cropped)
        Log.i(TAG, "crop: ${srcW}x$srcH guide -> ${rect.width}x${rect.height} at (${rect.left},${rect.top})")

        val out = File(context.filesDir, "images").apply { mkdirs() }
            .resolve(input.nameWithoutExtension + "_pre.jpg")
        val result = if (loaded) {
            opencvPipeline(context, cropped, ts, debug, useAdaptiveThreshold)
        } else {
            Log.w(TAG, "OpenCV not loaded — falling back to grayscale + resize")
            fallback(cropped)
        }
        FileOutputStream(out).use { result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (debug) saveDebug(context, ts, "4final", result)
        return out
    }

    private fun resolveRect(crop: CropSpec?, w: Int, h: Int): PixelRect = when (crop) {
        null -> PixelRect(0, 0, w, h)
        // Defensive: if the preview was never measured, don't crop to a degenerate rect.
        is CropSpec.GuideBox -> if (crop.viewWidth <= 0f || crop.viewHeight <= 0f) PixelRect(0, 0, w, h)
        // `upright` is already EXIF-rotated, so rotationDegrees = 0 here; the mapping's
        // rotation handling is exercised by CropMappingTest.
        else CropMapping.guideBoxToImageRect(
            viewW = crop.viewWidth, viewH = crop.viewHeight,
            rawW = w, rawH = h, rotationDegrees = 0,
            guideLeftFrac = crop.leftFrac, guideTopFrac = crop.topFrac,
            guideWidthFrac = crop.widthFrac, guideHeightFrac = crop.heightFrac,
            marginFrac = crop.marginFrac, scaleType = crop.scaleType,
        )
        is CropSpec.NormalizedRect -> CropMapping.normalizedToImageRect(
            crop.leftFrac, crop.topFrac, crop.rightFrac, crop.bottomFrac, w, h, crop.marginFrac,
        )
    }

    private fun opencvPipeline(
        context: Context,
        src: Bitmap,
        ts: String,
        debug: Boolean,
        useAdaptiveThreshold: Boolean,
    ): Bitmap {
        val scratch = LinkedHashSet<Mat>()
        fun track(m: Mat) = m.also { scratch.add(it) }

        val rgba = track(Mat()).also { Utils.bitmapToMat(src, it) }
        val gray = track(Mat()).also { Imgproc.cvtColor(rgba, it, Imgproc.COLOR_RGBA2GRAY) }

        // 4. Cap long side to 1600 after cropping; never upscale a small crop.
        val capped = track(capLongSide(gray))
        if (debug) saveDebug(context, ts, "2gray", capped)

        // 3. Opt-in, safe perspective warp (logs which branch it took).
        val geo = track(maybeWarp(capped))

        // Gentle tonal pipeline: mild denoise → CLAHE → shadow flatten.
        val den = track(Mat()).also { Imgproc.bilateralFilter(geo, it, 5, 50.0, 50.0) }
        val eq = track(Mat())
        Imgproc.createCLAHE(2.0, Size(8.0, 8.0)).apply(den, eq)
        val flat = track(flattenShadows(eq))
        if (debug) saveDebug(context, ts, "3flat", flat)

        val finalMat = if (useAdaptiveThreshold) {
            track(Mat()).also {
                Imgproc.adaptiveThreshold(flat, it, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 31, 15.0)
            }
        } else {
            flat
        }

        val outBmp = Bitmap.createBitmap(finalMat.cols(), finalMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(finalMat, outBmp)
        scratch.forEach { it.release() }
        if (src !== outBmp) src.recycle()
        return outBmp
    }

    /** Resize so the long side is at most [MAX_SIDE]. Returns a fresh Mat; does not upscale. */
    private fun capLongSide(m: Mat): Mat {
        val longest = max(m.rows(), m.cols())
        if (longest <= MAX_SIDE) return m.clone()
        val f = MAX_SIDE.toDouble() / longest
        return Mat().also { Imgproc.resize(m, it, Size(), f, f, Imgproc.INTER_AREA) }
    }

    /** Divide by a large-kernel blur to flatten uneven lighting / desk-lamp shadows. */
    private fun flattenShadows(gray: Mat): Mat {
        var k = (max(gray.rows(), gray.cols()) / 8)
        if (k % 2 == 0) k += 1
        k = k.coerceIn(31, 101)
        val bg = Mat().also { Imgproc.GaussianBlur(gray, it, Size(k.toDouble(), k.toDouble()), 0.0) }
        val flat = Mat().also { Core.divide(gray, bg, it, 255.0) }
        bg.release()
        return flat
    }

    /**
     * Opt-in, safe page detection. Warp only when a quadrilateral covers 20–95% of
     * the crop and has roughly rectangular corners; otherwise return the input
     * unchanged (no whole-frame warp). The branch taken is logged.
     */
    private fun maybeWarp(gray: Mat): Mat {
        val area = gray.rows().toDouble() * gray.cols()
        val blurred = Mat().also { Imgproc.GaussianBlur(gray, it, Size(5.0, 5.0), 0.0) }
        val edges = Mat().also { Imgproc.Canny(blurred, it, 50.0, 150.0) }
        Imgproc.dilate(edges, edges, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val page = contours
            .mapNotNull { quad(it) }
            .filter {
                val frac = Imgproc.contourArea(it) / area
                frac in 0.2..0.95 && isRectangular(orderQuad(it.toArray()))
            }
            .maxByOrNull { Imgproc.contourArea(it) }

        blurred.release(); edges.release()
        return if (page != null) {
            Log.i(TAG, "page-warp: applied (safe quad found)")
            val w = warp(gray, page)
            page.release()
            w
        } else {
            Log.i(TAG, "page-warp: skipped (no page-like quad in 20-95% with square corners)")
            gray.clone()
        }
    }

    /** Approximate a contour to 4 points; null if it is not roughly a quadrilateral. */
    private fun quad(contour: MatOfPoint): MatOfPoint2f? {
        val c2f = MatOfPoint2f(*contour.toArray())
        val peri = Imgproc.arcLength(c2f, true)
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
        c2f.release()
        return if (approx.total() == 4L) approx else { approx.release(); null }
    }

    /** Order 4 points as top-left, top-right, bottom-right, bottom-left. */
    private fun orderQuad(pts: Array<Point>): List<Point> {
        val byY = pts.sortedBy { it.y }
        val top = byY.take(2).sortedBy { it.x }
        val bottom = byY.drop(2).sortedBy { it.x }
        return listOf(top[0], top[1], bottom[1], bottom[0]) // tl, tr, br, bl
    }

    /** True when every interior angle is within [60°, 120°] of a right angle. */
    private fun isRectangular(q: List<Point>): Boolean {
        for (i in 0 until 4) {
            val prev = q[(i + 3) % 4]
            val cur = q[i]
            val next = q[(i + 1) % 4]
            val a = angleDeg(prev, cur, next)
            if (a < 60.0 || a > 120.0) return false
        }
        return true
    }

    private fun angleDeg(a: Point, vertex: Point, b: Point): Double {
        val v1x = a.x - vertex.x; val v1y = a.y - vertex.y
        val v2x = b.x - vertex.x; val v2y = b.y - vertex.y
        val dot = v1x * v2x + v1y * v2y
        val n1 = sqrt(v1x * v1x + v1y * v1y)
        val n2 = sqrt(v2x * v2x + v2y * v2y)
        if (n1 == 0.0 || n2 == 0.0) return 0.0
        val c = (dot / (n1 * n2)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(c))
    }

    private fun warp(src: Mat, quad: MatOfPoint2f): Mat {
        val (tl, tr, br, bl) = orderQuad(quad.toArray())
        val w = max(dist(tl, tr), dist(bl, br)).toInt().coerceAtLeast(1)
        val h = max(dist(tl, bl), dist(tr, br)).toInt().coerceAtLeast(1)
        val srcPts = MatOfPoint2f(tl, tr, br, bl)
        val dstPts = MatOfPoint2f(Point(0.0, 0.0), Point(w - 1.0, 0.0), Point(w - 1.0, h - 1.0), Point(0.0, h - 1.0))
        val m = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val outMat = Mat().also { Imgproc.warpPerspective(src, it, m, Size(w.toDouble(), h.toDouble())) }
        srcPts.release(); dstPts.release(); m.release()
        return outMat
    }

    private fun dist(a: Point, b: Point) = Math.hypot(a.x - b.x, a.y - b.y)

    private fun fallback(src: Bitmap): Bitmap {
        val longest = max(src.width, src.height)
        val f = if (longest > MAX_SIDE) MAX_SIDE.toFloat() / longest else 1f
        // Grayscale via a saturation-free scale; keep it simple without OpenCV.
        val scaled = if (f < 1f) {
            Bitmap.createScaledBitmap(src, (src.width * f).toInt(), (src.height * f).toInt(), true)
        } else {
            src
        }
        return scaled
    }

    private fun saveDebug(context: Context, ts: String, stage: String, bmp: Bitmap) {
        val dir = File(context.filesDir, "debug").apply { mkdirs() }
        val f = File(dir, "${ts}_$stage.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        Log.i(TAG, "debug stage -> ${f.absolutePath}")
    }

    private fun saveDebug(context: Context, ts: String, stage: String, mat: Mat) {
        val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bmp)
        saveDebug(context, ts, stage, bmp)
        bmp.recycle()
    }

    /** CameraX JPEGs carry EXIF rotation; apply it so the recognizer sees upright text. */
    private fun decodeUpright(file: File): Bitmap {
        val bmp = BitmapFactory.decodeFile(file.absolutePath)
        val orientation = ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bmp
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
    }
}
