package com.forganizer.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.forganizer.core.ConflictMode
import com.forganizer.core.Rules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val consent: Boolean = false,
    val treeUri: String? = null,
    val allowExisting: Boolean = false,
    val peekArchives: Boolean = true,
    val includeSubfolders: Boolean = false,
    val ignoreExtensions: List<String> = emptyList(),
    val ignoreFolders: List<String> = emptyList(),
    val oldDays: Int = 90,
    val conflictMode: ConflictMode = ConflictMode.RENAME,
    val serverUrl: String = "",
)

class SettingsStore(private val context: Context, private val rules: Rules) {
    private object K {
        val consent = booleanPreferencesKey("consent")
        val treeUri = stringPreferencesKey("tree_uri")
        val allowExisting = booleanPreferencesKey("allow_existing")
        val peekArchives = booleanPreferencesKey("peek_archives")
        val includeSubfolders = booleanPreferencesKey("include_subfolders")
        val ignoreExt = stringPreferencesKey("ignore_extensions")
        val ignoreDirs = stringPreferencesKey("ignore_folders")
        val oldDays = intPreferencesKey("old_days")
        val conflict = stringPreferencesKey("conflict_mode")
        val serverUrl = stringPreferencesKey("server_url")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p -> read(p) }

    suspend fun current(): AppSettings = flow.first()

    private fun read(p: Preferences) = AppSettings(
        consent = p[K.consent] ?: false,
        treeUri = p[K.treeUri],
        allowExisting = p[K.allowExisting] ?: false,
        peekArchives = p[K.peekArchives] ?: true,
        includeSubfolders = p[K.includeSubfolders] ?: false,
        ignoreExtensions = p[K.ignoreExt]?.let(::splitList) ?: rules.config.ignoreExtensions,
        ignoreFolders = p[K.ignoreDirs]?.let(::splitList) ?: rules.config.ignoreFolders,
        oldDays = p[K.oldDays] ?: rules.config.oldFilesDays,
        conflictMode = p[K.conflict]?.let { runCatching { ConflictMode.valueOf(it) }.getOrNull() } ?: ConflictMode.RENAME,
        serverUrl = p[K.serverUrl] ?: "",
    )

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val s = transform(read(p))
            p[K.consent] = s.consent
            if (s.treeUri != null) p[K.treeUri] = s.treeUri else p.remove(K.treeUri)
            p[K.allowExisting] = s.allowExisting
            p[K.peekArchives] = s.peekArchives
            p[K.includeSubfolders] = s.includeSubfolders
            p[K.ignoreExt] = s.ignoreExtensions.joinToString(",")
            p[K.ignoreDirs] = s.ignoreFolders.joinToString(",")
            p[K.oldDays] = s.oldDays
            p[K.conflict] = s.conflictMode.name
            p[K.serverUrl] = s.serverUrl
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    companion object {
        fun splitList(s: String): List<String> =
            s.split(',', ';', ' ', '\n').map { it.trim().trimStart('.') }.filter { it.isNotEmpty() }.distinct()
    }
}
