package com.forganizer.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ExportEntry(
    @SerialName("file_id") val fileId: String,
    val file: String,
    @SerialName("target_folder") val targetFolder: String,
    val bundle: String?,
    val reason: String,
    val confidence: Double,
    val selected: Boolean,
)

@Serializable
data class ExportLeave(@SerialName("file_id") val fileId: String, val file: String, val reason: String)

@Serializable
data class ExportDoc(
    val root: String,
    val folders: List<String>,
    val plan: List<ExportEntry>,
    val leave: List<ExportLeave>,
)

object PlanExport {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun toJson(root: String, plan: OrganizePlan): String = json.encodeToString(
        ExportDoc.serializer(),
        ExportDoc(
            root = root,
            folders = plan.folders.map { it.name },
            plan = plan.items.map { ExportEntry(it.file.id, it.file.name, it.folder, it.bundle, it.reason, it.confidence, it.checked) },
            leave = plan.leave.map { ExportLeave(it.file.id, it.file.name, it.reason) },
        ),
    )

    fun toText(root: String, plan: OrganizePlan): String = buildString {
        appendLine("План для: $root")
        appendLine()
        for (f in plan.folders) {
            val items = plan.itemsIn(f.name)
            appendLine("${f.name}/ (${items.size})" + if (f.desc.isNotEmpty()) " - ${f.desc}" else "")
            for (it in items) {
                val mark = if (it.checked) "" else " [не выбрано]"
                appendLine("  ${it.file.name} -> ${f.name}/$mark")
            }
            appendLine()
        }
        if (plan.leave.isNotEmpty()) {
            appendLine("Не определено (${plan.leave.size}):")
            plan.leave.forEach { appendLine("  ${it.file.name} - ${it.reason}") }
        }
    }
}
