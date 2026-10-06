package com.forganizer.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RulesConfig(
    val types: Map<String, List<String>> = emptyMap(),
    @SerialName("ignore_extensions") val ignoreExtensions: List<String> = emptyList(),
    @SerialName("ignore_folders") val ignoreFolders: List<String> = emptyList(),
    @SerialName("old_files_days") val oldFilesDays: Int = 90,
    @SerialName("cluster_min_size") val clusterMinSize: Int = 5,
    /** Types resolved locally without AI: type -> folder name. */
    @SerialName("local_folders") val localFolders: Map<String, String> = emptyMap(),
    @SerialName("old_local_folders") val oldLocalFolders: Map<String, String> = emptyMap(),
)

class Rules(val config: RulesConfig) {
    private val extToType: Map<String, String> = buildMap {
        config.types.forEach { (type, exts) -> exts.forEach { put(it.lowercase().trimStart('.'), type) } }
    }

    fun typeOf(name: String): String = extToType[extension(name)] ?: "other"

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): Rules = Rules(json.decodeFromString(RulesConfig.serializer(), text))
        val DEFAULT = Rules(
            RulesConfig(
                types = mapOf(
                    "image" to listOf("jpg", "jpeg", "png", "webp", "gif", "heic"),
                    "video" to listOf("mp4", "mkv", "mov", "avi", "webm"),
                    "audio" to listOf("mp3", "m4a", "ogg", "flac", "wav", "opus"),
                    "document" to listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "fb2", "epub"),
                    "archive" to listOf("zip", "rar", "7z", "tar", "gz"),
                    "apk" to listOf("apk", "xapk", "apks"),
                    "code" to listOf("py", "js", "html", "css", "java", "kt", "json", "sql", "sh"),
                ),
                ignoreExtensions = listOf("nomedia", "tmp", "crdownload"),
                localFolders = mapOf("apk" to "Установщики"),
                oldLocalFolders = mapOf("apk" to "Старые установщики"),
            )
        )
    }
}

fun extension(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
}

fun baseName(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0) name else name.substring(0, dot)
}
