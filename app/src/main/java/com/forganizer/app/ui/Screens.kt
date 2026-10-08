package com.forganizer.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.forganizer.app.fs.Access

@Composable
fun App(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    BackHandler(enabled = vm.canGoBack()) { vm.back() }

    when (state.screen) {
        Screen.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        Screen.CONSENT -> ConsentScreen(vm)
        Screen.ACCESS -> AccessScreen(vm, state)
        Screen.FOLDER -> FolderScreen(vm, state)
        Screen.SCAN -> ScanScreen(vm, state)
        Screen.PICTURE -> PictureScreen(vm, state)
        Screen.REFINE_DIFF -> RefineDiffScreen(vm, state)
        Screen.SAVED -> SavedScreen(vm, state)
        Screen.PREVIEW -> PreviewScreen(vm, state)
        Screen.APPLY -> ApplyScreen(vm, state)
        Screen.JOURNAL -> JournalScreen(vm, state)
        Screen.SETTINGS -> SettingsScreen(vm, state)
    }

    state.message?.let { msg ->
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = vm::dismissMessage,
            confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("OK") } },
            dismissButton = {
                if (msg.length > 120) TextButton(onClick = { clipboard.setText(AnnotatedString(msg)) }) { Text("Копировать") }
            },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(msg) } },
        )
    }
}

@Composable
private fun ConsentScreen(vm: MainViewModel) {
    ScreenScaffold("Forganizer") { padding ->
        ScrollColumn(padding) {
            Text("ИИ-помощник по организации папок", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Приложение смотрит на файлы в корне выбранной папки, находит связанные по смыслу и " +
                    "предлагает, как разложить их по папкам."
            )
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("Что отправляется")
                    Text(
                        "Для анализа имена файлов, расширения, размеры и даты отправляются на сервер и в " +
                            "сторонние ИИ-сервисы. Содержимое файлов не отправляется."
                    )
                    Text(
                        "Для zip-архивов дополнительно отправляется краткая сводка имён внутри (число файлов, " +
                            "корневые папки, типы файлов). Сами файлы из архива не читаются. Это можно отключить в настройках."
                    )
                    SectionTitle("Что не отправляется")
                    Text("Содержимое файлов, пути на устройстве, данные аккаунтов. Поиск дублей выполняется только на устройстве.")
                    SectionTitle("Гарантии")
                    Text(
                        "Приложение ничего не удаляет. Существующие папки не трогаются. Перемещение файлов возможно " +
                            "только после предпросмотра и записывается в журнал с возможностью отмены."
                    )
                }
            }
            Hint("Согласие можно отозвать в настройках.")
            Button(onClick = { vm.acceptConsent() }, modifier = Modifier.fillMaxWidth()) { Text("Согласен") }
        }
    }
}

@Composable
private fun AccessScreen(vm: MainViewModel, state: UiState) {
    val context = LocalContext.current
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onTreePicked(it) }
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onResume() }
    ScreenScaffold("Доступ к файлам", onBack = if (state.mode != null) vm::back else null) { padding ->
        ScrollColumn(padding) {
            if (Access.allFilesSupported) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle("Доступ ко всем файлам")
                        Text("Работает с корнем «Загрузок» и других стандартных папок. Перемещение быстрое, без копирования.")
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                try {
                                    context.startActivity(Access.allFilesIntent(context))
                                } catch (e: ActivityNotFoundException) {
                                    context.startActivity(Access.fallbackAllFilesIntent())
                                }
                            } else {
                                legacyPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                        }) { Text("Разрешить доступ ко всем файлам") }
                    }
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("Выбрать папку")
                    Text("Доступ только к одной выбранной папке через системный диалог.")
                    Hint("Корень «Загрузок» в этом режиме недоступен: выберите подпапку или включите полный режим.")
                    OutlinedButton(onClick = { treePicker.launch(null) }) { Text("Выбрать папку") }
                }
            }
        }
    }
}

@Composable
private fun FolderScreen(vm: MainViewModel, state: UiState) {
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onTreePicked(it) }
    ScreenScaffold(
        "Выбор папки",
        actions = {
            IconButton(onClick = vm::openJournal) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Журнал") }
            IconButton(onClick = vm::openSettings) { Icon(Icons.Default.Settings, contentDescription = "Настройки") }
        },
    ) { padding ->
        ScrollColumn(padding) {
            OutlinedButton(onClick = vm::openSavedList, modifier = Modifier.fillMaxWidth()) { Text("Сохранённые схемы") }
            if (state.mode == Mode.FULL) {
                Text("Какую папку разобрать?")
                for (f in Access.standardFolders()) {
                    Button(onClick = { vm.startScanFull(f.dir) }, modifier = Modifier.fillMaxWidth()) { Text(f.label) }
                }
                OutlinedButton(onClick = { treePicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Другая папка…") }
                Hint("Анализируется только корень папки. Вложенные папки и файлы в них не трогаются.")
            } else {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle("Выбранная папка")
                        Text(state.treeLabel ?: "—")
                        Button(onClick = vm::startScanSaf, modifier = Modifier.fillMaxWidth()) { Text("Анализировать") }
                        OutlinedButton(onClick = { treePicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Выбрать другую папку") }
                    }
                }
                Hint("Корень «Загрузок» недоступен, выберите подпапку или включите полный режим.")
                if (Access.allFilesSupported) {
                    TextButton(onClick = vm::openAccess) { Text("Включить полный режим") }
                }
            }
        }
    }
}

@Composable
private fun ScanScreen(vm: MainViewModel, state: UiState) {
    val s = state.stats
    ScreenScaffold("Анализ: ${state.rootLabel}", onBack = vm::back) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.scanError == null) {
                Text(s.stage, style = MaterialTheme.typography.titleMedium)
                if (s.aiTotal > 0) {
                    LinearProgressIndicator(progress = { s.aiDone.toFloat() / s.aiTotal }, modifier = Modifier.fillMaxWidth())
                    Hint("Запросов к ИИ: ${s.aiDone} из ${s.aiTotal}. Бесплатный сервер может просыпаться до минуты.")
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatRow("Файлов", s.files)
                    StatRow("Кластеров", s.clusters)
                    if (s.archives > 0) StatRow("Архивов просмотрено", s.archives)
                    StatRow("Возможных дублей", s.duplicates)
                    if (s.skipped > 0) StatRow("Недоступно (пропущено)", s.skipped)
                }
            }
            state.scanError?.let { err ->
                Text(err, color = MaterialTheme.colorScheme.error)
                Hint("Локальная сводка сохранена.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::retryAi) { Text("Повторить") }
                    OutlinedButton(onClick = vm::withoutAi) { Text("Показать без ИИ") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatRow(label: String, value: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value.toString(), style = MaterialTheme.typography.titleSmall)
    }
}
