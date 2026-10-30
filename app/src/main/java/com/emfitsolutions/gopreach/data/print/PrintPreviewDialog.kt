package com.emfitsolutions.gopreach.data.print

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The print preview every report opens before the system print dialog: the actual report page in a full-screen view that can be
 * zoomed in and out — with the − / + / Fit buttons or by pinching — and panned. **Print** hands the same document to the system
 * print dialog (paper, orientation, Save as PDF); **Close** leaves without printing.
 */
object PrintPreviewDialog {
    /**
     * The preview of a fitted report: the page as the paper holds it (its real width and margins), laid out by the same fit script the website uses. A4 / Letter change the paper and
     * lay the report out again; Print hands exactly this layout (paper, orientation) to the system print dialog.
     */
    fun showFitted(activity: Activity, title: String, html: String, fitOrientation: String) {
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        var paper = FittedPrint.paperOf(activity).let { if (it == "LEGAL") "LEGAL" else it }
        var layout: FitLayout? = null
        val web = WebView(activity).apply {
            setBackgroundColor(Color.parseColor("#E0E0E0"))
            settings.apply {
                setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
                useWideViewPort = true; loadWithOverviewMode = true
            }
        }
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val status = TextView(activity).apply {
            setTextColor(Color.WHITE); textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val paperButtons = mutableMapOf<String, Button>()
        fun paint() = paperButtons.forEach { (p, b) -> b.setBackgroundColor(Color.parseColor(if (p == paper) "#2E7D32" else "#546E7A")) }
        fun load() {
            layout = null
            status.text = "Laying out the page..."
            FittedPrint.layOut(web, activity, html, paper, fitOrientation, preview = true) { l ->
                layout = l
                status.text = (if (l.landscape) "Landscape" else "Portrait") + " · " + l.pages + (if (l.pages == 1) " page" else " pages") + (if (l.scale < 0.999) " · text at " + Math.round(l.scale * 100) + "%" else "")
            }
        }
        fun bar(): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#37474F")); setPadding(px(8), px(6), px(8), px(6))
        }
        fun button(text: String, onClick: () -> Unit) = Button(activity).apply {
            this.text = text; isAllCaps = false; minWidth = px(44); minimumWidth = px(44)
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#546E7A")); setPadding(px(10), 0, px(10), 0)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(38)).apply { marginStart = px(6) }
        }
        val top = bar().apply {
            addView(TextView(activity).apply {
                text = "Print Preview — $title"; setTextColor(Color.WHITE); textSize = 14f; setTypeface(typeface, Typeface.BOLD); maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(button("Close") { dialog.dismiss() })
        }
        val paperBar = bar().apply {
            addView(status)
            listOf("A4" to "A4", "LETTER" to "Letter").forEach { (key, label) ->
                val b = button(label) { if (paper != key) { paper = key; FittedPrint.setPaper(activity, key); paint(); load() } }
                paperButtons[key] = b; addView(b)
            }
        }
        val bottom = bar().apply {
            gravity = Gravity.CENTER; setPadding(px(8), px(6), px(8), px(28))
            fun zoomed(step: () -> Unit) { step(); web.postDelayed({ web.scrollTo(0, 0) }, 150) }
            addView(button("−") { zoomed { web.zoomOut() } })
            addView(button("Fit") { load() })
            addView(button("+") { zoomed { web.zoomIn() } })
            addView(button("Print") {
                dialog.dismiss()
                // exactly the preview's paper and orientation
                FittedPrint.print(activity, title, html, paper, layout?.let { if (it.landscape) "landscape" else "portrait" } ?: fitOrientation)
            }.apply { setBackgroundColor(Color.parseColor("#2E7D32")) })
        }
        val root = LinearLayout(activity).apply {
            this.orientation = LinearLayout.VERTICAL
            addView(top, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(paperBar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottom, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dialog.setContentView(root)
        dialog.setOnDismissListener { web.destroy() }
        paint(); load()
        dialog.show()
    }

    fun show(activity: Activity, title: String, html: String, onPrint: () -> Unit) {
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val web = WebView(activity).apply {
            setBackgroundColor(Color.parseColor("#E0E0E0"))
            settings.apply {
                javaScriptEnabled = false
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false // the on-screen − / + below replace the tiny system ones; pinch still works
                useWideViewPort = true
                loadWithOverviewMode = true // starts fitted to the screen, so a wide landscape sheet is visible whole
            }
            loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }

        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        fun bar(): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#37474F"))
            setPadding(px(8), px(6), px(8), px(6))
        }
        fun button(text: String, onClick: () -> Unit) = Button(activity).apply {
            this.text = text
            isAllCaps = false
            minWidth = px(44); minimumWidth = px(44)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#546E7A"))
            setPadding(px(10), 0, px(10), 0)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(38)).apply { marginStart = px(6) }
        }

        val top = bar().apply {
            addView(TextView(activity).apply {
                text = "Print Preview — $title"
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(button("Close") { dialog.dismiss() })
        }
        val bottom = bar().apply {
            gravity = Gravity.CENTER
            setPadding(px(8), px(6), px(8), px(28)) // clear of the gesture bar
            // WebView zooms about the middle of the view, which lands on empty paper below a short report: keep the page's
            // top-left in view after every step (pinch and drag still work freely from there).
            fun zoomed(step: () -> Unit) { step(); web.postDelayed({ web.scrollTo(0, 0) }, 150) }
            addView(button("−") { zoomed { web.zoomOut() } })
            addView(button("Fit") { web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null) }) // back to the fitted view
            addView(button("+") { zoomed { web.zoomIn() } })
            addView(button("Print") { dialog.dismiss(); onPrint() }.apply { setBackgroundColor(Color.parseColor("#2E7D32")) })
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(top, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottom, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dialog.setContentView(root)
        dialog.setOnDismissListener { web.destroy() }
        dialog.show()
    }
}
