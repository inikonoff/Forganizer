package com.forganizer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StoragePathsTest {
    private val primary = Files.createTempDirectory("primary").toFile().canonicalFile
    private val storage = Files.createTempDirectory("storage").toFile().canonicalFile.also { File(it, "1234-ABCD").mkdirs() }

    private fun resolve(id: String) = StoragePaths.fromTreeDocumentId(id, primary, storage)

    @Test fun internalStorage() {
        assertEquals(File(primary, "Download"), resolve("primary:Download"))
        assertEquals(primary, resolve("primary:"))
    }

    @Test fun sdCardWithSpacesInFolderName() {
        assertEquals(File(storage, "1234-ABCD/Test folder"), resolve("1234-ABCD:Test folder"))
        assertEquals(File(storage, "1234-ABCD"), resolve("1234-ABCD:"))
    }

    @Test fun unknownVolumesAndOddIdsAreRejected() {
        assertNull(resolve("9999-ZZZZ:Test"))   // not mounted
        assertNull(resolve("home:Documents"))
        assertNull(resolve("raw:/etc"))
        assertNull(resolve("../..:x"))
        assertNull(resolve(""))
    }

    @Test fun pathCannotLeaveTheVolume() {
        assertNull(resolve("primary:../other"))
        assertNull(resolve("1234-ABCD:../../etc"))
        assertEquals(File(storage, "1234-ABCD/a"), resolve("1234-ABCD:x/../a"))
    }
}
