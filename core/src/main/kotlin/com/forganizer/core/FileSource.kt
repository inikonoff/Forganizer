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

    /** Reads file bytes locally (used only for duplicate hashing, never sent anywhere). */
    fun openRead(node: FileNode): InputStream
}
