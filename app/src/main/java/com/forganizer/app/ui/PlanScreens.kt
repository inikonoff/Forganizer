package com.forganizer.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forganizer.core.FolderNames
import com.forganizer.core.PlanFolder
import com.forganizer.core.PlanItem

private sealed interface Dialog {
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
    val checked = plan.checkedItems.size

    ScreenScaffold(
        "Картина папки",
        onBack = vm::back,
        actions = {
            IconButton(onClick = { exportMenu = true }) { Icon(Icons.Default.Share, contentDescription = "Экспорт") }
            DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
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
            BottomAppBar {
                Text(
                    "Выбрано: $checked из ${plan.items.size}",
                    modifier = Modifier.weight(1f).padding(start = 16.dp),
                )
                Button(onClick = vm::openPreview, enabled = checked > 0, modifier = Modifier.padding(end = 16.dp)) {
                    Text("Предпросмотр")
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
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.rootLabel, style = MaterialTheme.typography.titleMedium)
                    Hint("Папок: ${plan.folders.size}, файлов в плане: ${plan.items.size}, не определено: ${plan.leave.size}")
                    Hint("Записи с уверенностью ниже 70% по умолчанию не выбраны.")
                    state.aiNote?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            for (folder in plan.folders) {
                item(key = "folder:" + folder.name) {
                    FolderCard(
                        folder = folder,
                        items = plan.itemsIn(folder.name),
                        onCheck = { ids, c -> vm.setChecked(ids, c) },
                        onRename = { dialog = Dialog.Rename(folder.name) },
                        onMove = { title, ids -> dialog = Dialog.Move(title, ids, folder.name) },
                    )
                }
            }
            leaveBlock(state)
            duplicatesBlock(state)
        }
    }

    when (val d = dialog) {
        is Dialog.Rename -> RenameDialog(d.folder, onDismiss = { dialog = null }) { if (vm.renameFolder(d.folder, it)) dialog = null }
        is Dialog.Move -> MoveDialog(d.title, plan.folders.filter { FolderNames.key(it.name) != FolderNames.key(d.from) }, onDismiss = { dialog = null }) {
            vm.moveFiles(d.ids, it); dialog = null
        }
        null -> Unit
    }
}

@Composable
private fun FolderCard(
    folder: PlanFolder,
    items: List<PlanItem>,
    onCheck: (Set<String>, Boolean) -> Unit,
    onRename: () -> Unit,
    onMove: (String, Set<String>) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                val st = toggleState(items)
                TriStateCheckbox(state = st, onClick = { onCheck(items.map { it.file.id }.toSet(), st != ToggleableState.On) })
                Column(Modifier.weight(1f)) {
                    Text(
                        folder.name + if (folder.existing) " (существующая)" else " (новая)",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    )
                    val sub = listOf(folder.desc, "файлов: ${items.size}").filter { it.isNotEmpty() }.joinToString(" · ")
                    Hint(sub)
                }
                if (!folder.existing) {
                    IconButton(onClick = onRename) { Icon(Icons.Default.Edit, contentDescription = "Переименовать") }
                }
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Ещё") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Перенести все файлы в другую папку") }, onClick = {
                        menu = false; onMove("Все файлы из «${folder.name}»", items.map { it.file.id }.toSet())
                    })
                }
            }
            val bundles = items.filter { it.bundle != null }.groupBy { it.bundle!! }
            for ((name, group) in bundles) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 4.dp)) {
                    val st = toggleState(group)
                    TriStateCheckbox(state = st, onClick = { onCheck(group.map { it.file.id }.toSet(), st != ToggleableState.On) })
                    Text("Набор: $name", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    TextButton(onClick = { onMove("Набор «$name»", group.map { it.file.id }.toSet()) }) { Text("Перенести") }
                }
                group.forEach { FileRow(it, indent = 24, onCheck) }
            }
            val singles = items.filter { it.bundle == null }
            if (singles.isNotEmpty() && bundles.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            singles.forEach { FileRow(it, indent = 0, onCheck) }
        }
    }
}

@Composable
private fun FileRow(item: PlanItem, indent: Int, onCheck: (Set<String>, Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheck(setOf(item.file.id), !item.checked) }
            .padding(start = (8 + indent).dp, end = 12.dp),
    ) {
        Checkbox(checked = item.checked, onCheckedChange = { onCheck(setOf(item.file.id), it) })
        Column(Modifier.weight(1f)) {
            Text(item.file.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.reason.isNotEmpty()) Hint(item.reason)
        }
        ConfidenceBadge(item.confidence)
    }
}

private fun LazyListScope.leaveBlock(state: UiState) {
    val leave = state.plan?.leave.orEmpty()
    if (leave.isEmpty()) return
    item(key = "leave") {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("Не определено (${leave.size})")
                Hint("Эти файлы останутся на месте.")
                leave.take(300).forEach { l ->
                    Column {
                        Text(l.file.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Hint(l.reason)
                    }
                }
                if (leave.size > 300) Hint("…и ещё ${leave.size - 300}")
            }
        }
    }
}

private fun LazyListScope.duplicatesBlock(state: UiState) {
    val dups = state.duplicates
    if (dups.isEmpty()) return
    item(key = "dups") {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("Возможные дубли (${dups.size} групп)")
                Hint("Одинаковое содержимое. Приложение ничего не удаляет, решение за вами.")
                dups.forEach { group ->
                    HorizontalDivider()
                    Hint(formatSize(group.first().size))
                    group.forEach { Text(it.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
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
        "Предпросмотр",
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
