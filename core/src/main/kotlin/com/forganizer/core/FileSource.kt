package com.forganizer.core

import kotlinx.serialization.Serializable
import java.io.InputStream

/** Opaque reference to a directory or file: a path or a content URI. */
data class NodeRef(val id: String)

@Serializable
data class FileNode(
    val id: String,        // opaque: path or URI
    val name: String,
    val size: Long,
    val modified: Long,
    val mime: String?,
    val isDir: Boolean,
    /** Id of the directory that holds the node when it is not the scanned root; empty for root entries. */
    val dir: String = "",
    /** Folder path relative to the scanned root ("Проект/img"); empty for root entries. */
    val rel: String = "",
) {
    val ref: NodeRef get() = NodeRef(id)
}

data class Capabilities(
    val canAccessRoot: Boolean,
    val nativeMove: Boolean,
    val fastListing: Boolean,
)

/** Result of listing a directory: readable nodes plus a count of entries that were skipped. */
data class Listing(val nodes: List<FileNode>, val skipped: Int = 0)

sealed interface MoveResult {
    data class Moved(val node: FileNode) : MoveResult
    data class Failed(val reason: String) : MoveResult
}

class AccessLostException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The only abstraction the scanner, AI client, validator, journal and UI depend on.
 * Implementations: java.io.File (all-files access) and SAF (DocumentsContract).
 */
interface FileSource {
    val capabilities: Capabilities

    suspend fun list(dir: NodeRef): Listing

    /** Fresh metadata for a node, or null if it no longer exists. */
    suspend fun stat(node: NodeRef): FileNode?

    suspend fun createDir(parent: NodeRef, name: String): NodeRef

    /**
     * Moves [node] from [fromDir] into [targetDir] under [targetName].
     * Must never overwrite an existing file and never delete anything except the source of a
     * verified copy.
     */
    suspend fun move(node: FileNode, fromDir: NodeRef, targetDir: NodeRef, targetName: String): MoveResult

    /**
     * Removes [dir] only if it is an empty directory; returns whether it was removed. The one place where
     * the app deletes anything, used solely for folders it created itself and only on the user's request.
     * Implementations must re-check emptiness themselves (SAF deletion is recursive).
     */
    suspend fun deleteEmptyDir(dir: NodeRef): Boolean = false

    /** Reads file bytes locally (duplicate hashing, archive entry names); contents are never sent anywhere. */
    fun openRead(node: FileNode): InputStream

    /** A real file for random access when the backend has one (all-files mode), otherwise null. */
    fun localFile(node: FileNode): java.io.File? = null
}
