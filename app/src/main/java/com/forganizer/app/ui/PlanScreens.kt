package com.forganizer.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.CircularProgressIndicator
import java.text.DateFormat
import java.util.Date
import com.forganizer.core.FolderNames
import com.forganizer.core.OrganizePlan
import com.forganizer.core.extension
import com.forganizer.core.PlanFolder
import com.forganizer.core.PlanItem

private sealed interface Dialog {
    data object Versions : Dialog
    data object Save : Dialog
    data object Dump : Dialog
    data class Rename(val folder: String) : Dialog
    data class Move(val title: String, val ids: Set<String>, val from: String) : Dialog
}

private fun toggleState(items: List<PlanItem>): ToggleableState = when {
    items.all { it.checked } -> ToggleableState.On
    items.none { it.checked } -> ToggleableState.Off
    else -> ToggleableState.Indeterminate
}

@Composable
fun PictureScreen(vm: MainViewModel, state: UiState) {
    val plan = state.plan ?: return
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var exportMenu by remember { mutableStateOf(false) }
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        ExportHelper.save(context, it, vm.exportJson())
    }
    val saveText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
        ExportHelper.save(context, it, vm.exportText())
    }
    val saveDumpText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
        ExportHelper.save(context, it, vm.dumpText())
    }
    val saveDumpJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        ExportHelper.save(context, it, vm.dumpJson())
    }
    val checked = plan.checkedItems.size
    // Folders are collapsed by default; the list holds the keys of the open ones.
    var openKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    fun folderKey(name: String) = "folder:" + FolderNames.key(name)
    fun toggle(key: String) { openKeys = if (key in openKeys) openKeys - key else openKeys + key }

    ScreenScaffold(
        "Картина папки",
        onBack = vm::back,
        actions = {
            IconButton(onClick = { exportMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Меню") }
            DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                DropdownMenuItem(text = { Text("Развернуть все папки") }, onClick = {
                    exportMenu = false
                    openKeys = plan.folders.map { folderKey(it.name) } + "leave" + "dups"
                })
                DropdownMenuItem(text = { Text("Свернуть все папки") }, onClick = {
                    exportMenu = false; openKeys = emptyList()
                })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Версии плана (${state.versions.size})") }, onClick = {
                    exportMenu = false; dialog = Dialog.Versions
                })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Поделиться JSON") }, onClick = {
                    exportMenu = false; ExportHelper.share(context, "forganizer-plan.json", "application/json", vm.exportJson())
                })
                DropdownMenuItem(text = { Text("Поделиться списком") }, onClick = {
                    exportMenu = false; ExportHelper.share(context, "forganizer-plan.txt", "text/plain", vm.exportText())
                })
                DropdownMenuItem(text = { Text("Сохранить JSON в файл") }, onClick = {
                    exportMenu = false; saveJson.launch("forganizer-plan.json")
                })
                DropdownMenuItem(text = { Text("Сохранить список в файл") }, onClick = {
                    exportMenu = false; saveText.launch("forganizer-plan.txt")
                })
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Выбрано: $checked из ${plan.items.size}", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { dialog = Dialog.Save }, modifier = Modifier.weight(1f)) {
                            Text("Сохранить схему", maxLines = 1)
                        }
                        Button(onClick = vm::openPreview, enabled = checked > 0, modifier = Modifier.weight(1.3f)) {
                            Text("Forganize", maxLines = 1)
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp, end = 12.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "stats") { StatsBlock(plan, state) { dialog = Dialog.Dump } }
            for (folder in plan.folders) {
                item(key = "folder:" + folder.name) {
                    FolderCard(
                        folder = folder,
                        items = plan.itemsIn(folder.name),
                        expanded = folderKey(folder.name) in openKeys,
                        onToggle = { toggle(folderKey(folder.name)) },
                        onCheck = { ids, c -> vm.setChecked(ids, c) },
                        onRename = { dialog = Dialog.Rename(folder.name) },
                        onMove = { title, ids -> dialog = Dialog.Move(title, ids, folder.name) },
                    )
                }
            }
            leaveBlock(state, "leave" in openKeys) { toggle("leave") }
            duplicatesBlock(state, "dups" in openKeys) { toggle("dups") }
            item(key = "refine") { RefineBox(vm, state) }
        }
    }

    when (val d = dialog) {
        Dialog.Versions -> VersionsDialog(state.versions, onDismiss = { dialog = null }) { vm.rollback(it); dialog = null }
        Dialog.Save -> SaveDialog(state.rootLabel, onDismiss = { dialog = null }) { vm.saveScheme(it); dialog = null }
        Dialog.Dump -> DumpDialog(
            onDismiss = { dialog = null },
            onSaveText = { dialog = null; saveDumpText.launch(vm.dumpFileName("txt")) },
            onSaveJson = { dialog = null; saveDumpJson.launch(vm.dumpFileName("json")) },
            onShareText = { dialog = null; ExportHelper.share(context, vm.dumpFileName("txt"), "text/plain", vm.dumpText()) },
            onShareJson = { dialog = null; ExportHelper.share(context, vm.dumpFileName("json"), "application/json", vm.dumpJson()) },
        )
        is Dialog.Rename -> RenameDialog(d.folder, onDismiss = { dialog = null }) { if (vm.renameFolder(d.folder, it)) dialog = null }
        is Dialog.Move -> MoveDialog(d.title, plan.folders.filter { FolderNames.key(it.name) != FolderNames.key(d.from) }, onDismiss = { dialog = null }) {
            vm.moveFiles(d.ids, it); dialog = null
        }
        null -> Unit
    }
}

private val CardShape = RoundedCornerShape(20.dp)

@Composable
private fun cardColors() = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)

/** Block 1: only the key numbers. Details appear only when there is something to look at. */
@Composable
private fun StatsBlock(plan: OrganizePlan, state: UiState, onDump: () -> Unit) {
    val unchecked = plan.items.count { !it.checked && it.stale == null }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Card(Modifier.fillMaxWidth(), shape = CardShape, colors = cardColors()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatCell("📂", plan.folders.size, "папок")
                StatCell("📄", plan.items.size, "файлов")
                StatCell("⚠️", plan.leave.size, "не определено", warn = plan.leave.isNotEmpty())
            }
        }
        state.aiNote?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Hint(
                if (unchecked > 0) "${state.rootLabel} · не выбрано: $unchecked (уверенность ниже 70%)" else state.rootLabel,
                modifier = Modifier.weight(1f), maxLines = 2,
            )
            TextButton(onClick = onDump) { Text("Снимок папки", maxLines = 1) }
        }
    }
}

@Composable
private fun StatCell(icon: String, value: Int, label: String, warn: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$icon $value",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Hint(label)
    }
}

/**
 * Block 2: one folder is one object. The whole header row opens and closes it; the chevron on the right
 * shows the state. The folder checkbox sits next to the name, file rows below are nested and lighter.
 */
@Composable
private fun FolderCard(
    folder: PlanFolder,
    items: List<PlanItem>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCheck: (Set<String>, Boolean) -> Unit,
    onRename: () -> Unit,
    onMove: (String, Set<String>) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val selected = items.count { it.checked }
    val notSelected = items.size - selected
    Card(Modifier.fillMaxWidth(), shape = CardShape, colors = cardColors()) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            ) {
                Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("📁") }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            folder.name,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            if (folder.existing) "  существующая" else "  новая",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (folder.desc.isNotEmpty()) Hint(folder.desc, maxLines = 2)
                    if (notSelected > 0) {
                        Text(
                            "⚠ не выбрано $notSelected из ${items.size}",
                            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Hint("файлов: ${items.size} · выбрано все")
                    }
                }
                val st = toggleState(items)
                TriStateCheckbox(state = st, onClick = { onCheck(items.map { it.file.id }.toSet(), st != ToggleableState.On) })
                Text(
                    if (expanded) "▾" else "▸",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 2.dp, end = 10.dp),
                )
            }
            if (!expanded) return@Column
            HorizontalDivider(Modifier.padding(horizontal = 12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            ) {
                Hint("Нажмите на заголовок, чтобы свернуть", modifier = Modifier.weight(1f))
                if (!folder.existing) {
                    IconButton(onClick = onRename) { Icon(Icons.Default.Edit, contentDescription = "Переименовать") }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Ещё") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Перенести все файлы в другую папку") }, onClick = {
                            menu = false; onMove("Все файлы из «${folder.name}»", items.map { it.file.id }.toSet())
                        })
                    }
                }
            }
            Row(Modifier.padding(start = 24.dp, end = 8.dp, bottom = 10.dp).height(IntrinsicSize.Min)) {
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.weight(1f)) {
                    val bundles = items.filter { it.bundle != null }.groupBy { it.bundle!! }
                    for ((name, group) in bundles) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                            val st = toggleState(group)
                            TriStateCheckbox(
                                state = st, modifier = Modifier.size(36.dp),
                                onClick = { onCheck(group.map { it.file.id }.toSet(), st != ToggleableState.On) },
                            )
                            Text("Набор: $name", modifier = Modifier.weight(1f).padding(start = 6.dp), fontWeight = FontWeight.Medium)
                            TextButton(onClick = { onMove("Набор «$name»", group.map { it.file.id }.toSet()) }) { Text("Перенести") }
                        }
                        group.forEach { FileRow(it, onCheck, Modifier.padding(start = 14.dp)) }
                    }
                    items.filter { it.bundle == null }.forEach { FileRow(it, onCheck) }
                }
            }
        }
    }
}

/** A thin row: small checkbox, type glyph, name, short reason and a quiet confidence number. */
@Composable
private fun FileRow(item: PlanItem, onCheck: (Set<String>, Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = item.stale == null) { onCheck(setOf(item.file.id), !item.checked) }
            .padding(start = 4.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Checkbox(
            checked = item.checked, enabled = item.stale == null,
            onCheckedChange = { onCheck(setOf(item.file.id), it) },
            modifier = Modifier.size(36.dp),
        )
        Text(fileGlyph(item.file.name), modifier = Modifier.padding(horizontal = 6.dp))
        Column(Modifier.weight(1f)) {
            Text(item.file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.stale != null) Hint("Устарело: ${item.stale}", color = MaterialTheme.colorScheme.error)
            else if (item.reason.isNotEmpty()) Hint(item.reason, maxLines = 1)
        }
        ConfidenceBadge(item.confidence)
    }
}

private fun fileGlyph(name: String): String = when (extension(name)) {
    "pdf" -> "📕"
    "xls", "xlsx", "csv", "ods" -> "📊"
    "ppt", "pptx" -> "📽️"
    "jpg", "jpeg", "png", "webp", "gif", "heic", "ico", "svg" -> "🖼️"
    "mp3", "wav", "ogg", "m4a", "flac", "opus" -> "🎵"
    "mp4", "mkv", "mov", "avi", "webm" -> "🎬"
    "zip", "rar", "7z", "tar", "gz" -> "📦"
    "apk", "xapk", "apks" -> "📱"
    "py", "js", "html", "css", "java", "kt", "json", "sql", "sh" -> "💻"
    else -> "📄"
}

private fun LazyListScope.leaveBlock(state: UiState, expanded: Boolean, onToggle: () -> Unit) {
    val leave = state.plan?.leave.orEmpty()
    if (leave.isEmpty()) return
    item(key = "leave") {
        Card(Modifier.fillMaxWidth(), shape = CardShape, colors = cardColors()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "⚠️ Не определено (${leave.size})",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Hint("Эти файлы останутся на месте" + if (!expanded) ". Нажмите, чтобы посмотреть." else "")
                    }
                    Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.headlineSmall)
                }
                if (expanded) {
                    leave.take(300).forEach { l ->
                        Column {
                            Text(l.file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Hint(l.reason)
                        }
                    }
                    if (leave.size > 300) Hint("…и ещё ${leave.size - 300}")
                }
            }
        }
    }
}

private fun LazyListScope.duplicatesBlock(state: UiState, expanded: Boolean, onToggle: () -> Unit) {
    val dups = state.duplicates
    if (dups.isEmpty()) return
    item(key = "dups") {
        Card(Modifier.fillMaxWidth(), shape = CardShape, colors = cardColors()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "👯 Возможные дубли (${dups.size} групп)",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        )
                        Hint("Одинаковое содержимое. Ничего не удаляется, решение за вами" + if (!expanded) ". Нажмите, чтобы посмотреть." else "")
                    }
                    Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.headlineSmall)
                }
                if (expanded) {
                    dups.forEach { group ->
                        HorizontalDivider()
                        Hint(formatSize(group.first().size))
                        group.forEach { Text(it.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DumpDialog(
    onDismiss: () -> Unit,
    onSaveText: () -> Unit,
    onSaveJson: () -> Unit,
    onShareText: () -> Unit,
    onShareJson: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Снимок папки") },
        text = {
            Column {
                Hint("Список файлов и папок до сортировки: имена, размеры, даты. Сами файлы не копируются.")
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onSaveText) { Text("Сохранить текстом в файл") }
                TextButton(onClick = onSaveJson) { Text("Сохранить JSON в файл") }
                TextButton(onClick = onShareText) { Text("Поделиться текстом") }
                TextButton(onClick = onShareJson) { Text("Поделиться JSON") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Имя папки") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it.take(40) }, singleLine = true)
                Hint("1–30 символов, без / \\ : * ? \" < > |")
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun MoveDialog(title: String, targets: List<PlanFolder>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (targets.isEmpty()) Text("Нет других папок в плане.")
            else Column {
                targets.forEach { f ->
                    Text(
                        f.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(f.name) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun PreviewScreen(vm: MainViewModel, state: UiState) {
    val p = state.preview ?: return
    val skippedByConflict = p.rows.count { it.finalName == null }
    ScreenScaffold(
        "Предпросмотр: Forganize",
        onBack = vm::back,
        bottomBar = {
            BottomAppBar {
                Text("Операций: ${p.rows.size - skippedByConflict}", modifier = Modifier.weight(1f).padding(start = 16.dp))
                Button(onClick = vm::apply, enabled = p.rows.size > skippedByConflict, modifier = Modifier.padding(end = 16.dp)) {
                    Text("Применить")
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Будет перемещено файлов: ${p.rows.size - skippedByConflict}")
                    Text("Будет создано папок: ${p.newFolders.size}" + if (p.newFolders.isNotEmpty()) " (${p.newFolders.joinToString()})" else "")
                    Hint("Ничего не удаляется и не перезаписывается. Все действия записываются в журнал и могут быть отменены.")
                }
            }
            if (p.conflicts.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SectionTitle("Конфликты имён (${p.conflicts.size})")
                            p.conflicts.forEach { c ->
                                Text(
                                    "${c.item.file.name} → ${c.item.folder}/: " +
                                        (c.resolvedName?.let { "будет сохранён как $it" } ?: "будет пропущен"),
                                )
                            }
                        }
                    }
                }
            }
            items(p.rows, key = { it.item.file.id }) { row ->
                Column {
                    Text(row.item.file.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Hint("→ ${row.item.folder}/" + (row.finalName?.takeIf { it != row.item.file.name } ?: if (row.finalName == null) " (пропуск)" else ""))
                }
            }
        }
    }
}

@Composable
fun ApplyScreen(vm: MainViewModel, state: UiState) {
    val a = state.apply
    ScreenScaffold("Применение", onBack = if (a.running) null else vm::back) { padding ->
        ScrollColumn(padding) {
            if (a.running) {
                Text("Перемещение файлов…", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(
                    progress = { if (a.total == 0) 0f else a.done.toFloat() / a.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${a.done} из ${a.total}")
                OutlinedButton(onClick = vm::stopApply) { Text("Остановить") }
                Hint("Остановка завершит текущую операцию.")
            }
            a.report?.let { r ->
                Text(
                    if (r.stopped) "Остановлено" else "Готово",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text("Выполнено ${r.done}, ошибок ${r.failed.size}, пропущено ${r.skipped.size}")
                if (r.createdDirs.isNotEmpty()) Hint("Созданы папки: ${r.createdDirs.joinToString()}")
                if (r.failed.isNotEmpty()) {
                    SectionTitle("Ошибки")
                    r.failed.forEach { Text("${it.name}: ${it.reason}".trimStart(':', ' ')) }
                }
                if (r.skipped.isNotEmpty()) {
                    SectionTitle("Пропущено")
                    r.skipped.forEach { Text("${it.name}: ${it.reason}") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::back) { Text("Готово") }
                    OutlinedButton(onClick = vm::openJournal) { Text("Журнал и отмена") }
                }
            }
        }
    }
}

@Composable
private fun RefineBox(vm: MainViewModel, state: UiState) {
    val r = state.refine
    var text by remember { mutableStateOf("") }
    Card(
        Modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("💡 Что поправить?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(500) },
                placeholder = { Text("Например: все PDF в Документы") },
                enabled = !r.running && state.refinesLeft > 0,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { vm.refine(text) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Hint(
                    if (state.refinesLeft > 0) "Правок ИИ осталось: ${state.refinesLeft}. Ваши ручные решения он не меняет."
                    else "Лимит правок ИИ исчерпан, правьте план вручную.",
                    modifier = Modifier.weight(1f),
                )
                if (r.running) CircularProgressIndicator(Modifier.padding(4.dp))
                else OutlinedButton(
                    onClick = { vm.refine(text) },
                    enabled = text.isNotBlank() && state.refinesLeft > 0,
                ) { Text("Отправить") }
            }
        }
    }
}

@Composable
private fun VersionsDialog(versions: List<VersionInfo>, onDismiss: () -> Unit, onRollback: (Int) -> Unit) {
    val fmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Версии плана") },
        text = {
            LazyColumn {
                items(versions.reversed(), key = { it.number }) { v ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("${v.number}. ${v.label}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Hint(fmt.format(Date(v.time)))
                        }
                        if (v.number != versions.last().number) {
                            TextButton(onClick = { onRollback(v.number) }) { Text("Откатить") }
                        } else Hint("текущая")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun SaveDialog(default: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val stamp = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date()) }
    var name by remember { mutableStateOf("$default, $stamp") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сохранить схему") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true, label = { Text("Название") })
                Hint("Схему можно открыть позже. Файлы, которые к тому времени исчезнут или изменятся, будут помечены и исключены. Экспорт в JSON или список доступен в меню.")
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun RefineDiffScreen(vm: MainViewModel, state: UiState) {
    val r = state.refine
    val result = r.result ?: return
    var confirmBig by remember(result) { mutableStateOf(false) }
    val needConfirm = result.bigOps.isNotEmpty()
    val oldFolder = remember(result) {
        result.before.items.associate { it.file.id to it.folder }
    }
    val names = remember(result) {
        (result.plan.items.map { it.file } + result.plan.leave.map { it.file }).associate { it.id to it.name }
    }
    ScreenScaffold(
        "Правка ИИ",
        onBack = vm::rejectRefine,
        bottomBar = {
            BottomAppBar {
                OutlinedButton(onClick = vm::rejectRefine, modifier = Modifier.padding(start = 16.dp)) { Text("Отклонить") }
                Column(Modifier.weight(1f)) {}
                Button(
                    onClick = vm::acceptRefine,
                    enabled = !needConfirm || confirmBig,
                    modifier = Modifier.padding(end = 16.dp),
                ) { Text("Принять") }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Hint("Ваша инструкция")
                    Text(r.instruction, style = MaterialTheme.typography.titleMedium)
                    if (r.note.isNotEmpty()) Text(r.note, color = MaterialTheme.colorScheme.primary)
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionTitle("Что изменится")
                        Text("Переедет файлов: ${result.movedFiles}")
                        if (result.createdFolders.isNotEmpty()) Text("Новые папки: ${result.createdFolders.joinToString()}")
                        if (result.renamedFolders.isNotEmpty()) {
                            Text("Переименование: " + result.renamedFolders.entries.joinToString { "${it.key} → ${it.value}" })
                        }
                        if (result.touchedFolders.isNotEmpty()) Hint("Затронуты папки: ${result.touchedFolders.joinToString()}")
                        Hint("План изменится только после «Принять».")
                    }
                }
            }
            if (needConfirm) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SectionTitle("Крупная правка")
                            result.bigOps.forEach { Text(it) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = confirmBig, onCheckedChange = { confirmBig = it })
                                Text("Понимаю, правка затрагивает больше половины файлов")
                            }
                        }
                    }
                }
            }
            if (result.applied.isNotEmpty()) {
                item { SectionTitle("Операции") }
                items(result.applied) { Text("• $it") }
            }
            if (result.skipped.isNotEmpty()) {
                item { SectionTitle("Не выполнено") }
                items(result.skipped) { Hint("• $it") }
            }
            if (result.changedFiles.isNotEmpty()) {
                item { SectionTitle("Файлы (${result.changedFiles.size})") }
                items(result.changedFiles.entries.take(300).toList(), key = { it.key }) { (id, to) ->
                    Column {
                        Text(names[id] ?: id, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Hint("${oldFolder[id] ?: "Не определено"} → ${to ?: "Не определено"}")
                    }
                }
                if (result.changedFiles.size > 300) item { Hint("…и ещё ${result.changedFiles.size - 300}") }
            }
        }
    }
}

@Composable
fun SavedScreen(vm: MainViewModel, state: UiState) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf<com.forganizer.app.data.SavedPlanInfo?>(null) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    ScreenScaffold("Сохранённые схемы", onBack = vm::back) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.savedPlans.isEmpty()) item { Text("Сохранённых схем пока нет. Сохраните план на экране «Картина папки».") }
            items(state.savedPlans, key = { it.id }) { p ->
                var menu by remember { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Hint(com.forganizer.app.fs.Access.label(p.rootId) + " · " + fmt.format(Date(p.time)))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { vm.openSaved(p) }) { Text("Открыть") }
                            Column(Modifier.weight(1f)) {}
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Ещё") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Поделиться JSON") }, onClick = {
                                    menu = false
                                    scope.launch {
                                        vm.savedExport(p, json = true)?.let { ExportHelper.share(context, "forganizer-plan.json", "application/json", it) }
                                    }
                                })
                                DropdownMenuItem(text = { Text("Поделиться списком") }, onClick = {
                                    menu = false
                                    scope.launch {
                                        vm.savedExport(p, json = false)?.let { ExportHelper.share(context, "forganizer-plan.txt", "text/plain", it) }
                                    }
                                })
                                DropdownMenuItem(text = { Text("Удалить схему") }, onClick = { menu = false; confirmDelete = p })
                            }
                        }
                    }
                }
            }
        }
    }
    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Удалить схему?") },
            text = { Text("Удаляется только сохранённая схема «${p.name}» в приложении. Файлы не затрагиваются.") },
            confirmButton = { TextButton(onClick = { confirmDelete = null; vm.deleteSaved(p) }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Отмена") } },
        )
    }
}
