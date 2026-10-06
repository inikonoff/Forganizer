package com.forganizer.core

data class ScanSettings(
    val ignoreExtensions: Set<String>,
    val ignoreFolders: Set<String>,
)

data class ScanResult(
    val root: NodeRef,
    val files: List<FileNode>,
    val existingFolders: List<FileNode>,
    val skipped: Int,
)

/** Lists only the root of the selected folder (no recursion into existing folders). */
class Scanner(private val source: FileSource) {
    suspend fun scan(root: NodeRef, settings: ScanSettings): ScanResult {
        val listing = source.list(root)
        val ignExt = settings.ignoreExtensions.map { it.lowercase().trimStart('.') }.toSet()
        val ignDir = settings.ignoreFolders.map { it.lowercase() }.toSet()
        val files = listing.nodes.filter { !it.isDir && extension(it.name) !in ignExt && !it.name.startsWith(".") }
        val dirs = listing.nodes.filter { it.isDir && it.name.lowercase() !in ignDir && !it.name.startsWith(".") }
        return ScanResult(
            root = root,
            files = files.sortedBy { it.name.lowercase() },
            existingFolders = dirs.sortedBy { it.name.lowercase() },
            skipped = listing.skipped,
        )
    }
}
