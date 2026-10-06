package com.forganizer.core

import java.io.InputStream
import java.security.MessageDigest

/** Local-only duplicate detection: size, then partial hash (first+last 64 KB), then full SHA-256. */
class DuplicateFinder(private val open: (FileNode) -> InputStream) {

    fun find(files: List<FileNode>, isCancelled: () -> Boolean = { false }): List<List<FileNode>> {
        val result = mutableListOf<List<FileNode>>()
        val bySize = files.filter { !it.isDir && it.size > 0 }.groupBy { it.size }.values.filter { it.size > 1 }
        for (group in bySize) {
            if (isCancelled()) break
            val byPartial = group.groupBy { safe { partialHash(it) } }.filterKeys { it != null }.values.filter { it.size > 1 }
            for (candidates in byPartial) {
                val full = if (candidates.first().size <= 2 * CHUNK) candidates.groupBy { "same" }
                else candidates.groupBy { safe { fullHash(it) } }.filterKeys { it != null }
                full.values.filter { it.size > 1 }.forEach { result += it }
            }
        }
        return result
    }

    private inline fun safe(block: () -> String): String? = try { block() } catch (e: Exception) { null }

    private fun partialHash(f: FileNode): String {
        val md = MessageDigest.getInstance("SHA-256")
        open(f).use { input ->
            val buf = ByteArray(CHUNK)
            md.update(buf, 0, readFully(input, buf))
            val tailStart = f.size - CHUNK
            if (tailStart > CHUNK) {
                skipFully(input, tailStart - CHUNK)
                md.update(buf, 0, readFully(input, buf))
            } else {
                // Small file: hash the rest of the content.
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
        }
        return md.digest().toHex()
    }

    private fun fullHash(f: FileNode): String {
        val md = MessageDigest.getInstance("SHA-256")
        open(f).use { input ->
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n <= 0) break
            off += n
        }
        return off
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n <= 0) {
                if (input.read() < 0) return
                left--
            } else left -= n
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        const val CHUNK = 64 * 1024
    }
}
