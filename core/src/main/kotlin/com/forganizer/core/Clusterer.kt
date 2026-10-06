package com.forganizer.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One object sent to the model: either a cluster of files or a single file. */
sealed interface SummaryObject {
    val id: String
    val members: List<FileNode>

    data class Cluster(override val id: String, val dto: ClusterDto, override val members: List<FileNode>) : SummaryObject
    data class Single(override val id: String, val dto: FileDto, val file: FileNode) : SummaryObject {
        override val members: List<FileNode> get() = listOf(file)
    }
}

/** A plan item resolved locally, without AI (e.g. installers). */
data class LocalAssignment(val file: FileNode, val folder: String, val reason: String)

data class Summary(
    val objects: List<SummaryObject>,
    val local: List<LocalAssignment>,
    /** Groups of single-file ids that may form one bundle; must go into one batch. */
    val bundleCandidates: List<Set<String>>,
) {
    val byId: Map<String, SummaryObject> = objects.associateBy { it.id }
    val clusters: List<SummaryObject.Cluster> get() = objects.filterIsInstance<SummaryObject.Cluster>()
    val singles: List<SummaryObject.Single> get() = objects.filterIsInstance<SummaryObject.Single>()
}

class Clusterer(
    private val rules: Rules,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: Long = System.currentTimeMillis(),
) {
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun summarize(files: List<FileNode>, oldDays: Int = rules.config.oldFilesDays): Summary {
        val minSize = rules.config.clusterMinSize.coerceAtLeast(2)
        val local = mutableListOf<LocalAssignment>()
        val rest = mutableListOf<FileNode>()
        val oldThreshold = now - oldDays.toLong() * 24 * 3600 * 1000
        for (f in files) {
            val type = rules.typeOf(f.name)
            val folder = rules.config.localFolders[type]
            if (folder != null) {
                val oldFolder = rules.config.oldLocalFolders[type]
                if (oldFolder != null && f.modified in 1 until oldThreshold) {
                    local += LocalAssignment(f, oldFolder, "Установщик старше $oldDays дней")
                } else {
                    local += LocalAssignment(f, folder, "Установочный файл приложения")
                }
            } else rest += f
        }

        val groups = mutableListOf<Pair<String, List<FileNode>>>() // pattern -> members
        val used = HashSet<String>()

        // 1. Counter series: "file (1).pdf", "file (2).pdf", plus "file.pdf".
        val seriesKey = HashMap<String, MutableList<FileNode>>()
        for (f in rest) {
            val m = SERIES.matchEntire(baseName(f.name))
            val base = (m?.groupValues?.get(1) ?: baseName(f.name)).trim().lowercase()
            seriesKey.getOrPut(base + "|" + extension(f.name)) { mutableListOf() } += f
        }
        for ((key, members) in seriesKey) {
            val numbered = members.count { SERIES.matches(baseName(it.name)) }
            if (members.size >= minSize && numbered >= minSize - 1) {
                val first = members.first { SERIES.matches(baseName(it.name)) }
                val base = SERIES.matchEntire(baseName(first.name))!!.groupValues[1].trim()
                val ext = key.substringAfter('|')
                groups += "$base (*)" + (if (ext.isNotEmpty()) ".$ext" else "") to members
                members.forEach { used += it.id }
            }
        }

        // 2. Common prefix: IMG_*, Screenshot_*, invoice_* (prefix followed by a digit).
        val byPrefix = LinkedHashMap<String, MutableList<FileNode>>()
        for (f in rest) {
            if (f.id in used) continue
            val m = PREFIX.find(f.name) ?: continue
            byPrefix.getOrPut(m.groupValues[1].lowercase()) { mutableListOf() } += f
        }
        for ((_, members) in byPrefix) {
            if (members.size >= minSize) {
                val p = PREFIX.find(members.first().name)!!.groupValues[1]
                groups += "$p*" to members
                members.forEach { used += it.id }
            }
        }

        // 3. Bursts: same type, created within one minute.
        val byType = rest.filter { it.id !in used }.groupBy { rules.typeOf(it.name) }
        for ((type, list) in byType) {
            val sorted = list.sortedBy { it.modified }
            var i = 0
            while (i < sorted.size) {
                var j = i
                while (j + 1 < sorted.size && sorted[j + 1].modified - sorted[i].modified <= 60_000) j++
                val window = sorted.subList(i, j + 1)
                if (window.size >= minSize) {
                    groups += "$type: ${window.size} шт. за 1 минуту" to window.toList()
                    window.forEach { used += it.id }
                    i = j + 1
                } else i++
            }
        }

        val objects = mutableListOf<SummaryObject>()
        groups.forEachIndexed { idx, (pattern, members) ->
            val id = "c${idx + 1}"
            val sorted = members.sortedBy { it.name.lowercase() }
            val exts = sorted.groupingBy { extension(it.name).ifEmpty { "-" } }.eachCount()
            val dates = sorted.map { it.modified }.let { listOf(date(it.min()), date(it.max())) }
            objects += SummaryObject.Cluster(
                id,
                ClusterDto(id, sorted.size, exts, pattern, dates.distinct(), sorted.take(3).map { it.name }),
                sorted,
            )
        }
        var n = 0
        val singles = rest.filter { it.id !in used }.map { f ->
            n++
            val id = "f$n"
            SummaryObject.Single(id, FileDto(id, f.name, (f.size + 1023) / 1024, date(f.modified)), f)
        }
        objects += singles
        return Summary(objects, local, bundleCandidates(singles))
    }

    private fun date(ms: Long): String = dateFmt.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** Union of single files that share a distinctive word in their names. */
    private fun bundleCandidates(singles: List<SummaryObject.Single>): List<Set<String>> {
        val tokenToIds = HashMap<String, MutableList<String>>()
        for (s in singles) {
            tokens(s.file.name).forEach { tokenToIds.getOrPut(it) { mutableListOf() } += s.id }
        }
        val parent = HashMap<String, String>()
        fun find(x: String): String {
            var r = x
            while (parent[r] != null && parent[r] != r) r = parent[r]!!
            parent[x] = r
            return r
        }
        for ((_, ids) in tokenToIds) {
            if (ids.size < 2 || ids.size > MAX_TOKEN_SHARE) continue
            val root = find(ids[0])
            for (id in ids.drop(1)) {
                val r = find(id)
                if (r != root) parent[r] = root
            }
        }
        return singles.map { it.id }.groupBy { find(it) }.values.filter { it.size > 1 }.map { it.toSet() }
    }

    companion object {
        private val SERIES = Regex("""^(.*?)\s*\((\d{1,4})\)$""")
        private val PREFIX = Regex("""^([A-Za-zА-Яа-яЁё]+[_\- ])\d""")
        private const val MAX_TOKEN_SHARE = 20
        private val STOP = setOf(
            "final", "copy", "new", "scan", "image", "img", "photo", "file", "document", "doc",
            "copia", "kopia", "untitled", "screenshot", "download", "temp", "test", "version",
            "the", "and", "for", "with", "from", "копия", "новый", "новая", "файл", "скан",
            "документ", "фото", "изображение", "без", "названия", "html", "files", "main", "index",
        )

        fun tokens(name: String): Set<String> =
            baseName(name).split(Regex("""[^\p{L}\p{N}]+|(?<=\p{Ll})(?=\p{Lu})"""))
                .map { it.lowercase() }
                .filter { it.length >= 3 && it.any { c -> c.isLetter() } && it !in STOP }
                .toSet()
    }
}
