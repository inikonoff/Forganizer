package com.forganizer.core

enum class ItemSource { LOCAL, AI }

data class PlanItem(
    val file: FileNode,
    val folder: String,
    val bundle: String?,
    val reason: String,
    val confidence: Double,
    val source: ItemSource,
    val checked: Boolean,
)

data class LeaveItem(val file: FileNode, val reason: String)

/** The editable plan shown on the "folder picture" screen. Folder names are unique case-insensitively. */
data class OrganizePlan(
    val folders: List<PlanFolder>,
    val items: List<PlanItem>,
    val leave: List<LeaveItem>,
    val existingFolders: List<String>,
) {
    fun itemsIn(folder: String) = items.filter { FolderNames.key(it.folder) == FolderNames.key(folder) }
    val checkedItems: List<PlanItem> get() = items.filter { it.checked }

    fun setChecked(fileIds: Set<String>, checked: Boolean) =
        copy(items = items.map { if (it.file.id in fileIds) it.copy(checked = checked) else it })

    /** Renames a new (not existing) folder. Returns null if the name is invalid or already taken. */
    fun renameFolder(old: String, newRaw: String): OrganizePlan? {
        val f = folders.firstOrNull { FolderNames.key(it.name) == FolderNames.key(old) } ?: return null
        if (f.existing) return null
        val name = FolderNames.normalize(newRaw) ?: return null
        val key = FolderNames.key(name)
        if (key != FolderNames.key(old) &&
            (folders.any { FolderNames.key(it.name) == key } || existingFolders.any { FolderNames.key(it) == key })
        ) return null
        return copy(
            folders = folders.map { if (it === f) it.copy(name = name) else it },
            items = items.map { if (FolderNames.key(it.folder) == FolderNames.key(old)) it.copy(folder = name) else it },
        )
    }

    /** Moves a group of files (a bundle or a whole folder) to another folder of the plan. */
    fun moveFiles(fileIds: Set<String>, target: String): OrganizePlan {
        val f = folders.firstOrNull { FolderNames.key(it.name) == FolderNames.key(target) } ?: return this
        val moved = copy(items = items.map { if (it.file.id in fileIds) it.copy(folder = f.name) else it })
        return moved.dropEmptyFolders()
    }

    private fun dropEmptyFolders(): OrganizePlan {
        val used = items.map { FolderNames.key(it.folder) }.toSet()
        return copy(folders = folders.filter { FolderNames.key(it.name) in used })
    }

    companion object {
        const val DEFAULT_CHECK_THRESHOLD = 0.7

        fun build(
            summary: Summary,
            ai: ValidatedPlan,
            existingFolders: List<String>,
            allowExisting: Boolean,
        ): OrganizePlan {
            val existingByKey = existingFolders.associateBy { FolderNames.key(it) }
            val folders = LinkedHashMap<String, PlanFolder>()
            val items = mutableListOf<PlanItem>()
            val leave = mutableListOf<LeaveItem>()

            for (l in summary.local) {
                val ex = existingByKey[FolderNames.key(l.folder)]
                if (ex != null && !allowExisting) {
                    leave += LeaveItem(l.file, Reasons.EXISTING_FORBIDDEN); continue
                }
                val name = ex ?: l.folder
                folders.putIfAbsent(FolderNames.key(name), PlanFolder(name, if (ex != null) "" else "Определено по расширению", existing = ex != null))
                items += PlanItem(l.file, name, null, l.reason, 1.0, ItemSource.LOCAL, checked = true)
            }
            ai.folders.forEach { folders.putIfAbsent(FolderNames.key(it.name), it) }
            for (a in ai.assignments) {
                val obj = summary.byId[a.ref] ?: continue
                val folder = folders[FolderNames.key(a.folder)]?.name ?: a.folder
                obj.members.forEach {
                    items += PlanItem(it, folder, a.bundle, a.reason, a.confidence, ItemSource.AI, a.confidence >= DEFAULT_CHECK_THRESHOLD)
                }
            }
            for (l in ai.leave) {
                val obj = summary.byId[l.ref] ?: continue
                obj.members.forEach { leave += LeaveItem(it, l.reason) }
            }
            val used = items.map { FolderNames.key(it.folder) }.toSet()
            return OrganizePlan(folders.values.filter { FolderNames.key(it.name) in used }, items, leave, existingFolders)
        }
    }
}

data class NameConflict(val item: PlanItem, val resolvedName: String?)

object Conflicts {
    /** "name.ext" -> "name (1).ext", the first free variant (case-insensitive). */
    fun uniqueName(name: String, taken: Set<String>): String {
        val lower = taken.map { it.lowercase() }.toSet()
        if (name.lowercase() !in lower) return name
        val ext = extension(name)
        val base = baseName(name)
        var i = 1
        while (true) {
            val candidate = if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"
            if (candidate.lowercase() !in lower) return candidate
            i++
        }
    }

    /**
     * Finds name conflicts for checked items: with files already present in the target folder and
     * between files of the plan going to the same folder.
     */
    fun detect(items: List<PlanItem>, existingNames: Map<String, Set<String>>, mode: ConflictMode): List<NameConflict> {
        val out = mutableListOf<NameConflict>()
        val taken = HashMap<String, MutableSet<String>>()
        for (it in items) {
            val key = FolderNames.key(it.folder)
            val names = taken.getOrPut(key) { existingNames[key].orEmpty().map { n -> n.lowercase() }.toMutableSet() }
            if (it.file.name.lowercase() in names) {
                val resolved = if (mode == ConflictMode.RENAME) uniqueName(it.file.name, names) else null
                out += NameConflict(it, resolved)
                if (resolved != null) names += resolved.lowercase()
            } else names += it.file.name.lowercase()
        }
        return out
    }
}

enum class ConflictMode { RENAME, SKIP }
