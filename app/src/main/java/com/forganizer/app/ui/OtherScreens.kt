package com.forganizer.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.Switch
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.forganizer.app.BuildConfig
import com.forganizer.app.data.SettingsStore
import com.forganizer.app.fs.Access
import com.forganizer.core.ConflictMode
import kotlinx.coroutines.delay
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
                    state.emptyDirs?.let { p ->
                        Text("Папки, созданные приложением, теперь пусты. Удалить их?")
                        Text(p.names.joinToString("\n") { "📁 $it" }, fontWeight = FontWeight.SemiBold)
                        Hint("Удалятся только эти пустые папки. Если в какой-то уже что-то лежит, она останется.")
                    } ?: if (r.createdDirs.isNotEmpty()) {
                        Text("Папки, созданные приложением, остались на месте:")
                        Hint(r.createdDirs.joinToString())
                    } else Unit
                }
            },
            confirmButton = {
                if (state.emptyDirs != null) TextButton(onClick = { vm.removeEmptyDirs(); vm.dismissMessage() }) { Text("Да, удалить") }
                else TextButton(onClick = vm::dismissMessage) { Text("OK") }
            },
            dismissButton = {
                if (state.emptyDirs != null) TextButton(onClick = vm::dismissMessage) { Text("Нет, я сам") }
            },
        )
    }
}

private data class Draft(val exts: String, val dirs: String, val days: String, val server: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, state: UiState) {
    val s = state.settings
    var exts by remember { mutableStateOf(s.ignoreExtensions.joinToString(", ")) }
    var dirs by remember { mutableStateOf(s.ignoreFolders.joinToString(", ")) }
    var days by remember { mutableStateOf(s.oldDays.toString()) }
    var server by remember { mutableStateOf(s.serverUrl) }
    var confirmClear by remember { mutableStateOf(false) }

    // Autosave: shortly after typing stops, and once more when the screen is left.
    val latest by rememberUpdatedState(Draft(exts, dirs, days, server))
    LaunchedEffect(exts, dirs, days, server) {
        delay(700)
        vm.saveDraft(exts, dirs, days, server)
    }
    DisposableEffect(Unit) {
        onDispose { latest.let { vm.saveDraft(it.exts, it.dirs, it.days, it.server) } }
    }

    ScreenScaffold("Настройки", onBack = vm::back) { padding ->
        ScrollColumn(padding) {
            SettingsSection("Анализ") {
                SwitchRow(
                    title = "Класть в существующие папки",
                    hint = "Предлагать уже созданные категории",
                    checked = s.allowExisting,
                ) { v -> vm.updateSettings { it.copy(allowExisting = v) } }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                SwitchRow(
                    title = "Заглядывать в ZIP-архивы",
                    hint = "Имена внутри архива помогают ИИ, содержимое не читается",
                    checked = s.peekArchives,
                ) { v -> vm.updateSettings { it.copy(peekArchives = v) } }
            }

            SettingsSection("Правила анализа") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SolidField("Игнорируемые расширения", exts, { exts = it }, "nomedia, tmp, crdownload")
                    SolidField("Игнорируемые папки", dirs, { dirs = it }, "Через запятую")
                    SolidField(
                        "Старые установщики (дней)", days, { days = it.filter(Char::isDigit).take(4) }, "90",
                        keyboardType = KeyboardType.Number,
                    )
                }
            }

            SettingsSection("Конфликт имён") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val modes = ConflictMode.entries
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        modes.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = s.conflictMode == mode,
                                onClick = { vm.updateSettings { it.copy(conflictMode = mode) } },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                                icon = {},
                            ) {
                                Text(if (mode == ConflictMode.RENAME) "Переименовать" else "Пропустить", maxLines = 1)
                            }
                        }
                    }
                    Hint(
                        if (s.conflictMode == ConflictMode.RENAME) "Новый файл сохранится как name (1).ext"
                        else "Файл с таким именем будет пропущен",
                    )
                }
            }

            SettingsSection("Сервер ИИ") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SolidField("Адрес сервера", server, { server = it.trim() }, BuildConfig.SERVER_URL, keyboardType = KeyboardType.Uri)
                    OutlinedButton(
                        onClick = { vm.saveDraft(exts, dirs, days, server); vm.checkServer() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Проверить соединение", maxLines = 1) }
                }
            }

            SettingsSection("Доступ и данные") {
                SettingsListItem(
                    icon = Icons.Default.Settings,
                    title = "Режим доступа к файлам",
                    subtitle = when (state.mode) {
                        Mode.FULL -> "Доступ ко всем файлам"
                        Mode.SAF -> "Выбранная папка"
                        null -> "Нет доступа"
                    },
                    onClick = vm::openAccess,
                )
                HorizontalDivider(Modifier.padding(start = 56.dp))
                SettingsListItem(
                    icon = Icons.Default.Search,
                    title = "Диагностика доступа",
                    subtitle = "Какие папки видит приложение",
                    onClick = vm::runAccessDiagnostics,
                )
                HorizontalDivider(Modifier.padding(start = 56.dp))
                SettingsListItem(
                    icon = Icons.Default.Lock,
                    title = "Отозвать согласие на отправку",
                    subtitle = "Имена файлов перестанут уходить на сервер",
                    onClick = vm::revokeConsent,
                )
                HorizontalDivider(Modifier.padding(start = 56.dp))
                SettingsListItem(
                    icon = Icons.Default.Delete,
                    title = "Очистить данные",
                    subtitle = "Журнал, схемы и настройки",
                    danger = true,
                    onClick = { confirmClear = true },
                )
            }

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Hint("Forganizer v${BuildConfig.VERSION_NAME}")
            }
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
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
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

/** Title and a switch on one line, a short description below. */
@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(hint)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A filled field: the label sits above it, no outline, a soft background. */
@Composable
private fun SolidField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        TextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(placeholder, maxLines = 1) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
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
