package com.forganizer.app.fs

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import com.forganizer.app.BuildConfig
import com.forganizer.core.StoragePaths
import java.io.File

data class StandardFolder(val label: String, val dir: File)

object Access {
    val allFilesSupported: Boolean get() = BuildConfig.ALL_FILES_ACCESS

    fun hasAllFiles(context: Context): Boolean {
        if (!allFilesSupported) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Intent for the system "All files access" screen (Android 11+). */
    fun allFilesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun fallbackAllFilesIntent(): Intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

    /** Reading is enough to analyze a folder; writing is checked separately when files are moved. */
    fun hasTreePermission(context: Context, tree: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }

    fun hasTreeWrite(context: Context, tree: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    /** Takes the persistable grant of a picked folder; falls back to read-only when write is not offered. */
    fun takePersistable(context: Context, uri: Uri) {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, rw)
        } catch (e: SecurityException) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    /** A plain-text report of what the app can see; safe to copy and send (no file contents). */
    fun diagnose(context: Context, picked: Uri?, saved: String?): String = buildString {
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Сборка: ${BuildConfig.FLAVOR} ${BuildConfig.VERSION_NAME}")
        appendLine("Доступ ко всем файлам: ${if (hasAllFiles(context)) "есть" else "нет"}")
        val perms = context.contentResolver.persistedUriPermissions
        appendLine("Сохранённых доступов к папкам: ${perms.size}")
        perms.take(6).forEach {
            appendLine(" • ${Uri.decode(it.uri.toString()).substringAfter("/tree/").take(70)}  чтение=${yn(it.isReadPermission)} запись=${yn(it.isWritePermission)}")
        }
        appendLine("Тома в /storage: " + (File("/storage").list()?.sorted()?.joinToString() ?: "не видны"))
        val trees = listOfNotNull(picked, saved?.let(Uri::parse)).distinct()
        if (trees.isEmpty()) appendLine("Папка не выбрана.")
        for (t in trees) {
            appendLine()
            appendLine("Папка: " + Uri.decode(t.toString()).substringAfter("/tree/").take(80))
            appendLine("  доступ сохранён: чтение=${yn(hasTreePermission(context, t))} запись=${yn(hasTreeWrite(context, t))}")
            val path = treeToPath(t)
            if (path == null) appendLine("  как путь: не определяется")
            else appendLine("  как путь: ${path.path}; каталог=${yn(path.isDirectory)}; список=${path.listFiles()?.size?.toString() ?: "нет доступа"}")
            val safCount = runCatching {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
                context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                    ?.use { it.count }
            }
            safCount.onSuccess { appendLine("  через системный доступ: ${it?.toString() ?: "нет ответа"} записей") }
                .onFailure { appendLine("  через системный доступ: ошибка ${it.javaClass.simpleName}") }
        }
    }

    private fun yn(b: Boolean) = if (b) "да" else "нет"

    fun standardFolders(): List<StandardFolder> = listOf(
        "Загрузки" to Environment.DIRECTORY_DOWNLOADS,
        "Документы" to Environment.DIRECTORY_DOCUMENTS,
        "Камера (DCIM)" to Environment.DIRECTORY_DCIM,
        "Изображения" to Environment.DIRECTORY_PICTURES,
        "Видео" to Environment.DIRECTORY_MOVIES,
        "Музыка" to Environment.DIRECTORY_MUSIC,
    ).map { (label, type) -> StandardFolder(label, Environment.getExternalStoragePublicDirectory(type)) }
        .filter { it.dir.isDirectory }

    /** Converts a primary-storage tree URI to a path (used in all-files mode); null otherwise. */
    fun treeToPath(tree: Uri): File? {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        if (tree.authority != "com.android.externalstorage.documents") return null
        return StoragePaths.fromTreeDocumentId(id, Environment.getExternalStorageDirectory())
    }

    /** For a document URI built with a tree, returns that tree URI. */
    fun treeOf(documentUri: Uri): Uri? = runCatching {
        DocumentsContract.buildTreeDocumentUri(documentUri.authority, DocumentsContract.getTreeDocumentId(documentUri))
    }.getOrNull()

    fun label(rootId: String): String =
        if (rootId.startsWith("content://")) {
            val id = runCatching { DocumentsContract.getDocumentId(Uri.parse(rootId)) }.getOrDefault(rootId)
            Uri.decode(id).substringAfter(':').ifEmpty { "Выбранная папка" }
        } else {
            val primary = Environment.getExternalStorageDirectory().path
            when {
                rootId.startsWith(primary) -> rootId.removePrefix(primary).trimStart('/').ifEmpty { "/" }
                rootId.startsWith("/storage/") ->
                    "SD: " + rootId.removePrefix("/storage/").substringAfter('/', "").ifEmpty { rootId.removePrefix("/storage/") }
                else -> rootId
            }
        }
}
