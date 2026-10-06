package com.forganizer.core

import java.io.ByteArrayInputStream
import java.io.InputStream

/** In-memory FileSource; ids are "/"-joined paths. Used to run the same contract tests as LocalFileSource. */
class InMemoryFileSource : FileSource {
    private data class Entry(var name: String, var parent: String, val isDir: Boolean, var data: ByteArray, var modified: Long)

    private val entries = LinkedHashMap<String, Entry>()
    private var counter = 0

    override val capabilities = Capabilities(canAccessRoot = true, nativeMove = true, fastListing = true)

    fun mkRoot(id: String): NodeRef { entries[id] = Entry(id, "", true, ByteArray(0), 0); return NodeRef(id) }

    fun addFile(dir: NodeRef, name: String, data: ByteArray = ByteArray(10), modified: Long = 1_000): FileNode {
        val id = "n${++counter}"
        entries[id] = Entry(name, dir.id, false, data, modified)
        return node(id)!!
    }

    private fun node(id: String): FileNode? = entries[id]?.let { FileNode(id, it.name, it.data.size.toLong(), it.modified, null, it.isDir) }

    override suspend fun list(dir: NodeRef) = Listing(entries.filter { it.value.parent == dir.id }.keys.map { node(it)!! })
    override suspend fun stat(node: NodeRef) = node(node.id)
    override suspend fun createDir(parent: NodeRef, name: String): NodeRef {
        entries.entries.firstOrNull { it.value.parent == parent.id && it.value.isDir && it.value.name == name }?.let { return NodeRef(it.key) }
        val id = "d${++counter}"
        entries[id] = Entry(name, parent.id, true, ByteArray(0), 0)
        return NodeRef(id)
    }
    override suspend fun move(node: FileNode, fromDir: NodeRef, targetDir: NodeRef, targetName: String): MoveResult {
        val e = entries[node.id] ?: return MoveResult.Failed("Файл не найден")
        if (entries.values.any { it.parent == targetDir.id && it.name.equals(targetName, true) }) return MoveResult.Failed("Файл уже существует")
        e.parent = targetDir.id; e.name = targetName
        return MoveResult.Moved(node(node.id)!!)
    }
    override fun openRead(node: FileNode): InputStream = ByteArrayInputStream(entries.getValue(node.id).data)

    fun parentOf(id: String) = entries[id]?.parent
    fun modify(id: String) { entries[id]!!.modified += 5 }
}

class MemoryJournal : Journal {
    val records = mutableListOf<JournalRecord>()
    override suspend fun insert(record: JournalRecord): Long { val r = record.copy(id = records.size + 1L); records += r; return r.id }
    override suspend fun update(record: JournalRecord) { records[(record.id - 1).toInt()] = record }
    override suspend fun session(session: String) = records.filter { it.session == session }
}

fun file(id: String, name: String, size: Long = 1000, modified: Long = 0) = FileNode(id, name, size, modified, null, false)
