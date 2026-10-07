package com.forganizer.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class AiPlannerTest {
    private class FakeApi(val respond: (PlanRequest) -> RawPlan) : PlanApi {
        val requests = mutableListOf<PlanRequest>()
        override suspend fun plan(request: PlanRequest): RawPlan { requests += request; return respond(request) }
    }

    private fun files(n: Int) = (1..n).map { file("id$it", "doc_${"x".repeat(it % 7)}${('a' + it % 26)}$it.pdf", modified = it * 3_600_000L) }

    @Test fun singlePassForSmallFolder() = runBlocking {
        val summary = Clusterer(Rules.DEFAULT, ZoneOffset.UTC).summarize(files(30))
        val api = FakeApi { r -> RawPlan(listOf(FolderDto("Документы")), r.files.map { AssignmentDto(it.id, "Документы", null, "pdf", 0.8) } + r.clusters.map { AssignmentDto(it.id, "Документы", null, "pdf", 0.8) }) }
        val res = AiPlanner(api).plan(summary, emptyList(), false)
        assertEquals(1, api.requests.size)
        assertEquals(0, api.requests.single().phase)
        assertEquals(summary.objects.size, res.plan.assignments.size)
    }

    @Test fun twoPhasesWithBatchesForLargeFolder() = runBlocking {
        val names = (1..520).map { i -> file("id$i", "file${i}_${listOf("alpha", "beta", "gamma")[i % 3]}word$i.dat", modified = i * 3_600_000L) }
        val summary = Clusterer(Rules.DEFAULT, ZoneOffset.UTC).summarize(names)
        val api = FakeApi { r ->
            when (r.phase) {
                1 -> RawPlan(folders = listOf(FolderDto("Данные")))
                else -> RawPlan(emptyList(), r.files.map { AssignmentDto(it.id, "Данные", null, "", 0.9) } + r.clusters.map { AssignmentDto(it.id, "Данные", null, "", 0.9) })
            }
        }
        val res = AiPlanner(api).plan(summary, emptyList(), false)
        assertEquals(1, api.requests.first().phase)
        assertTrue(api.requests.drop(1).all { it.phase == 2 && it.taxonomy == listOf("Данные") })
        assertTrue(api.requests.drop(1).all { it.clusters.size + it.files.size <= 100 })
        val all = res.plan.assignments.map { it.ref } + res.plan.leave.map { it.ref }
        assertEquals(summary.objects.map { it.id }.sorted(), all.sorted())
    }

    @Test fun bundleCandidatesStayInOneBatch() {
        val fs = (1..250).map { file("id$it", "u${it}q.bin", modified = it * 3_600_000L) } +
            listOf(file("p1", "pricing.html"), file("p2", "pricing_logo.png"), file("p3", "pricing_bg.png"))
        val summary = Clusterer(Rules.DEFAULT, ZoneOffset.UTC).summarize(fs)
        val group = summary.bundleCandidates.single { g -> g.any { summary.byId[it]!!.members.single().name == "pricing.html" } }
        val batches = AiPlanner(FakeApi { RawPlan() }).batches(summary)
        assertEquals(1, batches.count { b -> b.any { it.id in group } })
        assertEquals(summary.objects.size, batches.sumOf { it.size })
    }

    @Test fun oneFailedBatchKeepsTheRestAndAllFailedThrows() = runBlocking {
        val names = (1..300).map { i -> file("id$i", "file${i}_${listOf("alpha", "beta", "gamma")[i % 3]}word$i.dat", modified = i * 3_600_000L) }
        val summary = Clusterer(Rules.DEFAULT, ZoneOffset.UTC).summarize(names)
        var phase2Calls = 0
        val api = FakeApi { r ->
            when (r.phase) {
                1 -> RawPlan(folders = listOf(FolderDto("Данные")))
                else -> {
                    phase2Calls++
                    if (phase2Calls == 2) throw AiUnavailableException("down")
                    RawPlan(emptyList(), r.files.map { AssignmentDto(it.id, "Данные", null, "", 0.9) } + r.clusters.map { AssignmentDto(it.id, "Данные", null, "", 0.9) })
                }
            }
        }
        val res = AiPlanner(api).plan(summary, emptyList(), false)
        assertEquals(1, res.failedBatches)
        assertTrue(res.totalBatches > 1)
        val all = res.plan.assignments.map { it.ref } + res.plan.leave.map { it.ref }
        assertEquals(summary.objects.map { it.id }.sorted(), all.sorted())
        assertTrue(res.plan.leave.any { it.reason == Reasons.AI_NO_ANSWER })

        val dead = FakeApi { r -> if (r.phase == 1) RawPlan(folders = listOf(FolderDto("Данные"))) else throw AiUnavailableException("down") }
        try {
            AiPlanner(dead).plan(summary, emptyList(), false); error("must throw")
        } catch (e: AiUnavailableException) { }
    }
}
