package com.emfitsolutions.gopreach.data.print

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.util.Locale
import org.json.JSONObject

/** What gp-fit.js decided for the paper: its orientation, how far the tables had to shrink (1 = not at all) and how many pages the report takes. */
data class FitLayout(val paper: String, val landscape: Boolean, val scale: Double, val pages: Int, val fits: Boolean)

/**
 * Printing a report that carries tables marked `class="gp-fit"` (the Field Service Record first): the report's own HTML is printed as it is designed, and `assets/print/gp-fit.js` (the SAME
 * file the website uses) decides how large its tables can be laid out on the chosen paper: columns wrap first, a table whose unbreakable columns are still too wide is scaled down (never
 * below a readable size), upright or across is chosen by what needs the least shrinking and the fewest sheets, and a block that fits a page is kept whole.
 *
 * The page margins are set ONCE, here (8 mm, in the print attributes), and the document's own page margin is 0, so the printout never gets the margin twice.
 */
object FittedPrint {
    const val MARGIN_MM = 8
    private const val MARGIN_MILS = 315   // 8 mm in thousandths of an inch

    /** The paper the person chose for printing (Settings > Printing, or the preview's A4 / Letter buttons); Automatic means Letter where Letter is the norm, else A4. */
    fun paperOf(context: Context): String = when (PrintPreferences(context).paperSize) {
        PaperSize.A4 -> "A4"
        PaperSize.LETTER -> "LETTER"
        PaperSize.LEGAL -> "LEGAL"
        PaperSize.AUTO -> if (defaultPaperSizeFor(Locale.getDefault()) == PaperSize.LETTER) "LETTER" else "A4"
    }

    fun setPaper(context: Context, paper: String) {
        PrintPreferences(context).paperSize = when (paper) { "LETTER" -> PaperSize.LETTER; "LEGAL" -> PaperSize.LEGAL; else -> PaperSize.A4 }
    }

    fun isFitted(html: String) = html.contains("gp-fit")

    private var scriptCache: String? = null
    private fun fitScript(context: Context): String =
        scriptCache ?: context.assets.open("print/gp-fit.js").bufferedReader().use { it.readText() }.also { scriptCache = it }

    /** Orientation for gp-fit.js: the report's own choice, else the person's setting, else Automatic. */
    fun orientationOf(context: Context, options: PrintOptions): String {
        val mode = options.orientation ?: PrintPreferences(context).orientation
        return when (mode) { OrientationMode.PORTRAIT -> "portrait"; OrientationMode.LANDSCAPE -> "landscape"; OrientationMode.AUTO -> "auto" }
    }

    /**
     * [html] with the fit script and the call that lays it out for [paper] added. With [preview] the paper itself is drawn (its width and margins, on grey), so what the screen
     * shows is what a page holds.
     */
    fun document(context: Context, html: String, paper: String, orientation: String, preview: Boolean): String {
        val bootstrap = "(function(){var done=false;function go(){if(done)return;done=true;" +
            "var L=GPFit.layout({paper:'$paper',orientation:'$orientation',marginMm:$MARGIN_MM});" +
            (if (preview) "var b=document.body;b.style.boxSizing='content-box';b.style.width=Math.round(L.pageWidthPx)+'px';b.style.padding=Math.round($MARGIN_MM*96/25.4)+'px';b.style.margin='0 auto';b.style.background='#fff';document.documentElement.style.background='#e0e0e0';" else "") +
            "if(window.GPAndroid)GPAndroid.layoutDone(JSON.stringify(L));}" +
            "if(document.readyState==='complete')setTimeout(go,30);else window.addEventListener('load',function(){setTimeout(go,30);});})();"
        val tags = "<style>@page{margin:0}</style><script>${fitScript(context)}</script><script>$bootstrap</script>"
        val withRules = PrintLayout.withPageRules(html)
        val at = withRules.lastIndexOf("</body>", ignoreCase = true)
        return if (at >= 0) withRules.substring(0, at) + tags + withRules.substring(at) else withRules + tags
    }

    private class Bridge(private val onLayout: (FitLayout) -> Unit) {
        @JavascriptInterface
        fun layoutDone(json: String) {
            val o = JSONObject(json)
            val layout = FitLayout(o.optString("paper"), o.optBoolean("landscape"), o.optDouble("scale", 1.0), o.optInt("pages", 1), o.optBoolean("fits", true))
            Handler(Looper.getMainLooper()).post { onLayout(layout) }
        }
    }

    /** Loads [html] into [web] and calls [onLayout] when gp-fit.js has laid it out. */
    @android.annotation.SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun layOut(web: WebView, context: Context, html: String, paper: String, orientation: String, preview: Boolean, onLayout: (FitLayout) -> Unit) {
        web.settings.javaScriptEnabled = true
        web.addJavascriptInterface(Bridge(onLayout), "GPAndroid")
        web.loadDataWithBaseURL(null, document(context, html, paper, orientation, preview), "text/html", "UTF-8", null)
    }

    /** The paper and orientation gp-fit.js chose, as print attributes, with the one 8 mm margin. */
    fun attributes(layout: FitLayout): PrintAttributes {
        val media = when (layout.paper) {
            "LETTER" -> PrintAttributes.MediaSize.NA_LETTER
            "LEGAL" -> PrintAttributes.MediaSize.NA_LEGAL
            else -> PrintAttributes.MediaSize.ISO_A4
        }
        return PrintAttributes.Builder()
            .setMediaSize(if (layout.landscape) media.asLandscape() else media.asPortrait())
            .setMinMargins(PrintAttributes.Margins(MARGIN_MILS, MARGIN_MILS, MARGIN_MILS, MARGIN_MILS))
            .build()
    }

    private val inFlight = mutableSetOf<WebView>()

    /** Lays the report out for [paper] / [orientation] and hands it to the system print dialog (which offers "Save as PDF"). */
    fun print(context: Context, title: String, html: String, paper: String, orientation: String) {
        val web = WebView(context)
        inFlight += web
        web.layout(0, 0, 1200, 1700)
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                Log.e("FittedPrint", "WebView failed to load the report: $description")
                Toast.makeText(context, "Couldn't prepare the report for printing.", Toast.LENGTH_LONG).show()
                inFlight -= web
            }
        }
        layOut(web, context, html, paper, orientation, preview = false) { layout ->
            try {
                val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                if (manager == null) Toast.makeText(context, "Printing isn't available on this device.", Toast.LENGTH_LONG).show()
                else manager.print(title, web.createPrintDocumentAdapter(title), attributes(layout))
            } catch (e: Exception) {
                Log.e("FittedPrint", "Failed to open the print dialog", e)
                Toast.makeText(context, "Couldn't open the print dialog: ${e.localizedMessage ?: "unknown error"}", Toast.LENGTH_LONG).show()
            } finally {
                inFlight -= web
            }
        }
    }
}
