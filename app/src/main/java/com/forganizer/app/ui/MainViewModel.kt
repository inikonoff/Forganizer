package com.forganizer.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.forganizer.app.ForganizerApp
import com.forganizer.app.data.AppSettings
import com.forganizer.app.data.PinnedDecisionEntity
import com.forganizer.app.data.PlanVersionEntity
import com.forganizer.app.data.SavedPlanEntity
import com.forganizer.app.data.SavedPlanInfo
import com.forganizer.app.data.SessionSummary
import com.forganizer.app.fs.Access
import com.forganizer.app.fs.FileBackend
import com.forganizer.app.fs.SafBackend
import com.forganizer.core.AccessLostException
import com.forganizer.core.AiPlanner
import com.forganizer.core.AiRequestException
import com.forganizer.core.ArchivePeeker
import com.forganizer.core.AiUnavailableException
import com.forganizer.core.ApplyReport
import com.forganizer.core.Applier
import com.forganizer.core.Clusterer
import com.forganizer.core.ConflictMode
import com.forganizer.core.Conflicts
import com.forganizer.core.DumpDoc
import com.forganizer.core.DuplicateFinder
import com.forganizer.core.FolderDump
import com.forganizer.core.FileNode
import com.forganizer.core.FileSource
import com.forganizer.core.FolderNames
import com.forganizer.core.MoveOp
import com.forganizer.core.NameConflict
import com.forganizer.core.NodeRef
import com.forganizer.core.OrganizePlan
import com.forganizer.core.PatchResult
import com.forganizer.core.PlanExport
import com.forganizer.core.PlanSession
import com.forganizer.core.PlanSnapshot
import com.forganizer.core.PlanVersion
import com.forganizer.core.ProtocolJson
import com.forganizer.core.RefineLimitException
import com.forganizer.core.Staleness
import com.forganizer.core.Text
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class Screen { LOADING, CONSENT, ACCESS, FOLDER, SCAN, PICTURE, REFINE_DIFF, SAVED, PREVIEW, APPLY, JOURNAL, SETTINGS }
enum class Mode { FULL, SAF }

data class ScanStats(
    val stage: String = "",
    val files: Int = 0,
    val clusters: Int = 0,
    val duplicates: Int = 0,
    val skipped: Int = 0,
    val archives: Int = 0,
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

data class RefineState(
    val running: Boolean = false,
    val instruction: String = "",
    val result: PatchResult? = null,
    val note: String = "",
)

data class VersionInfo(val number: Int, val label: String, val time: Long)

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
    val refine: RefineState = RefineState(),
    val refinesLeft: Int = PlanSession.MAX_REFINES,
    val versions: List<VersionInfo> = emptyList(),
    val savedPlans: List<SavedPlanInfo> = emptyList(),
    /** Set when the plan was reopened from a saved scheme (no fresh scan / duplicates). */
    val fromSaved: Boolean = false,
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
    private var scanTime: Long = 0
    private var summary: Summary? = null
    private var session: PlanSession? = null
    private var planId: String = UUID.randomUUID().toString()
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

    /**
     * Chooses the entry screen. It runs asynchronously (also from onResume, e.g. right after the system
     * folder picker closes), so it must never replace a screen the user is already working on.
     */
    private suspend fun route() {
        val s = app.settings.current()
        val mode = detectMode(s)
        val label = s.treeUri?.let { u -> Access.label(SafBackend(app, Uri.parse(u)).root.id) }
        _state.update { cur ->
            val target = when {
                !s.consent -> Screen.CONSENT
                mode == null -> Screen.ACCESS
                else -> Screen.FOLDER
            }
            cur.copy(mode = mode, treeLabel = label, screen = if (cur.screen in ENTRY_SCREENS) target else cur.screen)
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
        Access.takePersistable(app, uri)
        viewModelScope.launch {
            if (Access.hasAllFiles(app)) {
                val path = Access.treeToPath(uri)
                // The path must be really readable: on some devices an SD card path resolves but listing is denied.
                val readable = path != null && withContext(Dispatchers.IO) { path.isDirectory && path.listFiles() != null }
                if (path != null && readable) {
                    startScanFull(path); return@launch
                }
            }
            // Everything else goes through the system file access (SAF), and analysis starts right away.
            if (!Access.hasTreePermission(app, uri)) {
                val report = withContext(Dispatchers.IO) { Access.diagnose(app, uri, null) }
                _state.update {
                    it.copy(message = "Система не сохранила доступ к выбранной папке. Выберите её ещё раз и подтвердите доступ в системном окне.\n\n$report")
                }
                return@launch
            }
            app.settings.update { it.copy(treeUri = uri.toString()) }
            val saf = SafBackend(app, uri)
            start(saf, saf.root, Access.label(saf.root.id))
        }
    }

    /** Shows what the app can see (permissions, volumes, listing results); the report can be copied. */
    fun runAccessDiagnostics() {
        _state.update { it.copy(message = "Проверяю доступ…") }
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { Access.diagnose(app, null, _state.value.settings.treeUri) }
            _state.update { it.copy(message = report) }
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
        Screen.SCAN, Screen.PICTURE, Screen.PREVIEW, Screen.JOURNAL, Screen.SETTINGS, Screen.SAVED -> true
        Screen.REFINE_DIFF -> true
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
            Screen.REFINE_DIFF -> { rejectRefine(); return }
            Screen.APPLY -> if (s.apply.running) return else Screen.FOLDER
            Screen.JOURNAL, Screen.SETTINGS, Screen.SAVED ->
                returnTo.takeIf { it != Screen.JOURNAL && it != Screen.SETTINGS && it != Screen.SAVED } ?: Screen.FOLDER
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
                scanTime = System.currentTimeMillis()
                val peeks = if (s.peekArchives) {
                    stats { it.copy(stage = "Заглядываю в архивы", files = result.files.size, skipped = result.skipped) }
                    withContext(Dispatchers.IO) {
                        val scope = this
                        ArchivePeeker(src).peekAll(result.files) { !scope.isActive }
                    }
                } else emptyMap()
                val sum = Clusterer(app.rules, now = System.currentTimeMillis()).summarize(result.files, s.oldDays, peeks)
                summary = sum
                stats { it.copy(stage = "Поиск дублей", files = result.files.size, skipped = result.skipped, clusters = sum.clusters.size, archives = peeks.size) }
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
                onAccessLost(e.message)
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
            val partial = if (res.failedBatches > 0) {
                "ИИ не ответил на ${res.failedBatches} из ${res.totalBatches} запросов, эти файлы в блоке «Не определено». Запустите анализ ещё раз, чтобы разобрать их."
            } else null
            showPlan(
                OrganizePlan.build(sum, res.plan, existing, s.allowExisting),
                aiEmpty = res.plan.noConfident && sum.objects.isNotEmpty(),
                extraNote = partial,
            )
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

    private fun showPlan(plan: OrganizePlan, aiEmpty: Boolean, extraNote: String? = null) {
        val s = PlanSession(plan, _state.value.settings.allowExisting)
        session = s
        planId = UUID.randomUUID().toString()
        persistVersion(s.versions.last(), "")
        _state.update {
            it.copy(
                screen = Screen.PICTURE, scanError = null, fromSaved = false, refine = RefineState(),
                aiNote = if (aiEmpty) "Не нашлось уверенных рекомендаций" else extraNote,
            )
        }
        publish()
    }

    private fun publish() {
        val s = session ?: return
        _state.update {
            it.copy(
                plan = s.plan,
                refinesLeft = s.refinesLeft,
                versions = s.versions.map { v -> VersionInfo(v.number, v.label, v.time) },
            )
        }
    }

    private fun persistVersion(v: PlanVersion, patch: String) {
        val id = planId
        val snapshot = ProtocolJson.encodeToString(OrganizePlan.serializer(), v.plan)
        viewModelScope.launch {
            runCatching { app.db.plans().insertVersion(PlanVersionEntity(0, id, v.number, patch, snapshot, v.time)) }
        }
    }

    private fun persistPins() {
        val s = session ?: return
        val id = planId
        val rows = s.pins.folders.map { PinnedDecisionEntity(0, id, "folder_name", it, null) } +
            s.pins.files.map { (file, folder) -> PinnedDecisionEntity(0, id, if (folder != null) "file_in_folder" else "file_excluded", file, folder) }
        viewModelScope.launch { runCatching { app.db.plans().replacePins(id, rows) } }
    }

    // --- plan editing ---------------------------------------------------------------------------

    fun setChecked(ids: Set<String>, checked: Boolean) {
        session?.setChecked(ids, checked) ?: return
        persistPins(); publish()
    }

    fun renameFolder(old: String, new: String): Boolean {
        val ok = session?.renameFolder(old, new) ?: false
        if (!ok) _state.update { it.copy(message = "Недопустимое или уже занятое имя папки") }
        else { persistPins(); publish() }
        return ok
    }

    fun moveFiles(ids: Set<String>, target: String) {
        session?.moveFiles(ids, target) ?: return
        persistPins(); publish()
    }

    // --- refine by text (AI patches) ------------------------------------------------------------

    fun refine(instruction: String) {
        val s = session ?: return
        val text = instruction.trim()
        if (text.isEmpty() || _state.value.refine.running) return
        if (s.refinesLeft <= 0) {
            _state.update { it.copy(message = "Лимит правок ИИ на эту сессию исчерпан (${s.maxRefines}). Правьте план вручную.") }
            return
        }
        _state.update { it.copy(refine = RefineState(running = true, instruction = text)) }
        viewModelScope.launch {
            try {
                app.api.warmUp()
                val (result, note) = s.refine(app.api, text)
                publish()
                if (!result.hasChanges) {
                    val msg = buildString {
                        append(note.ifEmpty { "ИИ не предложил изменений." })
                        if (result.skipped.isNotEmpty()) {
                            append("\n\nНе выполнено:\n")
                            append(result.skipped.joinToString("\n") { "• $it" })
                        }
                    }
                    _state.update { it.copy(refine = RefineState(), message = msg) }
                } else {
                    _state.update {
                        it.copy(screen = Screen.REFINE_DIFF, refine = RefineState(false, text, result, note))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RefineLimitException) {
                _state.update { it.copy(refine = RefineState(), message = e.message) }
            } catch (e: AiUnavailableException) {
                _state.update { it.copy(refine = RefineState(instruction = text), message = e.message ?: "ИИ временно недоступен") }
            } catch (e: AiRequestException) {
                _state.update { it.copy(refine = RefineState(instruction = text), message = e.message) }
            }
        }
    }

    fun acceptRefine() {
        val s = session ?: return
        val r = _state.value.refine
        val result = r.result ?: return
        s.accept(result, r.instruction)
        persistVersion(s.versions.last(), (listOf(r.instruction) + result.applied).joinToString("\n"))
        persistPins()
        _state.update { it.copy(screen = Screen.PICTURE, refine = RefineState()) }
        publish()
    }

    fun rejectRefine() = _state.update { it.copy(screen = Screen.PICTURE, refine = RefineState()) }

    fun rollback(number: Int) {
        val s = session ?: return
        if (s.rollback(number)) {
            persistVersion(s.versions.last(), "rollback:$number")
            publish()
        }
    }

    // --- saved schemes --------------------------------------------------------------------------

    fun saveScheme(name: String) {
        val s = session ?: return
        val rootRef = root ?: return
        val snapshot = s.snapshot(rootRef.id, _state.value.rootLabel).copy(versions = emptyList()).encode()
        val entity = SavedPlanEntity(planId, Text.clean(name, 60).ifEmpty { _state.value.rootLabel }, rootRef.id, snapshot, System.currentTimeMillis())
        viewModelScope.launch {
            app.db.plans().saveScheme(entity)
            _state.update { it.copy(message = "Схема «${entity.name}» сохранена. Её можно открыть в разделе «Сохранённые схемы».") }
        }
    }

    fun openSavedList() {
        returnTo = _state.value.screen
        _state.update { it.copy(screen = Screen.SAVED) }
        viewModelScope.launch { _state.update { it.copy(savedPlans = app.db.plans().schemes()) } }
    }

    fun deleteSaved(info: SavedPlanInfo) = viewModelScope.launch {
        app.db.plans().deleteScheme(info.id)
        _state.update { it.copy(savedPlans = app.db.plans().schemes()) }
    }

    suspend fun savedExport(info: SavedPlanInfo, json: Boolean): String? {
        val e = app.db.plans().scheme(info.id) ?: return null
        val snap = runCatching { PlanSnapshot.decode(e.snapshot) }.getOrNull() ?: return null
        return if (json) PlanExport.toJson(snap.rootLabel, snap.plan) else PlanExport.toText(snap.rootLabel, snap.plan)
    }

    /** Reopens a scheme: files that are gone or changed are marked and excluded from applying. */
    fun openSaved(info: SavedPlanInfo) {
        val src = sourceFor(info.rootId) ?: return
        viewModelScope.launch {
            try {
                val e = app.db.plans().scheme(info.id) ?: return@launch
                val snap = PlanSnapshot.decode(e.snapshot)
                val checked = withContext(Dispatchers.IO) { Staleness.check(snap.plan, src) }
                val s = PlanSession.restore(snap)
                s.replacePlan(checked)
                session = s
                planId = info.id
                source = src
                root = NodeRef(info.rootId)
                scan = null
                summary = null
                _state.update {
                    it.copy(
                        screen = Screen.PICTURE, rootLabel = snap.rootLabel, duplicates = emptyList(), fromSaved = true,
                        refine = RefineState(), preview = null, apply = ApplyState(),
                        aiNote = if (checked.staleCount > 0) "Устарело пунктов: ${checked.staleCount}. Они исключены из применения." else null,
                    )
                }
                publish()
            } catch (e: AccessLostException) {
                onAccessLost()
            } catch (e: Exception) {
                _state.update { it.copy(message = "Не удалось открыть схему: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /** FileSource able to work with the given root id, or null (a message is shown). */
    private fun sourceFor(rootId: String): FileSource? =
        if (rootId.startsWith("content://")) {
            val tree = Access.treeOf(Uri.parse(rootId))
            if (tree == null || !Access.hasTreePermission(app, tree)) {
                _state.update { it.copy(message = "Нет доступа к этой папке. Выберите её снова в режиме выбора папки.") }
                null
            } else SafBackend(app, tree)
        } else {
            if (!Access.hasAllFiles(app)) {
                _state.update { it.copy(message = "Для этой папки нужен доступ ко всем файлам.") }
                null
            } else FileBackend(app)
        }

    /** Snapshot of the selected folder as it was before sorting (names, sizes, dates only). */
    private fun buildDump(): DumpDoc? {
        val st = _state.value
        val s = scan
        if (s != null) {
            return FolderDump.build(
                root = st.rootLabel, takenAt = scanTime, files = s.files, folders = s.existingFolders,
                ignoredFiles = s.ignoredFiles, ignoredFolders = s.ignoredFolders, skipped = s.skipped,
                duplicates = st.duplicates, rules = app.rules, source = "сканирование до сортировки",
            )
        }
        val p = st.plan ?: return null
        return FolderDump.build(
            root = st.rootLabel, takenAt = System.currentTimeMillis(),
            files = p.items.map { it.file } + p.leave.map { it.file },
            folders = p.existingFolders.map { FileNode(it, it, 0, 0, null, true) },
            rules = app.rules, source = "сохранённая схема (только файлы из схемы)",
        )
    }

    fun dumpJson(): String = buildDump()?.let(FolderDump::toJson) ?: "{}"
    fun dumpText(): String = buildDump()?.let { FolderDump.toText(it) } ?: ""

    fun dumpFileName(ext: String): String {
        val base = _state.value.rootLabel.replace(Regex("[^A-Za-zА-Яа-яЁё0-9._-]+"), "_").trim('_').ifEmpty { "folder" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "forganizer-snapshot-$base-$stamp.$ext"
    }

    fun exportJson(): String = _state.value.plan?.let { PlanExport.toJson(_state.value.rootLabel, it) } ?: "{}"
    fun exportText(): String = _state.value.plan?.let { PlanExport.toText(_state.value.rootLabel, it) } ?: ""

    // --- preview & apply ------------------------------------------------------------------------

    fun openPreview() {
        val plan = _state.value.plan ?: return
        val src = source ?: return
        val rootRef = root ?: return
        viewModelScope.launch {
            try {
                val mode = app.settings.current().conflictMode
                val checked = plan.checkedItems
                val dirs = withContext(Dispatchers.IO) { src.list(rootRef).nodes.filter { it.isDir } }
                val existingByKey = dirs.associateBy { FolderNames.key(it.name) }
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
        val src: FileSource = sourceFor(session.rootId) ?: return
        _state.update { it.copy(undoRunning = true) }
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { Applier(src, app.journal).undo(session.session) }
            _state.update { it.copy(undoRunning = false, undoReport = report, sessions = app.db.journal().sessions()) }
        }
    }

    // --- settings -------------------------------------------------------------------------------

    fun updateSettings(f: (AppSettings) -> AppSettings) = viewModelScope.launch { app.settings.update(f) }

    fun checkServer() {
        _state.update { it.copy(message = "Проверяю соединение…") }
        viewModelScope.launch {
            val result = app.api.check()
            _state.update { it.copy(message = result) }
        }
    }

    fun revokeConsent() = viewModelScope.launch {
        app.settings.update { it.copy(consent = false) }
        _state.update { it.copy(screen = Screen.LOADING) }
        route()
    }

    fun clearData() = viewModelScope.launch {
        val tree = _state.value.settings.treeUri
        app.db.journal().clear()
        app.db.plans().clearVersions()
        app.db.plans().clearSchemes()
        app.db.plans().clearAllPins()
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

    private fun onAccessLost(detail: String? = null) {
        _state.update {
            it.copy(
                screen = Screen.ACCESS, mode = null,
                message = "Доступ к файлам потерян" + (detail?.let { d -> " ($d)" } ?: "") +
                    ". Журнал сохранён, выберите доступ заново.",
            )
        }
    }

    companion object {
        private val ENTRY_SCREENS = setOf(Screen.LOADING, Screen.CONSENT, Screen.ACCESS, Screen.FOLDER)
        val CONFLICT_LABELS = mapOf(ConflictMode.RENAME to "Переименовать: name (1).ext", ConflictMode.SKIP to "Пропустить файл")
    }
}
