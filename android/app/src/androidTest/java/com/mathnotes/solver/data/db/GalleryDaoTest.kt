package com.mathnotes.solver.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mathnotes.solver.data.repository.GalleryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * In-memory Room tests for the gallery data layer: folder counts, the past-week
 * boundary, delete-folder-keeps-notes, and tag search. Instrumented because Room
 * needs an Android Context.
 */
@RunWith(AndroidJUnit4::class)
class GalleryDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var gallery: GalleryDao
    private lateinit var sessions: SessionDao

    private val now = 1_700_000_000_000L
    private val week = GalleryRepository.WEEK_MS

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        gallery = db.gallery()
        sessions = db.sessions()
    }

    @After
    fun tearDown() = db.close()

    private fun note(
        id: String,
        folderId: Long? = null,
        savedAt: Long? = null,
        lastViewedAt: Long? = null,
        tags: List<String> = emptyList(),
        title: String? = null,
        verified: Boolean? = true,
        topic: String = "integral",
        difficulty: Difficulty? = null,
    ) = SessionEntity(
        sessionId = id,
        recognizedLatex = "\\int x \\, dx",
        confirmedLatex = "\\int x \\, dx",
        topic = topic,
        verified = verified,
        folderId = folderId,
        savedAt = savedAt,
        lastViewedAt = lastViewedAt,
        tags = tags,
        title = title,
        difficulty = difficulty,
    )

    @Test
    fun folderCounts() = runBlocking {
        val a = gallery.insertFolder(FolderEntity(name = "Calc 1", colorIndex = 0))
        val b = gallery.insertFolder(FolderEntity(name = "Calc 2", colorIndex = 1))
        sessions.upsert(note("1", folderId = a, savedAt = now))
        sessions.upsert(note("2", folderId = a, savedAt = now))
        sessions.upsert(note("3", folderId = b, savedAt = now))
        sessions.upsert(note("4")) // not filed in any folder

        val counts = gallery.foldersWithCounts().first().associate { it.folder.name to it.noteCount }
        assertEquals(2, counts["Calc 1"])
        assertEquals(1, counts["Calc 2"])
    }

    @Test
    fun pastWeekBoundary() = runBlocking {
        val f = gallery.insertFolder(FolderEntity(name = "F", colorIndex = 0))
        sessions.upsert(note("in_now", folderId = f, savedAt = now))
        sessions.upsert(note("in_boundary", folderId = f, savedAt = now - week)) // >= since → in
        sessions.upsert(note("out", folderId = f, savedAt = now - week - 1))      // < since → out

        val ids = gallery.savedSince(now - week).first().map { it.sessionId }.toSet()
        assertEquals(setOf("in_now", "in_boundary"), ids)
    }

    @Test
    fun deleteFolderKeepsNotes() = runBlocking {
        val f = gallery.insertFolder(FolderEntity(name = "Temp", colorIndex = 0))
        sessions.upsert(note("keep", folderId = f, savedAt = now))

        gallery.deleteFolder(f)

        val n = sessions.get("keep")
        assertNotNull("note must survive folder deletion", n)
        assertNull("its folderId must be reset to null", n!!.folderId)
        assertTrue("folder must be gone", gallery.foldersWithCounts().first().isEmpty())
    }

    @Test
    fun searchMatchesTags() = runBlocking {
        val f = gallery.insertFolder(FolderEntity(name = "F", colorIndex = 0))
        sessions.upsert(note("t1", folderId = f, savedAt = now, tags = listOf("chain-rule", "hard"), title = "Derivative drill"))
        sessions.upsert(note("t2", folderId = f, savedAt = now, tags = listOf("u-sub"), title = "Integral"))

        assertEquals(listOf("t1"), gallery.search("chain").first().map { it.sessionId })
        assertEquals(listOf("t1"), gallery.search("drill").first().map { it.sessionId }) // title match too
        assertTrue(gallery.search("nonexistent").first().isEmpty())
    }
}
