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
 * Flow (see scripts/preprocess_samples.sh, which wraps all of this):
 *   1. push your JPEGs to the app's external files dir, subfolder "samples/"
 *      (staged on the laptop in app/src/test/resources/samples/)
 *   2. run this test:  ./gradlew :app:connectedDebugAndroidTest
 *   3. pull "<name>_pre.jpg" back next to each input and eyeball them.
 *
 * It logs the output dimensions and file size for each sample and asserts the
 * ~500 KB upload budget from docs/api.md (413 over 2 MB) is respected.
 */
@RunWith(AndroidJUnit4::class)
class PreprocessorSampleTest {

    private val tag = "PreprocSamples"
    private val maxBytes = 500 * 1024

    @Test
    fun preprocessSamples() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "samples")
        if (!dir.isDirectory) {
            Log.w(tag, "No samples dir at ${dir.absolutePath}. Push JPEGs there first (see scripts/preprocess_samples.sh).")
        }
        val inputs = dir.listFiles { f ->
            f.isFile &&
                f.extension.lowercase() in setOf("jpg", "jpeg", "png") &&
                !f.nameWithoutExtension.endsWith("_pre")
        }?.sortedBy { it.name } ?: emptyList()

        assertTrue(
            "No sample images in ${dir.absolutePath} — push 5–10 handwritten-page JPEGs there first.",
            inputs.isNotEmpty(),
        )

        val oversized = mutableListOf<String>()
        for (img in inputs) {
            val t0 = System.currentTimeMillis()
            val result = Preprocessor.process(ctx, img)
            val ms = System.currentTimeMillis() - t0
            val dest = File(dir, img.nameWithoutExtension + "_pre.jpg")
            result.copyTo(dest, overwrite = true)

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(dest.absolutePath, opts)
            val kb = dest.length() / 1024
            Log.i(tag, "${img.name} -> ${dest.name}: ${opts.outWidth}x${opts.outHeight}, ${kb} KB, ${ms} ms")
            if (dest.length() > maxBytes) oversized += "${dest.name} (${kb} KB)"
        }

        Log.i(tag, "Processed ${inputs.size} sample(s). Pull them with: adb pull ${dir.absolutePath}")
        assertTrue("Output(s) exceed ~500 KB budget: $oversized", oversized.isEmpty())
    }
}
