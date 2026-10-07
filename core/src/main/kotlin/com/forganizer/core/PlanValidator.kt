package com.forganizer.core

import kotlinx.serialization.Serializable

@Serializable
data class PlanFolder(val name: String, val desc: String, val existing: Boolean = false)

data class PlanAssignment(
    val ref: String,
    val folder: String,
    val bundle: String?,
    val reason: String,
    val confidence: Double,
)

data class PlanLeave(val ref: String, val reason: String)

data class ValidatedPlan(
    val folders: List<PlanFolder>,
    val assignments: List<PlanAssignment>,
    val leave: List<PlanLeave>,
) {
    val noConfident: Boolean get() = assignments.isEmpty()
}

object Reasons {
    const val NOT_DETERMINED = "ИИ не определил"
    const val LOW_CONFIDENCE = "Низкая уверенность ИИ"
    const val BUNDLE_SPLIT = "Связанные файлы попали в разные папки"
    const val EXISTING_FORBIDDEN = "Назначение в существующую папку отключено"
    const val UNKNOWN_FOLDER = "Папка не из предложенного списка"
    const val BAD_PATH = "Недопустимое имя папки"
    const val AI_NO_ANSWER = "ИИ не ответил, запустите анализ ещё раз"
}

object FolderNames {
    private val FORBIDDEN = Regex("""[/\\:*?"<>|]""")
    private val CONTROL = Regex("""\p{Cntrl}""")
    /** Non-ASCII hyphens and dashes that models like to produce (U+2011 and friends). */
    private val DASHES = Regex("[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]")
    private val ODD_SPACES = Regex("[\u00A0\u2002\u2003\u2007\u2009\u200A\u202F]")
    const val MAX_LEN = 30

    /** Normalizes a model-proposed folder name; null if nothing usable remains. */
    fun normalize(raw: String): String? {
        var s = raw.replace(CONTROL, " ").replace(DASHES, "-").replace(ODD_SPACES, " ").replace(FORBIDDEN, " ")
        s = s.replace(Regex("""\s+"""), " ").trim()
        s = s.trimStart('.', ' ')
        if (s.length > MAX_LEN) s = s.substring(0, MAX_LEN).trimEnd()
        s = s.trimEnd('.', ' ')
        if (s.isEmpty() || !isInsideRoot(s)) return null
        return s
    }

    /** A normalized single-level name always resolves to a direct child of the root. */
    fun isInsideRoot(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\') && !name.contains('\u0000') && !name.startsWith(".")

    fun key(name: String) = name.lowercase()
}

object Text {
    private val CONTROL = Regex("""[\p{Cntrl}  ]""")
    fun clean(s: String?, max: Int): String {
        val t = (s ?: "").replace(CONTROL, " ").replace(Regex("""\s+"""), " ").trim()
        return if (t.length > max) t.substring(0, max).trimEnd() else t
    }
}

/**
 * Validates a model response against the request (section 8 of the spec). Never trusts the model:
 * every ref must exist in the input, every input id ends up exactly once in assignments or leave.
 */
class PlanValidator(
    private val existingFolders: List<String>,
    private val allowExisting: Boolean,
) {
    private val existingByKey = existingFolders.associateBy { FolderNames.key(it) }

    /** Phase 1: validates the proposed taxonomy only. */
    fun validateTaxonomy(raw: RawPlan, maxFolders: Int = 12): List<PlanFolder> {
        val out = LinkedHashMap<String, PlanFolder>()
        for (f in raw.folders) {
            val name = FolderNames.normalize(f.name) ?: continue
            val key = FolderNames.key(name)
            if (key in existingByKey) continue
            if (key !in out) out[key] = PlanFolder(name, Text.clean(f.desc, 60))
            if (out.size >= maxFolders) break
        }
        return out.values.toList()
    }

    fun validate(request: PlanRequest, raw: RawPlan, taxonomy: List<PlanFolder> = emptyList()): ValidatedPlan {
        val inputIds = request.ids()
        val seen = HashSet<String>()

        // Allowed new folders: those declared in this response plus the approved taxonomy.
        val newFolders = LinkedHashMap<String, PlanFolder>()
        taxonomy.forEach { newFolders.putIfAbsent(FolderNames.key(it.name), it) }
        for (f in raw.folders) {
            val name = FolderNames.normalize(f.name) ?: continue
            val key = FolderNames.key(name)
            if (key in existingByKey) continue
            newFolders.putIfAbsent(key, PlanFolder(name, Text.clean(f.desc, 60)))
        }

        val assignments = mutableListOf<PlanAssignment>()
        val leave = mutableListOf<PlanLeave>()

        // 1 + 3: drop unknown refs and duplicates (first occurrence wins, across both lists).
        for (a in raw.assignments) {
            if (a.ref !in inputIds || !seen.add(a.ref)) continue
            val conf = a.confidence
            // 4: low or missing confidence goes to leave.
            if (conf == null || conf.isNaN() || conf < 0.5) {
                leave += PlanLeave(a.ref, Reasons.LOW_CONFIDENCE)
                continue
            }
            // 6 + 7 + 8: folder must be known; existing folders only when allowed; path stays in root.
            val rawKey = FolderNames.key(a.folder.trim())
            val existing = existingByKey[rawKey]
            val target: String = if (existing != null) {
                if (!allowExisting) {
                    leave += PlanLeave(a.ref, Reasons.EXISTING_FORBIDDEN); continue
                }
                existing
            } else {
                val norm = FolderNames.normalize(a.folder)
                if (norm == null) {
                    leave += PlanLeave(a.ref, Reasons.BAD_PATH); continue
                }
                val key = FolderNames.key(norm)
                val ex = existingByKey[key]
                when {
                    ex != null && !allowExisting -> { leave += PlanLeave(a.ref, Reasons.EXISTING_FORBIDDEN); continue }
                    ex != null -> ex
                    key in newFolders -> newFolders.getValue(key).name
                    else -> { leave += PlanLeave(a.ref, Reasons.UNKNOWN_FOLDER); continue }
                }
            }
            if (!FolderNames.isInsideRoot(target)) {
                leave += PlanLeave(a.ref, Reasons.BAD_PATH); continue
            }
            assignments += PlanAssignment(
                ref = a.ref,
                folder = target,
                bundle = a.bundle?.let { Text.clean(it, 40) }?.ifEmpty { null },
                reason = Text.clean(a.reason, 80),
                confidence = conf.coerceIn(0.0, 1.0),
            )
        }
        for (l in raw.leave) {
            if (l.ref !in inputIds || !seen.add(l.ref)) continue
            leave += PlanLeave(l.ref, Text.clean(l.reason, 80).ifEmpty { Reasons.NOT_DETERMINED })
        }
        // 2: everything not mentioned.
        for (id in request.clusters.map { it.id } + request.files.map { it.id }) {
            if (id !in seen) leave += PlanLeave(id, Reasons.NOT_DETERMINED)
        }

        val (finalAssignments, extraLeave) = enforceBundles(assignments)
        leave += extraLeave

        val used = finalAssignments.map { FolderNames.key(it.folder) }.toSet()
        val folders = newFolders.values.filter { FolderNames.key(it.name) in used } +
            existingFolders.filter { FolderNames.key(it) in used }.map { PlanFolder(it, "", existing = true) }
        return ValidatedPlan(folders, finalAssignments, leave)
    }

    companion object {
        /**
         * 5: a bundle split across folders goes to leave entirely; a bundle with a single assigned
         * object loses its bundle name.
         */
        fun enforceBundles(assignments: List<PlanAssignment>): Pair<List<PlanAssignment>, List<PlanLeave>> {
            val byBundle = assignments.filter { it.bundle != null }.groupBy { it.bundle!! }
            val broken = byBundle.filter { (_, items) -> items.map { FolderNames.key(it.folder) }.toSet().size > 1 }.keys
            val leave = assignments.filter { it.bundle in broken }.map { PlanLeave(it.ref, Reasons.BUNDLE_SPLIT) }
            val singleBundles = byBundle.filter { it.value.size == 1 }.keys
            val kept = assignments.filter { it.bundle !in broken }
                .map { if (it.bundle in singleBundles) it.copy(bundle = null) else it }
            return kept to leave
        }
    }
}
