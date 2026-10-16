package com.emfitsolutions.gopreach.data.repository

import com.emfitsolutions.gopreach.domain.ListenLanguageChoice
import com.emfitsolutions.gopreach.domain.NarrationLanguage
import com.emfitsolutions.gopreach.domain.VoiceGender
import com.emfitsolutions.gopreach.platform.KeyValueStores
import com.emfitsolutions.gopreach.platform.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val KEY_VOICE_GENDER = "listen_voice_gender"
private const val KEY_LISTEN_LANGUAGE = "listen_language"

/**
 * The Listen (read aloud) choices (Weather, My Planner, My Bible Text Record, the comparative reports): which kind of
 * voice reads (any / female / male) and the language it reads in (automatic / English / Tagalog / Iloko). Per-device display choices, like the theme
 * and the name order: they apply at once, survive sign-out and are never synced. Defaults: any voice, automatic.
 */
class ListenButtonPreference(stores: KeyValueStores) {
    private val prefs = stores.open("gopreach_settings")

    private val _gender = MutableStateFlow(readEnum(KEY_VOICE_GENDER, VoiceGender.ANY))
    val gender: StateFlow<VoiceGender> = _gender

    private val _language = MutableStateFlow(readEnum(KEY_LISTEN_LANGUAGE, ListenLanguageChoice.AUTO))
    val language: StateFlow<ListenLanguageChoice> = _language

    fun setGender(value: VoiceGender) {
        prefs.edit { putString(KEY_VOICE_GENDER, value.name) }
        _gender.value = value
    }

    fun setLanguage(value: ListenLanguageChoice) {
        prefs.edit { putString(KEY_LISTEN_LANGUAGE, value.name) }
        _language.value = value
    }

    /** The language the app's own words are spoken in: the chosen one, or English for Automatic. */
    val narration: NarrationLanguage get() = _language.value.narration ?: NarrationLanguage.ENGLISH

    private inline fun <reified E : Enum<E>> readEnum(key: String, default: E): E =
        runCatching { enumValueOf<E>(prefs.getString(key, default.name)!!) }.getOrDefault(default)
}
