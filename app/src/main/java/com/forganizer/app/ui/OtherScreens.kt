package com.forganizer.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.forganizer.app.BuildConfig
import com.forganizer.app.data.SettingsStore
import com.forganizer.app.fs.Access
import com.forganizer.core.ConflictMode
import java.text.DateFormat
import java.util.Date

@Composable
fun JournalScreen(vm: MainViewModel, state: UiState) {
    var confirm by remember { mutableStateOf<com.forganizer.app.data.SessionSummary?>(null) }
    ScreenScaffold("Журнал", onBack = vm::back) { padding ->
        ScrollColumn(padding) {
            if (state.undoRunning) {
                Text("Отмена…")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.sessions.isEmpty()) Text("Применений пока не было.")
            val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            state.sessions.forEach { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(fmt.format(Date(s.started)), style = MaterialTheme.typography.titleSmall)
                        Text(Access.label(s.rootId))
                        Hint("Перемещено: ${s.moved}, отменено: ${s.undone}, ошибок: ${s.failed}, создано папок: ${s.dirs}")
                        if (s.moved > 0) {
                            OutlinedButton(onClick = { confirm = s }, enabled = !state.undoRunning) { Text("Отменить") }
                        }
                    }
                }
            }
        }
    }
    confirm?.let { s ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Отменить применение?") },
            text = { Text("${s.moved} файлов будут возвращены на исходные места. Если имя занято, будет добавлен суффикс.") },
            confirmButton = { TextButton(onClick = { confirm = null; vm.undo(s) }) { Text("Отменить применение") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Нет") } },
        )
    }
    state.undoReport?.let { r ->
        AlertDialog(
            onDismissRequest = vm::dismissMessage,
            title = { Text("Отмена выполнена") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Возвращено файлов: ${r.restored}, ошибок: ${r.failed.size}")
                    r.failed.take(20).forEach { Hint("${it.name}: ${it.reason}") }
                    if (r.createdDirs.isNotEmpty()) {
                        Text("Папки, созданные приложением, остались на месте (приложение ничего не удаляет):")
                        Hint(r.createdDirs.joinToString())
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("OK") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, state: UiState) {
    val s = state.settings
    var exts by remember(s.ignoreExtensions) { mutableStateOf(s.ignoreExtensions.joinToString(", ")) }
    var dirs by remember(s.ignoreFolders) { mutableStateOf(s.ignoreFolders.joinToString(", ")) }
    var days by remember(s.oldDays) { mutableStateOf(s.oldDays.toString()) }
    var server by remember(s.serverUrl) { mutableStateOf(s.serverUrl) }
    var confirmClear by remember { mutableStateOf(false) }

    ScreenScaffold("Настройки", onBack = vm::back) { padding ->
        ScrollColumn(padding) {
            SettingsSection("Анализ") {
                CheckRow(
                    checked = s.allowExisting,
                    title = "Класть в существующие папки",
                    hint = "Разрешить ИИ предлагать уже существующие папки.",
                ) { v -> vm.updateSettings { it.copy(allowExisting = v) } }
                HorizontalDivider()
                CheckRow(
                    checked = s.peekArchives,
                    title = "Заглядывать в zip-архивы",
                    hint = "Для zip отправляется краткая сводка имён внутри (число файлов, корневые папки, типы). Содержимое файлов не читается.",
                ) { v -> vm.updateSettings { it.copy(peekArchives = v) } }
            }

            SettingsSection("Правила анализа") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = exts, onValueChange = { exts = it },
                        label = { Text("Игнорируемые расширения") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = dirs, onValueChange = { dirs = it },
                        label = { Text("Игнорируемые папки") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = days, onValueChange = { days = it.filter(Char::isDigit).take(4) },
                        label = { Text("Старые установщики, дней") },
                        supportingText = { Text("APK старше этого срока попадают в «Старые установщики»") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            vm.updateSettings {
                                it.copy(
                                    ignoreExtensions = SettingsStore.splitList(exts),
                                    ignoreFolders = SettingsStore.splitList(dirs),
                                    oldDays = days.toIntOrNull()?.coerceIn(1, 3650) ?: it.oldDays,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Сохранить") }
                }
            }

            SettingsSection("Если имя файла уже занято") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val modes = ConflictMode.entries
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        modes.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = s.conflictMode == mode,
                                onClick = { vm.updateSettings { it.copy(conflictMode = mode) } },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                            ) {
                                Text(if (mode == ConflictMode.RENAME) "Переименовать" else "Пропустить", maxLines = 1)
                            }
                        }
                    }
                    Hint(
                        (if (s.conflictMode == ConflictMode.RENAME) "Новый файл сохранится как name (1).ext. " else "Файл с таким именем будет пропущен. ") +
                            "Перезапись запрещена всегда.",
                    )
                }
            }

            SettingsSection("Сервер ИИ") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = server, onValueChange = { server = it.trim() },
                        label = { Text("Адрес сервера") },
                        placeholder = { Text(BuildConfig.SERVER_URL) },
                        supportingText = { Text("Пусто: адрес по умолчанию") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { vm.updateSettings { it.copy(serverUrl = server) } },
                            modifier = Modifier.weight(1f),
                        ) { Text("Сохранить", maxLines = 1) }
                        OutlinedButton(onClick = vm::checkServer, modifier = Modifier.weight(1f)) {
                            Text("Проверить", maxLines = 1)
                        }
                    }
                    Hint("Сначала сохраните адрес, затем нажмите «Проверить»: приложение покажет, видит ли оно сервер и какие модели отвечают.")
                }
            }

            SettingsSection("Доступ и данные") {
                SettingsListItem(
                    icon = Icons.Default.Lock,
                    title = "Режим доступа к файлам",
                    subtitle = when (state.mode) {
                        Mode.FULL -> "Сейчас: доступ ко всем файлам"
                        Mode.SAF -> "Сейчас: выбранная папка"
                        null -> "Сейчас: нет доступа"
                    },
                    onClick = vm::openAccess,
                )
                HorizontalDivider(Modifier.padding(start = 56.dp))
                SettingsListItem(
                    icon = Icons.Default.Search,
                    title = "Диагностика доступа",
                    subtitle = "Показывает, какие папки видит приложение. Отчёт можно скопировать",
                    onClick = vm::runAccessDiagnostics,
                )
                HorizontalDivider(Modifier.padding(start = 56.dp))
                SettingsListItem(
                    icon = Icons.Default.Info,
                    title = "Отозвать согласие",
                    subtitle = "Приложение перестанет отправлять имена файлов, пока вы не согласитесь снова",
                    onClick = vm::revokeConsent,
                )
                HorizontalDivider(Modifier.padding(start = 56.dp))
                SettingsListItem(
                    icon = Icons.Default.Delete,
                    title = "Очистить данные",
                    subtitle = "Журнал, сохранённые схемы и настройки. Ваши файлы не затрагиваются",
                    danger = true,
                    onClick = { confirmClear = true },
                )
            }

            Hint("Forganizer ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR})", modifier = Modifier.padding(start = 4.dp))
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить данные?") },
            text = { Text("Журнал применений, сохранённые схемы и настройки будут удалены. После этого отменить прошлые применения будет нельзя. Ваши файлы не затрагиваются.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; vm.clearData() }) {
                    Text("Очистить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}

/** A titled group of settings on a softly tinted, rounded card. */
@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun CheckRow(checked: Boolean, title: String, hint: String, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title)
            Hint(hint)
        }
    }
}

/** A classic settings row: icon, title, subtitle and a chevron. Dangerous actions use the error color. */
@Composable
private fun SettingsListItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Icon(
            icon, contentDescription = null,
            tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Hint(subtitle)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
