package com.forganizer.app.fs

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import com.forganizer.core.AccessLostException
import com.forganizer.core.Capabilities
import com.forganizer.core.FileNode
import com.forganizer.core.FileSource
import com.forganizer.core.Listing
import com.forganizer.core.MoveResult
import com.forganizer.core.NodeRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Storage Access Framework backend. Listing uses buildChildDocumentsUriUsingTree with a column
 * query (not DocumentFile.listFiles). Moves use moveDocument when supported, otherwise a verified
 * copy followed by removal of the original.
 */
class SafBackend(context: Context, val treeUri: Uri) : FileSource {
    private val resolver: ContentResolver = context.contentResolver

    override val capabilities = Capabilities(canAccessRoot = false, nativeMove = true, fastListing = false)

    val root: NodeRef
        get() = NodeRef(DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)).toString())

    private val projection = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS,
    )

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw AccessLostException("Доступ к папке отозван", e)
    }

    override suspend fun list(dir: NodeRef): Listing = withContext(Dispatchers.IO) {
        guard {
            val dirUri = Uri.parse(dir.id)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(dirUri))
            val nodes = mutableListOf<FileNode>()
            var skipped = 0
            val cursor = resolver.query(children, projection, null, null, null)
                ?: throw AccessLostException("Папка недоступна")
            cursor.use { c ->
                while (c.moveToNext()) {
                    try {
                        val docId = c.getString(0)
                        nodes += row(c, DocumentsContract.buildDocumentUriUsingTree(treeUri, docId))
                    } catch (e: Exception) {
                        Log.w(TAG, "skip entry", e)
                        skipped++
                    }
                }
            }
            Listing(nodes, skipped)
        }
    }

    private fun row(c: Cursor, uri: Uri): FileNode {
        val mime = c.getString(4)
        return FileNode(
            id = uri.toString(),
            name = c.getString(1) ?: "?",
            size = if (c.isNull(2)) 0 else c.getLong(2),
            modified = if (c.isNull(3)) 0 else c.getLong(3),
            mime = mime,
            isDir = mime == Document.MIME_TYPE_DIR,
        )
    }

    private fun query(uri: Uri): Pair<FileNode, Int>? = try {
        resolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) row(c, uri) to (if (c.isNull(5)) 0 else c.getInt(5)) else null
        }
    } catch (e: SecurityException) {
        throw AccessLostException("Доступ к папке отозван", e)
    } catch (e: FileNotFoundException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: IllegalStateException) {
        null
    }

    override suspend fun stat(node: NodeRef): FileNode? = withContext(Dispatchers.IO) { query(Uri.parse(node.id))?.first }

    override suspend fun createDir(parent: NodeRef, name: String): NodeRef = withContext(Dispatchers.IO) {
        guard {
            val uri = DocumentsContract.createDocument(resolver, Uri.parse(parent.id), Document.MIME_TYPE_DIR, name)
                ?: throw IllegalStateException("createDocument вернул null")
            NodeRef(uri.toString())
        }
    }

    override suspend fun move(node: FileNode, fromDir: NodeRef, targetDir: NodeRef, targetName: String): MoveResult =
        withContext(Dispatchers.IO) {
            guard {
                val (current, flags) = query(Uri.parse(node.id)) ?: return@guard MoveResult.Failed("Файл не найден")
                var uri = Uri.parse(current.id)
                val renamed = targetName != current.name
                if (renamed) {
                    if (flags and Document.FLAG_SUPPORTS_RENAME == 0) return@guard MoveResult.Failed("Провайдер не поддерживает переименование")
                    uri = try {
                        DocumentsContract.renameDocument(resolver, uri, targetName)
                    } catch (e: Exception) {
                        null
                    } ?: return@guard MoveResult.Failed("Не удалось переименовать для разрешения конфликта")
                }
                val result = if (flags and Document.FLAG_SUPPORTS_MOVE != 0) {
                    try {
                        DocumentsContract.moveDocument(resolver, uri, Uri.parse(fromDir.id), Uri.parse(targetDir.id))
                    } catch (e: Exception) {
                        Log.w(TAG, "move failed", e); null
                    }
                } else {
                    copyThenRemove(uri, current, Uri.parse(targetDir.id), targetName)
                }
                if (result == null) {
                    if (renamed) runCatching { DocumentsContract.renameDocument(resolver, uri, current.name) }
                    return@guard MoveResult.Failed("Не удалось переместить")
                }
                val moved = query(result)?.first ?: return@guard MoveResult.Failed("Файл перемещён, но не найден")
                MoveResult.Moved(moved)
            }
        }

    /** Copy, verify size, then remove the original. On any failure the original stays untouched. */
    private fun copyThenRemove(src: Uri, node: FileNode, targetDir: Uri, name: String): Uri? {
        val copy = DocumentsContract.createDocument(resolver, targetDir, node.mime ?: "application/octet-stream", name)
            ?: return null
        val ok = try {
            resolver.openInputStream(src).use { input ->
                resolver.openOutputStream(copy, "w").use { output ->
                    if (input == null || output == null) return@use false
                    input.copyTo(output, 256 * 1024)
                    true
                }
            } && query(copy)?.first?.size == node.size
        } catch (e: Exception) {
            Log.w(TAG, "copy failed", e)
            false
        }
        if (!ok) {
            // Remove only our own incomplete copy; the user's original is kept.
            runCatching { DocumentsContract.deleteDocument(resolver, copy) }
            return null
        }
        runCatching { DocumentsContract.deleteDocument(resolver, src) }
        return copy
    }

    override fun openRead(node: FileNode): InputStream =
        resolver.openInputStream(Uri.parse(node.id)) ?: throw FileNotFoundException(node.name)

    companion object {
        private const val TAG = "SafBackend"
    }
}
