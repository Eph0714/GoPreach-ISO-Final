package com.emfitsolutions.gopreach.ui.screens.bibletext

import androidx.lifecycle.ViewModel
import com.emfitsolutions.gopreach.data.repository.BibleVerseTextRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Thin bridge from the Add/Edit Bible Text dialog to [BibleVerseTextRepository]. */
@HiltViewModel
class BibleVerseTextViewModel @Inject constructor(
    private val repository: BibleVerseTextRepository,
) : ViewModel() {
    /** See [BibleVerseTextRepository.fetchVerses]; null on any failure. */
    suspend fun fetchVerses(jwLocale: String, bookNumber: Int, chapter: Int, verses: String): String? =
        runCatching { repository.fetchVerses(jwLocale, bookNumber, chapter, verses) }
            .onFailure { android.util.Log.e("BibleVerseText", "verse lookup failed", it) }
            .getOrNull()
}
