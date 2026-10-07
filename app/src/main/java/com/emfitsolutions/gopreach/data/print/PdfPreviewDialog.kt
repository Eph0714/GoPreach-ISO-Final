package com.emfitsolutions.gopreach.data.print

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Print preview for reports that are produced as a PDF file (the comparative report, publisher records, map PDFs): the real pages
 * in a full-screen view that zooms in and out with − / + / Fit or a pinch, and pans in both directions. **Share / Print** then
 * hands the same file to the system share sheet (print, save, send); **Close** leaves without sharing.
 */
object PdfPreviewDialog {
    /** Shows the preview when there is an Activity to show it in; otherwise goes straight to [onShare]. */
    fun showOrShare(context: Context, file: File, title: String, onShare: () -> Unit) {
        var c: Context? = context
        while (c is android.content.ContextWrapper && c !is Activity) c = c.baseContext
        val activity = c as? Activity
        if (activity == null || activity.isFinishing) onShare() else show(activity, file, title, onShare)
    }

    private fun show(activity: Activity, file: File, title: String, onShare: () -> Unit) {
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val screenW = activity.resources.displayMetrics.widthPixels

        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fd)
        var zoom = 1f
        val pagesColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(px(6), px(6), px(6), px(6)) }

        fun render() {
            pagesColumn.removeAllViews()
            val width = (screenW * zoom).toInt().coerceIn(200, 4096)
            for (i in 0 until renderer.pageCount) {
                renderer.openPage(i).use { page ->
                    val height = (width.toFloat() * page.height / page.width).toInt()
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    pagesColumn.addView(
                        ImageView(activity).apply { setImageBitmap(bitmap); adjustViewBounds = true; setBackgroundColor(Color.WHITE) },
                        LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8) },
                    )
                }
            }
        }
        fun setZoom(z: Float) { zoom = z.coerceIn(1f, 4f); render() }
        render()

        val horizontal = HorizontalScrollView(activity).apply { addView(pagesColumn) }
        val vertical = ScrollView(activity).apply {
            setBackgroundColor(Color.parseColor("#E0E0E0"))
            addView(horizontal)
        }
        val pinch = ScaleGestureDetector(activity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var start = 1f
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { start = zoom; return true }
            override fun onScaleEnd(detector: ScaleGestureDetector) { setZoom(start * detector.scaleFactor) }
        })
        vertical.setOnTouchListener { _, event -> pinch.onTouchEvent(event); false }

        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        fun bar() = LinearLayout(activity).apply {
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
                setTextColor(Color.WHITE); textSize = 14f; setTypeface(typeface, Typeface.BOLD); maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(button("Close") { dialog.dismiss() })
        }
        val bottom = bar().apply {
            gravity = Gravity.CENTER
            setPadding(px(8), px(6), px(8), px(28)) // clear of the gesture bar
            addView(button("−") { setZoom(zoom - 0.5f) })
            addView(button("Fit") { setZoom(1f); horizontal.scrollTo(0, 0); vertical.scrollTo(0, 0) })
            addView(button("+") { setZoom(zoom + 0.5f) })
            addView(button("Share / Print") { dialog.dismiss(); onShare() }.apply { setBackgroundColor(Color.parseColor("#2E7D32")) })
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(top, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(vertical, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottom, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dialog.setContentView(root)
        dialog.setOnDismissListener { runCatching { renderer.close(); fd.close() } }
        dialog.show()
    }
}
