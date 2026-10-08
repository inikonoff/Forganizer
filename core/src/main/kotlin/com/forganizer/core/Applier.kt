package com.forganizer.core

import java.util.concurrent.atomic.AtomicBoolean

enum class JournalKind { MKDIR, MOVE, RMDIR }
enum class JournalStatus { PENDING, DONE, FAILED, SKIPPED, UNDONE, UNDO_FAILED }

data class JournalRecord(
    val id: Long = 0,
    val session: String,
    val kind: JournalKind,
    val rootId: String,
    val srcDir: String,
    val srcId: String,
    val srcName: String,
    val dstDir: String,
    val dstId: String?,
    val dstName: String,
    val size: Long,
    val status: JournalStatus,
    val error: String? = null,
    val time: Long = System.currentTimeMillis(),
)

/** Persistent operation log, written after every operation (not at the end). */
interface Journal {
    suspend fun insert(record: JournalRecord): Long
    suspend fun update(record: JournalRecord)
    suspend fun session(session: String): List<JournalRecord>
}

data class MoveOp(val file: FileNode, val folder: String)

data class OpError(val name: String, val reason: String)

/** A nested folder files were moved out of: its id and its path relative to the scanned root. */
data class SourceDir(val id: String, val rel: String)

data class ApplyReport(
    val session: String,
    val done: Int,
    val skipped: List<OpError>,
    val failed: List<OpError>,
    val createdDirs: List<String>,
    val stopped: Boolean,
    val accessLost: Boolean,
    /** Nested folders that files were moved out of (candidates for "folder is empty now"). */
    val sourceDirs: List<SourceDir> = emptyList(),
)

data class UndoReport(
    val restored: Int,
    val failed: List<OpError>,
    /** Folders created by the app; they are left in place (nothing is ever deleted). */
    val createdDirs: List<String>,
    val accessLost: Boolean,
)

data class RemoveDirsReport(val removed: List<String>, val kept: List<String>, val accessLost: Boolean)

class Applier(
    private val source: FileSource,
    private val journal: Journal,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun apply(
        session: String,
        root: NodeRef,
        ops: List<MoveOp>,
        mode: ConflictMode,
        stop: AtomicBoolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ApplyReport {
        var done = 0
        val skipped = mutableListOf<OpError>()
        val failed = mutableListOf<OpError>()
        val created = mutableListOf<String>()
        val sources = LinkedHashMap<String, SourceDir>()
        try {
            val rootListing = source.list(root).nodes
            val dirs = HashMap<String, NodeRef>()
            rootListing.filter { it.isDir }.forEach { dirs[FolderNames.key(it.name)] = it.ref }

            // 1. Create the missing folders.
            for (folder in ops.map { it.folder }.distinctBy { FolderNames.key(it) }) {
                val key = FolderNames.key(folder)
                if (key in dirs) continue
                if (!FolderNames.isInsideRoot(folder)) {
                    ops.filter { FolderNames.key(it.folder) == key }.forEach { failed += OpError(it.file.name, Reasons.BAD_PATH) }
                    continue
                }
                if (rootListing.any { !it.isDir && FolderNames.key(it.name) == key }) {
                    ops.filter { FolderNames.key(it.folder) == key }
                        .forEach { failed += OpError(it.file.name, "В корне есть файл с именем папки $folder") }
                    continue
                }
                try {
                    val ref = source.createDir(root, folder)
                    dirs[key] = ref
                    created += folder
                    journal.insert(
                        JournalRecord(
                            session = session, kind = JournalKind.MKDIR, rootId = root.id, srcDir = root.id,
                            srcId = ref.id, srcName = folder, dstDir = root.id, dstId = ref.id, dstName = folder,
                            size = 0, status = JournalStatus.DONE, time = now(),
                        )
                    )
                } catch (e: AccessLostException) {
                    throw e
                } catch (e: Exception) {
                    ops.filter { FolderNames.key(it.folder) == key }
                        .forEach { failed += OpError(it.file.name, "Не удалось создать папку: ${e.message}") }
                }
            }

            // 2. Move files, one journal record per operation.
            val names = HashMap<String, MutableSet<String>>()
            ops.forEachIndexed { index, op ->
                if (stop.get()) {
                    return ApplyReport(session, done, skipped, failed, created, stopped = true, accessLost = false, sourceDirs = sources.values.toList())
                }
                onProgress(index, ops.size)
                val target = dirs[FolderNames.key(op.folder)] ?: return@forEachIndexed
                val current = source.stat(op.file.ref)
                if (current == null) {
                    skipped += OpError(op.file.name, "Файл не найден"); return@forEachIndexed
                }
                if (current.size != op.file.size || current.modified != op.file.modified) {
                    skipped += OpError(op.file.name, "Файл изменился после сканирования"); return@forEachIndexed
                }
                val taken = names.getOrPut(target.id) { source.list(target).nodes.map { it.name.lowercase() }.toMutableSet() }
                var name = op.file.name
                if (name.lowercase() in taken) {
                    if (mode == ConflictMode.SKIP) {
                        skipped += OpError(name, "В папке уже есть файл с таким именем"); return@forEachIndexed
                    }
                    name = Conflicts.uniqueName(name, taken)
                }
                val srcDir = if (op.file.dir.isEmpty()) root else NodeRef(op.file.dir)
                if (srcDir.id == target.id) {
                    skipped += OpError(op.file.name, "Файл уже лежит в этой папке"); return@forEachIndexed
                }
                val pending = JournalRecord(
                    session = session, kind = JournalKind.MOVE, rootId = root.id, srcDir = srcDir.id,
                    srcId = op.file.id, srcName = op.file.name, dstDir = target.id, dstId = null, dstName = name,
                    size = op.file.size, status = JournalStatus.PENDING, time = now(),
                )
                val id = journal.insert(pending)
                when (val r = source.move(current, srcDir, target, name)) {
                    is MoveResult.Moved -> {
                        taken += r.node.name.lowercase()
                        journal.update(pending.copy(id = id, dstId = r.node.id, dstName = r.node.name, status = JournalStatus.DONE, time = now()))
                        done++
                        if (op.file.dir.isNotEmpty() && op.file.rel.isNotEmpty()) sources.putIfAbsent(op.file.dir, SourceDir(op.file.dir, op.file.rel))
                    }
                    is MoveResult.Failed -> {
                        journal.update(pending.copy(id = id, status = JournalStatus.FAILED, error = r.reason, time = now()))
                        failed += OpError(op.file.name, r.reason)
                    }
                }
            }
            onProgress(ops.size, ops.size)
            return ApplyReport(session, done, skipped, failed, created, stopped = false, accessLost = false, sourceDirs = sources.values.toList())
        } catch (e: AccessLostException) {
            return ApplyReport(session, done, skipped, failed + OpError("", e.message ?: "Доступ потерян"), created, stopped = true, accessLost = true)
        }
    }

    /**
     * Names of folders this session created that are empty now. Only folders the app itself created
     * (MKDIR records still marked DONE) are ever considered.
     */
    suspend fun emptyCreatedDirs(session: String): List<String> =
        createdDirRecords(session).filter { isEmptyDir(NodeRef(it.dstId!!)) }.map { it.dstName }

    /** Deletes the still-empty folders created by [session]; anything that got a file meanwhile is kept. */
    suspend fun removeEmptyDirs(session: String): RemoveDirsReport {
        val removed = mutableListOf<String>()
        val kept = mutableListOf<String>()
        try {
            for (rec in createdDirRecords(session)) {
                val ref = NodeRef(rec.dstId!!)
                if (isEmptyDir(ref) && source.deleteEmptyDir(ref)) {
                    journal.update(rec.copy(status = JournalStatus.UNDONE, time = now()))
                    removed += rec.dstName
                } else kept += rec.dstName
            }
        } catch (e: AccessLostException) {
            return RemoveDirsReport(removed, kept, accessLost = true)
        }
        return RemoveDirsReport(removed, kept, accessLost = false)
    }

    /**
     * Of the nested folders files were moved out of, those that are empty now (or hold only such folders).
     * These are the user's own folders, so they are only offered, never removed on their own.
     */
    suspend fun emptiedSourceDirs(dirs: List<SourceDir>): List<SourceDir> {
        val ordered = dirs.sortedByDescending { it.rel.count { c -> c == '/' } }
        val deletable = LinkedHashMap<String, SourceDir>()
        for (d in ordered) {
            val node = source.stat(NodeRef(d.id)) ?: continue
            if (!node.isDir) continue
            val listing = source.list(NodeRef(d.id))
            if (listing.skipped == 0 && listing.nodes.all { it.isDir && it.id in deletable }) deletable[d.id] = d
        }
        return ordered.filter { it.id in deletable }
    }

    /**
     * Deletes the emptied source folders (deepest first) and journals each removal, so an undo can
     * recreate the folder and put the files back where they were.
     */
    suspend fun removeEmptiedSourceDirs(session: String, root: NodeRef, dirs: List<SourceDir>): RemoveDirsReport {
        val removed = mutableListOf<String>()
        val kept = mutableListOf<String>()
        try {
            for (d in dirs.sortedByDescending { it.rel.count { c -> c == '/' } }) {
                val ref = NodeRef(d.id)
                if (isEmptyDir(ref) && source.deleteEmptyDir(ref)) {
                    journal.insert(
                        JournalRecord(
                            session = session, kind = JournalKind.RMDIR, rootId = root.id, srcDir = root.id,
                            srcId = d.id, srcName = d.rel, dstDir = root.id, dstId = null, dstName = d.rel,
                            size = 0, status = JournalStatus.DONE, time = now(),
                        )
                    )
                    removed += d.rel
                } else kept += d.rel
            }
        } catch (e: AccessLostException) {
            return RemoveDirsReport(removed, kept, accessLost = true)
        }
        return RemoveDirsReport(removed, kept, accessLost = false)
    }

    /** Finds or creates the folder chain [rel] below [root] (case-insensitive match, one level at a time). */
    private suspend fun ensureChain(root: NodeRef, rel: String): NodeRef {
        var cur = root
        for (seg in rel.split('/').filter { it.isNotEmpty() }) {
            val child = source.list(cur).nodes.firstOrNull { it.isDir && it.name.equals(seg, ignoreCase = true) }
            cur = child?.ref ?: source.createDir(cur, seg)
        }
        return cur
    }

    private suspend fun createdDirRecords(session: String) =
        journal.session(session).filter { it.kind == JournalKind.MKDIR && it.status == JournalStatus.DONE && it.dstId != null }

    private suspend fun isEmptyDir(ref: NodeRef): Boolean {
        val node = source.stat(ref) ?: return false
        if (!node.isDir) return false
        val listing = source.list(ref)
        return listing.nodes.isEmpty() && listing.skipped == 0
    }

    /** Walks the journal backwards and returns moved files to their original folder. */
    suspend fun undo(session: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): UndoReport {
        val records = journal.session(session)
        val moves = records.filter { it.kind == JournalKind.MOVE && it.status == JournalStatus.DONE }.sortedByDescending { it.id }
        val created = records.filter { it.kind == JournalKind.MKDIR }.map { it.dstName }
        var restored = 0
        val failed = mutableListOf<OpError>()
        val names = HashMap<String, MutableSet<String>>()
        val remap = HashMap<String, String>()
        try {
            // Folders the app deleted after emptying them are recreated first, so their files can return.
            for (rm in records.filter { it.kind == JournalKind.RMDIR && it.status == JournalStatus.DONE }.sortedBy { it.id }) {
                remap[rm.srcId] = ensureChain(NodeRef(rm.rootId), rm.srcName).id
                journal.update(rm.copy(status = JournalStatus.UNDONE, time = now()))
            }
            moves.forEachIndexed { i, rec ->
                onProgress(i, moves.size)
                val node = rec.dstId?.let { source.stat(NodeRef(it)) }
                if (node == null) {
                    failed += OpError(rec.dstName, "Файл не найден в ${rec.dstDir.substringAfterLast('/')}")
                    journal.update(rec.copy(status = JournalStatus.UNDO_FAILED, error = "not found", time = now()))
                    return@forEachIndexed
                }
                val src = NodeRef(remap[rec.srcDir] ?: rec.srcDir)
                val taken = names.getOrPut(src.id) { source.list(src).nodes.map { it.name.lowercase() }.toMutableSet() }
                val name = Conflicts.uniqueName(rec.srcName, taken)
                when (val r = source.move(node, NodeRef(rec.dstDir), src, name)) {
                    is MoveResult.Moved -> {
                        taken += r.node.name.lowercase()
                        journal.update(rec.copy(status = JournalStatus.UNDONE, time = now()))
                        restored++
                    }
                    is MoveResult.Failed -> {
                        failed += OpError(rec.dstName, r.reason)
                        journal.update(rec.copy(status = JournalStatus.UNDO_FAILED, error = r.reason, time = now()))
                    }
                }
            }
        } catch (e: AccessLostException) {
            return UndoReport(restored, failed + OpError("", e.message ?: "Доступ потерян"), created, accessLost = true)
        }
        onProgress(moves.size, moves.size)
        return UndoReport(restored, failed, created, accessLost = false)
    }
}
