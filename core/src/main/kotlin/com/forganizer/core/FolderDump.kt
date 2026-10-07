package com.forganizer.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
data class DumpFile(
    val id: String,
    val name: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val modified: String,
    val ext: String,
    val type: String,
    val ignored: Boolean = false,
)

@Serializable
data class DumpFolder(val id: String, val name: String, val ignored: Boolean = false)

/** A snapshot of the selected folder before any sorting: names, sizes and dates only, no contents. */
@Serializable
data class DumpDoc(
    val app: String = "Forganizer",
    val kind: String = "folder_snapshot",
    val root: String,
    val taken: String,
    val source: String,
    val folders: List<DumpFolder>,
    val files: List<DumpFile>,
    val skipped: Int = 0,
    @SerialName("possible_duplicates") val possibleDuplicates: List<List<String>> = emptyList(),
)

object FolderDump {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val textDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun build(
        root: String,
        takenAt: Long,
        files: List<FileNode>,
        folders: List<FileNode>,
        ignoredFiles: List<FileNode> = emptyList(),
        ignoredFolders: List<FileNode> = emptyList(),
        skipped: Int = 0,
        duplicates: List<List<FileNode>> = emptyList(),
        rules: Rules = Rules.DEFAULT,
        source: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): DumpDoc {
        fun file(n: FileNode, ignored: Boolean) = DumpFile(
            id = n.id, name = n.name, sizeBytes = n.size,
            modified = if (n.modified > 0) Instant.ofEpochMilli(n.modified).toString() else "",
            ext = extension(n.name), type = rules.typeOf(n.name), ignored = ignored,
        )
        return DumpDoc(
            root = root,
            taken = Instant.ofEpochMilli(takenAt).atZone(zone).format(textDate),
            source = source,
            folders = (folders.map { DumpFolder(it.id, it.name) } + ignoredFolders.map { DumpFolder(it.id, it.name, true) })
                .sortedBy { it.name.lowercase() },
            files = (files.map { file(it, false) } + ignoredFiles.map { file(it, true) }).sortedBy { it.name.lowercase() },
            skipped = skipped,
            possibleDuplicates = duplicates.map { g -> g.map { it.name } },
        )
    }

    fun toJson(doc: DumpDoc): String = json.encodeToString(DumpDoc.serializer(), doc)

    fun toText(doc: DumpDoc, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        appendLine("Снимок папки: ${doc.root}")
        appendLine("Снято: ${doc.taken}")
        appendLine("Источник: ${doc.source}")
        appendLine("Файлов: ${doc.files.size}, папок: ${doc.folders.size}" + if (doc.skipped > 0) ", недоступно: ${doc.skipped}" else "")
        appendLine("Это только список имён, размеров и дат. Сами файлы не копируются.")
        appendLine()
        appendLine("Папки (${doc.folders.size}):")
        doc.folders.forEach { appendLine("  ${it.name}/" + if (it.ignored) "  [игнор.]" else "") }
        appendLine()
        appendLine("Файлы (${doc.files.size}):")
        for (f in doc.files) {
            val date = if (f.modified.isEmpty()) "                " else Instant.parse(f.modified).atZone(zone).format(textDate)
            appendLine("  $date  ${size(f.sizeBytes).padStart(9)}  ${f.name}" + if (f.ignored) "  [игнор.]" else "")
        }
        if (doc.possibleDuplicates.isNotEmpty()) {
            appendLine()
            appendLine("Возможные дубли (${doc.possibleDuplicates.size} групп):")
            doc.possibleDuplicates.forEach { g -> appendLine("  " + g.joinToString("  =  ")) }
        }
    }

    private fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f ГБ".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f МБ".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "${bytes / (1L shl 10)} КБ"
        else -> "$bytes Б"
    }
}
