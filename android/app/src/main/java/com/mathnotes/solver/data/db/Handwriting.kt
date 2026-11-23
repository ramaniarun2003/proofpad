package com.mathnotes.solver.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One captured line from the first-launch handwriting setup wizard: the expected
 * expression we asked the user to write, what the recognizer read, an optional
 * user correction, and the cropped strip image used for the thumbnail.
 */
@Entity(tableName = "handwriting_samples")
data class HandwritingSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expectedLatex: String,
    val recognizedLatex: String,
    val correctedLatex: String? = null,
    val cropPath: String? = null,
    val confidence: Float = 0f,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface HandwritingDao {
    @Query("SELECT * FROM handwriting_samples ORDER BY id ASC")
    fun observeAll(): Flow<List<HandwritingSample>>

    @Query("SELECT * FROM handwriting_samples ORDER BY id ASC")
    suspend fun getAll(): List<HandwritingSample>

    @Insert
    suspend fun insertAll(samples: List<HandwritingSample>)

    @Query("DELETE FROM handwriting_samples")
    suspend fun clear()
}
