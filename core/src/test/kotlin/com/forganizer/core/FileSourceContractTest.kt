package com.forganizer.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** One contract, run against every JVM-testable FileSource implementation. */
abstract class FileSourceContract {
    abstract fun setup(): Triple<FileSource, NodeRef, (String, ByteArray) -> Unit>

    @Test fun listCreateMoveNeverOverwrite() = runBlocking {
        val (src, root, add) = setup()
        add("a.txt", "hello".toByteArray())
        add("b.txt", "world".toByteArray())
        val listed = src.list(root).nodes
        assertEquals(setOf("a.txt", "b.txt"), listed.map { it.name }.toSet())
        val dir = src.createDir(root, "Папка")
        val a = listed.first { it.name == "a.txt" }
        val moved = src.move(a, root, dir, "a.txt")
        assertTrue(moved is MoveResult.Moved)
        assertTrue(src.list(root).nodes.none { it.name == "a.txt" })
        val b = listed.first { it.name == "b.txt" }
        assertTrue("never overwrite", src.move(b, root, dir, "a.txt") is MoveResult.Failed)
        assertNotNull(src.stat(b.ref))
        assertEquals(listOf("a.txt"), src.list(dir).nodes.map { it.name })
    }

    @Test fun applyThenUndoRestoresEverything() = runBlocking {
        val (src, root, add) = setup()
        add("x.pdf", ByteArray(3)); add("y.pdf", ByteArray(4)); add("z.jpg", ByteArray(5))
        val files = src.list(root).nodes
        val existing = src.createDir(root, "Docs")
        // a name conflict in the target folder
        val conflictSource = src.list(root).nodes.first { it.name == "y.pdf" }
        val journal = MemoryJournal()
        val applier = Applier(src, journal)
        src.createDir(existing, "tmp") // keep Docs non-trivial
        val ops = files.map { MoveOp(it, if (it.name.endsWith("pdf")) "Docs" else "Фото") }
        val report = applier.apply("s1", root, ops, ConflictMode.RENAME, java.util.concurrent.atomic.AtomicBoolean(false))
        assertEquals(3, report.done)
        assertEquals(listOf("Фото"), report.createdDirs)
        assertEquals(setOf("Docs", "Фото"), src.list(root).nodes.map { it.name }.toSet())

        // Occupy an original name before undo to force a suffix on the way back.
        add("x.pdf", ByteArray(1))
        val undo = applier.undo("s1")
        assertEquals(3, undo.restored)
        assertEquals(listOf("Фото"), undo.createdDirs)
        val names = src.list(root).nodes.filter { !it.isDir }.map { it.name }.toSet()
        assertEquals(setOf("x.pdf", "x (1).pdf", "y.pdf", "z.jpg"), names)
        assertEquals("y.pdf", conflictSource.name)
    }

    @Test fun skipsChangedFilesAndHonoursSkipMode() = runBlocking {
        val (src, root, add) = setup()
        add("a.txt", ByteArray(2))
        val docs = src.createDir(root, "Docs")
        val a = src.list(root).nodes.first { it.name == "a.txt" }
        val journal = MemoryJournal()
        val stale = a.copy(size = a.size + 1)
        val r1 = Applier(src, journal).apply("s", root, listOf(MoveOp(stale, "Docs")), ConflictMode.RENAME, java.util.concurrent.atomic.AtomicBoolean(false))
        assertEquals(0, r1.done); assertEquals(1, r1.skipped.size)

        src.move(a, root, docs, "a.txt")
        add("a.txt", ByteArray(2))
        val a2 = src.list(root).nodes.first { it.name == "a.txt" }
        val r2 = Applier(src, journal).apply("s2", root, listOf(MoveOp(a2, "Docs")), ConflictMode.SKIP, java.util.concurrent.atomic.AtomicBoolean(false))
        assertEquals(0, r2.done); assertEquals(1, r2.skipped.size)
    }
}

class LocalFileSourceTest : FileSourceContract() {
    override fun setup(): Triple<FileSource, NodeRef, (String, ByteArray) -> Unit> {
        val dir = Files.createTempDirectory("fs").toFile()
        return Triple(LocalFileSource(), NodeRef(dir.path)) { name, data -> File(dir, name).writeBytes(data) }
    }

    @Test fun createDirRejectsTraversal() = runBlocking {
        val dir = Files.createTempDirectory("fs").toFile()
        val ok = runCatching { LocalFileSource().createDir(NodeRef(dir.path), "../evil") }
        assertTrue(ok.isFailure)
    }
}

class InMemoryFileSourceTest : FileSourceContract() {
    override fun setup(): Triple<FileSource, NodeRef, (String, ByteArray) -> Unit> {
        val src = InMemoryFileSource()
        val root = src.mkRoot("root")
        return Triple(src, root) { name, data -> src.addFile(root, name, data) }
    }
}
