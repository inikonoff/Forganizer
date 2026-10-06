package com.forganizer.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
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

@Database(entities = [JournalEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun journal(): JournalDao

    companion object {
        fun create(context: Context) =
            Room.databaseBuilder(context, AppDatabase::class.java, "forganizer.db").build()
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
