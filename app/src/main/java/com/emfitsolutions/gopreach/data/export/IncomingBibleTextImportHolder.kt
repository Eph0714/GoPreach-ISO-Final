package com.emfitsolutions.gopreach.data.export

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * "If the receiving Publisher downloads and taps the exported file, it must
 * import automatically" — [com.emfitsolutions.gopreach.MainActivity] writes
 * the incoming file's [Uri] here the moment an ACTION_VIEW intent for one of
 * these exports arrives (app cold-started by it, or already running and
 * handed it via onNewIntent); [com.emfitsolutions.gopreach.ui.screens
 * .bibletext.BibleTextRecordScreen] is the single reader, since importing
 * needs a signed-in Publisher's id and their existing Events, both already
 * in scope there. A plain object, not Hilt-scoped — MainActivity is a plain
 * Activity (not itself Compose-injected), so this is the simplest thing both
 * sides can share.
 */
object IncomingBibleTextImportHolder {
    private val _uri = MutableStateFlow<Uri?>(null)
    val uri: StateFlow<Uri?> = _uri

    fun set(uri: Uri) {
        _uri.value = uri
    }

    fun consume() {
        _uri.value = null
    }
}
