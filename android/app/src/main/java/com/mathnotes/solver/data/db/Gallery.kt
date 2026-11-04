package com.mathnotes.solver.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.TypeConverter
import kotlinx.coroutines.flow.Flow

/** User-assigned difficulty for a gallery note; stored as its name() string. */
enum class Difficulty { EASY, MEDIUM, HARD }

/** Room TypeConverters for the gallery fields on [SessionEntity]. */
class Converters {
    @TypeConverter
    fun tagsToString(tags: List<String>): String =
        tags.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")

    @TypeConverter
    fun stringToTags(value: String?): List<String> =
        if (value.isNullOrBlank()) emptyList()
        else value.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    @TypeConverter
    fun difficultyToString(d: Difficulty?): String? = d?.name

    @TypeConverter
    fun stringToDifficulty(value: String?): Difficulty? =
        value?.let { runCatching { Difficulty.valueOf(it) }.getOrNull() }
}

/** A class folder notes are filed into. The palette (colorIndex 0–5) is a UI concern. */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0,
)

/** A folder plus how many notes are filed in it (for the gallery's folder grid). */
data class FolderWithCount(
    @Embedded val folder: FolderEntity,
    val noteCount: Int,
)

/**
 * Gallery reads/writes over the same `sessions` + `folders` tables. Kept separate
 * from [SessionDao] (the solve flow) so each surface owns its own queries.
 */
@Dao
interface GalleryDao {

    // --- Folders ---

    @Insert
    suspend fun insertFolder(folder: FolderEntity): Long

    /** All folders with their note counts, in one query (LEFT JOIN so empty folders show 0). */
    @Query(
        """
        SELECT f.*, COUNT(s.sessionId) AS noteCount
        FROM folders f LEFT JOIN sessions s ON s.folderId = f.id
        GROUP BY f.id
        ORDER BY f.sortOrder ASC, f.createdAt ASC
        """
    )
    fun foldersWithCounts(): Flow<List<FolderWithCount>>

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun renameFolder(id: Long, name: String)

    /**
     * Delete a folder but KEEP its notes — their folderId is reset to null first.
     * (The [SessionEntity] foreign key also declares ON DELETE SET NULL; doing it
     * explicitly makes the behaviour independent of PRAGMA foreign_keys.)
     */
    @Transaction
    suspend fun deleteFolder(id: Long) {
        clearFolderOnNotes(id)
        deleteFolderRow(id)
    }

    @Query("UPDATE sessions SET folderId = NULL WHERE folderId = :id")
    suspend fun clearFolderOnNotes(id: Long)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteFolderRow(id: Long)

    // --- Notes ---

    /**
     * Notes filed in a folder, newest first, with optional filters. Pass
     * flaggedOnly=false / topic=null / difficulty=null to skip a filter.
     * "Flagged" means the verifier rejected a step (verified = false).
     */
    @Query(
        """
        SELECT * FROM sessions
        WHERE folderId = :folderId
          AND (:flaggedOnly = 0 OR verified = 0)
          AND (:topic IS NULL OR topic = :topic)
          AND (:difficulty IS NULL OR difficulty = :difficulty)
        ORDER BY savedAt DESC, createdAt DESC
        """
    )
    fun notesInFolder(folderId: Long, flaggedOnly: Boolean, topic: String?, difficulty: Difficulty?): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE lastViewedAt IS NOT NULL ORDER BY lastViewedAt DESC LIMIT 10")
    fun recentlyViewed(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE savedAt IS NOT NULL AND savedAt >= :since ORDER BY savedAt DESC")
    fun savedSince(since: Long): Flow<List<SessionEntity>>

    /** Full-text-ish search across a saved note's title, tags, and original LaTeX. */
    @Query(
        """
        SELECT * FROM sessions
        WHERE folderId IS NOT NULL AND (
            title LIKE '%' || :q || '%'
            OR tags LIKE '%' || :q || '%'
            OR recognizedLatex LIKE '%' || :q || '%'
            OR confirmedLatex LIKE '%' || :q || '%'
        )
        ORDER BY savedAt DESC
        """
    )
    fun search(q: String): Flow<List<SessionEntity>>

    /** Raw comma-joined tag strings of saved notes; the repository splits + dedupes. */
    @Query("SELECT tags FROM sessions WHERE folderId IS NOT NULL AND tags != ''")
    fun rawTagStrings(): Flow<List<String>>

    // --- Mutations ---

    @Query("UPDATE sessions SET lastViewedAt = :now WHERE sessionId = :id")
    suspend fun touchViewed(id: String, now: Long)

    @Query("UPDATE sessions SET folderId = :folderId WHERE sessionId IN (:ids)")
    suspend fun moveToFolder(ids: List<String>, folderId: Long)

    @Query("DELETE FROM sessions WHERE sessionId IN (:ids)")
    suspend fun deleteNotes(ids: List<String>)

    /** File a solved note into the gallery with its user-entered metadata. */
    @Query(
        """
        UPDATE sessions
        SET folderId = :folderId, title = :title, difficulty = :difficulty,
            tags = :tags, keepPhoto = :keepPhoto, savedAt = :savedAt
        WHERE sessionId = :id
        """
    )
    suspend fun saveToGallery(
        id: String,
        folderId: Long,
        title: String?,
        difficulty: Difficulty?,
        tags: List<String>,
        keepPhoto: Boolean,
        savedAt: Long,
    )
}
