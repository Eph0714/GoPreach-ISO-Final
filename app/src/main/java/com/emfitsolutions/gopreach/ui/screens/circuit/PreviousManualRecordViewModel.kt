package com.emfitsolutions.gopreach.ui.screens.circuit

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.repository.CellEdit
import com.emfitsolutions.gopreach.data.repository.CoApiException
import com.emfitsolutions.gopreach.data.repository.CoDocumentsRepository
import com.emfitsolutions.gopreach.data.repository.CoListing
import com.emfitsolutions.gopreach.data.repository.CompareGroup
import com.emfitsolutions.gopreach.data.repository.Comparison
import com.emfitsolutions.gopreach.data.repository.ComparisonReport
import com.emfitsolutions.gopreach.data.repository.ManualAnalysis
import com.emfitsolutions.gopreach.data.repository.ManualImportInfo
import com.emfitsolutions.gopreach.data.repository.ManualSheetInfo
import com.emfitsolutions.gopreach.data.repository.ManualSheetView
import com.emfitsolutions.gopreach.data.repository.ManualSource
import com.emfitsolutions.gopreach.data.repository.ManualSummary
import com.emfitsolutions.gopreach.data.repository.SheetSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_EXCEL_BYTES = 10L * 1024 * 1024

/** A cell address as one number (row in the high half), the key of the pending edits. */
fun cellKey(r: Int, c: Int): Long = (r.toLong() shl 32) or c.toLong()

/**
 * The record on screen: a file that was just imported and is NOT saved yet ([draft]), or a saved one ([info]). [edits] are cell changes not yet saved.
 * The sheet [snapshot] is the worksheet as it looks in Excel.
 */
data class Viewer(
    val draft: Boolean,
    val source: ManualSource?,
    val fileName: String,
    val info: ManualImportInfo?,
    val sheets: List<ManualSheetInfo>,
    val index: Int,
    val snapshot: SheetSnapshot,
    val summary: ManualSummary,
    /** What the server found to compare in this worksheet, with its recommended report. */
    val comparison: Comparison = Comparison(),
    val find: String = "",
    val edits: Map<Long, String> = emptyMap(),
    val editing: Boolean = false,
    val busy: Boolean = false,
) { val dirty get() = edits.isNotEmpty() }

/** Another saved worksheet the open one can be compared with. */
data class CompareOther(val importId: String, val index: Int, val name: String, val createdAt: Long)

/** The comparative report on screen: which group and pair of columns (or which other file) is compared, and the server's report for it. */
data class CompareUi(
    val groups: List<CompareGroup> = emptyList(), val group: CompareGroup? = null, val previous: String = "", val current: String = "",
    val report: ComparisonReport? = null, val others: List<CompareOther> = emptyList(), val other: CompareOther? = null,
    val note: String = "", val loading: Boolean = false, val error: String? = null,
)

/**
 * The analysis on screen for ONE worksheet ([forImport], [forIndex]): the calculated figures arrive first, then (while [aiBusy]) the free AI's wording.
 * It is cleared whenever another record or worksheet is opened or the cells change, so a stale analysis is never shown.
 */
data class AnalysisUi(
    val analysis: ManualAnalysis? = null, val group: String = "", val previous: String = "", val current: String = "",
    val loading: Boolean = false, val aiBusy: Boolean = false, val error: String? = null, val forImport: String = "", val forIndex: Int = -1,
)

/** The "Save this record" form of a draft: the congregation (required) and the remarks (optional) are asked only now. */
data class SaveForm(val congregationId: String? = null, val remarks: String = "", val saving: Boolean = false, val error: String? = null, val duplicate: Boolean = false)

/**
 * The Circuit Overseer's Previous Manual Record. Importing a file just opens it (a draft: it can be searched, summarised and read at once);
 * pressing Save asks for the congregation and the remarks and keeps the WHOLE file as it looks. Saved files are listed by congregation, searched
 * inside, edited cell by cell and saved again. The server does the reading, the searching and the saving.
 */
class PreviousManualRecordViewModel(
    private val repository: CoDocumentsRepository,
    private val context: Context,
) : ViewModel() {

    private val _imports = MutableStateFlow<List<ManualImportInfo>>(emptyList())
    val imports: StateFlow<List<ManualImportInfo>> = _imports.asStateFlow()
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    /** What the list is currently showing a search for (set by the Search button, not on every key). */
    private val _searched = MutableStateFlow("")
    val searched: StateFlow<String> = _searched.asStateFlow()
    private val _congregationFilter = MutableStateFlow<String?>(null)
    val congregationFilter: StateFlow<String?> = _congregationFilter.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _compare = MutableStateFlow(CompareUi())
    val compare: StateFlow<CompareUi> = _compare.asStateFlow()

    /** Sets the report of the open worksheet (the one the server recommended) and lists the other saved files it can be compared with. */
    fun startCompare() {
        val v = _viewer.value ?: return
        val c = v.comparison
        val g = c.groups.firstOrNull()
        _compare.value = CompareUi(groups = c.groups, group = g, previous = g?.recommended?.previous.orEmpty(), current = g?.recommended?.current.orEmpty(), report = c.report)
        val info = v.info ?: return
        viewModelScope.launch {
            val others = runCatching { repository.imports(congregationId = info.congregationId) }.getOrDefault(emptyList())
                .flatMap { i -> i.sheets.map { s -> CompareOther(i.id, s.index, i.fileName + (if (i.sheets.size > 1) " - ${s.name}" else "") , i.createdAt) } }
                .filter { !(it.importId == info.id && it.index == v.index) }.take(80)
            _compare.update { it.copy(others = others) }
        }
    }

    fun pickGroup(g: CompareGroup) { _compare.update { it.copy(group = g, previous = g.recommended.previous, current = g.recommended.current, other = null, note = "") }; refreshReport() }
    fun pickPair(previous: String, current: String) {
        if (previous == current) { _compare.update { it.copy(error = "Choose two different columns.") }; return }
        _compare.update { it.copy(previous = previous, current = current, other = null, note = "", error = null) }; refreshReport()
    }

    private fun refreshReport() {
        val v = _viewer.value ?: return
        val info = v.info ?: return
        val s = _compare.value
        val g = s.group ?: return
        _compare.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val r = repository.compare(info.id, v.index, g.id, s.previous, s.current)
                _compare.update { it.copy(report = r, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _compare.update { it.copy(loading = false, error = reason(e)) }
            }
        }
    }

    /** Compares the open worksheet with another saved one (the older of the two is the earlier); null goes back to the worksheet's own comparison. */
    fun compareWith(other: CompareOther?) {
        val v = _viewer.value ?: return
        val info = v.info ?: return
        if (other == null) {
            val c = v.comparison
            val g = c.groups.firstOrNull()
            _compare.update { it.copy(group = g, previous = g?.recommended?.previous.orEmpty(), current = g?.recommended?.current.orEmpty(), report = c.report, other = null, note = "", error = null) }
            return
        }
        _compare.update { it.copy(loading = true, error = null, other = other) }
        viewModelScope.launch {
            try {
                val older = other.createdAt < info.createdAt
                val here = info.id to v.index
                val there = other.importId to other.index
                val r = repository.compareFiles(if (older) there else here, if (older) here else there)
                _compare.update { it.copy(report = r.report, note = if (r.report == null) r.message else "", loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _compare.update { it.copy(loading = false, error = reason(e)) }
            }
        }
    }

    private val _analysis = MutableStateFlow(AnalysisUi())
    val analysis: StateFlow<AnalysisUi> = _analysis.asStateFlow()
    private var analysisJob: Job? = null

    /** Opens the analysis of the shown (saved) worksheet; the one already on screen for it is kept unless [refresh]. A draft has no saved cells on the server to analyse. */
    fun startAnalysis(refresh: Boolean = false) {
        val v = _viewer.value ?: return
        val info = v.info ?: return
        val now = _analysis.value
        if (!refresh && now.forImport == info.id && now.forIndex == v.index && (now.analysis != null || now.loading)) return
        analysisJob?.cancel()
        _analysis.value = AnalysisUi(forImport = info.id, forIndex = v.index)
        runAnalysis(info.id, v.index, "", "", "")
    }

    fun pickAnalysis(group: String, previous: String, current: String) {
        val s = _analysis.value
        if (previous == current) { _analysis.update { it.copy(error = "Choose two different periods.") }; return }
        _analysis.update { it.copy(group = group, previous = previous, current = current, error = null) }
        runAnalysis(s.forImport, s.forIndex, group, previous, current)
    }

    /** "Refresh Analysis": reanalyses the same worksheet and the same pair of periods, including the AI wording. */
    fun refreshAnalysis() { val s = _analysis.value; if (s.forImport.isNotEmpty()) runAnalysis(s.forImport, s.forIndex, s.group, s.previous, s.current) }

    private fun clearAnalysis() { analysisJob?.cancel(); _analysis.value = AnalysisUi() }

    private fun runAnalysis(importId: String, index: Int, group: String, previous: String, current: String) {
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            _analysis.update { it.copy(loading = it.analysis == null, aiBusy = false, error = null) }
            try {
                val calculated = repository.analysis(importId, index, group, previous, current, withAi = false)
                if (_analysis.value.forImport != importId || _analysis.value.forIndex != index) return@launch
                _analysis.update { it.copy(analysis = calculated, group = calculated.group.id, previous = calculated.selection.previous, current = calculated.selection.current, loading = false, aiBusy = calculated.available) }
                if (!calculated.available) return@launch
                val worded = repository.analysis(importId, index, calculated.group.id, calculated.selection.previous, calculated.selection.current, withAi = true)
                if (_analysis.value.forImport != importId || _analysis.value.forIndex != index) return@launch
                _analysis.update { it.copy(analysis = worded, aiBusy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _analysis.update { it.copy(loading = false, aiBusy = false, error = reason(e)) }
            }
        }
    }

    private val _viewer = MutableStateFlow<Viewer?>(null)
    val viewer: StateFlow<Viewer?> = _viewer.asStateFlow()
    private val _importDialog = MutableStateFlow(false)
    val importDialog: StateFlow<Boolean> = _importDialog.asStateFlow()
    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()
    private val _reading = MutableStateFlow(false)
    val reading: StateFlow<Boolean> = _reading.asStateFlow()
    private val _saveForm = MutableStateFlow<SaveForm?>(null)
    val saveForm: StateFlow<SaveForm?> = _saveForm.asStateFlow()
    private val _stored = MutableStateFlow<CoListing?>(null)
    val stored: StateFlow<CoListing?> = _stored.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var loadJob: Job? = null

    init { reload() }

    private fun reason(e: Exception, fallback: String = "Couldn't reach the server. Check your connection and try again.") = (e as? CoApiException)?.message ?: fallback

    // ---------------------------------------------------------------- the saved files

    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _loading.value = true
            try {
                _imports.value = repository.imports(_searched.value, _congregationFilter.value)
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = reason(e)
            } finally {
                _loading.value = false
            }
        }
    }

    fun onQuery(text: String) { _query.value = text }
    fun search() { _searched.value = _query.value.trim(); reload() }
    fun clearSearch() { _query.value = ""; _searched.value = ""; reload() }
    fun setCongregationFilter(id: String?) { _congregationFilter.value = id; reload() }

    /** Saves the remarks of a saved file. */
    fun editRemarks(info: ManualImportInfo, remarks: String, done: () -> Unit) {
        viewModelScope.launch {
            _saving.value = true
            try {
                val saved = repository.editRemarks(info.id, remarks)
                _imports.update { list -> list.map { if (it.id == info.id) it.copy(remarks = saved) else it } }
                _viewer.update { v -> if (v?.info?.id == info.id) v.copy(info = info.copy(remarks = saved)) else v }
                _messages.tryEmit("Remarks saved.")
                done()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(reason(e, "Couldn't reach the server. Nothing was changed."))
            } finally {
                _saving.value = false
            }
        }
    }

    /** Saves the file as it was uploaded to Downloads/GoPreach, under the same file name. */
    fun downloadOriginal(info: ManualImportInfo) {
        viewModelScope.launch {
            try {
                val bytes = repository.downloadImport(info.id)
                val mime = when (info.fileName.substringAfterLast('.', "").lowercase()) {
                    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    "xls" -> "application/vnd.ms-excel"
                    "csv" -> "text/csv"
                    else -> "application/octet-stream"
                }
                val where = withContext(Dispatchers.IO) {
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, info.fileName)
                            put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/GoPreach")
                        }
                        val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("Couldn't create the file in Downloads.")
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("Couldn't write the file.")
                        "Downloads/GoPreach"
                    } else {
                        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
                        java.io.File(dir, info.fileName).also { it.writeBytes(bytes) }.parent.orEmpty()
                    }
                }
                _messages.tryEmit("Saved to $where: ${info.fileName}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(if (e is CoApiException && e.status == 404) "The original file was not kept for this record." else reason(e, "The download failed."))
            }
        }
    }

    /** Removes a saved file (all its worksheets). The original Excel file is not touched. */
    fun removeFile(info: ManualImportInfo) {
        viewModelScope.launch {
            try {
                repository.deleteImport(info.id)
                if (_viewer.value?.info?.id == info.id) _viewer.value = null
                _messages.tryEmit("The saved file ${info.fileName} was removed.")
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(reason(e, "Couldn't reach the server."))
            }
        }
    }

    // ---------------------------------------------------------------- one record on screen

    /** Opens a saved file on one worksheet; [find] pre-fills the search inside the sheet. */
    fun open(info: ManualImportInfo, index: Int, find: String = "") {
        viewModelScope.launch {
            try {
                val v = repository.openSheet(info.id, index)
                _viewer.value = Viewer(false, null, info.fileName, v.info ?: info, info.sheets, v.sheet.index, v.sheet.snapshot, v.summary, v.comparison, find)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(reason(e))
            }
        }
    }

    fun switchSheet(index: Int) {
        val cur = _viewer.value ?: return
        if (index == cur.index || cur.busy) return
        _viewer.update { it?.copy(busy = true) }
        viewModelScope.launch {
            try {
                val v: ManualSheetView = if (cur.draft) repository.preview(cur.source!!, index).view else repository.openSheet(cur.info!!.id, index)
                _viewer.update { it?.copy(index = v.sheet.index, snapshot = v.sheet.snapshot, summary = v.summary, comparison = v.comparison, edits = emptyMap(), editing = false, busy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _viewer.update { it?.copy(busy = false) }
                _messages.tryEmit(reason(e))
            }
        }
    }

    fun setFind(text: String) = _viewer.update { it?.copy(find = text) }
    fun setEditing(on: Boolean) = _viewer.update { it?.copy(editing = on) }
    fun closeViewer() { clearAnalysis(); _viewer.value = null; reload() }

    /** A cell typed on the phone; it is only kept (on the server) when Save changes is pressed. */
    fun editCell(r: Int, c: Int, text: String) {
        _viewer.update { v ->
            if (v == null || v.draft) return@update v
            val was = v.snapshot.cells.firstOrNull { it.r == r && it.c == c }?.text.orEmpty()
            val next = if (text == was) v.edits - cellKey(r, c) else v.edits + (cellKey(r, c) to text.take(1000))
            v.copy(edits = next)
        }
    }

    fun discardEdits() = _viewer.update { it?.copy(edits = emptyMap()) }

    fun saveEdits() {
        val v = _viewer.value ?: return
        val info = v.info ?: return
        if (v.edits.isEmpty() || v.busy) return
        _viewer.update { it?.copy(busy = true) }
        viewModelScope.launch {
            try {
                val edits = v.edits.map { (k, text) -> CellEdit((k shr 32).toInt(), (k and 0xffffffffL).toInt(), text) }
                val done = repository.editCells(info.id, v.index, edits)
                clearAnalysis()   // the cells changed: the earlier analysis no longer matches them
                _viewer.update { it?.copy(snapshot = done.sheet.snapshot, summary = done.summary, comparison = done.comparison, edits = emptyMap(), busy = false) }
                _messages.tryEmit("Saved ${done.changed} change${if (done.changed == 1) "" else "s"}.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _viewer.update { it?.copy(busy = false) }
                _messages.tryEmit(reason(e, "Couldn't reach the server. Nothing was changed."))
            }
        }
    }

    // ---------------------------------------------------------------- import: the file just opens

    fun startImport() { _importError.value = null; _importDialog.value = true }
    fun cancelImport() { _importDialog.value = false; _stored.value = null }

    /** A workbook picked from the phone: read it here (size checked), then open it. */
    fun pickFile(uri: Uri) {
        viewModelScope.launch {
            val resolver = context.contentResolver
            var name = "workbook"
            var size = -1L
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0) name = c.getString(n) ?: name
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
            if (size > MAX_EXCEL_BYTES) { _importError.value = "\"$name\" is larger than 10 MB."; return@launch }
            val bytes = withContext(Dispatchers.IO) { runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
            if (bytes == null) { _importError.value = "Couldn't read \"$name\"."; return@launch }
            if (bytes.size > MAX_EXCEL_BYTES) { _importError.value = "\"$name\" is larger than 10 MB."; return@launch }
            openDraft(ManualSource.Bytes(name, bytes), name)
        }
    }

    /** A workbook already kept in the File Manager (the original stays there). */
    fun pickStored(fileId: String, name: String) {
        _stored.value = null
        viewModelScope.launch { openDraft(ManualSource.Stored(fileId), name) }
    }

    private suspend fun openDraft(source: ManualSource, name: String) {
        _reading.value = true
        _importError.value = null
        try {
            val p = repository.preview(source, null)
            _viewer.value = Viewer(true, source, name, null, p.sheets, p.view.sheet.index, p.view.sheet.snapshot, p.view.summary, p.view.comparison)
            _importDialog.value = false
            if (p.truncated) _messages.tryEmit("A very large sheet was cut at 3000 rows and 120 columns.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _importError.value = reason(e)
        } finally {
            _reading.value = false
        }
    }

    fun browseStored(folderId: String?) {
        viewModelScope.launch {
            try { _stored.value = repository.list(folderId) } catch (e: CancellationException) { throw e } catch (e: Exception) { _messages.tryEmit((e as? CoApiException)?.message ?: "Couldn't open the File Manager.") }
        }
    }

    fun closeStored() { _stored.value = null }

    // ---------------------------------------------------------------- Save: the congregation and the remarks are asked now

    fun askSave() { _saveForm.value = SaveForm() }
    fun cancelSave() { _saveForm.value = null }
    fun setCongregation(id: String) = _saveForm.update { it?.copy(congregationId = id, error = null) }
    fun setRemarks(text: String) = _saveForm.update { it?.copy(remarks = text.take(500)) }
    fun dismissDuplicate() = _saveForm.update { it?.copy(duplicate = false) }

    /** Saves the whole draft file (one all-or-nothing step on the server), then opens the saved record. */
    fun confirmSave(allowDuplicate: Boolean = false) {
        val form = _saveForm.value ?: return
        val v = _viewer.value ?: return
        val source = v.source ?: return
        val congregation = form.congregationId
        if (congregation == null) { _saveForm.update { it?.copy(error = "Choose the congregation first.") }; return }
        _saveForm.update { it?.copy(saving = true, error = null, duplicate = false) }
        viewModelScope.launch {
            try {
                val result = repository.save(source, congregation, form.remarks, allowDuplicate)
                _saveForm.value = null
                _messages.tryEmit("Saved the full file (${result.sheets.size} worksheet${if (result.sheets.size == 1) "" else "s"}) for ${result.congregationName}. You can open it any time.")
                val info = ManualImportInfo(result.importId, congregation, result.congregationName, result.remarks, v.fileName, result.sheets, System.currentTimeMillis())
                open(info, minOf(v.index, result.sheets.size - 1).coerceAtLeast(0), v.find)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CoApiException) {
                _saveForm.update { it?.copy(saving = false, duplicate = e.isDuplicate, error = if (e.isDuplicate) null else e.message) }
            } catch (e: Exception) {
                _saveForm.update { it?.copy(saving = false, error = "Couldn't reach the server. Nothing was saved.") }
            }
        }
    }
}
