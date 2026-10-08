package com.forganizer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PlanImportTest {
    private fun f(id: String, name: String, rel: String = "") = FileNode(id, name, 10, 1, null, false, rel = rel)
    private fun base(): OrganizePlan {
        val a = f("/r/a.pdf", "a.pdf"); val b = f("/r/b.jpg", "b.jpg"); val c = f("/r/c.txt", "c.txt")
        return OrganizePlan(
            folders = listOf(PlanFolder("Docs", "d")),
            items = listOf(PlanItem(a, "f1", "Docs", null, "r", 0.9, ItemSource.AI, true), PlanItem(b, "f2", "Docs", null, "r", 0.9, ItemSource.AI, true)),
            leave = listOf(LeaveItem(c, "f3", "x")),
            existingFolders = listOf("Old"),
        )
    }

    @Test fun roundTripOfOwnExport() {
        val b = base()
        val r = PlanImport.parse(PlanExport.toJson("root", b), b, allowExisting = false)
        assertEquals(2, r.placed)
        assertEquals(setOf("a.pdf", "b.jpg"), r.plan.itemsIn("Docs").map { it.file.name }.toSet())
        assertEquals(listOf("c.txt"), r.plan.leave.map { it.file.name })
    }

    @Test fun editedSchemeWithFencesAndNameFallback() {
        val text = """
            Конечно! Вот схема:
            ```json
            {"plan":[
              {"file_id":"/r/a.pdf","file":"a.pdf","target_folder":"Книги","confidence":0.95},
              {"file":"b.jpg","target_folder":"Фото 2024","selected":false},
              {"file_id":"zzz","file":"ghost.bin","target_folder":"Книги"},
              {"file_id":"/r/c.txt","file":"c.txt","target_folder":"..."}
            ]}
            ```
        """.trimIndent()
        val r = PlanImport.parse(text, base(), allowExisting = false)
        assertEquals(setOf("Книги", "Фото 2024"), r.plan.folders.map { it.name }.toSet())
        assertEquals(false, r.plan.items.first { it.file.name == "b.jpg" }.checked)
        assertEquals(listOf("ghost.bin"), r.unknown)
        assertTrue(r.plan.items.none { it.file.name == "c.txt" }) // "..." is not a usable name
        assertEquals(listOf("c.txt"), r.rejected)
        assertEquals(0, r.notMentioned)
    }

    @Test fun existingFolderRespectsSetting() {
        val text = """{"plan":[{"file_id":"/r/a.pdf","file":"a.pdf","target_folder":"old"}]}"""
        val off = PlanImport.parse(text, base(), allowExisting = false)
        assertTrue(off.plan.items.isEmpty()); assertEquals(1, off.rejected.size)
        val on = PlanImport.parse(text, base(), allowExisting = true)
        assertEquals("Old", on.plan.items.single().folder)
        assertEquals(2, on.notMentioned)
    }

    @Test fun badInputIsRejected() {
        for (t in listOf("hello", "{}", "{\"plan\": 5}")) {
            try { PlanImport.parse(t, base(), false); fail(t) } catch (e: ImportException) { }
        }
    }

    @Test fun handoffContainsJson() {
        assertTrue(PlanImport.handoff("{\"x\":1}").endsWith("{\"x\":1}"))
    }
}

class CompactExportTest {
    @Test fun compactIsSmallerAndStillImportable() {
        val f = FileNode("/r/" + "x".repeat(80), "a.pdf", 10, 1, null, false)
        val plan = OrganizePlan(listOf(PlanFolder("Docs", "")), listOf(PlanItem(f, "f1", "Docs", null, "r".repeat(200), 0.9, ItemSource.AI, true)), emptyList(), emptyList())
        val pretty = PlanExport.toJson("root", plan)
        val compact = PlanExport.toCompactJson("root", plan)
        assertTrue(compact.length < pretty.length)
        assertTrue(!compact.contains("\n"))
        assertEquals(1, PlanImport.parse(compact, plan, false).placed)
    }
}
