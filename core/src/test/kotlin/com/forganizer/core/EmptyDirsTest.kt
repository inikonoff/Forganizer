package com.forganizer.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class EmptyDirsTest {
    private fun setup(): Triple<InMemoryFileSource, NodeRef, Applier> {
        val src = InMemoryFileSource()
        val root = src.mkRoot("r")
        return Triple(src, root, Applier(src, MemoryJournal()))
    }

    @Test fun undoLeavesEmptyCreatedFoldersAndTheyCanBeRemoved() = runBlocking {
        val (src, root, applier) = setup()
        val a = src.addFile(root, "a.pdf"); val b = src.addFile(root, "b.jpg")
        applier.apply("s", root, listOf(MoveOp(a, "Docs"), MoveOp(b, "Photos")), ConflictMode.RENAME, AtomicBoolean(false))
        assertTrue(applier.emptyCreatedDirs("s").isEmpty()) // files are inside after applying
        applier.undo("s")
        assertEquals(setOf("Docs", "Photos"), applier.emptyCreatedDirs("s").toSet())
        val rep = applier.removeEmptyDirs("s")
        assertEquals(setOf("Docs", "Photos"), rep.removed.toSet())
        assertTrue(src.list(root).nodes.none { it.isDir })
        assertEquals(setOf("a.pdf", "b.jpg"), src.list(root).nodes.map { it.name }.toSet())
        assertTrue(applier.emptyCreatedDirs("s").isEmpty()) // journal marked them as handled
    }

    @Test fun folderThatGotAFileIsKept() = runBlocking {
        val (src, root, applier) = setup()
        val a = src.addFile(root, "a.pdf")
        applier.apply("s", root, listOf(MoveOp(a, "Docs")), ConflictMode.RENAME, AtomicBoolean(false))
        applier.undo("s")
        val docs = src.list(root).nodes.first { it.isDir }
        src.addFile(docs.ref, "mine.txt") // the user put something there in the meantime
        val rep = applier.removeEmptyDirs("s")
        assertTrue(rep.removed.isEmpty()); assertEquals(listOf("Docs"), rep.kept)
        assertEquals(1, src.list(docs.ref).nodes.size)
    }

    @Test fun foldersNotCreatedByTheAppAreNeverTouched() = runBlocking {
        val (src, root, applier) = setup()
        src.createDir(root, "Old") // pre-existing, empty, not journaled
        val a = src.addFile(root, "a.pdf")
        applier.apply("s", root, listOf(MoveOp(a, "Docs")), ConflictMode.RENAME, AtomicBoolean(false))
        applier.undo("s")
        applier.removeEmptyDirs("s")
        assertEquals(listOf("Old"), src.list(root).nodes.filter { it.isDir }.map { it.name })
    }
}

class EmptiedSourceDirsTest {
    private val scan = ScanSettings(emptySet(), emptySet(), depth = 3)

    @Test fun emptiedNestedFoldersAreOfferedRemovedAndRecreatedByUndo() = runBlocking {
        val src = InMemoryFileSource()
        val root = src.mkRoot("r")
        val books = src.createDir(root, "Books")
        val heller = src.createDir(books, "Heller")
        src.addFile(heller, "catch22.fb2")
        val keep = src.createDir(root, "Keep")
        src.addFile(keep, "stay.txt"); src.addFile(keep, "go.pdf")
        val applier = Applier(src, MemoryJournal())
        val files = Scanner(src).scan(root, scan).files
        val ops = files.filter { it.name != "stay.txt" }.map { MoveOp(it, "Docs") }
        val report = applier.apply("s", root, ops, ConflictMode.RENAME, AtomicBoolean(false))
        assertEquals(2, report.done)

        val emptied = applier.emptiedSourceDirs(report.sourceDirs)
        assertEquals(setOf("Books/Heller"), emptied.map { it.rel }.toSet()) // Keep still holds stay.txt
        val rep = applier.removeEmptiedSourceDirs("s", root, emptied)
        assertEquals(listOf("Books/Heller"), rep.removed)
        assertTrue(src.list(books).nodes.isEmpty())

        val undo = applier.undo("s")
        assertEquals(2, undo.restored)
        val heller2 = src.list(books).nodes.single { it.isDir }
        assertEquals("Heller", heller2.name)
        assertEquals(listOf("catch22.fb2"), src.list(heller2.ref).nodes.map { it.name })
        assertEquals(setOf("stay.txt", "go.pdf"), src.list(keep).nodes.map { it.name }.toSet())
    }

    @Test fun parentFolderThatOnlyHoldsEmptiedFoldersIsOfferedToo() = runBlocking {
        val src = InMemoryFileSource()
        val root = src.mkRoot("r")
        val a = src.createDir(root, "A")
        val b = src.createDir(a, "B")
        src.addFile(a, "top.txt"); src.addFile(b, "deep.txt")
        val applier = Applier(src, MemoryJournal())
        val files = Scanner(src).scan(root, scan).files
        val report = applier.apply("s", root, files.map { MoveOp(it, "All") }, ConflictMode.RENAME, AtomicBoolean(false))
        val emptied = applier.emptiedSourceDirs(report.sourceDirs)
        assertEquals(listOf("A/B", "A"), emptied.map { it.rel })
        val rep = applier.removeEmptiedSourceDirs("s", root, emptied)
        assertEquals(listOf("A/B", "A"), rep.removed)
        assertEquals(1, applier.undo("s").let { u -> if (u.restored == 2) 1 else 0 })
        assertEquals(listOf("A"), src.list(root).nodes.filter { it.isDir && it.name == "A" }.map { it.name })
    }

    @Test fun folderThatGotSomethingElseIsKept() = runBlocking {
        val src = InMemoryFileSource()
        val root = src.mkRoot("r")
        val a = src.createDir(root, "A")
        src.addFile(a, "x.txt")
        val applier = Applier(src, MemoryJournal())
        val report = applier.apply("s", root, Scanner(src).scan(root, scan).files.map { MoveOp(it, "All") }, ConflictMode.RENAME, AtomicBoolean(false))
        val emptied = applier.emptiedSourceDirs(report.sourceDirs)
        src.addFile(a.let { NodeRef(it.id) }, "late.txt") // appears before the user confirms
        val rep = applier.removeEmptiedSourceDirs("s", root, emptied)
        assertTrue(rep.removed.isEmpty()); assertEquals(listOf("A"), rep.kept)
    }
}
