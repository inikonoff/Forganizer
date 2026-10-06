package com.forganizer.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.forganizer.app.ForganizerApp
import com.forganizer.app.data.AppSettings
import com.forganizer.app.data.SessionSummary
import com.forganizer.app.fs.Access
import com.forganizer.app.fs.FileBackend
import com.forganizer.app.fs.SafBackend
import com.forganizer.core.AccessLostException
import com.forganizer.core.AiPlanner
import com.forganizer.core.AiRequestException
import com.forganizer.core.AiUnavailableException
import com.forganizer.core.ApplyReport
import com.forganizer.core.Applier
import com.forganizer.core.Clusterer
import com.forganizer.core.ConflictMode
import com.forganizer.core.Conflicts
import com.forganizer.core.DuplicateFinder
import com.forganizer.core.FileNode
import com.forganizer.core.FileSource
import com.forganizer.core.FolderNames
import com.forganizer.core.MoveOp
import com.forganizer.core.NameConflict
import com.forganizer.core.NodeRef
import com.forganizer.core.OrganizePlan
import com.forganizer.core.PlanExport
import com.forganizer.core.PlanItem
import com.forganizer.core.PlanLeave
import com.forganizer.core.ScanResult
import com.forganizer.core.ScanSettings
import com.forganizer.core.Scanner
import com.forganizer.core.Summary
import com.forganizer.core.UndoReport
import com.forganizer.core.ValidatedPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class Screen { LOADING, CONSENT, ACCESS, FOLDER, SCAN, PICTURE, PREVIEW, APPLY, JOURNAL, SETTINGS }
enum class Mode { FULL, SAF }

data class ScanStats(
    val stage: String = "",
    val files: Int = 0,
    val clusters: Int = 0,
    val duplicates: Int = 0,
    val skipped: Int = 0,
    val aiDone: Int = 0,
    val aiTotal: Int = 0,
)

data class PreviewRow(val item: PlanItem, val finalName: String?)

data class PreviewData(val rows: List<PreviewRow>, val newFolders: List<String>, val conflicts: List<NameConflict>)

data class ApplyState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val report: ApplyReport? = null,
)

data class UiState(
    val screen: Screen = Screen.LOADING,
    val settings: AppSettings = AppSettings(),
    val mode: Mode? = null,
    val treeLabel: String? = null,
    val rootLabel: String = "",
    val stats: ScanStats = ScanStats(),
    val scanError: String? = null,
    val plan: OrganizePlan? = null,
    val aiNote: String? = null,
    val duplicates: List<List<FileNode>> = emptyList(),
    val preview: PreviewData? = null,
    val apply: ApplyState = ApplyState(),
    val sessions: List<SessionSummary> = emptyList(),
    val undoRunning: Boolean = false,
    val undoReport: UndoReport? = null,
    val message: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ForganizerApp
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var source: FileSource? = null
    private var root: NodeRef? = null
    private var scan: ScanResult? = null
    private var summary: Summary? = null
    private var job: Job? = null
    private val stopFlag = AtomicBoolean(false)
    private var returnTo: Screen = Screen.FOLDER

    init {
        viewModelScope.launch {
            app.settings.flow.collect { s ->
                app.serverUrlOverride = s.serverUrl
                _state.update { it.copy(settings = s) }
            }
        }
        viewModelScope.launch { route() }
    }

    private suspend fun route() {
        val s = app.settings.current()
        val mode = detectMode(s)
        _state.update {
            it.copy(
                mode = mode,
                treeLabel = s.treeUri?.let { u -> Access.label(SafBackend(app, Uri.parse(u)).root.id) },
                screen = when {
                    !s.consent -> Screen.CONSENT
                    mode == null -> Screen.ACCESS
                    else -> Screen.FOLDER
                },
            )
        }
    }

    private fun detectMode(s: AppSettings): Mode? = when {
        Access.hasAllFiles(app) -> Mode.FULL
        s.treeUri != null && Access.hasTreePermission(app, Uri.parse(s.treeUri)) -> Mode.SAF
        else -> null
    }

    fun onResume() {
        val screen = _state.value.screen
        if (screen == Screen.ACCESS || screen == Screen.FOLDER) viewModelScope.launch { route() }
    }

    fun dismissMessage() = _state.update { it.copy(message = null, undoReport = null) }

    // --- consent & access -------------------------------------------------------------------

    fun acceptConsent() = viewModelScope.launch {
        app.settings.update { it.copy(consent = true) }
        route()
    }

    fun onTreePicked(uri: Uri?) {
        if (uri == null) return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { app.contentResolver.takePersistableUriPermission(uri, flags) }
        viewModelScope.launch {
            if (Access.hasAllFiles(app)) {
                val path = Access.treeToPath(uri)
                if (path != null && path.isDirectory) {
                    startScanFull(path); return@launch
                }
            }
            app.settings.update { it.copy(treeUri = uri.toString()) }
            route()
        }
    }

    // --- navigation ---------------------------------------------------------------------------

    fun openJournal() {
        returnTo = _state.value.screen
        _state.update { it.copy(screen = Screen.JOURNAL) }
        loadSessions()
    }

    fun openSettings() {
        returnTo = _state.value.screen
        _state.update { it.copy(screen = Screen.SETTINGS) }
    }

    fun openAccess() = _state.update { it.copy(screen = Screen.ACCESS) }

    fun canGoBack(): Boolean = when (_state.value.screen) {
        Screen.SCAN, Screen.PICTURE, Screen.PREVIEW, Screen.JOURNAL, Screen.SETTINGS -> true
        Screen.APPLY -> !_state.value.apply.running
        Screen.ACCESS -> _state.value.mode != null
        else -> false
    }

    fun back() {
        val s = _state.value
        val target = when (s.screen) {
            Screen.SCAN -> { job?.cancel(); Screen.FOLDER }
            Screen.PICTURE -> Screen.FOLDER
            Screen.PREVIEW -> Screen.PICTURE
            Screen.APPLY -> if (s.apply.running) return else Screen.FOLDER
            Screen.JOURNAL, Screen.SETTINGS -> returnTo.takeIf { it != Screen.JOURNAL && it != Screen.SETTINGS } ?: Screen.FOLDER
            Screen.ACCESS -> if (s.mode != null) Screen.FOLDER else return
            else -> return
        }
        _state.update { it.copy(screen = target) }
        if (target == Screen.FOLDER) viewModelScope.launch { route() }
    }

    // --- scan & AI ----------------------------------------------------------------------------

    fun startScanFull(dir: File) {
        if (!Access.hasAllFiles(app)) {
            onAccessLost(); return
        }
        start(FileBackend(app), NodeRef(dir.path), Access.label(dir.path))
    }

    fun startScanSaf() {
        val tree = _state.value.settings.treeUri?.let(Uri::parse) ?: return onAccessLost()
        val saf = SafBackend(app, tree)
        start(saf, saf.root, Access.label(saf.root.id))
    }

    private fun start(src: FileSource, rootRef: NodeRef, label: String) {
        job?.cancel()
        source = src
        root = rootRef
        _state.update {
            it.copy(
                screen = Screen.SCAN, rootLabel = label, stats = ScanStats(stage = "Сканирование"), scanError = null,
                plan = null, preview = null, duplicates = emptyList(), aiNote = null, apply = ApplyState(),
            )
        }
        job = viewModelScope.launch {
            try {
                val s = app.settings.current()
                val result = withContext(Dispatchers.IO) {
                    Scanner(src).scan(rootRef, ScanSettings(s.ignoreExtensions.toSet(), s.ignoreFolders.toSet()))
                }
                scan = result
                val sum = Clusterer(app.rules, now = System.currentTimeMillis()).summarize(result.files, s.oldDays)
                summary = sum
                stats { it.copy(stage = "Поиск дублей", files = result.files.size, skipped = result.skipped, clusters = sum.clusters.size) }
                val dups = withContext(Dispatchers.IO) {
                    val scope = this
                    DuplicateFinder { src.openRead(it) }.find(result.files) { !scope.isActive }
                }
                _state.update { it.copy(duplicates = dups) }
                stats { it.copy(duplicates = dups.sumOf { g -> g.size }) }
                runAi()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccessLostException) {
                onAccessLost()
            } catch (e: Exception) {
                _state.update { it.copy(scanError = "Ошибка сканирования: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    private fun stats(f: (ScanStats) -> ScanStats) = _state.update { it.copy(stats = f(it.stats)) }

    private suspend fun runAi() {
        val sum = summary ?: return
        val result = scan ?: return
        val s = app.settings.current()
        val existing = result.existingFolders.map { it.name }
        _state.update { it.copy(scanError = null) }
        stats { it.copy(stage = "Анализ ИИ") }
        try {
            if (sum.objects.isNotEmpty()) app.api.warmUp()
            val res = AiPlanner(app.api).plan(sum, existing, s.allowExisting) { done, total ->
                stats { it.copy(aiDone = done, aiTotal = total) }
            }
            showPlan(OrganizePlan.build(sum, res.plan, existing, s.allowExisting), aiEmpty = res.plan.noConfident && sum.objects.isNotEmpty())
        } catch (e: AiUnavailableException) {
            _state.update { it.copy(scanError = e.message ?: "ИИ временно недоступен") }
        } catch (e: AiRequestException) {
            _state.update { it.copy(scanError = e.message) }
        }
    }

    fun retryAi() {
        job?.cancel()
        job = viewModelScope.launch { runAi() }
    }

    /** Shows the local result only (installers, duplicates); everything else goes to "not determined". */
    fun withoutAi() {
        val sum = summary ?: return
        val result = scan ?: return
        val s = _state.value.settings
        val ai = ValidatedPlan(emptyList(), emptyList(), sum.objects.map { PlanLeave(it.id, "Анализ ИИ не выполнялся") })
        showPlan(OrganizePlan.build(sum, ai, result.existingFolders.map { it.name }, s.allowExisting), aiEmpty = false)
    }

    private fun showPlan(plan: OrganizePlan, aiEmpty: Boolean) {
        _state.update {
            it.copy(
                screen = Screen.PICTURE, plan = plan, scanError = null,
                aiNote = if (aiEmpty) "Не нашлось уверенных рекомендаций" else null,
            )
        }
    }

    // --- plan editing ---------------------------------------------------------------------------

    private fun editPlan(f: (OrganizePlan) -> OrganizePlan?) : Boolean {
        val p = _state.value.plan ?: return false
        val n = f(p) ?: return false
        _state.update { it.copy(plan = n) }
        return true
    }

    fun setChecked(ids: Set<String>, checked: Boolean) = editPlan { it.setChecked(ids, checked) }

    fun renameFolder(old: String, new: String): Boolean {
        val ok = editPlan { it.renameFolder(old, new) }
        if (!ok) _state.update { it.copy(message = "Недопустимое или уже занятое имя папки") }
        return ok
    }

    fun moveFiles(ids: Set<String>, target: String) = editPlan { it.moveFiles(ids, target) }

    fun exportJson(): String = _state.value.plan?.let { PlanExport.toJson(_state.value.rootLabel, it) } ?: "{}"
    fun exportText(): String = _state.value.plan?.let { PlanExport.toText(_state.value.rootLabel, it) } ?: ""

    // --- preview & apply ------------------------------------------------------------------------

    fun openPreview() {
        val plan = _state.value.plan ?: return
        val src = source ?: return
        val result = scan ?: return
        viewModelScope.launch {
            try {
                val mode = app.settings.current().conflictMode
                val checked = plan.checkedItems
                val existingByKey = result.existingFolders.associateBy { FolderNames.key(it.name) }
                val names = HashMap<String, Set<String>>()
                for (key in checked.map { FolderNames.key(it.folder) }.toSet()) {
                    val dir = existingByKey[key] ?: continue
                    names[key] = withContext(Dispatchers.IO) { src.list(dir.ref).nodes.map { it.name }.toSet() }
                }
                val conflicts = Conflicts.detect(checked, names, mode)
                val byId = conflicts.associateBy { it.item.file.id }
                val rows = checked.map { item ->
                    val c = byId[item.file.id]
                    PreviewRow(item, if (c == null) item.file.name else c.resolvedName)
                }
                val newFolders = checked.map { it.folder }.distinctBy { FolderNames.key(it) }
                    .filter { FolderNames.key(it) !in existingByKey }
                _state.update { it.copy(screen = Screen.PREVIEW, preview = PreviewData(rows, newFolders, conflicts)) }
            } catch (e: AccessLostException) {
                onAccessLost()
            }
        }
    }

    fun apply() {
        val preview = _state.value.preview ?: return
        val src = source ?: return
        val rootRef = root ?: return
        val ops = preview.rows.map { MoveOp(it.item.file, it.item.folder) }
        if (ops.isEmpty()) return
        stopFlag.set(false)
        val session = UUID.randomUUID().toString()
        _state.update { it.copy(screen = Screen.APPLY, apply = ApplyState(running = true, total = ops.size)) }
        viewModelScope.launch {
            val mode = app.settings.current().conflictMode
            val report = withContext(Dispatchers.IO) {
                Applier(src, app.journal).apply(session, rootRef, ops, mode, stopFlag) { done, total ->
                    _state.update { it.copy(apply = it.apply.copy(done = done, total = total)) }
                }
            }
            _state.update { it.copy(apply = it.apply.copy(running = false, report = report)) }
            if (report.accessLost) {
                _state.update { it.copy(message = "Доступ к файлам отозван. Журнал сохранён, отменить можно позже.") }
            }
        }
    }

    fun stopApply() = stopFlag.set(true)

    // --- journal & undo -------------------------------------------------------------------------

    fun loadSessions() = viewModelScope.launch {
        _state.update { it.copy(sessions = app.db.journal().sessions()) }
    }

    fun undo(session: SessionSummary) {
        val src: FileSource = if (session.rootId.startsWith("content://")) {
            val tree = Access.treeOf(Uri.parse(session.rootId))
            if (tree == null || !Access.hasTreePermission(app, tree)) {
                _state.update { it.copy(message = "Нет доступа к папке этого применения. Выберите её снова в режиме выбора папки.") }
                return
            }
            SafBackend(app, tree)
        } else {
            if (!Access.hasAllFiles(app)) {
                _state.update { it.copy(message = "Для отмены нужен доступ ко всем файлам.") }
                return
            }
            FileBackend(app)
        }
        _state.update { it.copy(undoRunning = true) }
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { Applier(src, app.journal).undo(session.session) }
            _state.update { it.copy(undoRunning = false, undoReport = report, sessions = app.db.journal().sessions()) }
        }
    }

    // --- settings -------------------------------------------------------------------------------

    fun updateSettings(f: (AppSettings) -> AppSettings) = viewModelScope.launch { app.settings.update(f) }

    fun revokeConsent() = viewModelScope.launch {
        app.settings.update { it.copy(consent = false) }
        route()
    }

    fun clearData() = viewModelScope.launch {
        val tree = _state.value.settings.treeUri
        app.db.journal().clear()
        app.settings.clear()
        if (tree != null) {
            runCatching {
                app.contentResolver.releasePersistableUriPermission(
                    Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        _state.value = UiState()
        route()
    }

    private fun onAccessLost() {
        _state.update {
            it.copy(screen = Screen.ACCESS, mode = null, message = "Доступ к файлам потерян. Журнал сохранён, выберите доступ заново.")
        }
    }

    companion object {
        val CONFLICT_LABELS = mapOf(ConflictMode.RENAME to "Переименовать: name (1).ext", ConflictMode.SKIP to "Пропустить файл")
    }
}
