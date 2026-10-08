package com.forganizer.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forganizer.app.fs.Access
import com.forganizer.app.fs.StandardFolder
import com.forganizer.core.RuPlural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val TileShape = RoundedCornerShape(20.dp)

private val GLYPHS = mapOf(
    "Загрузки" to "📥",
    "Документы" to "📄",
    "Камера (DCIM)" to "📸",
    "Изображения" to "🖼️",
    "Видео" to "🎬",
    "Музыка" to "🎵",
)
private val POPULAR = listOf("Загрузки", "Документы", "Камера (DCIM)", "Изображения")

@Composable
fun FolderScreen(vm: MainViewModel, state: UiState) {
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onTreePicked(it) }
    ScreenScaffold(
        "Forganizer",
        actions = {
            TextButton(onClick = vm::openSavedList) { Text("Схемы", maxLines = 1) }
            IconButton(onClick = vm::openJournal) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Журнал") }
            IconButton(onClick = vm::openSettings) { Icon(Icons.Default.Settings, contentDescription = "Настройки") }
        },
    ) { padding ->
        ScrollColumn(padding) {
            Text("Какую папку разобрать?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (state.mode == Mode.FULL) {
                FullModeFolders(vm) { treePicker.launch(null) }
                SubfoldersSwitch(state.settings.includeSubfolders) { v -> vm.updateSettings { it.copy(includeSubfolders = v) } }
                InfoPlate(rootInfo(state.settings.includeSubfolders))
            } else {
                SavedFolderCard(state.treeLabel, onAnalyze = vm::startScanSaf)
                PickerRow("Выбрать другую папку…") { treePicker.launch(null) }
                if (Access.allFilesSupported) {
                    Card(shape = TileShape, colors = tileColors()) {
                        ChevronRow("🔓", "Включить полный режим", "Доступ к корню «Загрузок» и других папок", vm::openAccess)
                    }
                }
                SubfoldersSwitch(state.settings.includeSubfolders) { v -> vm.updateSettings { it.copy(includeSubfolders = v) } }
                InfoPlate("Корень «Загрузок» в этом режиме недоступен: выберите подпапку или включите полный режим.")
            }
        }
    }
}

@Composable
private fun FullModeFolders(vm: MainViewModel, pickOther: () -> Unit) {
    val folders = remember { Access.standardFolders() }
    // File counts are read off the main thread; tiles show a dash until they arrive.
    val counts by produceState(initialValue = emptyMap<String, Int>(), folders) {
        value = withContext(Dispatchers.IO) {
            folders.associate { it.dir.path to (it.dir.listFiles()?.count { f -> f.isFile } ?: -1) }
        }
    }
    fun subtitle(f: StandardFolder): String = counts[f.dir.path]?.takeIf { it >= 0 }?.let(RuPlural::files) ?: "…"

    val popular = POPULAR.mapNotNull { label -> folders.firstOrNull { it.label == label } }
    val others = folders.filter { it.label !in POPULAR }

    if (popular.isNotEmpty()) {
        SectionLabel("Популярные")
        popular.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                pair.forEach { f ->
                    FolderTile(GLYPHS[f.label] ?: "📁", f.label, subtitle(f), Modifier.weight(1f)) { vm.startScanFull(f.dir) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    if (others.isNotEmpty()) {
        SectionLabel("Другие")
        Card(shape = TileShape, colors = tileColors()) {
            others.forEachIndexed { i, f ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 68.dp))
                ChevronRow(GLYPHS[f.label] ?: "📁", f.label, subtitle(f)) { vm.startScanFull(f.dir) }
            }
        }
    }
    PickerRow("Выбрать другую папку на устройстве…", pickOther)
}

@Composable
private fun tileColors() = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun GlyphBox(glyph: String) {
    Box(
        Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) { Text(glyph) }
}

@Composable
private fun FolderTile(glyph: String, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, shape = TileShape, colors = tileColors()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlyphBox(glyph)
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Hint(subtitle, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ChevronRow(glyph: String, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        GlyphBox(glyph)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Hint(subtitle)
        }
        Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The way into the system file picker: an accented outline so it reads as "go and look for a folder". */
@Composable
private fun PickerRow(title: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = TileShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphBox("📂")
            Spacer(Modifier.width(14.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SavedFolderCard(label: String?, onAnalyze: () -> Unit) {
    SectionLabel("Выбранная папка")
    Card(shape = TileShape, colors = tileColors()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphBox("📂")
                Spacer(Modifier.width(14.dp))
                Text(label ?: "Папка не выбрана", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Button(onClick = onAnalyze, enabled = label != null, modifier = Modifier.fillMaxWidth()) { Text("Анализировать") }
        }
    }
}

/** A readable hint on a tinted plate with proper contrast. */
@Composable
private fun InfoPlate(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("ℹ️")
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

private fun rootInfo(deep: Boolean) =
    if (deep) "Файлы из вложенных папок (до 3 уровней) тоже попадут в план. Папки проектов (.git, build.gradle, package.json…) остаются нетронутыми, пустые папки не удаляются."
    else "Анализируется только корень папки. Вложенные папки и файлы в них не затрагиваются."

/** Opt-in: include files from nested folders (off by default). */
@Composable
private fun SubfoldersSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(shape = TileShape, colors = tileColors()) {
        Row(
            Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Включать вложенные папки", style = MaterialTheme.typography.bodyLarge)
                Hint("Файлы из подпапок до 3 уровней, кроме проектов")
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
