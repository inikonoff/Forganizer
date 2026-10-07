package com.forganizer.core

import java.io.File

object StoragePaths {
    /**
     * Turns a document id of the system file picker ("primary:Download", "1234-ABCD:Test folder") into a
     * real directory, for the all-files-access mode. Works for internal storage and for SD cards or USB
     * drives (mounted under /storage/<volume-id>). Returns null if the volume is unknown or the path
     * would leave the volume.
     */
    fun fromTreeDocumentId(docId: String, primaryRoot: File, storageRoot: File = File("/storage")): File? {
        val volume = docId.substringBefore(':')
        val rel = docId.substringAfter(':', "")
        val base = if (volume == "primary") {
            primaryRoot
        } else {
            if (!VOLUME.matches(volume)) return null
            File(storageRoot, volume).takeIf { it.isDirectory } ?: return null
        }
        val baseCanonical = base.canonicalFile
        val target = File(baseCanonical, rel).canonicalFile
        val inside = target.path == baseCanonical.path || target.path.startsWith(baseCanonical.path + File.separator)
        return target.takeIf { inside }
    }

    private val VOLUME = Regex("[A-Za-z0-9][A-Za-z0-9-]{3,}")
}
