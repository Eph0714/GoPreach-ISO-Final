package com.emfitsolutions.gopreach.domain

/**
 * The language the Listen (read aloud) buttons speak the app's own words in - Weather, My Planner and My Bible Text Record. The Publisher chooses it
 * (Settings, or in the player). Verse text and the Publisher's own notes are always read as saved; only the fixed phrases around them change.
 * [code] is the language code of the phone's text-to-speech voice to look for.
 */
enum class NarrationLanguage(val label: String, val code: String) {
    ENGLISH("English", "en"),
    TAGALOG("Tagalog", "fil"),
    ILOKO("Iloko", "ilo"),
    ;

    /** The wording for this language: one value per language, written next to each other where it is used. */
    fun <T> pick(english: T, tagalog: T, iloko: T): T = when (this) {
        ENGLISH -> english
        TAGALOG -> tagalog
        ILOKO -> iloko
    }
}

/** Which kind of voice the Publisher prefers. Android has no gender field for a voice, so this is matched from the voice's name; when the phone has no such voice, any voice reads. */
enum class VoiceGender(val label: String) { ANY("Any voice"), FEMALE("Female"), MALE("Male") }

/**
 * "Automatic" speaks the app's words in English and reads Bible texts in their own language; the others force that language for the words
 * and for the voice.
 */
enum class ListenLanguageChoice(val label: String, val narration: NarrationLanguage?) {
    AUTO("Automatic", null),
    ENGLISH("English", NarrationLanguage.ENGLISH),
    TAGALOG("Tagalog", NarrationLanguage.TAGALOG),
    ILOKO("Iloko", NarrationLanguage.ILOKO),
}

/**
 * Male / female / unknown for an Android voice. Some phones put it in the name ("en-us-x-sfg#female_1-local", Samsung "en-US-SMTf00"); newer Google voices
 * only carry a three-letter code ("en-us-x-tpd-local"), so the codes of Google's US English voices are listed here. Anything else is unknown (null) and is
 * never guessed: the player then varies the pitch instead.
 */
object VoiceNames {
    private val googleUsEnglish = mapOf(
        "sfg" to VoiceGender.FEMALE, "tpf" to VoiceGender.FEMALE, "tpc" to VoiceGender.FEMALE, "iob" to VoiceGender.FEMALE, "iog" to VoiceGender.FEMALE,
        "tpd" to VoiceGender.MALE, "iol" to VoiceGender.MALE, "iom" to VoiceGender.MALE,
    )

    fun gender(name: String): VoiceGender? {
        val n = name.lowercase()
        return when {
            "female" in n -> VoiceGender.FEMALE
            "#male" in n || "_male" in n || "-male" in n || n.endsWith("male") -> VoiceGender.MALE
            Regex("smtf[0-9]").containsMatchIn(n) -> VoiceGender.FEMALE
            Regex("smtm[0-9]").containsMatchIn(n) -> VoiceGender.MALE
            else -> Regex("^en-us-x-([a-z]{3})-").find(n)?.let { googleUsEnglish[it.groupValues[1]] }
        }
    }
}
