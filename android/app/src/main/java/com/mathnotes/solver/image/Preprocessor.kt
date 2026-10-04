package com.mathnotes.solver.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream

/**
 * On-device cleanup before upload (architecture doc §3.3.1):
 *   grayscale → blur → Canny → largest quadrilateral → perspective warp → adaptive threshold.
 * Falls back to grayscale + resize when no page-like contour is found.
 *
 * Output: a JPEG under filesDir/images, ≈200–500 KB, ready for /recognize.
 */
object Preprocessor {
    private const val MAX_SIDE = 1600
    private const val JPEG_QUALITY = 85

    private val loaded: Boolean by lazy { OpenCVLoader.initLocal() }

    fun process(context: Context, input: File): File {
        val bitmap = decodeUpright(input)
        val out = File(context.filesDir, "images").apply { mkdirs() }
            .resolve(input.nameWithoutExtension + "_pre.jpg")
        val result = if (loaded) opencvPipeline(bitmap) else fallback(bitmap)
        FileOutputStream(out).use { result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        return out
    }

    private fun opencvPipeline(src: Bitmap): Bitmap {
        val rgba = Mat().also { Utils.bitmapToMat(src, it) }
        val gray = Mat().also { Imgproc.cvtColor(rgba, it, Imgproc.COLOR_RGBA2GRAY) }
        val scaled = downscale(gray)

        // Page detection
        val blurred = Mat().also { Imgproc.GaussianBlur(scaled, it, Size(5.0, 5.0), 0.0) }
        val edges = Mat().also { Imgproc.Canny(blurred, it, 50.0, 150.0) }
        Imgproc.dilate(edges, edges, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val page = contours
            .filter { Imgproc.contourArea(it) > 0.2 * scaled.rows() * scaled.cols() }
            .maxByOrNull { Imgproc.contourArea(it) }
            ?.let { quad(it) }

        val warped = if (page != null) warp(scaled, page) else scaled

        // Lighting normalization: adaptive threshold keeps ink, drops shadows.
        val bin = Mat()
        Imgproc.adaptiveThreshold(warped, bin, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 31, 15.0)

        val outBmp = Bitmap.createBitmap(bin.cols(), bin.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(bin, outBmp)
        listOf(rgba, gray, scaled, blurred, edges, warped, bin).forEach { it.release() }
        return outBmp
    }

    /** Approximate a contour to 4 points; null if it is not roughly a quadrilateral. */
    private fun quad(contour: MatOfPoint): MatOfPoint2f? {
        val c2f = MatOfPoint2f(*contour.toArray())
        val peri = Imgproc.arcLength(c2f, true)
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
        return if (approx.total() == 4L) approx else null
    }

    private fun warp(src: Mat, quad: MatOfPoint2f): Mat {
        val pts = quad.toArray().sortedBy { it.y }
        val (top, bottom) = pts.take(2).sortedBy { it.x } to pts.drop(2).sortedBy { it.x }
        val tl = top[0]; val tr = top[1]; val bl = bottom[0]; val br = bottom[1]
        val w = maxOf(dist(tl, tr), dist(bl, br)).toInt()
        val h = maxOf(dist(tl, bl), dist(tr, br)).toInt()
        val srcPts = MatOfPoint2f(tl, tr, br, bl)
        val dstPts = MatOfPoint2f(Point(0.0, 0.0), Point(w - 1.0, 0.0), Point(w - 1.0, h - 1.0), Point(0.0, h - 1.0))
        val m = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        return Mat().also { Imgproc.warpPerspective(src, it, m, Size(w.toDouble(), h.toDouble())) }
    }

    private fun downscale(m: Mat): Mat {
        val longest = maxOf(m.rows(), m.cols())
        if (longest <= MAX_SIDE) return m
        val f = MAX_SIDE.toDouble() / longest
        return Mat().also { Imgproc.resize(m, it, Size(), f, f, Imgproc.INTER_AREA) }
    }

    private fun dist(a: Point, b: Point) = Math.hypot(a.x - b.x, a.y - b.y)

    private fun fallback(src: Bitmap): Bitmap {
        val longest = maxOf(src.width, src.height)
        val f = if (longest > MAX_SIDE) MAX_SIDE.toFloat() / longest else 1f
        return Bitmap.createScaledBitmap(src, (src.width * f).toInt(), (src.height * f).toInt(), true)
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
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}
