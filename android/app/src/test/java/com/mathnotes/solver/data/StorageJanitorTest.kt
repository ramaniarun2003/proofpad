package com.mathnotes.solver.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Storage clean-up: what is deleted, and above all what is not. */
class StorageJanitorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_000_000_000_000L
    private val h = StorageJanitor.HOUR
    private lateinit var cache: File
    private lateinit var files: File

    private fun file(dir: File, path: String, ageHours: Double, size: Int = 1000): File {
        val f = File(dir, path)
        f.parentFile!!.mkdirs()
        f.writeBytes(ByteArray(size))
        f.setLastModified(now - (ageHours * h).toLong())
        return f
    }

    private fun setUp() {
        cache = tmp.newFolder("cache"); files = tmp.newFolder("files")
    }

    @Test fun old_originals_go_recent_ones_stay() {
        setUp()
        val oldCapture = file(cache, "capture_1.jpg", 2.0, size = 4_000_000)
        val oldPicked = file(cache, "picked_1.jpg", 30.0)
        val fresh = file(cache, "capture_2.jpg", 0.2)
        val unrelated = file(cache, "something_else.jpg", 99.0)
        val report = StorageJanitor.sweep(cache, files, emptySet(), emptySet(), now = now)
        assertFalse(oldCapture.exists()); assertFalse(oldPicked.exists())
        assertTrue(fresh.exists()); assertTrue(unrelated.exists())
        assertEquals(StorageJanitor.Report(files = 2, bytes = 4_001_000), report)
    }

    @Test fun a_notes_photo_is_never_deleted_but_an_orphan_is() {
        setUp()
        val kept = file(files, "images/a_pre.jpg", 500.0)
        val orphan = file(files, "images/b_pre.jpg", 3.0)
        val wizardPage = file(files, "images/c_page.jpg", 3.0)
        val justWritten = file(files, "images/d_pre.jpg", 0.1)      // a capture not saved to the database yet
        StorageJanitor.sweep(cache, files, noteImages = setOf(kept.absolutePath), sampleImages = emptySet(), now = now)
        assertTrue(kept.exists()); assertTrue(justWritten.exists())
        assertFalse(orphan.exists()); assertFalse(wizardPage.exists())
    }

    @Test fun files_of_the_capture_in_progress_are_protected_whatever_their_age() {
        setUp()
        val raw = file(cache, "capture_9.jpg", 48.0)
        val pre = file(files, "images/capture_9_pre.jpg", 48.0)
        StorageJanitor.sweep(cache, files, emptySet(), emptySet(), protect = setOf(raw.absolutePath, pre.absolutePath), now = now)
        assertTrue(raw.exists()); assertTrue(pre.exists())
    }

    @Test fun handwriting_thumbnails_follow_the_saved_samples() {
        setUp()
        val used = file(files, "handwriting/line_1_0.jpg", 200.0)
        val stale = file(files, "handwriting/line_0_0.jpg", 200.0)
        StorageJanitor.sweep(cache, files, emptySet(), sampleImages = setOf(used.absolutePath), now = now)
        assertTrue(used.exists()); assertFalse(stale.exists())
    }

    @Test fun exports_last_a_day_and_debug_stages_a_week() {
        setUp()
        val pdfOld = file(cache, "exports/solution_a.pdf", 25.0)
        val pdfNew = file(cache, "exports/solution_b.pdf", 5.0)
        val dbgOld = file(files, "debug/1_2gray.jpg", 8 * 24.0)
        val dbgNew = file(files, "debug/2_2gray.jpg", 24.0)
        StorageJanitor.sweep(cache, files, emptySet(), emptySet(), now = now)
        assertFalse(pdfOld.exists()); assertTrue(pdfNew.exists())
        assertFalse(dbgOld.exists()); assertTrue(dbgNew.exists())
    }

    @Test fun free_up_space_ignores_age_but_not_references_or_protection() {
        setUp()
        val note = file(files, "images/a_pre.jpg", 0.0)
        val inUse = file(cache, "capture_now.jpg", 0.0)
        val fresh = listOf(
            file(cache, "capture_2.jpg", 0.0), file(files, "images/orphan_pre.jpg", 0.0),
            file(cache, "exports/x.pdf", 0.0), file(files, "debug/1_raw.jpg", 0.0), file(files, "handwriting/t.jpg", 0.0),
        )
        val before = StorageJanitor.usage(cache, files)
        val report = StorageJanitor.sweep(cache, files, setOf(note.absolutePath), emptySet(), protect = setOf(inUse.absolutePath), now = now, minAge = 0)
        assertTrue(note.exists()); assertTrue(inUse.exists())
        fresh.forEach { assertFalse(it.name, it.exists()) }
        assertEquals(5, report.files)
        assertEquals(before - report.bytes, StorageJanitor.usage(cache, files))
        assertEquals(2000L, StorageJanitor.usage(cache, files))
    }

    @Test fun missing_directories_are_not_an_error() {
        val empty = tmp.newFolder("empty")
        assertEquals(StorageJanitor.Report(), StorageJanitor.sweep(File(empty, "cache"), File(empty, "files"), emptySet(), emptySet(), now = now))
    }
}
