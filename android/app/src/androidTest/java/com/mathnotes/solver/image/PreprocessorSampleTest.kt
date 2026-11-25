package com.mathnotes.solver.image

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * CTC 1 proof: run the real [Preprocessor] on committed notebook photos and assert it crops to the
 * guide box, not the full frame. OpenCV ships as an Android .aar (native .so only), so the pipeline
 * can't run in a plain JVM unit test — this is instrumented and runs on a device/emulator.
 *
 * Inputs live in the test APK assets under "samples": a few small JPEGs plus a `samples.json` listing
 * each file's expected crop rect (normalized fractions). For each sample we crop with that rect and
 * check the output's aspect ratio matches the crop, not the original frame — i.e. the guide-box crop
 * actually happened. scripts/preprocess_samples.sh can stage more photos and pull the "_pre.jpg"
 * outputs back for eyeballing.
 */
@RunWith(AndroidJUnit4::class)
class PreprocessorSampleTest {

    private val tag = "PreprocSamples"
    private val maxBytes = 500 * 1024

    @Test
    fun preprocessSamples() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val testAssets = inst.context.assets          // test APK assets (the inputs)
        val ctx = inst.targetContext                  // app under test (OpenCV + filesDir)

        val specs = JSONArray(testAssets.open("samples/samples.json").bufferedReader().use { it.readText() })
        assertTrue(
            "No samples in samples.json — run scripts/preprocess_samples.sh to stage photos.",
            specs.length() > 0,
        )

        val outDir = File(ctx.getExternalFilesDir(null), "samples").apply { mkdirs() }
        val oversized = mutableListOf<String>()
        for (i in 0 until specs.length()) {
            val spec = specs.getJSONObject(i)
            val name = spec.getString("file")
            val input = File(ctx.cacheDir, name)
            testAssets.open("samples/$name").use { s -> input.outputStream().use { o -> s.copyTo(o) } }

            // Original (upright) dimensions.
            val src = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(input.absolutePath, src)
            val fullAspect = src.outWidth.toFloat() / src.outHeight

            val l = spec.getDouble("leftFrac").toFloat()
            val t = spec.getDouble("topFrac").toFloat()
            val r = spec.getDouble("rightFrac").toFloat()
            val b = spec.getDouble("bottomFrac").toFloat()
            val crop = CropSpec.NormalizedRect(l, t, r, b, spec.optDouble("marginFrac", 0.0).toFloat())
            val cropAspect = ((r - l) * src.outWidth) / ((b - t) * src.outHeight)

            val t0 = System.currentTimeMillis()
            val result = Preprocessor.process(ctx, input, crop)
            val ms = System.currentTimeMillis() - t0

            val dest = File(outDir, input.nameWithoutExtension + "_pre.jpg")
            result.copyTo(dest, overwrite = true)

            val out = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(dest.absolutePath, out)
            val outAspect = out.outWidth.toFloat() / out.outHeight
            val kb = dest.length() / 1024
            Log.i(tag, "$name ${src.outWidth}x${src.outHeight} -> ${out.outWidth}x${out.outHeight} " +
                "(${kb} KB, ${ms} ms) cropAspect=$cropAspect fullAspect=$fullAspect outAspect=$outAspect")

            // The guide-box crop changed the aspect ratio: the output must look like the crop, not the frame.
            assertTrue(
                "$name: output aspect $outAspect is closer to the full frame ($fullAspect) than the " +
                    "guide-box crop ($cropAspect) — the crop was not applied",
                abs(outAspect - cropAspect) < abs(outAspect - fullAspect),
            )
            if (dest.length() > maxBytes) oversized += "${dest.name} (${kb} KB)"
        }

        Log.i(tag, "Processed ${specs.length()} sample(s) -> ${outDir.absolutePath}")
        assertTrue("Output(s) exceed ~500 KB budget: $oversized", oversized.isEmpty())
    }
}
