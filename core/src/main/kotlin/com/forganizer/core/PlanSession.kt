package com.forganizer.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PlanVersion(val number: Int, val label: String, val plan: OrganizePlan, val time: Long)

/** Everything needed to reopen a scheme: the plan, its versions, pinned decisions and history. */
@Serializable
data class PlanSnapshot(
    val rootId: String,
    val rootLabel: String,
    val plan: OrganizePlan,
    val versions: List<PlanVersion>,
    val pins: Pins,
    val history: List<String>,
    val refinesUsed: Int,
    val allowExisting: Boolean,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(text: String): PlanSnapshot = json.decodeFromString(serializer(), text)
    }
}

class RefineLimitException : Exception("Достигнут лимит правок на сессию")

/**
 * The editable plan with its history. Manual edits are pinned; AI edits arrive as patches that are
 * applied to a copy and only change the plan after [accept].
 */
class PlanSession(
    initial: OrganizePlan,
    val allowExisting: Boolean,
    val maxRefines: Int = MAX_REFINES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    var plan: OrganizePlan = initial
        private set
    var pins: Pins = Pins()
        private set
    var history: List<String> = emptyList()
        private set
    var refinesUsed: Int = 0
        private set
    var versions: List<PlanVersion> = listOf(PlanVersion(1, "Исходный план", initial, now()))
        private set

    val refinesLeft: Int get() = (maxRefines - refinesUsed).coerceAtLeast(0)
    private val engine get() = PatchEngine(plan.existingFolders, allowExisting)

    // --- manual edits (pinned) ---------------------------------------------------------------------

    fun setChecked(ids: Set<String>, checked: Boolean) {
        plan = plan.setChecked(ids, checked)
        val folderOf = plan.items.associate { it.file.id to it.folder }
        pins = pins.pinFiles(ids.filter { it in folderOf }.associateWith { if (checked) folderOf[it] else null })
    }

    fun renameFolder(old: String, new: String): Boolean {
        val next = plan.renameFolder(old, new) ?: return false
        val newName = FolderNames.normalize(new) ?: return false
        plan = next
        pins = pins.renameFolder(old, newName).pinFolder(newName)
        return true
    }

    fun moveFiles(ids: Set<String>, target: String) {
        plan = plan.moveFiles(ids, target)
        val folderOf = plan.items.associate { it.file.id to it.folder }
        pins = pins.pinFiles(ids.filter { it in folderOf }.associateWith { folderOf[it] })
    }

    // --- AI edits ----------------------------------------------------------------------------------

    fun refineRequest(instruction: String): RefineRequest {
        val folders = plan.folders.map { f ->
            val items = plan.itemsIn(f.name)
            FolderSummaryDto(
                name = f.name,
                count = items.size,
                exts = items.groupingBy { extension(it.file.name).ifEmpty { "-" } }.eachCount(),
                bundles = items.mapNotNull { it.bundle }.distinct().take(20),
            )
        }
        return RefineRequest(
            instruction = Text.clean(instruction, 500),
            existingFolders = plan.existingFolders,
            allowExisting = allowExisting,
            folders = folders,
            leave = LeaveSummaryDto(plan.leave.size, plan.leave.groupingBy { extension(it.file.name).ifEmpty { "-" } }.eachCount()),
            pinned = pins.toDto(plan),
            history = history.takeLast(3),
        )
    }

    /** Sends the instruction and returns the patch preview; the plan itself is not changed. */
    suspend fun refine(api: RefineApi, instruction: String): Pair<PatchResult, String> {
        if (refinesLeft <= 0) throw RefineLimitException()
        val raw = api.refine(refineRequest(instruction))
        refinesUsed++
        return preview(PatchOp.parseAll(raw)) to Text.clean(raw.note, 120)
    }

    fun preview(ops: List<PatchOp>): PatchResult = engine.apply(plan, ops, pins)

    fun accept(result: PatchResult, instruction: String) {
        plan = result.plan
        history = (history + Text.clean(instruction, 200)).takeLast(10)
        var p = pins
        result.renamedFolders.forEach { (old, new) -> p = p.renameFolder(old, new).pinFolder(new) }
        result.createdFolders.forEach { p = p.pinFolder(it) }
        p = p.pinFiles(result.changedFiles)
        pins = p
        versions = versions + PlanVersion(versions.last().number + 1, Text.clean(instruction, 80), plan, now())
    }

    /** Rolls back to a version; the rollback itself becomes a new version so it can be undone too. */
    fun rollback(number: Int): Boolean {
        val v = versions.firstOrNull { it.number == number } ?: return false
        plan = v.plan
        versions = versions + PlanVersion(versions.last().number + 1, "Откат к версии $number", plan, now())
        return true
    }

    /** Takes a scheme loaded from outside as a new version; nothing is pinned, the previous plan stays in versions. */
    fun importPlan(result: ImportResult, label: String = "Загруженная схема") {
        plan = result.plan
        pins = Pins()
        versions = versions + PlanVersion(versions.last().number + 1, label, plan, now())
    }

    /** Replaces the plan without history (e.g. stale marks after reopening). */
    fun replacePlan(p: OrganizePlan) {
        plan = p
    }

    fun snapshot(rootId: String, rootLabel: String) =
        PlanSnapshot(rootId, rootLabel, plan, versions, pins, history, refinesUsed, allowExisting)

    companion object {
        const val MAX_REFINES = 10

        fun restore(s: PlanSnapshot, now: () -> Long = System::currentTimeMillis): PlanSession =
            PlanSession(s.plan, s.allowExisting, now = now).also {
                it.plan = s.plan
                it.pins = s.pins
                it.history = s.history
                it.refinesUsed = 0 // the limit is per session; a reopened scheme starts a new one
                if (s.versions.isNotEmpty()) it.versions = s.versions
            }
    }
}

/** Checks a reopened scheme against the disk: missing or changed files are marked and excluded. */
object Staleness {
    suspend fun check(plan: OrganizePlan, source: FileSource): OrganizePlan {
        val reasons = HashMap<String, String>()
        for (item in plan.items) {
            val cur = try {
                source.stat(item.file.ref)
            } catch (e: AccessLostException) {
                throw e
            } catch (e: Exception) {
                null
            }
            when {
                cur == null -> reasons[item.file.id] = "Файла больше нет"
                cur.size != item.file.size || cur.modified != item.file.modified -> reasons[item.file.id] = "Файл изменился после сохранения"
            }
        }
        // Clear old marks for files that are fine again.
        val cleared = plan.copy(items = plan.items.map { if (it.stale != null && it.file.id !in reasons) it.copy(stale = null) else it })
        return cleared.markStale(reasons)
    }
}
