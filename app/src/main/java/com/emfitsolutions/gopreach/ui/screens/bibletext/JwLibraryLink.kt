package com.emfitsolutions.gopreach.ui.screens.bibletext

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

private const val JW_LIBRARY_PACKAGE = "org.jw.jwlibrary.mobile"

/**
 * Opens a Bible reference in the JW Library app so it can be read there — with
 * no internet if that Bible is downloaded in JW Library. GoPreach can't read
 * JW Library's own storage, so this just hands it the reference (book number,
 * chapter, and the first verse or verse range) through its `jwlibrary://`
 * link, in the language whose jw.org locale code is [jwLocale]. If JW Library
 * isn't installed it falls back to the same reference on jw.org in the browser.
 * Returns false only if neither could be opened.
 */
internal fun openInJwLibrary(context: Context, jwLocale: String, bookNumber: Int, chapter: Int, verses: String): Boolean {
    val firstPart = verses.split(",").firstOrNull()?.trim().orEmpty()
    val bounds = firstPart.split("-").map { it.trim().toIntOrNull() ?: return false }
    if (bounds.isEmpty() || bounds.size > 2) return false
    fun code(verse: Int) = "%02d%03d%03d".format(bookNumber, chapter, verse)
    val bible = if (bounds.size == 2 && bounds[1] > bounds[0]) "${code(bounds[0])}-${code(bounds[1])}" else code(bounds[0])
    val query = "srcid=jwlshare&wtlocale=$jwLocale&prefer=lang&bible=$bible"

    val inApp = Intent(Intent.ACTION_VIEW, Uri.parse("jwlibrary:///finder?$query")).setPackage(JW_LIBRARY_PACKAGE)
    return try {
        context.startActivity(inApp)
        true
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.jw.org/finder?$query")))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}

/**
 * Opens a video in the JW Library app by its jw.org key ([lank], e.g.
 * "pub-jwbvod26_34_VIDEO"), in the language with jw.org locale code [jwLocale] —
 * it plays offline there if the video is downloaded in JW Library. Falls back to
 * the same link on jw.org in the browser when JW Library isn't installed.
 * Returns false only if neither could be opened.
 */
internal fun openVideoInJwLibrary(context: Context, jwLocale: String, lank: String): Boolean {
    val query = "srcid=jwlshare&wtlocale=$jwLocale&lank=$lank&prefer=lang"
    val inApp = Intent(Intent.ACTION_VIEW, Uri.parse("jwlibrary:///finder?$query")).setPackage(JW_LIBRARY_PACKAGE)
    return try {
        context.startActivity(inApp)
        true
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.jw.org/finder?$query")))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
