package com.forganizer.core

import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Builds a short summary of the entry NAMES inside a zip archive: counts, root folders, file types
 * and well-known project markers. File contents are never read. Only whitelisted markers, extensions
 * and short cleaned folder names make it into the text, so names inside an archive cannot smuggle
 * long instructions to the model.
 */
object ArchiveSummary {
    private val MARKERS = setOf(
        "package.json", "requirements.txt", "pyproject.toml", "setup.py", "pom.xml", "build.gradle",
        "build.gradle.kts", "settings.gradle", "cargo.toml", "go.mod", "composer.json", "dockerfile",
        "docker-compose.yml", "makefile", "androidmanifest.xml", "readme.md", "index.html", ".gitignore",
    )
    private val EXT = Regex("[a-z0-9]{1,8}")

    fun summarize(entryNames: List<String>, truncated: Boolean = false, maxLen: Int = 220): String? {
        val names = entryNames.map { it.replace('\\', '/').removePrefix("./") }.filter { it.isNotBlank() }
        if (names.isEmpty()) return null
        val files = names.filter { !it.endsWith("/") }

        val rootDirs = names.filter { it.contains('/') }.map { it.substringBefore('/') }
            .filter { it.isNotEmpty() }.distinct().take(3).map { Text.clean(it, 30) + "/" }
        val rootFiles = files.count { !it.contains('/') }

        val exts = files.mapNotNull { n ->
            val e = extension(n.substringAfterLast('/'))
            e.takeIf { EXT.matches(it) }
        }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(5)

        val baseNames = files.map { it.substringAfterLast('/').lowercase() }.toSet()
        val markers = MARKERS.filter { it in baseNames }.sorted().take(5).toMutableList()
        if (names.any { it.startsWith(".git/") || it.contains("/.git/") }) markers.add(0, ".git")

        val parts = mutableListOf<String>()
        parts += "${files.size}${if (truncated) "+" else ""} файлов"
        parts += when {
            rootDirs.isNotEmpty() -> "корень: " + rootDirs.joinToString(", ") + if (rootFiles > 0) " и файлы в корне" else ""
            else -> "файлы без папки"
        }
        if (exts.isNotEmpty()) parts += "типы: " + exts.joinToString(", ") { "${it.key} ${it.value}" }
        if (markers.isNotEmpty()) parts += "маркеры: " + markers.joinToString(", ")
        val text = parts.joinToString("; ")
        return if (text.length > maxLen) text.substring(0, maxLen - 1).trimEnd() + "…" else text
    }
}

/**
 * Reads zip entry names (never contents) for archives in the selected folder. With a real file the
 * central directory is read directly (fast, any size); otherwise the stream is walked, which is
 * limited to smaller archives and to a time budget.
 */
class ArchivePeeker(
    private val source: FileSource,
    private val maxArchives: Int = 80,
    private val budgetMs: Long = 25_000,
    private val maxStreamBytes: Long = 64L * 1024 * 1024,
    private val maxEntries: Int = 20_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Returns file id -> summary for every zip archive that could be read. */
    fun peekAll(files: List<FileNode>, isCancelled: () -> Boolean = { false }): Map<String, String> {
        val start = clock()
        val out = HashMap<String, String>()
        for (f in files.filter { !it.isDir && extension(it.name) == "zip" }.take(maxArchives)) {
            if (isCancelled() || clock() - start > budgetMs) break
            val read = try {
                readNames(f)
            } catch (e: Exception) {
                null
            } ?: continue
            ArchiveSummary.summarize(read.first, read.second)?.let { out[f.id] = it }
        }
        return out
    }

    private fun readNames(f: FileNode): Pair<List<String>, Boolean>? {
        source.localFile(f)?.let { return namesFromFile(it) }
        if (f.size > maxStreamBytes) return null
        return namesFromStream(f)
    }

    /** Charsets to try: modern UTF-8 names first, then DOS Cyrillic (common for zips made on Windows). */
    private val charsets: List<Charset> = listOfNotNull(
        Charsets.UTF_8,
        runCatching { Charset.forName("IBM866") }.getOrNull(),
        Charsets.ISO_8859_1,
    )

    private fun namesFromFile(file: File): Pair<List<String>, Boolean>? {
        for (cs in charsets) {
            try {
                ZipFile(file, cs).use { zip ->
                    val names = ArrayList<String>()
                    val en = zip.entries()
                    while (en.hasMoreElements() && names.size < maxEntries) names += en.nextElement().name
                    return names to en.hasMoreElements()
                }
            } catch (e: Exception) {
                // malformed names for this charset or a broken archive: try the next charset
            }
        }
        return null
    }

    private fun namesFromStream(f: FileNode): Pair<List<String>, Boolean>? {
        for (cs in charsets) {
            try {
                ZipInputStream(source.openRead(f), cs).use { zip ->
                    val names = ArrayList<String>()
                    while (names.size < maxEntries) {
                        val e = zip.nextEntry ?: return names to false
                        names += e.name
                    }
                    return names to true
                }
            } catch (e: Exception) {
                // try the next charset
            }
        }
        return null
    }
}
