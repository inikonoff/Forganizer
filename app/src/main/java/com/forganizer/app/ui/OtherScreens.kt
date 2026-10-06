package com.forganizer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Класть в существующие папки")
                    Hint("Разрешить ИИ предлагать уже существующие папки (allow_existing)")
                }
                Switch(checked = s.allowExisting, onCheckedChange = { v -> vm.updateSettings { it.copy(allowExisting = v) } })
            }
            HorizontalDivider()
            OutlinedTextField(
                value = exts, onValueChange = { exts = it },
                label = { Text("Игнорируемые расширения") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = dirs, onValueChange = { dirs = it },
                label = { Text("Игнорируемые папки") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = days, onValueChange = { days = it.filter(Char::isDigit).take(4) },
                label = { Text("Старые установщики: старше N дней") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {
                vm.updateSettings {
                    it.copy(
                        ignoreExtensions = SettingsStore.splitList(exts),
                        ignoreFolders = SettingsStore.splitList(dirs),
                        oldDays = days.toIntOrNull()?.coerceIn(1, 3650) ?: it.oldDays,
                    )
                }
            }) { Text("Сохранить правила анализа") }
            HorizontalDivider()
            SectionTitle("Конфликт имён при перемещении")
            for (mode in ConflictMode.entries) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = s.conflictMode == mode, onClick = { vm.updateSettings { it.copy(conflictMode = mode) } }),
                ) {
                    RadioButton(selected = s.conflictMode == mode, onClick = { vm.updateSettings { it.copy(conflictMode = mode) } })
                    Text(MainViewModel.CONFLICT_LABELS.getValue(mode))
                }
            }
            Hint("Перезапись запрещена всегда.")
            HorizontalDivider()
            OutlinedTextField(
                value = server, onValueChange = { server = it.trim() },
                label = { Text("Адрес сервера (пусто = по умолчанию)") },
                placeholder = { Text(BuildConfig.SERVER_URL) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { vm.updateSettings { it.copy(serverUrl = server) } }) { Text("Сохранить адрес") }
            HorizontalDivider()
            SectionTitle("Доступ и данные")
            Hint(
                "Режим: " + when (state.mode) {
                    Mode.FULL -> "доступ ко всем файлам"
                    Mode.SAF -> "выбранная папка"
                    null -> "нет доступа"
                }
            )
            OutlinedButton(onClick = vm::openAccess) { Text("Сменить режим доступа") }
            OutlinedButton(onClick = vm::revokeConsent) { Text("Отозвать согласие на отправку имён") }
            OutlinedButton(onClick = { confirmClear = true }) { Text("Очистить данные (журнал, настройки)") }
            Hint("Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR})")
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить данные?") },
            text = { Text("Журнал применений и настройки будут удалены. После этого отменить прошлые применения будет нельзя. Ваши файлы не затрагиваются.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearData() }) { Text("Очистить") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}
