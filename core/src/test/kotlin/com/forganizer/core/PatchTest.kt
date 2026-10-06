package com.forganizer.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchTest {
    private fun item(id: String, name: String, folder: String, bundle: String? = null, ref: String = id) =
        PlanItem(file(id, name), ref, folder, bundle, "r", 0.9, ItemSource.AI, checked = true)

    private fun plan() = OrganizePlan(
        folders = listOf(PlanFolder("Документы", ""), PlanFolder("Фото", ""), PlanFolder("Проекты", "")),
        items = listOf(
            item("1", "report.pdf", "Документы"),
            item("2", "Screenshot_1.png", "Фото", ref = "c1"),
            item("3", "Screenshot_2.png", "Фото", ref = "c1"),
            item("4", "IMG_1.jpg", "Фото"),
            item("5", "bot.py", "Проекты", bundle = "Бот"),
            item("6", "bot_config.json", "Проекты", bundle = "Бот"),
        ),
        leave = listOf(LeaveItem(file("7", "scan.pdf"), "f7", "?"), LeaveItem(file("8", "data.zip"), "f8", "?")),
        existingFolders = listOf("Docs"),
    )

    private fun ops(json: String): List<PatchOp> =
        PatchOp.parseAll(RawPatch(Json.decodeFromString<List<JsonObject>>(json)))

    private fun folderOf(p: OrganizePlan, id: String) = p.items.firstOrNull { it.file.id == id }?.folder

    private fun invariant(p: OrganizePlan) {
        val ids = p.items.map { it.file.id } + p.leave.map { it.file.id }
        assertEquals("each file once", ids.size, ids.toSet().size)
        assertEquals(8, ids.size)
        assertTrue(p.items.all { i -> p.folders.any { FolderNames.key(it.name) == FolderNames.key(i.folder) } })
        assertTrue(p.folders.all { FolderNames.isInsideRoot(it.name) })
    }

    @Test fun moveByExtAndCreateFolder() {
        val r = PatchEngine(listOf("Docs"), false).apply(
            plan(),
            ops("""[{"op":"move","select":{"ext":["pdf"]},"to":"Документы"},
                   {"op":"create_folder","name":"Скриншоты","desc":"Снимки"},
                   {"op":"move","select":{"name_starts_with":"screenshot_"},"to":"Скриншоты"}]"""),
            Pins(),
        )
        invariant(r.plan)
        assertEquals("Документы", folderOf(r.plan, "7"))
        assertEquals("Скриншоты", folderOf(r.plan, "2"))
        assertEquals(listOf("Скриншоты"), r.createdFolders)
        assertEquals(3, r.movedFiles)
        assertTrue(r.plan.items.first { it.file.id == "7" }.checked)
    }

    @Test fun pinnedFilesAndFoldersAreNotChanged() {
        val pins = Pins().pinFiles(mapOf("1" to "Документы")).pinFolder("Фото")
        val r = PatchEngine(emptyList(), false).apply(
            plan(),
            ops("""[{"op":"to_leave","select":{"ext":["pdf"]}},
                   {"op":"rename_folder","from":"Фото","to":"Картинки"},
                   {"op":"merge_folders","from":["Фото"],"into":"Документы"}]"""),
            pins,
        )
        invariant(r.plan)
        assertEquals("Документы", folderOf(r.plan, "1"))
        assertEquals("Фото", folderOf(r.plan, "4"))
        assertTrue(r.skipped.size >= 3)
        assertFalse(r.hasChanges)
    }

    @Test fun deleteAndUnknownOpsNeverExecute() {
        val before = plan()
        val r = PatchEngine(emptyList(), false).apply(
            before,
            ops("""[{"op":"delete","select":{"ext":["pdf"]}},{"op":"delete_duplicates"},{"op":"move","select":{},"to":"X"},{"foo":1}]"""),
            Pins(),
        )
        assertEquals(before, r.plan)
        assertEquals(4, r.skipped.size)
        assertTrue(r.applied.isEmpty())
    }

    @Test fun existingFoldersAndBadNames() {
        val r = PatchEngine(listOf("Docs"), false).apply(
            plan(),
            ops("""[{"op":"move","select":{"refs":["c1"]},"to":"docs"},
                   {"op":"move","select":{"refs":["f8"]},"to":"../../etc"},
                   {"op":"rename_folder","from":"Проекты","to":"Docs"}]"""),
            Pins(),
        )
        invariant(r.plan)
        assertEquals("Фото", folderOf(r.plan, "2"))
        assertEquals("etc", folderOf(r.plan, "8"))
        assertEquals("Проекты", folderOf(r.plan, "5"))

        val allowed = PatchEngine(listOf("Docs"), true).apply(plan(), ops("""[{"op":"move","select":{"group":"c1"},"to":"docs"}]"""), Pins())
        assertEquals("Docs", folderOf(allowed.plan, "2"))
        assertTrue(allowed.plan.folders.first { it.name == "Docs" }.existing)
    }

    @Test fun bigChangeWarningAndBundleSplit() {
        val r = PatchEngine(emptyList(), false).apply(
            plan(),
            ops("""[{"op":"merge_folders","from":["Фото","Проекты"],"into":"Всё"},
                   {"op":"move","select":{"refs":["5"]},"to":"Документы"}]"""),
            Pins(),
        )
        invariant(r.plan)
        assertEquals(1, r.bigOps.size)
        // the bundle got split by the second op, so it is no longer a bundle
        assertTrue(r.plan.items.all { it.bundle == null })
        assertTrue(r.touchedFolders.containsAll(listOf("Фото", "Проекты", "Всё", "Документы")))
    }

    @Test fun renameAndUnbundle() {
        val r = PatchEngine(emptyList(), false).apply(
            plan(),
            ops("""[{"op":"rename_folder","from":"проекты","to":"Код"},{"op":"unbundle","bundle":"бот"}]"""),
            Pins(),
        )
        invariant(r.plan)
        assertEquals("Код", folderOf(r.plan, "5"))
        assertEquals(mapOf("Проекты" to "Код"), r.renamedFolders)
        assertNull(r.plan.items.first { it.file.id == "5" }.bundle)
        assertEquals(0, r.movedFiles)
        assertTrue(r.hasChanges)
    }

    private class FakeRefine(val patch: String) : RefineApi {
        var last: RefineRequest? = null
        override suspend fun refine(request: RefineRequest): RawPatch {
            last = request
            return RawPatch(Json.decodeFromString<List<JsonObject>>(patch), "")
        }
    }

    @Test fun sessionAcceptRejectPinsVersionsAndLimit() = runBlocking {
        val s = PlanSession(plan(), allowExisting = false, maxRefines = 2)
        s.setChecked(setOf("4"), false)
        assertTrue(s.renameFolder("Документы", "Бумаги"))
        val api = FakeRefine("""[{"op":"move","select":{"ext":["pdf","jpg"]},"to":"Архив"}]""")

        val (preview, _) = s.refine(api, "pdf и jpg в Архив")
        val req = api.last!!
        assertTrue(req.pinned.any { it.kind == "folder_name" && it.name == "Бумаги" })
        assertTrue(req.pinned.any { it.kind == "file_excluded" && it.ref == "4" })
        // reject: the plan is unchanged
        assertEquals("Бумаги", folderOf(s.plan, "1"))
        assertEquals("Архив", folderOf(preview.plan, "1"))
        assertEquals("Фото", folderOf(preview.plan, "4")) // pinned by unchecking

        s.accept(preview, "pdf и jpg в Архив")
        assertEquals("Архив", folderOf(s.plan, "1"))
        assertEquals(2, s.versions.size)
        assertEquals(listOf("pdf и jpg в Архив"), s.refineRequest("x").history)

        // accepted decisions are pinned too
        val again = s.preview(ops("""[{"op":"to_leave","select":{"ext":["pdf"]}}]"""))
        assertFalse(again.hasChanges)

        assertTrue(s.rollback(1))
        assertEquals("Документы", folderOf(s.plan, "1"))
        assertEquals(3, s.versions.size)

        s.refine(api, "ещё")
        try {
            s.refine(api, "и ещё"); error("limit not enforced")
        } catch (e: RefineLimitException) { }

        val restored = PlanSession.restore(PlanSnapshot.decode(s.snapshot("root", "Загрузки").encode()))
        assertEquals(s.plan, restored.plan)
        assertEquals(s.pins, restored.pins)
        assertEquals(3, restored.versions.size)
    }

    @Test fun stalenessMarksMissingAndChanged() = runBlocking {
        val src = InMemoryFileSource()
        val root = src.mkRoot("r")
        val a = src.addFile(root, "a.pdf")
        val b = src.addFile(root, "b.pdf")
        val p = OrganizePlan(
            listOf(PlanFolder("Д", "")),
            listOf(
                PlanItem(a, "f1", "Д", null, "", 0.9, ItemSource.AI, true),
                PlanItem(b, "f2", "Д", null, "", 0.9, ItemSource.AI, true),
                PlanItem(file("gone", "c.pdf"), "f3", "Д", null, "", 0.9, ItemSource.AI, true),
            ),
            emptyList(), emptyList(),
        )
        src.modify(b.id)
        val checked = Staleness.check(p, src)
        assertNull(checked.items[0].stale)
        assertEquals(listOf("f1"), checked.checkedItems.map { it.ref })
        assertEquals(2, checked.staleCount)
        assertEquals(checked, checked.setChecked(setOf("gone"), true))
    }
}
