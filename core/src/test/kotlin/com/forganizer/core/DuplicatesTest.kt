package com.forganizer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicatesTest {
    @Test fun findsDuplicatesBySizeAndHash() {
        val src = InMemoryFileSource()
        val root = src.mkRoot("r")
        val big = ByteArray(300_000) { (it % 251).toByte() }
        val bigOther = big.copyOf().also { it[150_000] = 7 } // same head and tail, different middle
        val a = src.addFile(root, "a.bin", big)
        val b = src.addFile(root, "b.bin", big.copyOf())
        val c = src.addFile(root, "c.bin", bigOther)
        val d = src.addFile(root, "d.txt", "hello".toByteArray())
        val e = src.addFile(root, "e.txt", "hello".toByteArray())
        val f = src.addFile(root, "f.txt", "world".toByteArray())
        val groups = DuplicateFinder { src.openRead(it) }.find(listOf(a, b, c, d, e, f))
        assertEquals(setOf(setOf("a.bin", "b.bin"), setOf("d.txt", "e.txt")), groups.map { g -> g.map { it.name }.toSet() }.toSet())
    }

    @Test fun conflictNames() {
        assertEquals("a (2).pdf", Conflicts.uniqueName("a.pdf", setOf("A.pdf", "a (1).pdf")))
        assertEquals("README (1)", Conflicts.uniqueName("README", setOf("readme")))
    }
}
