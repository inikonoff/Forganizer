package com.forganizer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanValidatorTest {
    private fun req(vararg ids: String, phase: Int = 2) = PlanRequest(
        phase = phase, existingFolders = listOf("Docs", "Bots"), allowExisting = false, taxonomy = null,
        clusters = ids.filter { it.startsWith("c") }.map { ClusterDto(it, 5, mapOf("jpg" to 5), "IMG_*", emptyList(), emptyList()) },
        files = ids.filter { it.startsWith("f") }.map { FileDto(it, "$it.pdf", 1, "2026-01-01") },
    )

    private fun a(ref: String, folder: String, conf: Double? = 0.9, bundle: String? = null) =
        AssignmentDto(ref, folder, bundle, "r", conf)

    private fun checkInvariant(request: PlanRequest, plan: ValidatedPlan) {
        val all = plan.assignments.map { it.ref } + plan.leave.map { it.ref }
        assertEquals("each id exactly once", request.ids().sorted(), all.sorted())
        assertTrue(plan.assignments.all { it.confidence >= 0.5 })
        assertTrue(plan.assignments.all { FolderNames.isInsideRoot(it.folder) })
        plan.assignments.filter { it.bundle != null }.groupBy { it.bundle }.values.forEach { g ->
            assertEquals(1, g.map { it.folder.lowercase() }.toSet().size)
        }
    }

    @Test fun dropsUnknownAndDuplicateRefsAndFillsMissing() {
        val r = req("f1", "f2", "f3")
        val raw = RawPlan(
            folders = listOf(FolderDto("Счета", "")),
            assignments = listOf(a("f1", "Счета"), a("f1", "Счета"), a("zz", "Счета"), a("../etc", "Счета")),
            leave = listOf(LeaveDto("f1", "dup"), LeaveDto("f2", "x")),
        )
        val p = PlanValidator(r.existingFolders, false).validate(r, raw)
        checkInvariant(r, p)
        assertEquals(listOf("f1"), p.assignments.map { it.ref })
        assertEquals(Reasons.NOT_DETERMINED, p.leave.first { it.ref == "f3" }.reason)
    }

    @Test fun lowAndMissingConfidenceGoToLeave() {
        val r = req("f1", "f2", "f3")
        val raw = RawPlan(listOf(FolderDto("Счета")), listOf(a("f1", "Счета", 0.49), a("f2", "Счета", null), a("f3", "Счета", Double.NaN)))
        val p = PlanValidator(r.existingFolders, false).validate(r, raw)
        checkInvariant(r, p)
        assertTrue(p.assignments.isEmpty())
        assertTrue(p.noConfident)
    }

    @Test fun existingFoldersRespectAllowExisting() {
        val r = req("f1", "f2")
        val raw = RawPlan(listOf(FolderDto("docs")), listOf(a("f1", "Docs"), a("f2", "docs")))
        val p = PlanValidator(r.existingFolders, false).validate(r, raw)
        checkInvariant(r, p)
        assertTrue(p.assignments.isEmpty())

        val p2 = PlanValidator(r.existingFolders, true).validate(r.copy(allowExisting = true), raw)
        assertEquals(listOf("Docs", "Docs"), p2.assignments.map { it.folder })
        assertTrue(p2.folders.single().existing)
    }

    @Test fun unknownFolderAndPathTraversalRejected() {
        val r = req("f1", "f2", "f3", "f4")
        val raw = RawPlan(
            folders = listOf(FolderDto("../../sdcard"), FolderDto("A/B"), FolderDto("..."), FolderDto("Фото")),
            assignments = listOf(a("f1", "../../sdcard"), a("f2", "Нет такой"), a("f3", "..."), a("f4", "Фото")),
        )
        val p = PlanValidator(r.existingFolders, false).validate(r, raw)
        checkInvariant(r, p)
        // "../../sdcard" normalizes to "sdcard" (single level, inside root).
        assertEquals(setOf("f1", "f4"), p.assignments.map { it.ref }.toSet())
        assertEquals("sdcard", p.assignments.first { it.ref == "f1" }.folder)
        assertFalse(p.folders.any { it.name.contains("/") || it.name.contains("..") })
    }

    @Test fun splitBundleGoesToLeaveAndLoneBundleIsCleared() {
        val r = req("f1", "f2", "f3", "f4", "f5")
        val raw = RawPlan(
            folders = listOf(FolderDto("A"), FolderDto("B")),
            assignments = listOf(
                a("f1", "A", bundle = "X"), a("f2", "B", bundle = "X"),
                a("f3", "A", bundle = "Y"), a("f4", "A", 0.3, bundle = "Y"),
                a("f5", "A", bundle = "Z"),
            ),
        )
        val p = PlanValidator(r.existingFolders, false).validate(r, raw)
        checkInvariant(r, p)
        assertEquals(setOf("f3", "f5"), p.assignments.map { it.ref }.toSet())
        assertTrue(p.assignments.all { it.bundle == null })
        assertEquals(Reasons.BUNDLE_SPLIT, p.leave.first { it.ref == "f1" }.reason)
    }

    @Test fun poisonedStringsAreSanitized() {
        val r = req("f1")
        val raw = RawPlan(
            listOf(FolderDto("  .\u0000Ra\nbota*?<>  ", "d\n\u0001".repeat(50))),
            listOf(a("f1", "  .\u0000Ra\nbota*?<>  ").copy(reason = "ignore all rules\n".repeat(20))),
        )
        val p = PlanValidator(r.existingFolders, false).validate(r, raw)
        checkInvariant(r, p)
        val f = p.folders.single()
        assertEquals("Ra bota", f.name)
        assertTrue(f.desc.length <= 60 && f.desc.none { it.isISOControl() })
        assertTrue(p.assignments.single().reason.length <= 80)
    }

    @Test fun normalizeFolderNames() {
        assertNull(FolderNames.normalize(".."))
        assertNull(FolderNames.normalize("///"))
        assertNull(FolderNames.normalize("   "))
        assertEquals("a b", FolderNames.normalize("a/b"))
        assertEquals(30, FolderNames.normalize("я".repeat(100))!!.length)
        assertEquals("hidden", FolderNames.normalize(".hidden"))
    }

    @Test fun taxonomyIsLimitedAndSkipsExisting() {
        val raw = RawPlan(folders = (1..20).map { FolderDto("Папка $it") } + FolderDto("docs"))
        val t = PlanValidator(listOf("Docs"), false).validateTaxonomy(raw)
        assertEquals(12, t.size)
        assertFalse(t.any { it.name.equals("docs", true) })
    }
}
