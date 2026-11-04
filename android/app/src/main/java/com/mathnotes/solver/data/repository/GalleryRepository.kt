package com.mathnotes.solver.data.repository

import android.content.Context
import com.mathnotes.solver.data.db.AppDatabase
import com.mathnotes.solver.data.db.Difficulty
import com.mathnotes.solver.data.db.FolderEntity
import com.mathnotes.solver.data.db.FolderWithCount
import com.mathnotes.solver.data.db.SessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Entry point for the on-device gallery. Wraps [com.mathnotes.solver.data.db.GalleryDao]
 * and exposes Flows for the UI; nothing here touches the backend.
 */
class GalleryRepository(context: Context) {
    private val dao = AppDatabase.get(context.applicationContext).gallery()

    // --- Folders ---
    val folders: Flow<List<FolderWithCount>> = dao.foldersWithCounts()

    suspend fun createFolder(name: String, colorIndex: Int, term: String? = null, sortOrder: Int = 0): Long =
        dao.insertFolder(FolderEntity(name = name, colorIndex = colorIndex, term = term, sortOrder = sortOrder))

    suspend fun renameFolder(id: Long, name: String) = dao.renameFolder(id, name)

    /** Deletes the folder; its notes stay, with folderId reset to null. */
    suspend fun deleteFolder(id: Long) = dao.deleteFolder(id)

    // --- Note lists ---
    fun notesInFolder(
        folderId: Long,
        flaggedOnly: Boolean = false,
        topic: String? = null,
        difficulty: Difficulty? = null,
    ): Flow<List<SessionEntity>> = dao.notesInFolder(folderId, flaggedOnly, topic, difficulty)

    val recentlyViewed: Flow<List<SessionEntity>> = dao.recentlyViewed()

    fun savedThisWeek(now: Long = System.currentTimeMillis()): Flow<List<SessionEntity>> =
        dao.savedSince(now - WEEK_MS)

    fun search(query: String): Flow<List<SessionEntity>> = dao.search(query)

    /** Distinct tags across all saved notes, sorted — for the tag filter chips. */
    val allTags: Flow<List<String>> = dao.rawTagStrings().map { rows ->
        rows.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
    }

    // --- Mutations ---
    suspend fun touchViewed(id: String, now: Long = System.currentTimeMillis()) = dao.touchViewed(id, now)

    suspend fun moveToFolder(ids: List<String>, folderId: Long) = dao.moveToFolder(ids, folderId)

    suspend fun deleteNotes(ids: List<String>) = dao.deleteNotes(ids)

    suspend fun saveToGallery(
        id: String,
        folderId: Long,
        title: String?,
        difficulty: Difficulty?,
        tags: List<String>,
        keepPhoto: Boolean = true,
        savedAt: Long = System.currentTimeMillis(),
    ) = dao.saveToGallery(id, folderId, title, difficulty, tags, keepPhoto, savedAt)

    companion object {
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    }
}
