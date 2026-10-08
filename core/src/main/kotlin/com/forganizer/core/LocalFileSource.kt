package com.forganizer.core

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URLConnection

/**
 * java.io.File backend (all-files access). Moves use renameTo, so inside one storage no data is
 * copied. [onChanged] receives old and new paths (the Android layer runs the media scanner).
 */
open class LocalFileSource(
    private val onChanged: (List<String>) -> Unit = {},
) : FileSource {
    override val capabilities = Capabilities(canAccessRoot = true, nativeMove = true, fastListing = true)

    override suspend fun list(dir: NodeRef): Listing {
        val d = File(dir.id)
        val children = d.listFiles() ?: throw AccessLostException("Нет доступа к ${d.path}")
        var skipped = 0
        val nodes = children.mapNotNull { f ->
            try {
                if (!f.canRead()) { skipped++; null } else toNode(f)
            } catch (e: SecurityException) {
                skipped++; null
            }
        }
        return Listing(nodes, skipped)
    }

    override suspend fun stat(node: NodeRef): FileNode? {
        val f = File(node.id)
        return if (f.exists()) toNode(f) else null
    }

    override suspend fun createDir(parent: NodeRef, name: String): NodeRef {
        val p = File(parent.id).canonicalFile
        val dir = File(p, name)
        check(dir.canonicalFile.parentFile == p) { "Путь вне корневой папки" }
        if (!dir.isDirectory && !dir.mkdir()) throw IllegalStateException("mkdir failed")
        onChanged(listOf(dir.path))
        return NodeRef(dir.path)
    }

    override suspend fun move(node: FileNode, fromDir: NodeRef, targetDir: NodeRef, targetName: String): MoveResult {
        val src = File(node.id)
        val dstDir = File(targetDir.id).canonicalFile
        val dst = File(dstDir, targetName)
        if (dst.canonicalFile.parentFile != dstDir) return MoveResult.Failed("Путь вне целевой папки")
        if (!src.exists()) return MoveResult.Failed("Файл не найден")
        if (dst.exists()) return MoveResult.Failed("Файл уже существует")
        return if (src.renameTo(dst)) {
            onChanged(listOf(src.path, dst.path))
            MoveResult.Moved(toNode(dst))
        } else MoveResult.Failed("Не удалось переместить")
    }

    override suspend fun deleteEmptyDir(dir: NodeRef): Boolean {
        val d = File(dir.id)
        if (!d.isDirectory) return false
        val children = d.list() ?: return false
        if (children.isNotEmpty()) return false
        val ok = d.delete()
        if (ok) onChanged(listOf(d.path))
        return ok
    }

    override fun openRead(node: FileNode): InputStream = FileInputStream(File(node.id))

    override fun localFile(node: FileNode): File? = File(node.id).takeIf { it.isFile }

    private fun toNode(f: File) = FileNode(
        id = f.path,
        name = f.name,
        size = if (f.isDirectory) 0 else f.length(),
        modified = f.lastModified(),
        mime = if (f.isDirectory) null else URLConnection.guessContentTypeFromName(f.name),
        isDir = f.isDirectory,
    )
}
