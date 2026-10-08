package com.forganizer.core

data class ScanSettings(
    val ignoreExtensions: Set<String>,
    val ignoreFolders: Set<String>,
    /** How many folder levels below the root are read; 0 means the root only. */
    val depth: Int = 0,
)

data class ScanResult(
    val root: NodeRef,
    val files: List<FileNode>,
    val existingFolders: List<FileNode>,
    val skipped: Int,
    /** Root entries the scan ignored (hidden, ignored extensions or folders); kept only for the folder dump. */
    val ignoredFiles: List<FileNode> = emptyList(),
    val ignoredFolders: List<FileNode> = emptyList(),
    /** Relative paths of nested folders treated as one unit (projects); their files are not touched. */
    val projectFolders: List<String> = emptyList(),
    /** True when the file limit was reached and the rest of the nested folders was not read. */
    val truncated: Boolean = false,
)

/** Files and folders whose presence marks a directory as a project / application that must stay intact. */
object ProjectMarkers {
    private val names = setOf(
        ".git", ".hg", ".svn", ".idea", ".vscode", "build.gradle", "build.gradle.kts", "settings.gradle",
        "settings.gradle.kts", "package.json", "pom.xml", "cargo.toml", "go.mod", "pyproject.toml",
        "requirements.txt", "setup.py", "makefile", "cmakelists.txt", "androidmanifest.xml", ".project",
        "composer.json", "gemfile", "pubspec.yaml", "manage.py", "dockerfile",
    )
    private val extensions = setOf("sln", "csproj", "xcodeproj", "uproject")

    fun isProject(children: List<FileNode>): Boolean = children.any {
        val n = it.name.lowercase()
        n in names || (!it.isDir && extension(n) in extensions) || (it.isDir && extension(n) in extensions)
    }
}

/**
 * Lists the root of the selected folder; with [ScanSettings.depth] > 0 also reads nested folders
 * (breadth-first), except hidden and ignored ones and folders that look like projects.
 */
class Scanner(private val source: FileSource, private val maxFiles: Int = 5000) {
    suspend fun scan(root: NodeRef, settings: ScanSettings): ScanResult {
        val listing = source.list(root)
        val ignExt = settings.ignoreExtensions.map { it.lowercase().trimStart('.') }.toSet()
        val ignDir = settings.ignoreFolders.map { it.lowercase() }.toSet()
        fun keepFile(n: FileNode) = !n.isDir && extension(n.name) !in ignExt && !n.name.startsWith(".")
        fun keepDir(n: FileNode) = n.isDir && n.name.lowercase() !in ignDir && !n.name.startsWith(".")
        val files = listing.nodes.filter(::keepFile).toMutableList()
        val dirs = listing.nodes.filter(::keepDir)
        var skipped = listing.skipped
        val projects = mutableListOf<String>()
        var truncated = false

        if (settings.depth > 0) {
            // queue of (directory, relative path, level)
            val queue = ArrayDeque<Triple<FileNode, String, Int>>()
            dirs.sortedBy { it.name.lowercase() }.forEach { queue.add(Triple(it, it.name, 1)) }
            while (queue.isNotEmpty()) {
                val (dir, rel, level) = queue.removeFirst()
                if (files.size >= maxFiles) { truncated = true; break }
                val inner = source.list(dir.ref)
                skipped += inner.skipped
                if (ProjectMarkers.isProject(inner.nodes)) { projects += rel; continue }
                inner.nodes.filter(::keepFile).sortedBy { it.name.lowercase() }
                    .forEach { files += it.copy(dir = dir.id, rel = rel) }
                if (level < settings.depth) {
                    inner.nodes.filter(::keepDir).sortedBy { it.name.lowercase() }
                        .forEach { queue.add(Triple(it, "$rel/${it.name}", level + 1)) }
                }
            }
        }
        return ScanResult(
            root = root,
            files = files.sortedWith(compareBy({ it.rel.lowercase() }, { it.name.lowercase() })),
            existingFolders = dirs.sortedBy { it.name.lowercase() },
            skipped = skipped,
            ignoredFiles = listing.nodes.filter { !it.isDir && !keepFile(it) }.sortedBy { it.name.lowercase() },
            ignoredFolders = listing.nodes.filter { it.isDir && !keepDir(it) }.sortedBy { it.name.lowercase() },
            projectFolders = projects.sorted(),
            truncated = truncated,
        )
    }
}
