package com.emfitsolutions.gopreach.domain

/** "My Bible Text Record" module spec §21 — [BibleVersion]/[BibleLanguage]/
 * [BibleBook] as a real relational shape, matching the spec's own table
 * definitions field-for-field, but held as **bundled static data** in this
 * file rather than a synced Firestore collection like every user-generated
 * collection elsewhere in this app.
 *
 * Why: this is reference/lookup metadata — book names, canonical order,
 * chapter counts — that is identical for every congregation, every device,
 * and never changes at runtime; it has none of the "written by one device,
 * needs to reach every other device" shape [OfflineFirestoreRepository]'s
 * whole sync pipeline exists for. Bundling it means correct data ships with
 * the app on day one with no admin seeding step, and "the language/book
 * list must not be hard-coded into the UI" (spec §3/§5) is satisfied by
 * having exactly one canonical source screens read from — just a Kotlin
 * object instead of a Firestore round-trip. [BibleTextRecord]/
 * [com.emfitsolutions.gopreach.data.model.BibleTextRecord] stores plain ids
 * (`bibleVersionId`/`languageId`/`bibleBookId`) that resolve through this
 * object today; moving this data to a real backend table later (spec §21's
 * "future Bible versions" hook) is a data-source swap behind the same
 * lookup functions below, not a schema change to the record itself.
 *
 * Content note (spec §32): only book *names*, canonical order, and chapter
 * *counts* live here — structural facts common to the standard 66-book
 * canon this app's New World Translation uses, not the licensed verse text
 * itself. The book titles in every language were read from the jw.org Online Bible
 * itself (not typed from memory).
 */
object NwtBibleReferenceData {

    /** Spec §2/§21 — one row today (`nwt`), but every lookup below is keyed
     * by [BibleVersion.id] rather than assuming NWT everywhere, so a second
     * version can be added as a second entry in [versions] without touching
     * any screen. */
    data class BibleVersion(val id: String, val name: String, val abbreviation: String, val isActive: Boolean = true)

    /** Spec §3/§4 — the dropdown source; never hard-code a language list in
     * a screen, read [languages] instead. */
    /** [jwLocale] is the language's jw.org locale code, used to look verse text up
     * in that language's New World Translation. */
    data class BibleLanguage(val id: String, val name: String, val code: String, val jwLocale: String, val isActive: Boolean = true)

    /** Spec §5/§8 — one Bible book, scoped to a specific Version+Language
     * (spec §21's exact composite: "Bible Version + Language + Bible Book"),
     * carrying its own localized [name], canonical [order] (for sorting/
     * filter lists), [testament], and [chapterCount] (spec §8's chapter-
     * dropdown/validation source). */
    data class BibleBook(
        val id: String,
        val bibleVersionId: String,
        val languageId: String,
        val name: String,
        val order: Int,
        val testament: Testament,
        val chapterCount: Int,
    )

    enum class Testament { OLD, NEW }

    val versions: List<BibleVersion> = listOf(
        BibleVersion(id = "nwt", name = "New World Translation of the Holy Scriptures", abbreviation = "NWT"),
    )
    val defaultVersion: BibleVersion = versions.first()

    /** Every language the jw.org Online Bible offers the New World Translation in
     * that is spoken in the Philippines, plus English. [BibleLanguage.id] "fil"
     * (Filipino) is Tagalog, kept under its original id so earlier records still
     * resolve. */
    val languages: List<BibleLanguage> = listOf(
        BibleLanguage(id = "en", name = "English", code = "en", jwLocale = "E"),
        BibleLanguage(id = "fil", name = "Filipino (Tagalog)", code = "fil", jwLocale = "TG"),
        BibleLanguage(id = "bcl", name = "Bicol", code = "bcl", jwLocale = "BI"),
        BibleLanguage(id = "ceb", name = "Cebuano", code = "ceb", jwLocale = "CV"),
        BibleLanguage(id = "hil", name = "Hiligaynon", code = "hil", jwLocale = "HV"),
        BibleLanguage(id = "ilo", name = "Iloko", code = "ilo", jwLocale = "IL"),
        BibleLanguage(id = "pag", name = "Pangasinan", code = "pag", jwLocale = "PN"),
        BibleLanguage(id = "war", name = "Waray-Waray", code = "war", jwLocale = "SA"),
    )

    fun language(id: String?): BibleLanguage? = languages.firstOrNull { it.id == id }
    fun version(id: String?): BibleVersion? = versions.firstOrNull { it.id == id }

    /** Spec §5-§8 — every book for [versionId]+[languageId], in canonical
     * ([BibleBook.order]) order; empty for a version/language combination
     * with no data yet rather than throwing, so an unrecognized/removed
     * language degrades to an empty book picker instead of crashing the
     * Add/Edit form. */
    fun booksFor(versionId: String, languageId: String): List<BibleBook> =
        booksByVersionAndLanguage[versionId to languageId].orEmpty()

    fun book(versionId: String, languageId: String, bookId: String): BibleBook? =
        booksFor(versionId, languageId).firstOrNull { it.id == bookId }

    /** Canonical (book slug, order, testament, chapter count) — the 66-book
     * structure standard across mainstream Bible translations, including
     * NWT; shared by every language's localized name list below so the
     * order/testament/chapterCount never has to be repeated per language. */
    private data class BookShape(val slug: String, val order: Int, val testament: Testament, val chapterCount: Int)

    private val bookShapes: List<BookShape> = listOf(
        BookShape("genesis", 1, Testament.OLD, 50),
        BookShape("exodus", 2, Testament.OLD, 40),
        BookShape("leviticus", 3, Testament.OLD, 27),
        BookShape("numbers", 4, Testament.OLD, 36),
        BookShape("deuteronomy", 5, Testament.OLD, 34),
        BookShape("joshua", 6, Testament.OLD, 24),
        BookShape("judges", 7, Testament.OLD, 21),
        BookShape("ruth", 8, Testament.OLD, 4),
        BookShape("1samuel", 9, Testament.OLD, 31),
        BookShape("2samuel", 10, Testament.OLD, 24),
        BookShape("1kings", 11, Testament.OLD, 22),
        BookShape("2kings", 12, Testament.OLD, 25),
        BookShape("1chronicles", 13, Testament.OLD, 29),
        BookShape("2chronicles", 14, Testament.OLD, 36),
        BookShape("ezra", 15, Testament.OLD, 10),
        BookShape("nehemiah", 16, Testament.OLD, 13),
        BookShape("esther", 17, Testament.OLD, 10),
        BookShape("job", 18, Testament.OLD, 42),
        BookShape("psalms", 19, Testament.OLD, 150),
        BookShape("proverbs", 20, Testament.OLD, 31),
        BookShape("ecclesiastes", 21, Testament.OLD, 12),
        BookShape("songofsolomon", 22, Testament.OLD, 8),
        BookShape("isaiah", 23, Testament.OLD, 66),
        BookShape("jeremiah", 24, Testament.OLD, 52),
        BookShape("lamentations", 25, Testament.OLD, 5),
        BookShape("ezekiel", 26, Testament.OLD, 48),
        BookShape("daniel", 27, Testament.OLD, 12),
        BookShape("hosea", 28, Testament.OLD, 14),
        BookShape("joel", 29, Testament.OLD, 3),
        BookShape("amos", 30, Testament.OLD, 9),
        BookShape("obadiah", 31, Testament.OLD, 1),
        BookShape("jonah", 32, Testament.OLD, 4),
        BookShape("micah", 33, Testament.OLD, 7),
        BookShape("nahum", 34, Testament.OLD, 3),
        BookShape("habakkuk", 35, Testament.OLD, 3),
        BookShape("zephaniah", 36, Testament.OLD, 3),
        BookShape("haggai", 37, Testament.OLD, 2),
        BookShape("zechariah", 38, Testament.OLD, 14),
        BookShape("malachi", 39, Testament.OLD, 4),
        BookShape("matthew", 40, Testament.NEW, 28),
        BookShape("mark", 41, Testament.NEW, 16),
        BookShape("luke", 42, Testament.NEW, 24),
        BookShape("john", 43, Testament.NEW, 21),
        BookShape("acts", 44, Testament.NEW, 28),
        BookShape("romans", 45, Testament.NEW, 16),
        BookShape("1corinthians", 46, Testament.NEW, 16),
        BookShape("2corinthians", 47, Testament.NEW, 13),
        BookShape("galatians", 48, Testament.NEW, 6),
        BookShape("ephesians", 49, Testament.NEW, 6),
        BookShape("philippians", 50, Testament.NEW, 4),
        BookShape("colossians", 51, Testament.NEW, 4),
        BookShape("1thessalonians", 52, Testament.NEW, 5),
        BookShape("2thessalonians", 53, Testament.NEW, 3),
        BookShape("1timothy", 54, Testament.NEW, 6),
        BookShape("2timothy", 55, Testament.NEW, 4),
        BookShape("titus", 56, Testament.NEW, 3),
        BookShape("philemon", 57, Testament.NEW, 1),
        BookShape("hebrews", 58, Testament.NEW, 13),
        BookShape("james", 59, Testament.NEW, 5),
        BookShape("1peter", 60, Testament.NEW, 5),
        BookShape("2peter", 61, Testament.NEW, 3),
        BookShape("1john", 62, Testament.NEW, 5),
        BookShape("2john", 63, Testament.NEW, 1),
        BookShape("3john", 64, Testament.NEW, 1),
        BookShape("jude", 65, Testament.NEW, 1),
        BookShape("revelation", 66, Testament.NEW, 22),
    )

    /** Official book names, in canonical (1-66) order, as printed by the jw.org
     * Online Bible for each language — read from jw.org rather than typed from
     * memory. Keyed by [BibleLanguage.id]. */
    private val bookNamesByLanguage: Map<String, List<String>> = mapOf(
        "en" to listOf(
            "Genesis", "Exodus", "Leviticus", "Numbers", "Deuteronomy", "Joshua",
            "Judges", "Ruth", "1 Samuel", "2 Samuel", "1 Kings", "2 Kings",
            "1 Chronicles", "2 Chronicles", "Ezra", "Nehemiah", "Esther", "Job",
            "Psalms", "Proverbs", "Ecclesiastes", "Song of Solomon", "Isaiah", "Jeremiah",
            "Lamentations", "Ezekiel", "Daniel", "Hosea", "Joel", "Amos",
            "Obadiah", "Jonah", "Micah", "Nahum", "Habakkuk", "Zephaniah",
            "Haggai", "Zechariah", "Malachi", "Matthew", "Mark", "Luke",
            "John", "Acts", "Romans", "1 Corinthians", "2 Corinthians", "Galatians",
            "Ephesians", "Philippians", "Colossians", "1 Thessalonians", "2 Thessalonians", "1 Timothy",
            "2 Timothy", "Titus", "Philemon", "Hebrews", "James", "1 Peter",
            "2 Peter", "1 John", "2 John", "3 John", "Jude", "Revelation",
        ),
        "fil" to listOf(
            "Genesis", "Exodo", "Levitico", "Bilang", "Deuteronomio", "Josue",
            "Hukom", "Ruth", "1 Samuel", "2 Samuel", "1 Hari", "2 Hari",
            "1 Cronica", "2 Cronica", "Ezra", "Nehemias", "Esther", "Job",
            "Awit", "Kawikaan", "Eclesiastes", "Awit ni Solomon", "Isaias", "Jeremias",
            "Panaghoy", "Ezekiel", "Daniel", "Oseas", "Joel", "Amos",
            "Obadias", "Jonas", "Mikas", "Nahum", "Habakuk", "Zefanias",
            "Hagai", "Zacarias", "Malakias", "Mateo", "Marcos", "Lucas",
            "Juan", "Gawa", "Roma", "1 Corinto", "2 Corinto", "Galacia",
            "Efeso", "Filipos", "Colosas", "1 Tesalonica", "2 Tesalonica", "1 Timoteo",
            "2 Timoteo", "Tito", "Filemon", "Hebreo", "Santiago", "1 Pedro",
            "2 Pedro", "1 Juan", "2 Juan", "3 Juan", "Judas", "Apocalipsis",
        ),
        "bcl" to listOf(
            "Genesis", "Exodo", "Levitico", "Bilang", "Deuteronomio", "Josue",
            "Hukom", "Ruth", "1 Samuel", "2 Samuel", "1 Hadi", "2 Hadi",
            "1 Cronica", "2 Cronica", "Esdras", "Nehemias", "Esther", "Job",
            "Salmo", "Talinhaga", "Eclesiastes", "Awit ni Solomon", "Isaias", "Jeremias",
            "Lamentasyon", "Ezekiel", "Daniel", "Oseas", "Joel", "Amos",
            "Obadias", "Jonas", "Mikas", "Nahum", "Habakuk", "Sofonias",
            "Hageo", "Zacarias", "Malakias", "Mateo", "Marcos", "Lucas",
            "Juan", "Gibo", "Roma", "1 Corinto", "2 Corinto", "Galacia",
            "Efeso", "Filipos", "Colosas", "1 Tesalonica", "2 Tesalonica", "1 Timoteo",
            "2 Timoteo", "Tito", "Filemon", "Hebreo", "Santiago", "1 Pedro",
            "2 Pedro", "1 Juan", "2 Juan", "3 Juan", "Judas", "Kapahayagan",
        ),
        "ceb" to listOf(
            "Genesis", "Exodo", "Levitico", "Numeros", "Deuteronomio", "Josue",
            "Maghuhukom", "Ruth", "1 Samuel", "2 Samuel", "1 Hari", "2 Hari",
            "1 Cronicas", "2 Cronicas", "Esdras", "Nehemias", "Ester", "Job",
            "Salmo", "Proverbio", "Ecclesiastes", "Awit ni Solomon", "Isaias", "Jeremias",
            "Lamentaciones", "Ezequiel", "Daniel", "Oseas", "Joel", "Amos",
            "Abdias", "Jonas", "Miqueas", "Nahum", "Habacuc", "Sofonias",
            "Haggeo", "Zacarias", "Malaquias", "Mateo", "Marcos", "Lucas",
            "Juan", "Buhat", "Roma", "1 Corinto", "2 Corinto", "Galacia",
            "Efeso", "Filipos", "Colosas", "1 Tesalonica", "2 Tesalonica", "1 Timoteo",
            "2 Timoteo", "Tito", "Filemon", "Hebreohanon", "Santiago", "1 Pedro",
            "2 Pedro", "1 Juan", "2 Juan", "3 Juan", "Judas", "Pinadayag",
        ),
        "hil" to listOf(
            "Genesis", "Exodo", "Levitico", "Numeros", "Deuteronomio", "Josue",
            "Hukom", "Rut", "1 Samuel", "2 Samuel", "1 Hari", "2 Hari",
            "1 Cronica", "2 Cronica", "Esdras", "Nehemias", "Ester", "Job",
            "Salmo", "Hulubaton", "Manugwali", "Ambahanon ni Solomon", "Isaias", "Jeremias",
            "Panalambiton", "Ezequiel", "Daniel", "Oseas", "Joel", "Amos",
            "Obadias", "Jonas", "Miqueas", "Nahum", "Habacuc", "Sofonias",
            "Hageo", "Zacarias", "Malaquias", "Mateo", "Marcos", "Lucas",
            "Juan", "Binuhatan", "Roma", "1 Corinto", "2 Corinto", "Galacia",
            "Efeso", "Filipos", "Colosas", "1 Tesalonica", "2 Tesalonica", "1 Timoteo",
            "2 Timoteo", "Tito", "Filemon", "Hebreo", "Santiago", "1 Pedro",
            "2 Pedro", "1 Juan", "2 Juan", "3 Juan", "Judas", "Bugna",
        ),
        "ilo" to listOf(
            "Genesis", "Exodo", "Levitico", "Numeros", "Deuteronomio", "Josue",
            "Uk-ukom", "Ruth", "1 Samuel", "2 Samuel", "1 Ar-ari", "2 Ar-ari",
            "1 Cronicas", "2 Cronicas", "Esdras", "Nehemias", "Ester", "Job",
            "Salmo", "Proverbio", "Eclesiastes", "Kanta ni Solomon", "Isaias", "Jeremias",
            "Un-unnoy", "Ezekiel", "Daniel", "Oseas", "Joel", "Amos",
            "Abdias", "Jonas", "Mikias", "Nahum", "Habakuk", "Sofonias",
            "Haggeo", "Zacarias", "Malakias", "Mateo", "Marcos", "Lucas",
            "Juan", "Aramid", "Roma", "1 Corinto", "2 Corinto", "Galacia",
            "Efeso", "Filipos", "Colosas", "1 Tesalonica", "2 Tesalonica", "1 Timoteo",
            "2 Timoteo", "Tito", "Filemon", "Hebreo", "Santiago", "1 Pedro",
            "2 Pedro", "1 Juan", "2 Juan", "3 Juan", "Judas", "Apocalipsis",
        ),
        "pag" to listOf(
            "Genesis", "Exodo", "Levitico", "Numeros", "Deuteronomio", "Josue",
            "Ukom", "Ruth", "1 Samuel", "2 Samuel", "1 Arari", "2 Arari",
            "1 Awaran", "2 Awaran", "Esdras", "Nehemias", "Ester", "Job",
            "Salmo", "Proverbio", "Eclesiastes", "Kansion nen Solomon", "Isaias", "Jeremias",
            "Tagleey", "Ezequiel", "Daniel", "Oseas", "Joel", "Amos",
            "Obadias", "Jonas", "Miqueas", "Nahum", "Habacuc", "Sofonias",
            "Aggeo", "Zacarias", "Malaquias", "Mateo", "Marcos", "Lucas",
            "Juan", "Gawa", "Roma", "1 Corinto", "2 Corinto", "Galacia",
            "Efeso", "Filipos", "Colosas", "1 Tesalonica", "2 Tesalonica", "1 Timoteo",
            "2 Timoteo", "Tito", "Filemon", "Hebreo", "Santiago", "1 Pedro",
            "2 Pedro", "1 Juan", "2 Juan", "3 Juan", "Judas", "Apocalipsis",
        ),
        "war" to listOf(
            "Genesis", "Exodo", "Levitico", "Numeros", "Deuteronomio", "Josue",
            "Hukom", "Ruth", "1 Samuel", "2 Samuel", "1 Hadi", "2 Hadi",
            "1 Cronicas", "2 Cronicas", "Ezra", "Nehemias", "Esther", "Job",
            "Salmo", "Proberbios", "Eclesiastes", "Kanta ni Solomon", "Isaias", "Jeremias",
            "Pagtangis", "Ezekiel", "Daniel", "Hosea", "Joel", "Amos",
            "Obadias", "Jonas", "Micas", "Nahum", "Habakuk", "Zepanias",
            "Hagai", "Zacarias", "Malakias", "Mateo", "Marcos", "Lucas",
            "Juan", "Buhat", "Roma", "1 Corinto", "2 Corinto", "Galacia",
            "Efeso", "Filipos", "Colosas", "1 Tesalonica", "2 Tesalonica", "1 Timoteo",
            "2 Timoteo", "Tito", "Filemon", "Hebreo", "Santiago", "1 Pedro",
            "2 Pedro", "1 Juan", "2 Juan", "3 Juan", "Judas", "Pahayag",
        ),
    )

    private val namesByLanguage: Map<String, Map<String, String>> = bookNamesByLanguage.mapValues { (_, names) ->
        bookShapes.zip(names) { shape, name -> shape.slug to name }.toMap()
    }

    private val booksByVersionAndLanguage: Map<Pair<String, String>, List<BibleBook>> = buildMap {
        for (version in versions) {
            for (language in languages) {
                val names = namesByLanguage[language.id] ?: continue
                val books = bookShapes.mapNotNull { shape ->
                    val name = names[shape.slug] ?: return@mapNotNull null
                    BibleBook(
                        id = shape.slug,
                        bibleVersionId = version.id,
                        languageId = language.id,
                        name = name,
                        order = shape.order,
                        testament = shape.testament,
                        chapterCount = shape.chapterCount,
                    )
                }
                put(version.id to language.id, books)
            }
        }
    }
}
