package com.forganizer.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ClusterDto(
    val id: String,
    val count: Int,
    val exts: Map<String, Int>,
    val pattern: String,
    val dates: List<String>,
    val samples: List<String>,
    /** Summary of names inside the first archive of the cluster (empty if none). */
    val inside: String = "",
)

@Serializable
data class FileDto(
    val id: String,
    val name: String,
    @SerialName("size_kb") val sizeKb: Long,
    val date: String,
    /** Summary of names inside a zip archive (empty if not an archive or not peeked). */
    val inside: String = "",
    /** Folder the file was found in, relative to the scanned root (empty for root files). */
    val dir: String = "",
)

@Serializable
data class PlanRequest(
    val phase: Int,
    @SerialName("existing_folders") val existingFolders: List<String>,
    @SerialName("allow_existing") val allowExisting: Boolean,
    val taxonomy: List<String>? = null,
    val clusters: List<ClusterDto>,
    val files: List<FileDto>,
    /** "ru" or "en": the language of new folder names. */
    @SerialName("folder_language") val folderLanguage: String = "ru",
) {
    fun ids(): Set<String> = (clusters.map { it.id } + files.map { it.id }).toSet()
}

@Serializable
data class FolderDto(val name: String = "", val desc: String = "")

@Serializable
data class AssignmentDto(
    val ref: String = "",
    val folder: String = "",
    val bundle: String? = null,
    val reason: String = "",
    /** Kept nullable: a model may send a string or nothing; validator treats it as low confidence. */
    val confidence: Double? = null,
)

@Serializable
data class LeaveDto(val ref: String = "", val reason: String = "")

@Serializable
data class RawPlan(
    val folders: List<FolderDto> = emptyList(),
    val assignments: List<AssignmentDto> = emptyList(),
    val leave: List<LeaveDto> = emptyList(),
)

val ProtocolJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = true
    encodeDefaults = true
    coerceInputValues = true
    isLenient = true
}

/** Transport to the /plan endpoint. */
interface PlanApi {
    suspend fun plan(request: PlanRequest): RawPlan
}

/** Transport to the /refine endpoint. */
interface RefineApi {
    suspend fun refine(request: RefineRequest): RawPatch
}

@Serializable
data class FolderSummaryDto(
    val name: String,
    val count: Int,
    val exts: Map<String, Int>,
    val bundles: List<String>,
)

@Serializable
data class LeaveSummaryDto(val count: Int, val exts: Map<String, Int>)

@Serializable
data class PinDto(
    val kind: String,
    val name: String? = null,
    val ref: String? = null,
    val folder: String? = null,
)

@Serializable
data class RefineRequest(
    val instruction: String,
    @SerialName("existing_folders") val existingFolders: List<String>,
    @SerialName("allow_existing") val allowExisting: Boolean,
    val folders: List<FolderSummaryDto>,
    val leave: LeaveSummaryDto,
    val pinned: List<PinDto>,
    val history: List<String>,
    @SerialName("folder_language") val folderLanguage: String = "ru",
)

/** Raw patch as returned by the server; every op is re-checked on the device. */
@Serializable
data class RawPatch(
    val ops: List<kotlinx.serialization.json.JsonObject> = emptyList(),
    val note: String = "",
)

class AiUnavailableException(message: String) : Exception(message)
class AiRequestException(val code: Int, message: String) : Exception(message)
