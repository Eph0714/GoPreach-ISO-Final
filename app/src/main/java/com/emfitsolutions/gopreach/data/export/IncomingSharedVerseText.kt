package com.emfitsolutions.gopreach.data.export

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Verse text another app (JW Library's Share) sends to GoPreach. [com.emfitsolutions.gopreach.MainActivity] hands it over here, but only
 * while an Add / Edit Bible Verse form is open and listening ([consumerActive]); otherwise nothing is kept (it is reported back so the
 * user can be told to open the form first), so a share can never be applied later to a form the user did not mean.
 */
object IncomingSharedVerseText {
    @Volatile
    var consumerActive: Boolean = false

    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text

    /** Returns false (and keeps nothing) when no Bible Verse form is open to receive it. */
    fun offer(text: String): Boolean {
        if (!consumerActive) return false
        _text.value = text
        return true
    }

    fun consume() {
        _text.value = null
    }
}
