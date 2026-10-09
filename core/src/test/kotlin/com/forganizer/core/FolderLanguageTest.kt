package com.forganizer.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

class FolderLanguageTest {
    private val apk = file("1", "app.apk")

    @Test fun localFolderNamesFollowTheLanguage() {
        val ru = Clusterer(Rules.DEFAULT, ZoneOffset.UTC).summarize(listOf(apk))
        val en = Clusterer(Rules.DEFAULT, ZoneOffset.UTC, language = FolderLanguage.EN).summarize(listOf(apk))
        assertEquals("Установщики", ru.local.single().folder)
        assertEquals("Installers", en.local.single().folder)
    }

    @Test fun codeParsingFallsBackToRussian() {
        assertEquals(FolderLanguage.EN, FolderLanguage.fromCode("en"))
        assertEquals(FolderLanguage.RU, FolderLanguage.fromCode(null))
        assertEquals(FolderLanguage.RU, FolderLanguage.fromCode("de"))
    }

    @Test fun planRequestsCarryTheLanguage() = runBlocking {
        val seen = mutableListOf<String>()
        val api = object : PlanApi {
            override suspend fun plan(req: PlanRequest): RawPlan { seen += req.folderLanguage; return RawPlan() }
        }
        val summary = Clusterer(Rules.DEFAULT, ZoneOffset.UTC).summarize(listOf(file("1", "a.pdf"), file("2", "b.jpg")))
        AiPlanner(api).plan(summary, emptyList(), false, FolderLanguage.EN)
        assertEquals(listOf("en"), seen)
        val session = PlanSession(OrganizePlan(emptyList(), emptyList(), emptyList(), emptyList()), false, folderLanguage = FolderLanguage.EN)
        assertEquals("en", session.refineRequest("x").folderLanguage)
    }
}
