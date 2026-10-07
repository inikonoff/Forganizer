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

    fun hasTreePermission(context: Context, tree: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }

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
