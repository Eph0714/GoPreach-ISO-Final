package com.emfitsolutions.gopreach.domain

/**
 * The words the Bible Text Record reads aloud: one text (its reference, the verse text, then the Publisher's own note), or a whole Event with its
 * sub topics. Everything is cut into short sentences - the player speaks one at a time, so Pause / Resume continue where it stopped and the engine's
 * length limit is never reached. Only what the Publisher saved is read: a text with no saved verse text says so instead of inventing any.
 * The fixed phrases around the saved text can be spoken in English, Tagalog or Iloko ([NarrationLanguage]); the verse text and notes are read as saved.
 */
object BibleTextNarration {
    data class Text(val reference: String, val verseText: String, val remarks: String, val isNote: Boolean)

    /** A sub topic (null title = texts directly under the Event) and its texts, in the order the screen shows them. */
    data class Group(val title: String?, val texts: List<Text>)

    private const val MAX_SENTENCE = 280

    /** "Revelation 21, verses 3 to 4" - a range reads "to", a list reads "and"; one verse reads "verse 3"; no verses reads just the chapter. */
    fun spokenReference(book: String, chapter: Int, verses: String, l: NarrationLanguage = NarrationLanguage.ENGLISH): String {
        val v = verses.trim()
        if (v.isEmpty()) return "$book $chapter"
        val many = v.contains('-') || v.contains(',')
        val to = l.pick(" to ", " hanggang ", " agingga ")
        val and = l.pick(" and ", " at ", " ken ")
        val words = v.replace(Regex("\\s*-\\s*"), to).replace(Regex("\\s*,\\s*"), and)
        val word = l.pick(if (many) "verses" else "verse", "talata", "bersikulo")
        return "$book $chapter, $word $words"
    }

    /** Short sentences: split at the end of a sentence or a line, and any longer piece at a space. Footnote marks are dropped. */
    fun sentences(text: String): List<String> {
        val cleaned = text.replace('\r', '\n').replace(Regex("[*+]"), "")
        val out = mutableListOf<String>()
        cleaned.split('\n').forEach { line ->
            line.trim().split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { s ->
                var rest = s
                while (rest.length > MAX_SENTENCE) {
                    val cut = rest.lastIndexOf(' ', MAX_SENTENCE).takeIf { it > 0 } ?: MAX_SENTENCE
                    out += rest.substring(0, cut).trim()
                    rest = rest.substring(cut).trim()
                }
                if (rest.isNotEmpty()) out += rest
            }
        }
        return out
    }

    /** One Bible text or note. */
    fun textScript(t: Text, l: NarrationLanguage = NarrationLanguage.ENGLISH): List<String> = buildList {
        if (t.isNote) {
            add(l.pick("Note.", "Tala.", "Nota."))
            val body = sentences(t.verseText.ifBlank { t.remarks })
            if (body.isEmpty()) add(l.pick("This note is empty.", "Walang laman ang talang ito.", "Awan ti linaon daytoy a nota.")) else addAll(body)
            return@buildList
        }
        add(t.reference.trimEnd('.') + ".")
        val verse = sentences(t.verseText)
        if (verse.isEmpty()) {
            add(
                l.pick(
                    "The verse text has not been saved for this record.", "Hindi pa naka-save ang teksto ng talata para sa rekord na ito.",
                    "Saan pay a naidulin ti teksto ti bersikulo para iti daytoy a rekord.",
                ),
            )
        } else addAll(verse)
        val note = sentences(t.remarks)
        if (note.isNotEmpty()) { add(l.pick("My note.", "Aking tala.", "Nota ko.")); addAll(note) }
    }

    /** A whole Event: its details, then every text, grouped by sub topic. */
    fun eventScript(title: String, eventLabel: String, speaker: String?, groups: List<Group>, l: NarrationLanguage = NarrationLanguage.ENGLISH): List<String> = buildList {
        add(title.trimEnd('.') + ".")
        if (eventLabel.isNotBlank()) add(l.pick("Event: ", "Kaganapan: ", "Pasamak: ") + eventLabel.trimEnd('.') + ".")
        speaker?.takeIf { it.isNotBlank() }?.let { add(l.pick("Speaker: ", "Tagapagsalita: ", "Agsasao: ") + it.trimEnd('.') + ".") }
        val total = groups.sumOf { it.texts.size }
        if (total == 0) {
            add(
                l.pick(
                    "There are no Bible texts or notes in this event yet.", "Wala pang teksto sa Bibliya o tala sa kaganapang ito.",
                    "Awan pay dagiti teksto iti Biblia wenno nota iti daytoy a pasamak.",
                ),
            )
            return@buildList
        }
        add(
            l.pick(
                if (total == 1) "This event has 1 Bible text or note." else "This event has $total Bible texts and notes.",
                "Ang kaganapang ito ay may $total teksto sa Bibliya at tala.",
                "Daytoy a pasamak ket addaan iti $total a teksto iti Biblia ken nota.",
            ),
        )
        groups.filter { it.texts.isNotEmpty() }.forEach { g ->
            g.title?.takeIf { it.isNotBlank() }?.let { add(l.pick("Sub topic: ", "Sub-paksa: ", "Sub-tema: ") + it.trimEnd('.') + ".") }
            g.texts.forEach { addAll(textScript(it, l)) }
        }
        add(l.pick("End of the event.", "Katapusan ng kaganapan.", "Nalpas ti pasamak."))
    }
}
