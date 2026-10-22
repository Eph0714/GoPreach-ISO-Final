package com.emfitsolutions.gopreach.ui.screens.circuit

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.repository.CoApiException
import com.emfitsolutions.gopreach.data.repository.CoDocumentsRepository
import com.emfitsolutions.gopreach.data.repository.CoFile
import com.emfitsolutions.gopreach.data.repository.CoFolder
import com.emfitsolutions.gopreach.data.repository.CoListing
import com.emfitsolutions.gopreach.data.repository.CoSearchResult
import com.emfitsolutions.gopreach.data.repository.CoTrashItem
import com.emfitsolutions.gopreach.data.repository.CoTree
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
import java.io.File

/** One file on its way to the server (the list shows its state until the folder is refreshed). */
data class UploadItem(val key: String, val name: String, val state: UploadState, val error: String? = null)
enum class UploadState { UPLOADING, DONE, FAILED }

/** A non-empty folder the Circuit Overseer asked to delete: the screen warns with these counts before anything is removed. */
data class FolderDeleteWarning(val folder: CoFolder, val folders: Int, val files: Int)

/** Selected rows are keyed "file:<id>" / "folder:<id>". */
private fun fileKey(id: String) = "file:$id"
private fun folderKey(id: String) = "folder:$id"

/**
 * The Circuit Overseer's File Manager. All data lives on the server (private to this person); this class holds what is on screen: the open folder, the sort and
 * the list/grid choice (remembered), the search, a multi-selection, the uploads in progress and the recycle bin. Every action reports back through [messages].
 */
class FileManagerViewModel(
    private val repository: CoDocumentsRepository,
    private val context: Context,
) : ViewModel() {

    private val prefs = context.getSharedPreferences("file_manager", Context.MODE_PRIVATE)

    private val _folderId = MutableStateFlow<String?>(null)
    val folderId: StateFlow<String?> = _folderId.asStateFlow()
    private val _listing = MutableStateFlow<CoListing?>(null)
    val listing: StateFlow<CoListing?> = _listing.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _tree = MutableStateFlow<CoTree?>(null)
    val tree: StateFlow<CoTree?> = _tree.asStateFlow()

    private val _sort = MutableStateFlow(prefs.getString("sort", "name") ?: "name")
    val sort: StateFlow<String> = _sort.asStateFlow()
    private val _descending = MutableStateFlow(prefs.getBoolean("descending", false))
    val descending: StateFlow<Boolean> = _descending.asStateFlow()
    private val _grid = MutableStateFlow(prefs.getBoolean("grid", false))
    val grid: StateFlow<Boolean> = _grid.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _search = MutableStateFlow<CoSearchResult?>(null)
    val search: StateFlow<CoSearchResult?> = _search.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()
    private val _uploads = MutableStateFlow<List<UploadItem>>(emptyList())
    val uploads: StateFlow<List<UploadItem>> = _uploads.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _folderWarning = MutableStateFlow<FolderDeleteWarning?>(null)
    val folderWarning: StateFlow<FolderDeleteWarning?> = _folderWarning.asStateFlow()
    private val _trash = MutableStateFlow<List<CoTrashItem>?>(null)
    val trash: StateFlow<List<CoTrashItem>?> = _trash.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var searchJob: Job? = null

    init { refresh() }

    // ---------------------------------------------------------------- browsing

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            try {
                _listing.value = repository.list(_folderId.value, _sort.value, _descending.value)
                _error.value = null
            } catch (e: CoApiException) {
                if (e.status == 404 && _folderId.value != null) { _folderId.value = null; refresh(); return@launch }
                _error.value = e.message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Couldn't reach the server. Check your connection and try again."
            } finally {
                _loading.value = false
            }
            runCatching { _tree.value = repository.tree() }
        }
    }

    fun open(folderId: String?) {
        _folderId.value = folderId
        _selection.value = emptySet()
        clearSearch()
        refresh()
    }

    fun setSort(key: String) { _sort.value = key; prefs.edit().putString("sort", key).apply(); refresh() }
    fun toggleDirection() { _descending.value = !_descending.value; prefs.edit().putBoolean("descending", _descending.value).apply(); refresh() }
    fun setGrid(grid: Boolean) { _grid.value = grid; prefs.edit().putBoolean("grid", grid).apply() }

    // ---------------------------------------------------------------- search

    fun onQuery(text: String) {
        _query.value = text
        searchJob?.cancel()
        if (text.isBlank()) { _search.value = null; _searching.value = false; return }
        searchJob = viewModelScope.launch {
            delay(300)
            _searching.value = true
            try {
                _search.value = repository.search(text.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _search.value = null
                say(e.message ?: "The search failed.")
            } finally {
                _searching.value = false
            }
        }
    }

    fun clearSearch() { searchJob?.cancel(); _query.value = ""; _search.value = null; _searching.value = false }

    // ---------------------------------------------------------------- selection

    fun isSelecting() = _selection.value.isNotEmpty()
    fun toggleFile(id: String) = toggle(fileKey(id))
    fun toggleFolder(id: String) = toggle(folderKey(id))
    private fun toggle(key: String) = _selection.update { if (key in it) it - key else it + key }
    fun clearSelection() { _selection.value = emptySet() }
    fun isFileSelected(id: String) = fileKey(id) in _selection.value
    fun isFolderSelected(id: String) = folderKey(id) in _selection.value

    fun deleteSelection() = act("Moved to the recycle bin.") {
        val files = _selection.value.filter { it.startsWith("file:") }.map { it.removePrefix("file:") }
        val folders = _selection.value.filter { it.startsWith("folder:") }.map { it.removePrefix("folder:") }
        files.forEach { repository.deleteFile(it) }
        folders.forEach { repository.deleteFolder(it, confirm = true) }
        _selection.value = emptySet()
    }

    fun moveSelection(targetFolderId: String?) = act("Moved.") {
        _selection.value.filter { it.startsWith("file:") }.forEach { repository.moveFile(it.removePrefix("file:"), targetFolderId) }
        _selection.value.filter { it.startsWith("folder:") }.forEach { repository.moveFolder(it.removePrefix("folder:"), targetFolderId) }
        _selection.value = emptySet()
    }

    // ---------------------------------------------------------------- folders and files

    fun createFolder(name: String) = act("Folder \"${name.trim()}\" created.") { repository.createFolder(name, _folderId.value) }
    fun renameFolder(folder: CoFolder, name: String) = act("Renamed.") { repository.renameFolder(folder.id, name) }
    fun moveFolder(folder: CoFolder, target: String?) = act("Moved \"${folder.name}\".") { repository.moveFolder(folder.id, target) }
    fun renameFile(file: CoFile, name: String) = act("Renamed.") { repository.renameFile(file.id, name) }
    fun moveFile(file: CoFile, target: String?) = act("Moved \"${file.name}\".") { repository.moveFile(file.id, target) }
    fun deleteFile(file: CoFile) = act("\"${file.name}\" was moved to the recycle bin.") { repository.deleteFile(file.id) }

    /** Asks the server to delete a folder: a non-empty one is first reported through [folderWarning] so nothing is removed without a warning. */
    fun deleteFolder(folder: CoFolder, confirmed: Boolean = false) {
        viewModelScope.launch {
            _busy.value = true
            try {
                repository.deleteFolder(folder.id, confirm = confirmed)
                _folderWarning.value = null
                say("\"${folder.name}\" was moved to the recycle bin.")
                refresh()
            } catch (e: CoApiException) {
                if (e.needsConfirm) _folderWarning.value = FolderDeleteWarning(folder, e.containedFolders, e.containedFiles) else say(e.message.orEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Couldn't reach the server.")
            } finally {
                _busy.value = false
            }
        }
    }

    fun dismissFolderWarning() { _folderWarning.value = null }

    // ---------------------------------------------------------------- upload / download

    /** Uploads the picked files into the open folder, one after another. The server checks type, content and size and explains a refusal. */
    fun upload(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val maxMb = _listing.value?.maxFileMb ?: 25
        val target = _folderId.value
        viewModelScope.launch {
            var done = 0
            for (uri in uris) {
                val resolver = context.contentResolver
                var name = "file"
                var size = -1L
                resolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val s = c.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0) name = c.getString(n) ?: name
                        if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                    }
                }
                val key = uri.toString()
                setUpload(UploadItem(key, name, UploadState.UPLOADING))
                try {
                    if (size > maxMb * 1024L * 1024L) throw IllegalArgumentException("\"$name\" is larger than $maxMb MB.")
                    val bytes = withContext(Dispatchers.IO) { resolver.openInputStream(uri)?.use { it.readBytes() } } ?: throw IllegalStateException("Couldn't read \"$name\".")
                    val mime = resolver.getType(uri) ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
                    repository.upload(name, bytes, mime, target)
                    setUpload(UploadItem(key, name, UploadState.DONE))
                    done++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    setUpload(UploadItem(key, name, UploadState.FAILED, e.message ?: "The upload failed."))
                }
            }
            if (done > 0) say(if (done == 1) "1 file uploaded." else "$done files uploaded.")
            refresh()
        }
    }

    private fun setUpload(item: UploadItem) = _uploads.update { list -> if (list.any { it.key == item.key }) list.map { if (it.key == item.key) item else it } else list + item }
    fun dismissUploads() { _uploads.update { list -> list.filter { it.state == UploadState.UPLOADING } } }

    suspend fun bytesOf(file: CoFile): ByteArray = repository.download(file.id)
    suspend fun detailsOf(file: CoFile): CoFile = repository.details(file.id)

    /** Copies the file into the phone's Downloads/GoPreach folder; returns what to tell the person. */
    suspend fun saveToDownloads(file: CoFile, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, file.mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GoPreach")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("Couldn't create the file in Downloads.")
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("Couldn't write the file.")
            "Saved to Downloads/GoPreach: ${file.name}"
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
            val saved = File(dir, file.name).also { it.writeBytes(bytes) }
            "Saved to ${saved.path}"
        }
    }

    /** Opens the file in the phone's own app for it (Word, Excel, a PDF reader ...). The copy lives in the app cache only. */
    suspend fun openExternally(activityContext: Context, file: CoFile, bytes: ByteArray) {
        val copy = withContext(Dispatchers.IO) {
            File(context.cacheDir, "exports").apply { mkdirs() }.let { dir -> File(dir, file.id + "_" + file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")).also { it.writeBytes(bytes) } }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", copy)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activityContext.startActivity(Intent.createChooser(intent, file.name))
    }

    // ---------------------------------------------------------------- recycle bin

    fun openTrash() { viewModelScope.launch { runCatching { _trash.value = repository.trash() }.onFailure { say(it.message ?: "Couldn't open the recycle bin.") } } }
    fun closeTrash() { _trash.value = null }
    fun restore(item: CoTrashItem) = actAndTrash("\"${item.name}\" was restored.") { repository.restore(item) }
    fun deleteForever(item: CoTrashItem) = actAndTrash("\"${item.name}\" was deleted for good.") { repository.deleteForever(item) }
    fun emptyTrash() = actAndTrash("The recycle bin is empty.") { repository.emptyTrash() }

    private fun actAndTrash(done: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
                _trash.value = repository.trash()
                say(done)
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(e.message ?: "That didn't work.")
            } finally {
                _busy.value = false
            }
        }
    }

    // ---------------------------------------------------------------- plumbing

    /** Runs a server action, says [done] when it worked or the server's reason when it did not, then reloads the open folder. */
    private fun act(done: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
                say(done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CoApiException) {
                say(e.message.orEmpty())
            } catch (e: Exception) {
                say("Couldn't reach the server. Check your connection and try again.")
            } finally {
                _busy.value = false
                refresh()
                if (_query.value.isNotBlank()) onQuery(_query.value)
            }
        }
    }

    private fun say(text: String) { _messages.tryEmit(text) }
}
