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
