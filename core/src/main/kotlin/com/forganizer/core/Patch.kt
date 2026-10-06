package com.forganizer.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** File selector of a patch op; set fields are combined with AND. */
data class Selector(
    val ext: List<String>? = null,
    val nameContains: String? = null,
    val nameStartsWith: String? = null,
    val bundle: String? = null,
    val fromFolder: String? = null,
    val group: String? = null,
    val refs: List<String>? = null,
) {
    val isEmpty: Boolean
        get() = ext.isNullOrEmpty() && nameContains.isNullOrBlank() && nameStartsWith.isNullOrBlank() &&
            bundle.isNullOrBlank() && fromFolder.isNullOrBlank() && group.isNullOrBlank() && refs.isNullOrEmpty()
}

sealed interface PatchOp {
    data class Move(val select: Selector, val to: String) : PatchOp
    data class ToLeave(val select: Selector) : PatchOp
    data class CreateFolder(val name: String, val desc: String) : PatchOp
    data class RenameFolder(val from: String, val to: String) : PatchOp
    data class MergeFolders(val from: List<String>, val into: String) : PatchOp
    data class Unbundle(val bundle: String) : PatchOp
    /** Anything else the model sent: never executed. */
    data class Rejected(val name: String, val why: String) : PatchOp

    companion object {
        const val MAX_OPS = 20
        const val MAX_REFS = 20

        fun parseAll(raw: RawPatch): List<PatchOp> = raw.ops.take(MAX_OPS).map(::parse)

        fun parse(o: JsonObject): PatchOp {
            val name = o.str("op") ?: return Rejected("?", "Операция без названия")
            fun sel(): Selector? = (o["select"] as? JsonObject)?.let { s ->
                Selector(
                    ext = s.list("ext")?.map { it.lowercase().trimStart('.') }?.filter { it.isNotEmpty() },
                    nameContains = s.str("name_contains"),
                    nameStartsWith = s.str("name_starts_with"),
                    bundle = s.str("bundle"),
                    fromFolder = s.str("from_folder"),
                    group = s.str("group"),
                    refs = s.list("refs")?.take(MAX_REFS),
                )
            }?.takeIf { !it.isEmpty }
            return when (name) {
                "move" -> {
                    val s = sel() ?: return Rejected(name, "Пустой селектор")
                    val to = o.str("to") ?: return Rejected(name, "Не указана папка")
                    Move(s, to)
                }
                "to_leave" -> ToLeave(sel() ?: return Rejected(name, "Пустой селектор"))
                "create_folder" -> CreateFolder(o.str("name") ?: return Rejected(name, "Нет имени"), o.str("desc") ?: "")
                "rename_folder" -> RenameFolder(
                    o.str("from") ?: return Rejected(name, "Нет исходного имени"),
                    o.str("to") ?: return Rejected(name, "Нет нового имени"),
                )
                "merge_folders" -> MergeFolders(
                    o.list("from")?.takeIf { it.isNotEmpty() } ?: return Rejected(name, "Нет исходных папок"),
                    o.str("into") ?: return Rejected(name, "Нет целевой папки"),
                )
                "unbundle" -> Unbundle(o.str("bundle") ?: return Rejected(name, "Нет имени набора"))
                else -> Rejected(name, "Операция «${Text.clean(name, 30)}» не поддерживается")
            }
        }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.ifEmpty { null }

        private fun JsonObject.list(key: String): List<String>? = when (val v = this[key]) {
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull?.trim() }.filter { it.isNotEmpty() }
            is JsonPrimitive -> v.contentOrNull?.trim()?.let { listOf(it) }
            else -> null
        }
    }
}

/** Decisions made by the user by hand or by accepting an AI edit. The AI cannot change them. */
@kotlinx.serialization.Serializable
data class Pins(
    val folders: List<String> = emptyList(),
    /** file id -> folder name, or null when the file was deliberately left out / unchecked. */
    val files: Map<String, String?> = emptyMap(),
    /** Order of file pins, most recent last (used to send the latest 50). */
    val order: List<String> = emptyList(),
) {
    fun folderPinned(name: String) = folders.any { FolderNames.key(it) == FolderNames.key(name) }
    fun filePinned(id: String) = id in files

    fun pinFolder(name: String, replacing: String? = null): Pins {
        val rest = folders.filter { FolderNames.key(it) != FolderNames.key(name) && (replacing == null || FolderNames.key(it) != FolderNames.key(replacing)) }
        return copy(folders = rest + name)
    }

    fun renameFolder(old: String, new: String): Pins = copy(
        folders = folders.map { if (FolderNames.key(it) == FolderNames.key(old)) new else it },
        files = files.mapValues { (_, f) -> if (f != null && FolderNames.key(f) == FolderNames.key(old)) new else f },
    )

    fun pinFiles(entries: Map<String, String?>): Pins {
        if (entries.isEmpty()) return this
        return copy(files = files + entries, order = order.filter { it !in entries } + entries.keys)
    }

    fun toDto(plan: OrganizePlan, limit: Int = 50): List<PinDto> {
        val refOf = plan.items.associate { it.file.id to it.ref } + plan.leave.associate { it.file.id to it.ref }
        val out = LinkedHashMap<String, PinDto>()
        folders.reversed().forEach { out.putIfAbsent("folder:" + FolderNames.key(it), PinDto("folder_name", name = it)) }
        for (id in order.reversed()) {
            val ref = refOf[id] ?: continue
            val folder = files[id]
            val dto = if (folder != null) PinDto("file_in_folder", ref = ref, folder = folder) else PinDto("file_excluded", ref = ref)
            out.putIfAbsent("file:$ref", dto)
            if (out.size >= limit) break
        }
        return out.values.take(limit)
    }
}

data class PatchResult(
    val before: OrganizePlan,
    val plan: OrganizePlan,
    val applied: List<String>,
    val skipped: List<String>,
    /** Ops that touch more than half of all files; acceptance needs an extra confirmation. */
    val bigOps: List<String>,
    val movedFiles: Int,
    val createdFolders: List<String>,
    val touchedFolders: List<String>,
    /** File id -> new folder (null = not determined) for every file whose place changed. */
    val changedFiles: Map<String, String?>,
    val renamedFolders: Map<String, String>,
) {
    val hasChanges: Boolean get() = changedFiles.isNotEmpty() || renamedFolders.isNotEmpty() ||
        plan.items.map { it.bundle } != before.items.map { it.bundle }
}

/**
 * Applies a patch to a copy of the plan. Selectors are expanded locally, pinned decisions are never
 * touched, folder names go through the same checks as in the validator, nothing is ever deleted.
 */
class PatchEngine(
    private val existingFolders: List<String>,
    private val allowExisting: Boolean,
) {
    private data class Entry(
        val file: FileNode,
        val ref: String,
        var folder: String?,
        var bundle: String?,
        var reason: String,
        var confidence: Double,
        var source: ItemSource,
        var checked: Boolean,
        val stale: String?,
    )

    private val existingByKey = existingFolders.associateBy { FolderNames.key(it) }

    fun apply(plan: OrganizePlan, ops: List<PatchOp>, pins: Pins): PatchResult {
        val entries = LinkedHashMap<String, Entry>()
        plan.items.forEach { entries[it.file.id] = Entry(it.file, it.ref, it.folder, it.bundle, it.reason, it.confidence, it.source, it.checked, it.stale) }
        plan.leave.forEach { entries[it.file.id] = Entry(it.file, it.ref, null, null, it.reason, 0.0, ItemSource.AI, false, null) }
        val folders = LinkedHashMap<String, PlanFolder>()
        plan.folders.forEach { folders[FolderNames.key(it.name)] = it }
        val total = entries.size.coerceAtLeast(1)
        val applied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val big = mutableListOf<String>()
        val renamed = LinkedHashMap<String, String>()

        fun inLeave(name: String) = name.trim().lowercase() in setOf("leave", "не определено", "неопределено")

        fun select(s: Selector): List<Entry> = entries.values.filter { e ->
            val name = e.file.name
            (s.ext.isNullOrEmpty() || extension(name) in s.ext) &&
                (s.nameContains.isNullOrBlank() || name.contains(s.nameContains, ignoreCase = true)) &&
                (s.nameStartsWith.isNullOrBlank() || name.startsWith(s.nameStartsWith, ignoreCase = true)) &&
                (s.bundle.isNullOrBlank() || e.bundle.equals(s.bundle, ignoreCase = true)) &&
                (s.fromFolder.isNullOrBlank() || (if (inLeave(s.fromFolder)) e.folder == null else e.folder?.let { FolderNames.key(it) } == FolderNames.key(s.fromFolder))) &&
                (s.group.isNullOrBlank() || e.ref == s.group) &&
                (s.refs.isNullOrEmpty() || e.ref in s.refs)
        }

        /** Splits matched files into editable and pinned; reports what could not be done. */
        fun editable(label: String, matched: List<Entry>): List<Entry>? {
            if (matched.isEmpty()) {
                skipped += "$label: ничего не найдено"; return null
            }
            val (pinned, free) = matched.partition { pins.filePinned(it.file.id) }
            if (pinned.isNotEmpty()) skipped += "$label: закреплённые вами файлы не изменены (${pinned.size})"
            if (free.isEmpty()) return null
            if (free.size * 2 > total) big += "$label: затронет ${free.size} из $total файлов"
            return free
        }

        /** Resolves a destination folder; may create a new plan folder. Null = rejected (reason added). */
        fun target(rawName: String, label: String, desc: String = ""): PlanFolder? {
            val key = FolderNames.key(rawName.trim())
            folders[key]?.let { return it }
            existingByKey[key]?.let { ex ->
                if (!allowExisting) { skipped += "$label: ${Reasons.EXISTING_FORBIDDEN.lowercase()}"; return null }
                return PlanFolder(ex, "", existing = true).also { folders[key] = it }
            }
            val norm = FolderNames.normalize(rawName)
            if (norm == null) { skipped += "$label: ${Reasons.BAD_PATH.lowercase()}"; return null }
            val nk = FolderNames.key(norm)
            folders[nk]?.let { return it }
            existingByKey[nk]?.let { ex ->
                if (!allowExisting) { skipped += "$label: ${Reasons.EXISTING_FORBIDDEN.lowercase()}"; return null }
                return PlanFolder(ex, "", existing = true).also { folders[nk] = it }
            }
            return PlanFolder(norm, Text.clean(desc, 60)).also { folders[nk] = it }
        }

        fun moveTo(list: List<Entry>, f: PlanFolder, reason: String) {
            for (e in list) {
                if (e.folder == null) {
                    e.reason = reason; e.confidence = 1.0; e.source = ItemSource.AI
                    e.checked = e.stale == null
                } else if (FolderNames.key(e.folder!!) != FolderNames.key(f.name)) {
                    e.reason = reason
                }
                e.folder = f.name
            }
        }

        for (op in ops) {
            when (op) {
                is PatchOp.Move -> {
                    val label = "Перенести в ${Text.clean(op.to, 30)}"
                    val list = editable(label, select(op.select)) ?: continue
                    val f = target(op.to, label) ?: continue
                    moveTo(list, f, "Перенесено по вашей инструкции")
                    applied += "$label: ${list.size} файл."
                }
                is PatchOp.ToLeave -> {
                    val label = "Оставить на месте"
                    val list = editable(label, select(op.select)) ?: continue
                    list.forEach { it.folder = null; it.bundle = null; it.reason = "Исключено по вашей инструкции"; it.checked = false }
                    applied += "$label: ${list.size} файл."
                }
                is PatchOp.CreateFolder -> {
                    val label = "Создать папку ${Text.clean(op.name, 30)}"
                    val norm = FolderNames.normalize(op.name)
                    when {
                        norm == null -> skipped += "$label: ${Reasons.BAD_PATH.lowercase()}"
                        FolderNames.key(norm) in folders -> skipped += "$label: такая папка уже есть в плане"
                        FolderNames.key(norm) in existingByKey && !allowExisting -> skipped += "$label: папка уже существует на диске"
                        else -> {
                            target(norm, label, op.desc)
                            applied += label
                        }
                    }
                }
                is PatchOp.RenameFolder -> {
                    val label = "Переименовать ${Text.clean(op.from, 30)}"
                    val f = folders[FolderNames.key(op.from.trim())]
                    val norm = FolderNames.normalize(op.to)
                    when {
                        f == null -> skipped += "$label: папки нет в плане"
                        f.existing -> skipped += "$label: существующие папки не переименовываются"
                        pins.folderPinned(f.name) -> skipped += "$label: имя закреплено вами"
                        norm == null -> skipped += "$label: ${Reasons.BAD_PATH.lowercase()}"
                        FolderNames.key(norm) != FolderNames.key(f.name) &&
                            (FolderNames.key(norm) in folders || FolderNames.key(norm) in existingByKey) -> skipped += "$label: имя $norm уже занято"
                        else -> {
                            folders.remove(FolderNames.key(f.name))
                            folders[FolderNames.key(norm)] = f.copy(name = norm)
                            entries.values.filter { it.folder != null && FolderNames.key(it.folder!!) == FolderNames.key(f.name) }
                                .forEach { it.folder = norm }
                            renamed[f.name] = norm
                            applied += "$label → $norm"
                        }
                    }
                }
                is PatchOp.MergeFolders -> {
                    val label = "Объединить в ${Text.clean(op.into, 30)}"
                    val sources = op.from.mapNotNull { name ->
                        val f = folders[FolderNames.key(name.trim())]
                        when {
                            f == null -> { skipped += "$label: папки ${Text.clean(name, 30)} нет в плане"; null }
                            f.existing -> { skipped += "$label: существующую папку ${f.name} объединять нельзя"; null }
                            pins.folderPinned(f.name) -> { skipped += "$label: папка ${f.name} закреплена вами"; null }
                            FolderNames.key(f.name) == FolderNames.key(op.into.trim()) -> null
                            else -> f
                        }
                    }
                    if (sources.isEmpty()) continue
                    val keys = sources.map { FolderNames.key(it.name) }.toSet()
                    val list = editable(label, entries.values.filter { it.folder != null && FolderNames.key(it.folder!!) in keys }) ?: continue
                    val f = target(op.into, label) ?: continue
                    moveTo(list, f, "Папки объединены по вашей инструкции")
                    applied += "$label: ${sources.joinToString { it.name }}"
                }
                is PatchOp.Unbundle -> {
                    val list = entries.values.filter { it.bundle.equals(op.bundle, ignoreCase = true) }
                    if (list.isEmpty()) skipped += "Разделить набор ${Text.clean(op.bundle, 30)}: ничего не найдено"
                    else {
                        list.forEach { it.bundle = null }
                        applied += "Разделить набор ${list.first().bundle ?: op.bundle}"
                    }
                }
                is PatchOp.Rejected -> skipped += op.why
            }
        }

        // A bundle must stay in one folder; a split one simply stops being a bundle.
        entries.values.filter { it.bundle != null }.groupBy { it.bundle!!.lowercase() }.values.forEach { g ->
            if (g.size < 2 || g.map { it.folder?.let(FolderNames::key) }.toSet().size > 1) g.forEach { it.bundle = null }
        }

        val items = entries.values.filter { it.folder != null }.map {
            PlanItem(it.file, it.ref, it.folder!!, it.bundle, it.reason, it.confidence, it.source, it.checked, it.stale)
        }
        val leave = entries.values.filter { it.folder == null }.map { LeaveItem(it.file, it.ref, it.reason) }
        val used = items.map { FolderNames.key(it.folder) }.toSet()
        val newPlan = plan.copy(folders = folders.values.filter { FolderNames.key(it.name) in used }, items = items, leave = leave)

        // Diff against the original plan.
        val oldFolderOf = plan.items.associate { it.file.id to it.folder }
        val changed = LinkedHashMap<String, String?>()
        for (e in entries.values) {
            val old = oldFolderOf[e.file.id]?.let { renamed[it] ?: it }
            if (old?.let(FolderNames::key) != e.folder?.let(FolderNames::key)) changed[e.file.id] = e.folder
        }
        val oldKeys = plan.folders.map { FolderNames.key(renamed[it.name] ?: it.name) }.toSet()
        val created = newPlan.folders.filter { FolderNames.key(it.name) !in oldKeys }.map { it.name }
        val touched = LinkedHashSet<String>()
        for (id in changed.keys) {
            oldFolderOf[id]?.let { touched += renamed[it] ?: it }
            changed[id]?.let { touched += it }
        }
        touched += renamed.values
        return PatchResult(
            before = plan, plan = newPlan, applied = applied, skipped = skipped, bigOps = big,
            movedFiles = changed.size, createdFolders = created,
            touchedFolders = touched.distinctBy { FolderNames.key(it) }, changedFiles = changed, renamedFolders = renamed,
        )
    }
}
