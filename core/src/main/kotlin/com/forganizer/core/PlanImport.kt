package com.forganizer.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class ImportEntry(
    @SerialName("file_id") val fileId: String = "",
    val file: String = "",
    @SerialName("target_folder") val targetFolder: String? = null,
    val bundle: String? = null,
    val reason: String? = null,
    val confidence: Double? = null,
    val selected: Boolean? = null,
)

@Serializable
private data class ImportLeave(
    @SerialName("file_id") val fileId: String = "",
    val file: String = "",
    val reason: String? = null,
)

@Serializable
private data class ImportDoc(
    val plan: List<ImportEntry> = emptyList(),
    val leave: List<ImportLeave> = emptyList(),
)

class ImportException(message: String) : Exception(message)

data class ImportResult(
    val plan: OrganizePlan,
    /** Files placed into folders by the loaded scheme. */
    val placed: Int,
    /** Files the loaded scheme did not mention; they are left where they are. */
    val notMentioned: Int,
    /** Entries that match no file of the current folder (names, for the report). */
    val unknown: List<String>,
    /** Files whose folder name was unusable or forbidden; they are left where they are. */
    val rejected: List<String>,
)

/**
 * Loads a scheme produced elsewhere (e.g. by another chat model) in the same JSON format the app exports.
 * The file set always comes from the current scan: the loaded scheme can only choose folders, never add files.
 */
object PlanImport {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    const val MAX_CHARS = 2_000_000
    const val NOT_MENTIONED = "Нет в загруженной схеме"
    const val FROM_IMPORT = "Загруженная схема"

    fun parse(text: String, base: OrganizePlan, allowExisting: Boolean): ImportResult {
        if (text.length > MAX_CHARS) throw ImportException("Файл слишком большой")
        val doc = decode(text)
        if (doc.plan.isEmpty() && doc.leave.isEmpty()) throw ImportException("В схеме нет раздела plan с файлами")

        // The current files and what the app already knows about them.
        val files = LinkedHashMap<String, FileNode>()
        val refs = HashMap<String, String>()
        base.items.forEach { files.putIfAbsent(it.file.id, it.file); refs[it.file.id] = it.ref }
        base.leave.forEach { files.putIfAbsent(it.file.id, it.file); refs.putIfAbsent(it.file.id, it.ref) }
        val byName = files.values.groupBy { it.name }
        val byLowerName = files.values.groupBy { it.name.lowercase() }
        fun resolve(id: String, name: String): FileNode? =
            files[id] ?: byName[name]?.singleOrNull() ?: byLowerName[name.lowercase()]?.singleOrNull()

        val existingByKey = base.existingFolders.associateBy { FolderNames.key(it) }
        val oldFolders = base.folders.associateBy { FolderNames.key(it.name) }
        val folders = LinkedHashMap<String, PlanFolder>()
        val items = LinkedHashMap<String, PlanItem>()
        val leave = LinkedHashMap<String, LeaveItem>()
        val unknown = mutableListOf<String>()
        val rejected = mutableListOf<String>()

        fun refOf(file: FileNode) = refs[file.id] ?: "i${refs.size + 1}".also { refs[file.id] = it }
        fun leaveFile(file: FileNode, reason: String) {
            items.remove(file.id)
            leave[file.id] = LeaveItem(file, refOf(file), reason)
        }

        for (e in doc.plan) {
            val file = resolve(e.fileId, e.file) ?: run { unknown += e.file.ifEmpty { e.fileId }; null } ?: continue
            val raw = e.targetFolder?.trim().orEmpty()
            if (raw.isEmpty()) { leaveFile(file, e.reason?.takeIf { it.isNotBlank() } ?: NOT_MENTIONED); continue }
            val name = FolderNames.normalize(raw)
            if (name == null) { rejected += file.name; leaveFile(file, Reasons.BAD_PATH); continue }
            val key = FolderNames.key(name)
            val existing = existingByKey[key]
            if (existing != null && !allowExisting) { rejected += file.name; leaveFile(file, Reasons.EXISTING_FORBIDDEN); continue }
            val folder = existing ?: oldFolders[key]?.name ?: name
            if (file.alreadyIn(folder)) { items.remove(file.id); leave.remove(file.id); continue }
            folders.putIfAbsent(FolderNames.key(folder), PlanFolder(folder, if (existing != null) "" else oldFolders[key]?.desc.orEmpty(), existing = existing != null))
            val conf = (e.confidence ?: 0.8).coerceIn(0.0, 1.0)
            leave.remove(file.id)
            items[file.id] = PlanItem(
                file = file, ref = refOf(file), folder = folder, bundle = e.bundle?.takeIf { it.isNotBlank() },
                reason = e.reason?.takeIf { it.isNotBlank() } ?: FROM_IMPORT, confidence = conf,
                source = ItemSource.AI, checked = e.selected ?: (conf >= OrganizePlan.DEFAULT_CHECK_THRESHOLD),
            )
        }
        for (l in doc.leave) {
            val file = resolve(l.fileId, l.file) ?: continue
            if (file.id !in items) leaveFile(file, l.reason?.takeIf { it.isNotBlank() } ?: NOT_MENTIONED)
        }
        var notMentioned = 0
        for (file in files.values) {
            if (file.id !in items && file.id !in leave) { notMentioned++; leaveFile(file, NOT_MENTIONED) }
        }
        val plan = OrganizePlan(folders.values.toList(), items.values.toList(), leave.values.toList(), base.existingFolders)
        return ImportResult(plan, items.size, notMentioned, unknown.distinct(), rejected)
    }

    private fun FileNode.alreadyIn(folder: String) = OrganizePlan.alreadyThere(this, folder)

    /** Tolerates code fences and chatter around the JSON object, which chat models like to add. */
    private fun decode(text: String): ImportDoc {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw ImportException("Не нашёл JSON: схема должна быть в том же формате, что экспорт приложения")
        return try {
            json.decodeFromString(ImportDoc.serializer(), text.substring(start, end + 1))
        } catch (e: Exception) {
            throw ImportException("Не удалось прочитать схему: формат отличается от экспорта приложения")
        }
    }

    /** Text for a chat model: how to edit the exported scheme, followed by the scheme itself. */
    /** Above this size the text is offered as a file: the Android clipboard is unreliable for very large texts. */
    const val CLIPBOARD_LIMIT = 300_000

    fun handoff(json: String): String = """
Ниже схема раскладки файлов по папкам в формате JSON. Предложи свой вариант и верни результат в том же формате.
Правила:
- Верни один JSON-объект с теми же полями (root, folders, plan, leave). Без пояснений до и после, лучше в блоке кода.
- В каждом элементе plan меняй только target_folder (и при желании bundle, reason, confidence, selected). Поля file_id и file не меняй.
- Каждый файл должен остаться в plan или в leave. Новые файлы добавлять нельзя.
- Название папки: один уровень, до 30 символов, без символов / \ : * ? " < > |. Папки в списке folders можно переименовывать и создавать новые.
- Если файл не стоит трогать, перенеси его в leave.
- selected: true, если файл нужно перемещать, false, если лишь предложение.

$json
""".trimIndent()
}
