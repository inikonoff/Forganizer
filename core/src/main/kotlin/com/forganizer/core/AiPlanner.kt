package com.forganizer.core

data class PlannerResult(
    val taxonomy: List<PlanFolder>,
    val plan: ValidatedPlan,
    /** Phase-2 requests the AI did not answer; their objects are in "leave". */
    val failedBatches: Int = 0,
    val totalBatches: Int = 0,
)

/**
 * Drives the request flow: one pass (phase 0) for small folders, or phase 1 (taxonomy) followed by
 * phase 2 batches. Bundle candidates are kept within one batch.
 */
class AiPlanner(
    private val api: PlanApi,
    private val singlePassLimit: Int = 60,
    private val batchSize: Int = 60,
    private val taxonomySample: Int = 100,
) {
    suspend fun plan(
        summary: Summary,
        existingFolders: List<String>,
        allowExisting: Boolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): PlannerResult {
        val validator = PlanValidator(existingFolders, allowExisting)
        val objects = summary.objects
        if (objects.isEmpty()) return PlannerResult(emptyList(), ValidatedPlan(emptyList(), emptyList(), emptyList()))

        if (objects.size <= singlePassLimit) {
            onProgress(0, 1)
            val req = request(0, objects, existingFolders, allowExisting, null)
            val plan = validator.validate(req, api.plan(req))
            onProgress(1, 1)
            return PlannerResult(plan.folders.filter { !it.existing }, plan)
        }

        val batches = batches(summary)
        val total = batches.size + 1
        onProgress(0, total)
        val phase1Objects = summary.clusters + summary.singles.take(taxonomySample)
        val req1 = request(1, phase1Objects, existingFolders, allowExisting, null)
        val taxonomy = validator.validateTaxonomy(api.plan(req1))
        onProgress(1, total)

        val folders = LinkedHashMap<String, PlanFolder>()
        val assignments = mutableListOf<PlanAssignment>()
        val leave = mutableListOf<PlanLeave>()
        var current = taxonomy
        var failed = 0
        var lastError: AiUnavailableException? = null
        batches.forEachIndexed { i, batch ->
            try {
                val req = request(2, batch, existingFolders, allowExisting, current.map { it.name })
                val p = validator.validate(req, api.plan(req), current)
                p.folders.forEach { folders.putIfAbsent(FolderNames.key(it.name), it) }
                // New folders proposed in a batch extend the taxonomy for the next batches.
                current = (current + p.folders.filter { !it.existing }).distinctBy { FolderNames.key(it.name) }
                assignments += p.assignments
                leave += p.leave
            } catch (e: AiUnavailableException) {
                // One flaky request must not throw away the whole analysis.
                failed++
                lastError = e
                batch.forEach { leave += PlanLeave(it.id, Reasons.AI_NO_ANSWER) }
            }
            onProgress(i + 2, total)
        }
        if (failed == batches.size) throw lastError ?: AiUnavailableException("Помощник временно недоступен")
        // A bundle that ended up split across batches goes to leave as a whole.
        val (kept, extra) = PlanValidator.enforceBundles(assignments)
        val used = kept.map { FolderNames.key(it.folder) }.toSet()
        return PlannerResult(
            current,
            ValidatedPlan(folders.values.filter { FolderNames.key(it.name) in used }, kept, leave + extra),
            failedBatches = failed,
            totalBatches = batches.size,
        )
    }

    /** Splits all objects into batches of about [batchSize]; bundle candidates are never split. */
    fun batches(summary: Summary): List<List<SummaryObject>> {
        val units = mutableListOf<List<SummaryObject>>()
        val inGroup = HashSet<String>()
        for (group in summary.bundleCandidates) {
            units += group.mapNotNull { summary.byId[it] }
            inGroup += group
        }
        summary.objects.filter { it.id !in inGroup }.forEach { units += listOf(it) }
        // Keep original order roughly: sort units by first object position.
        val pos = summary.objects.withIndex().associate { it.value.id to it.index }
        units.sortBy { u -> u.minOf { pos.getValue(it.id) } }

        val out = mutableListOf<List<SummaryObject>>()
        var cur = mutableListOf<SummaryObject>()
        for (u in units) {
            if (cur.isNotEmpty() && cur.size + u.size > batchSize) {
                out += cur; cur = mutableListOf()
            }
            cur.addAll(u)
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private fun request(
        phase: Int,
        objects: List<SummaryObject>,
        existing: List<String>,
        allowExisting: Boolean,
        taxonomy: List<String>?,
    ) = PlanRequest(
        phase = phase,
        existingFolders = existing,
        allowExisting = allowExisting,
        taxonomy = taxonomy,
        clusters = objects.filterIsInstance<SummaryObject.Cluster>().map { it.dto },
        files = objects.filterIsInstance<SummaryObject.Single>().map { it.dto },
    )
}
