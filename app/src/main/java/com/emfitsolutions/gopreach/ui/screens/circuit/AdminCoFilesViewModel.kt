package com.emfitsolutions.gopreach.ui.screens.circuit

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emfitsolutions.gopreach.data.repository.CoAccount
import com.emfitsolutions.gopreach.data.repository.CoApiException
import com.emfitsolutions.gopreach.data.repository.CoDocumentsRepository
import com.emfitsolutions.gopreach.data.repository.CoFile
import com.emfitsolutions.gopreach.data.repository.CoListing
import com.emfitsolutions.gopreach.data.repository.CoSearchResult
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Super Admin's CO File Management: pick a Circuit Overseer, then browse (never change) that one's files. The server resolves the owner from the
 * Circuit Overseer's immutable account id and returns only their files, so the two can never mix; every open or download is audited there.
 */
class AdminCoFilesViewModel(
    private val repository: CoDocumentsRepository,
    private val context: Context,
) : ViewModel() {
    private val _accounts = MutableStateFlow<List<CoAccount>?>(null)
    val accounts: StateFlow<List<CoAccount>?> = _accounts.asStateFlow()
    private val _selected = MutableStateFlow<CoAccount?>(null)
    val selected: StateFlow<CoAccount?> = _selected.asStateFlow()
    private val _folderId = MutableStateFlow<String?>(null)
    private val _listing = MutableStateFlow<CoListing?>(null)
    val listing: StateFlow<CoListing?> = _listing.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _search = MutableStateFlow<CoSearchResult?>(null)
    val search: StateFlow<CoSearchResult?> = _search.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var searchJob: Job? = null

    init { loadAccounts() }

    fun loadAccounts() {
        viewModelScope.launch {
            _loading.value = true
            try {
                _accounts.value = repository.coAccounts()
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = (e as? CoApiException)?.message ?: "Couldn't reach the server. Check your connection and try again."
            } finally {
                _loading.value = false
            }
        }
    }

    fun pick(account: CoAccount?) {
        _selected.value = account
        _folderId.value = null
        _listing.value = null
        clearSearch()
        if (account != null) refresh() else loadAccounts()
    }

    fun open(folderId: String?) { _folderId.value = folderId; clearSearch(); refresh() }

    fun refresh() {
        val co = _selected.value ?: return
        viewModelScope.launch {
            _loading.value = true
            try {
                _listing.value = repository.adminList(co.id, _folderId.value)
                _error.value = null
            } catch (e: CoApiException) {
                if (e.status == 404 && _folderId.value != null) { _folderId.value = null; refresh(); return@launch }
                _listing.value = null
                _error.value = e.message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _listing.value = null
                _error.value = "Couldn't reach the server. Check your connection and try again."
            } finally {
                _loading.value = false
            }
        }
    }

    fun onQuery(text: String) {
        _query.value = text
        searchJob?.cancel()
        val co = _selected.value
        if (text.isBlank() || co == null) { _search.value = null; return }
        searchJob = viewModelScope.launch {
            delay(300)
            try {
                _search.value = repository.adminSearch(co.id, text.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _search.value = null
                _messages.tryEmit(e.message ?: "The search failed.")
            }
        }
    }

    fun clearSearch() { searchJob?.cancel(); _query.value = ""; _search.value = null }

    suspend fun bytesOf(file: CoFile): ByteArray = repository.adminDownload(_selected.value?.id ?: error("No Circuit Overseer picked."), file.id)

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
            "Saved to ${File(dir, file.name).also { it.writeBytes(bytes) }.path}"
        }
    }

    suspend fun openExternally(activityContext: Context, file: CoFile, bytes: ByteArray) {
        val copy = withContext(Dispatchers.IO) {
            File(context.cacheDir, "exports").apply { mkdirs() }.let { dir -> File(dir, file.id + "_" + file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")).also { it.writeBytes(bytes) } }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", copy)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activityContext.startActivity(Intent.createChooser(intent, file.name))
    }
}
