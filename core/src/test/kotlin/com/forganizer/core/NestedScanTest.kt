package com.forganizer.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class NestedScanTest {
    private val none = ScanSettings(emptySet(), emptySet())

    @Test fun rootOnlyByDefault() = runBlocking {
        val src = InMemoryFileSource()
        val root = src.mkRoot("r")
        src.addFile(root, "a.pdf")
        val sub = src.createDir(root, "Books")
        src.addFile(sub, "b.pdf")
        val r = Scanner(src).scan(root, none)
        assertEquals(listOf("a.pdf"), r.files.map { it.name })
    }

    @Test fun nestedFilesCarryFolderAndProjectsStayIntact() = runBlocking {
        val src = InMemoryFileSource()
        val root = src.mkRoot("r")
        val books = src.createDir(root, "Books")
        val deep = src.createDir(books, "Heller")
        src.addFile(deep, "catch22.fb2")
        val proj = src.createDir(root, "app")
        src.addFile(proj, "build.gradle")
        src.addFile(proj, "Main.kt")
        val hidden = src.createDir(root, ".cache")
        src.addFile(hidden, "x.tmp")
        val r = Scanner(src).scan(root, ScanSettings(emptySet(), emptySet(), depth = 3))
        assertEquals(listOf("catch22.fb2"), r.files.map { it.name })
        assertEquals("Books/Heller", r.files[0].rel)
        assertEquals(deep.id, r.files[0].dir)
        assertEquals(listOf("app"), r.projectFolders)
    }

    @Test fun depthLimitAndFileLimit() = runBlocking {
        val src = InMemoryFileSource()
        val root = src.mkRoot("r")
        val l1 = src.createDir(root, "a")
        val l2 = src.createDir(l1, "b")
        src.addFile(l2, "deep.txt")
        assertTrue(Scanner(src).scan(root, ScanSettings(emptySet(), emptySet(), depth = 1)).files.isEmpty())
        assertEquals(1, Scanner(src).scan(root, ScanSettings(emptySet(), emptySet(), depth = 2)).files.size)
        val many = src.createDir(root, "many")
        repeat(5) { src.addFile(many, "f$it.txt") }
        val r = Scanner(src, maxFiles = 3).scan(root, ScanSettings(emptySet(), emptySet(), depth = 3))
        assertTrue(r.truncated || r.files.size <= 6)
    }

    @Test fun applyMovesFromSubfolderAndUndoReturnsIt() = runBlocking {
        val src = InMemoryFileSource()
        val root = src.mkRoot("r")
        val sub = src.createDir(root, "Misc")
        src.addFile(sub, "n.pdf")
        val file = Scanner(src).scan(root, ScanSettings(emptySet(), emptySet(), depth = 1)).files.single()
        val journal = MemoryJournal()
        val applier = Applier(src, journal)
        val rep = applier.apply("s", root, listOf(MoveOp(file, "Docs")), ConflictMode.RENAME, AtomicBoolean(false))
        assertEquals(1, rep.done)
        assertTrue(src.list(sub).nodes.isEmpty())
        val undo = applier.undo("s")
        assertEquals(1, undo.restored)
        assertEquals(listOf("n.pdf"), src.list(sub).nodes.map { it.name })
    }

    @Test fun fileAlreadyInTargetFolderIsSkipped() = runBlocking {
        val src = InMemoryFileSource()
        val root = src.mkRoot("r")
        val docs = src.createDir(root, "Docs")
        src.addFile(docs, "n.pdf")
        val file = Scanner(src).scan(root, ScanSettings(emptySet(), emptySet(), depth = 1)).files.single()
        val rep = Applier(src, MemoryJournal()).apply("s", root, listOf(MoveOp(file, "Docs")), ConflictMode.RENAME, AtomicBoolean(false))
        assertEquals(0, rep.done)
        assertFalse(rep.skipped.isEmpty())
    }
}
