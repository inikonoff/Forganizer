package com.forganizer.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import com.forganizer.core.Journal
import com.forganizer.core.JournalKind
import com.forganizer.core.JournalRecord
import com.forganizer.core.JournalStatus

@Entity(tableName = "operation_journal")
data class JournalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val session: String,
    val kind: String,
    val rootId: String,
    val srcDir: String,
    val srcId: String,
    val srcName: String,
    val dstDir: String,
    val dstId: String?,
    val dstName: String,
    val size: Long,
    val status: String,
    val error: String?,
    val time: Long,
)

data class SessionSummary(
    val session: String,
    val rootId: String,
    val started: Long,
    val moved: Int,
    val undone: Int,
    val failed: Int,
    val dirs: Int,
)

@Dao
interface JournalDao {
    @Insert
    suspend fun insert(e: JournalEntity): Long

    @Update
    suspend fun update(e: JournalEntity)

    @Query("SELECT * FROM operation_journal WHERE session = :session ORDER BY id")
    suspend fun bySession(session: String): List<JournalEntity>

    @Query(
        """SELECT session, MIN(rootId) AS rootId, MIN(time) AS started,
        SUM(CASE WHEN kind = 'MOVE' AND status = 'DONE' THEN 1 ELSE 0 END) AS moved,
        SUM(CASE WHEN status = 'UNDONE' THEN 1 ELSE 0 END) AS undone,
        SUM(CASE WHEN status IN ('FAILED', 'UNDO_FAILED') THEN 1 ELSE 0 END) AS failed,
        SUM(CASE WHEN kind = 'MKDIR' THEN 1 ELSE 0 END) AS dirs
        FROM operation_journal GROUP BY session ORDER BY started DESC"""
    )
    suspend fun sessions(): List<SessionSummary>

    @Query("DELETE FROM operation_journal")
    suspend fun clear()
}

@Entity(tableName = "plan_versions")
data class PlanVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: String,
    val number: Int,
    val patch: String,
    val snapshot: String,
    val time: Long,
)

@Entity(tableName = "saved_plans")
data class SavedPlanEntity(
    @PrimaryKey val id: String,
    val name: String,
    val rootId: String,
    val snapshot: String,
    val time: Long,
)

@Entity(tableName = "pinned_decisions")
data class PinnedDecisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: String,
    val kind: String,
    val obj: String,
    val value: String?,
)

/** Saved scheme row without the (large) snapshot, for the list screen. */
data class SavedPlanInfo(
    val id: String,
    val name: String,
    val rootId: String,
    val time: Long,
)

@Dao
interface PlanDao {
    @Insert
    suspend fun insertVersion(e: PlanVersionEntity): Long

    @Query("SELECT * FROM plan_versions WHERE planId = :planId AND number = :number LIMIT 1")
    suspend fun version(planId: String, number: Int): PlanVersionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveScheme(e: SavedPlanEntity)

    @Query("SELECT id, name, rootId, time FROM saved_plans ORDER BY time DESC")
    suspend fun schemes(): List<SavedPlanInfo>

    @Query("SELECT * FROM saved_plans WHERE id = :id")
    suspend fun scheme(id: String): SavedPlanEntity?

    @Query("DELETE FROM saved_plans WHERE id = :id")
    suspend fun deleteScheme(id: String)

    @Query("DELETE FROM pinned_decisions WHERE planId = :planId")
    suspend fun clearPins(planId: String)

    @Insert
    suspend fun insertPins(e: List<PinnedDecisionEntity>)

    @Transaction
    suspend fun replacePins(planId: String, pins: List<PinnedDecisionEntity>) {
        clearPins(planId)
        insertPins(pins)
    }

    @Query("DELETE FROM plan_versions")
    suspend fun clearVersions()

    @Query("DELETE FROM saved_plans")
    suspend fun clearSchemes()

    @Query("DELETE FROM pinned_decisions")
    suspend fun clearAllPins()
}

@Database(
    entities = [JournalEntity::class, PlanVersionEntity::class, SavedPlanEntity::class, PinnedDecisionEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun journal(): JournalDao
    abstract fun plans(): PlanDao

    companion object {
        /** v2: plan versions, saved schemes and pinned decisions. The journal is kept. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plan_versions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`planId` TEXT NOT NULL, `number` INTEGER NOT NULL, `patch` TEXT NOT NULL, " +
                        "`snapshot` TEXT NOT NULL, `time` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `saved_plans` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`rootId` TEXT NOT NULL, `snapshot` TEXT NOT NULL, `time` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pinned_decisions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`planId` TEXT NOT NULL, `kind` TEXT NOT NULL, `obj` TEXT NOT NULL, `value` TEXT)"
                )
            }
        }

        fun create(context: Context) =
            Room.databaseBuilder(context, AppDatabase::class.java, "forganizer.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}

class RoomJournal(private val dao: JournalDao) : Journal {
    override suspend fun insert(record: JournalRecord): Long = dao.insert(record.toEntity())
    override suspend fun update(record: JournalRecord) = dao.update(record.toEntity())
    override suspend fun session(session: String): List<JournalRecord> = dao.bySession(session).map { it.toRecord() }

    private fun JournalRecord.toEntity() = JournalEntity(
        id, session, kind.name, rootId, srcDir, srcId, srcName, dstDir, dstId, dstName, size, status.name, error, time,
    )

    private fun JournalEntity.toRecord() = JournalRecord(
        id, session, JournalKind.valueOf(kind), rootId, srcDir, srcId, srcName, dstDir, dstId, dstName, size,
        JournalStatus.valueOf(status), error, time,
    )
}
