package com.forganizer.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class FolderDumpTest {
    private fun node(id: String, name: String, size: Long, modified: Long, dir: Boolean = false) =
        FileNode(id, name, size, modified, null, dir)

    private val files = listOf(
        node("/d/b.pdf", "b.pdf", 1_500_000, 1_780_000_000_000),
        node("/d/A.jpg", "A.jpg", 2048, 1_790_000_000_000),
        node("/d/empty", "empty", 0, 0),
    )

    @Test fun dumpIsSortedCompleteAndRoundTrips() {
        val doc = FolderDump.build(
            root = "Download", takenAt = 1_791_000_000_000, files = files,
            folders = listOf(node("/d/Docs", "Docs", 0, 0, true)),
            ignoredFiles = listOf(node("/d/.nomedia", ".nomedia", 0, 0)),
            ignoredFolders = listOf(node("/d/.cache", ".cache", 0, 0, true)),
            skipped = 2,
            duplicates = listOf(listOf(files[0], files[1])),
            source = "сканирование до сортировки", zone = ZoneOffset.UTC,
        )
        assertEquals(listOf(".nomedia", "A.jpg", "b.pdf", "empty"), doc.files.map { it.name })
        assertTrue(doc.files.first { it.name == ".nomedia" }.ignored)
        assertEquals(listOf(".cache", "Docs"), doc.folders.map { it.name })
        assertEquals("image", doc.files.first { it.name == "A.jpg" }.type)
        assertEquals("", doc.files.first { it.name == "empty" }.modified)

        val back = Json.decodeFromString(DumpDoc.serializer(), FolderDump.toJson(doc))
        assertEquals(doc, back)
        assertTrue(FolderDump.toJson(doc).contains("\"size_bytes\": 1500000"))
    }

    @Test fun textIsReadableAndHonest() {
        val doc = FolderDump.build(
            "Download", 1_791_000_000_000, files, listOf(node("/d/Docs", "Docs", 0, 0, true)),
            skipped = 1, duplicates = listOf(listOf(files[0], files[1])),
            source = "сканирование до сортировки", zone = ZoneOffset.UTC,
        )
        val t = FolderDump.toText(doc, ZoneOffset.UTC)
        assertTrue(t, t.startsWith("Снимок папки: Download"))
        assertTrue(t.contains("Файлов: 3, папок: 1, недоступно: 1"))
        assertTrue(t.contains("Сами файлы не копируются"))
        assertTrue(t.contains("1.4 МБ") || t.contains("1,4 МБ"))
        assertTrue(t.contains("2 КБ") && t.contains("Docs/"))
        assertTrue(t.contains("b.pdf  =  A.jpg") || t.contains("A.jpg  =  b.pdf") || t.contains("b.pdf  =  A.jpg"))
        assertFalse(t.contains("/d/"))   // no device paths in the readable listing
    }

    @Test fun scannerKeepsIgnoredEntriesForTheDump() = runBlocking {
        val src = InMemoryFileSource()
        val root = src.mkRoot("r")
        src.addFile(root, "keep.pdf")
        src.addFile(root, "temp.tmp")
        src.addFile(root, ".hidden")
        runBlocking { src.createDir(root, "Docs"); src.createDir(root, ".cache") }
        val r = Scanner(src).scan(root, ScanSettings(setOf("tmp"), emptySet()))
        assertEquals(listOf("keep.pdf"), r.files.map { it.name })
        assertEquals(setOf("temp.tmp", ".hidden"), r.ignoredFiles.map { it.name }.toSet())
        assertEquals(listOf("Docs"), r.existingFolders.map { it.name })
        assertEquals(listOf(".cache"), r.ignoredFolders.map { it.name })
    }
}
