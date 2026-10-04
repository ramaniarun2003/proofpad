package com.mathnotes.solver.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One row per solved problem. Steps are stored as the JSON the backend returned,
 * so history and the demo fallback need no network. imagePath points at the
 * preprocessed JPEG in filesDir/images.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val imagePath: String? = null,
    val recognizedLatex: String = "",
    val confidence: Double = 0.0,
    val confirmedLatex: String = "",
    val topic: String = "unknown",
    val stepsJson: String = "[]",
    val answerLatex: String = "",
    val verified: Boolean? = null,
    val mismatchAt: Int? = null,
)

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE sessionId = :id")
    suspend fun get(id: String): SessionEntity?

    @Query("DELETE FROM sessions WHERE sessionId = :id")
    suspend fun delete(id: String)
}

@Database(entities = [SessionEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "mathnotes.db")
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
