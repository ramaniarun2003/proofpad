package com.mathnotes.solver.image

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Debug harness for eyeballing [Preprocessor] on real notebook photos.
 *
 * OpenCV ships here as an Android .aar (native libs are Android .so only), so the
 * pipeline cannot run in a plain JVM unit test — this is an instrumented test that
 * runs on a connected device/emulator with the real OpenCV + Android Bitmap/EXIF.
 *
 * Inputs are read from the test APK's assets under "samples" (files pushed into
 * the app's external dir via adb aren't reliably readable by the app itself), and
 * outputs are written to the app's external files dir, which pulls back cleanly.
 * scripts/preprocess_samples.sh wires all of this together: it stages the JPEGs
 * from app/src/test/resources/samples into the test APK assets, builds, installs,
 * runs this test, and pulls each "<name>_pre.jpg" back next to its input.
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

        val names = (testAssets.list("samples") ?: emptyArray())
            .filter { it.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png") }
            .filter { !it.substringBeforeLast('.').endsWith("_pre") }
            .sorted()

        assertTrue(
            "No sample images packaged in the test APK — drop JPEGs in " +
                "app/src/test/resources/samples/ and run scripts/preprocess_samples.sh.",
            names.isNotEmpty(),
        )

        val outDir = File(ctx.getExternalFilesDir(null), "samples").apply { mkdirs() }
        val oversized = mutableListOf<String>()
        for (name in names) {
            val input = File(ctx.cacheDir, name)
            testAssets.open("samples/$name").use { i -> input.outputStream().use { o -> i.copyTo(o) } }

            val t0 = System.currentTimeMillis()
            val result = Preprocessor.process(ctx, input)
            val ms = System.currentTimeMillis() - t0

            val dest = File(outDir, input.nameWithoutExtension + "_pre.jpg")
            result.copyTo(dest, overwrite = true)

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(dest.absolutePath, opts)
            val kb = dest.length() / 1024
            Log.i(tag, "$name -> ${dest.name}: ${opts.outWidth}x${opts.outHeight}, ${kb} KB, ${ms} ms")
            if (dest.length() > maxBytes) oversized += "${dest.name} (${kb} KB)"
        }

        Log.i(tag, "Processed ${names.size} sample(s) -> ${outDir.absolutePath}")
        assertTrue("Output(s) exceed ~500 KB budget: $oversized", oversized.isEmpty())
    }
}
