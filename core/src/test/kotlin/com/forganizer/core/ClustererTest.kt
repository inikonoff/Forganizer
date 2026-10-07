package com.forganizer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ClustererTest {
    private val day = 24 * 3600 * 1000L
    private val clusterer = Clusterer(Rules.DEFAULT, ZoneOffset.UTC, now = 200 * day)

    @Test fun prefixSeriesBurstAndLocal() {
        var n = 0
        val files = buildList {
            repeat(6) { add(file("p${n++}", "IMG_20${it}1.jpg", modified = it * 10 * day)) }
            repeat(5) { add(file("s${n++}", "report (${it + 1}).pdf", modified = it * 5 * day)) }
            add(file("s0x", "report.pdf"))
            repeat(5) { add(file("b${n++}", "a$it.png", modified = 100 * day + it * 1000)) }
            add(file("apk1", "app.apk", modified = 199 * day))
            add(file("apk2", "old.apk", modified = 10 * day))
            add(file("x1", "bot_config.json", modified = 3 * day))
            add(file("x2", "bot.py", modified = 50 * day))
            add(file("x3", "notes.txt", modified = 70 * day))
        }
        val s = clusterer.summarize(files, oldDays = 90)
        val patterns = s.clusters.map { it.dto.pattern }
        assertTrue(patterns.toString(), patterns.contains("IMG_*"))
        assertTrue(patterns.toString(), patterns.contains("report (*).pdf"))
        assertTrue(patterns.toString(), patterns.any { it.startsWith("image") })
        assertEquals(6, s.clusters.first { it.dto.pattern == "report (*).pdf" }.dto.count)
        assertEquals(setOf("Установщики", "Старые установщики"), s.local.map { it.folder }.toSet())
        assertEquals(setOf("bot_config.json", "bot.py", "notes.txt"), s.singles.map { it.file.name }.toSet())
        assertEquals(1, s.bundleCandidates.size)
        assertEquals(2, s.bundleCandidates.single().size)
        // every file appears exactly once
        val covered = s.objects.flatMap { it.members }.map { it.id } + s.local.map { it.file.id }
        assertEquals(files.map { it.id }.sorted(), covered.sorted())
        assertTrue(s.clusters.all { it.dto.samples.size <= 3 })
    }

    @Test fun smallGroupsAreNotClustered() {
        val files = (1..4).map { file("i$it", "IMG_$it.jpg", modified = it * day) }
        val s = clusterer.summarize(files)
        assertTrue(s.clusters.isEmpty())
        assertEquals(4, s.singles.size)
    }

    @Test fun hyphenNumberedSeriesBecomesOneCluster() {
        val files = listOf(file("m0", "voice_bot-main.zip", modified = 1_000_000_000)) +
            (1..8).map { file("m$it", "voice_bot-main-$it.zip", modified = 2_000_000_000 + it * 86_400_000L) } +
            listOf(file("t1", "TZ_folder_organizer-1.md"), file("t2", "TZ_folder_organizer-2.md"), file("t3", "TZ_folder_organizer.md"))
        val s = clusterer.summarize(files)
        val c = s.clusters.single { it.dto.pattern.startsWith("voice_bot-main") }
        assertEquals(9, c.dto.count)
        assertEquals("voice_bot-main-*.zip", c.dto.pattern)
        // three similar notes are not enough for a cluster
        assertEquals(3, s.singles.count { it.file.name.startsWith("TZ_folder_organizer") })
    }
}
