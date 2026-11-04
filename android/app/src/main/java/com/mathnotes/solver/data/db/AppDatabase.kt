package com.mathnotes.solver.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One row per solved problem. Steps are stored as the JSON the backend returned
 * (stepsJson) and the preprocessed crop as imagePath, so a note — including one
 * filed into the gallery — can be reopened without the backend.
 *
 * Gallery fields (v2) stay null/default until the user files the note into a folder.
 * folderId is a foreign key to [FolderEntity]; deleting a folder sets it back to null.
 */
@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("folderId")],
)
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
    // --- Gallery (v2) ---
    val folderId: Long? = null,
    val title: String? = null,
    val difficulty: Difficulty? = null,
    @ColumnInfo(defaultValue = "") val tags: List<String> = emptyList(),
    @ColumnInfo(defaultValue = "1") val keepPhoto: Boolean = true,
    val savedAt: Long? = null,
    val lastViewedAt: Long? = null,
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

/** v1 → v2: add the folders table and the gallery columns on sessions (no data loss). */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS folders (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                colorIndex INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                sortOrder INTEGER NOT NULL,
                term TEXT
            )
            """.trimIndent()
        )
        // Recreate sessions with the new columns + foreign key, carrying data over.
        db.execSQL(
            """
            CREATE TABLE sessions_new (
                sessionId TEXT NOT NULL PRIMARY KEY,
                createdAt INTEGER NOT NULL,
                imagePath TEXT,
                recognizedLatex TEXT NOT NULL,
                confidence REAL NOT NULL,
                confirmedLatex TEXT NOT NULL,
                topic TEXT NOT NULL,
                stepsJson TEXT NOT NULL,
                answerLatex TEXT NOT NULL,
                verified INTEGER,
                mismatchAt INTEGER,
                folderId INTEGER,
                title TEXT,
                difficulty TEXT,
                tags TEXT NOT NULL DEFAULT '',
                keepPhoto INTEGER NOT NULL DEFAULT 1,
                savedAt INTEGER,
                lastViewedAt INTEGER,
                FOREIGN KEY(folderId) REFERENCES folders(id) ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO sessions_new (
                sessionId, createdAt, imagePath, recognizedLatex, confidence,
                confirmedLatex, topic, stepsJson, answerLatex, verified, mismatchAt
            )
            SELECT sessionId, createdAt, imagePath, recognizedLatex, confidence,
                   confirmedLatex, topic, stepsJson, answerLatex, verified, mismatchAt
            FROM sessions
            """.trimIndent()
        )
        db.execSQL("DROP TABLE sessions")
        db.execSQL("ALTER TABLE sessions_new RENAME TO sessions")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_folderId ON sessions(folderId)")
    }
}

@Database(entities = [SessionEntity::class, FolderEntity::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun gallery(): GalleryDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "mathnotes.db")
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
