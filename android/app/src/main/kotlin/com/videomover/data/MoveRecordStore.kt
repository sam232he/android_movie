package com.videomover.data

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
import com.videomover.model.MoveFileRecord
import com.videomover.model.OutputResolution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(
    tableName = "move_files",
    indices = [androidx.room.Index(value = ["batchId"])],
)
data class MoveFileEntity(
    @PrimaryKey val id: String,
    val batchId: String,
    val sourceUri: String,
    val sourceParentDirectoryUri: String?,
    val sourceRelativePath: String?,
    val sourceDataPath: String?,
    val originalFileName: String,
    val destinationFileName: String?,
    val plannedOutputName: String?,
    val externalDestinationTreeUri: String,
    val destinationFileUri: String?,
    val destinationTempUri: String?,
    val outputResolution: String,
    val sourceChecksumSha256: String?,
    val destinationChecksumSha256: String?,
    val checksumMatch: Boolean?,
    val outputUri: String?,
    val progressPercent: Int,
    val state: String,
    val resumeState: String?,
    val errorMessage: String?,
    val attentionReason: String?,
    val sourceDeletedAtEpochMs: Long?,
    val deleteDispatched: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    fun toRecord(): MoveFileRecord = MoveFileRecord(
        id = id,
        batchId = batchId,
        sourceUri = sourceUri,
        sourceParentDirectoryUri = sourceParentDirectoryUri,
        sourceRelativePath = sourceRelativePath,
        sourceDataPath = sourceDataPath,
        originalFileName = originalFileName,
        destinationFileName = destinationFileName,
        plannedOutputName = plannedOutputName,
        externalDestinationTreeUri = externalDestinationTreeUri,
        destinationFileUri = destinationFileUri,
        destinationTempUri = destinationTempUri,
        outputResolution = OutputResolution.entries.firstOrNull { it.label == outputResolution }
            ?: OutputResolution.P480,
        sourceChecksumSha256 = sourceChecksumSha256,
        destinationChecksumSha256 = destinationChecksumSha256,
        checksumMatch = checksumMatch,
        outputUri = outputUri,
        progressPercent = progressPercent,
        state = state,
        resumeState = resumeState,
        errorMessage = errorMessage,
        attentionReason = attentionReason,
        sourceDeletedAtEpochMs = sourceDeletedAtEpochMs,
        deleteDispatched = deleteDispatched,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    companion object {
        fun fromRecord(record: MoveFileRecord): MoveFileEntity = MoveFileEntity(
            id = record.id,
            batchId = record.batchId,
            sourceUri = record.sourceUri,
            sourceParentDirectoryUri = record.sourceParentDirectoryUri,
            sourceRelativePath = record.sourceRelativePath,
            sourceDataPath = record.sourceDataPath,
            originalFileName = record.originalFileName,
            destinationFileName = record.destinationFileName,
            plannedOutputName = record.plannedOutputName,
            externalDestinationTreeUri = record.externalDestinationTreeUri,
            destinationFileUri = record.destinationFileUri,
            destinationTempUri = record.destinationTempUri,
            outputResolution = record.outputResolution.label,
            sourceChecksumSha256 = record.sourceChecksumSha256,
            destinationChecksumSha256 = record.destinationChecksumSha256,
            checksumMatch = record.checksumMatch,
            outputUri = record.outputUri,
            progressPercent = record.progressPercent,
            state = record.state,
            resumeState = record.resumeState,
            errorMessage = record.errorMessage,
            attentionReason = record.attentionReason,
            sourceDeletedAtEpochMs = record.sourceDeletedAtEpochMs,
            deleteDispatched = record.deleteDispatched,
            createdAtEpochMs = record.createdAtEpochMs,
            updatedAtEpochMs = record.updatedAtEpochMs,
        )
    }
}

@Dao
interface MoveRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: MoveFileEntity)

    @Query("SELECT * FROM move_files WHERE id = :id LIMIT 1")
    suspend fun find(id: String): MoveFileEntity?

    @Query("SELECT * FROM move_files WHERE batchId = :batchId ORDER BY id")
    suspend fun forBatch(batchId: String): List<MoveFileEntity>

    @Query("SELECT * FROM move_files ORDER BY updatedAtEpochMs DESC")
    suspend fun all(): List<MoveFileEntity>

    @Query("SELECT * FROM move_files ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<MoveFileEntity>>

    @Query("SELECT * FROM move_files WHERE batchId = :batchId ORDER BY createdAtEpochMs")
    fun observeBatch(batchId: String): Flow<List<MoveFileEntity>>
}

@Database(entities = [MoveFileEntity::class], version = 4, exportSchema = false)
abstract class MoveDatabase : RoomDatabase() {
    abstract fun records(): MoveRecordDao

    companion object {
        @Volatile private var instance: MoveDatabase? = null

        fun get(context: Context): MoveDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MoveDatabase::class.java,
                "video_mover.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE move_files ADD COLUMN sourceDataPath TEXT")
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE move_files ADD COLUMN plannedOutputName TEXT")
                database.execSQL("ALTER TABLE move_files ADD COLUMN progressPercent INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE move_files ADD COLUMN destinationFileName TEXT")
            }
        }
    }
}

class MoveRecordStore(context: Context) {
    private val dao = MoveDatabase.get(context).records()

    suspend fun upsert(record: MoveFileRecord) = dao.upsert(MoveFileEntity.fromRecord(record))

    suspend fun find(id: String): MoveFileRecord? = dao.find(id)?.toRecord()

    suspend fun forBatch(batchId: String): List<MoveFileRecord> = dao.forBatch(batchId).map { it.toRecord() }

    suspend fun all(): List<MoveFileRecord> = dao.all().map { it.toRecord() }

    fun observeAll(): Flow<List<MoveFileRecord>> = dao.observeAll().map { rows -> rows.map { it.toRecord() } }

    fun observeBatch(batchId: String): Flow<List<MoveFileRecord>> =
        dao.observeBatch(batchId).map { rows -> rows.map { it.toRecord() } }
}
