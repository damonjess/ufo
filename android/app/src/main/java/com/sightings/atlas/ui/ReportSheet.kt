package com.sightings.atlas.ui

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import com.sightings.atlas.R
import com.sightings.atlas.model.Sighting
import com.sightings.atlas.model.VideoInfo
import com.sightings.atlas.model.coordLabel
import com.sightings.atlas.model.formatDate
import com.sightings.atlas.model.shapeLabel
import java.util.Locale

/** Bottom sheet with the full detail for one report, clip or post. */
class ReportSheet(
    private val context: Context,
    private val sighting: Sighting,
    private val saved: Boolean = false,
    private val onSaveToggle: ((Boolean) -> Unit)? = null,
) {
    var onDismiss: (() -> Unit)? = null
    var dialog: Dialog? = null
        private set
    private var webView: WebView? = null

    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_detail, null)
        bind(view)

        val sheet = Dialog(context)
        sheet.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        sheet.setContentView(view)
        sheet.setCanceledOnTouchOutside(true)
        sheet.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        sheet.setOnDismissListener {
            onDismiss?.invoke()
            destroyPlayer()
        }
        sheet.show()

        view.post {
            val maxHeight = (context.resources.displayMetrics.heightPixels * 0.88f).toInt()
            if (view.height > maxHeight) {
                val scroll = view.findViewById<ScrollView>(R.id.detailScroll)
                scroll.layoutParams = scroll.layoutParams.apply { height = maxHeight }
            }
        }
        dialog = sheet
    }

    private fun bind(view: View) {
        val video = sighting.video
        val eraColor = if (video != null) {
            ContextCompat.getColor(context, R.color.video_ink)
        } else {
            com.sightings.atlas.model.eraOf(sighting.year).color
        }
        val dot = view.findViewById<View>(R.id.detailEraDot)
        dot.background = context.oval(eraColor)
        val eraLabel = view.findViewById<TextView>(R.id.detailEra)
        eraLabel.setTextColor(eraColor)
        eraLabel.text = when {
            video == null -> "${com.sightings.atlas.model.eraOf(sighting.year).label.uppercase(Locale.US)} · NUFORC"
            video.isTikTok -> "TIKTOK UPLOAD · CLAIMED"
            else -> "VIDEO UPLOAD · THIRD PARTY"
        }

        val date = view.findViewById<TextView>(R.id.detailDate)
        date.text = listOf(
            formatDate(sighting.date),
            sighting.time,
        ).filter { it.isNotBlank() }.joinToString(" · ")

        val title = view.findViewById<TextView>(R.id.detailTitle)
        title.text = sighting.city.ifBlank { sighting.region.ifBlank { sighting.country } }

        val videoTitle = view.findViewById<TextView>(R.id.detailVideoTitle)
        val meta = view.findViewById<LinearLayout>(R.id.detailMeta)
        val summary = view.findViewById<TextView>(R.id.detailSummary)
        val note = view.findViewById<TextView>(R.id.detailNote)
        val thumbFrame = view.findViewById<FrameLayout>(R.id.detailThumbFrame)
        val thumb = view.findViewById<ImageView>(R.id.detailThumb)
        val thumbBadge = view.findViewById<TextView>(R.id.detailThumbBadge)
        val play = view.findViewById<ImageView>(R.id.detailPlay)
        val footLeft = view.findViewById<TextView>(R.id.detailFootLeft)
        val footLink = view.findViewById<TextView>(R.id.detailFootLink)

        if (video != null) {
            thumbFrame.visibility = View.VISIBLE
            thumbBadge.visibility = if (video.isTikTok) View.VISIBLE else View.GONE
            thumbBadge.text = "TikTok clip"
            val open = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(video.watchUrl)))
                } catch (_: ActivityNotFoundException) {
                }
            }
            if (video.isTikTok) {
                thumb.visibility = View.GONE
                play.visibility = View.VISIBLE
                thumbBadge.visibility = View.VISIBLE
                thumbBadge.text = context.getString(R.string.tap_to_play)
                thumbFrame.background = context.rounded(
                    context.colorOf(R.color.sheet),
                    context.colorOf(R.color.line),
                    10f,
                )
                val frameParams = thumbFrame.layoutParams
                frameParams.height = context.dp(600f)
                thumbFrame.layoutParams = frameParams
            } else {
                ThumbCache.load(video.id, thumb)
                play.visibility = View.VISIBLE
            }
            val playInline = {
                startInlinePlayer(thumbFrame, thumb, play, thumbBadge, video)
            }
            thumbFrame.setOnClickListener { playInline() }
            play.setOnClickListener { playInline() }
            footLeft.text = if (video.isTikTok) {
                "TIKTOK · ${video.channel}".uppercase(Locale.US)
            } else {
                "YOUTUBE · ${video.channel}".uppercase(Locale.US)
            }
            footLink.text = context.getString(
                if (video.isTikTok) R.string.open_tiktok else R.string.watch_youtube,
            )
            footLink.setOnClickListener { open() }
        } else {
            thumbFrame.visibility = View.GONE
            footLeft.text = context.getString(R.string.nufo_report).uppercase(Locale.US)
            if (sighting.hasPosition) {
                footLink.text = context.getString(R.string.open_maps)
                footLink.setOnClickListener { openMaps() }
            } else {
                footLink.text = ""
            }
        }

        if (video == null) {
            meta.addView(chip(shapeLabel(sighting.shape), context.colorOf(R.color.mint)))
            if (sighting.duration.isNotBlank()) meta.addView(chip(sighting.duration))
            if (sighting.region.isNotBlank()) meta.addView(chip(sighting.region))
            meta.addView(chip(sighting.country))
            meta.addView(chip(context.getString(R.string.city_level)))
            if (sighting.hasPosition) meta.addView(coordsChip())
        } else {
            meta.addView(chip(context.getString(R.string.third_party), context.colorOf(R.color.warn)))
            meta.addView(chip(sighting.country))
            if (sighting.hasPosition) meta.addView(coordsChip())
        }

        if (video == null) {
            videoTitle.visibility = View.GONE
            summary.text = sighting.summary
            note.visibility = View.GONE
        } else {
            if (video.isTikTok) {
                videoTitle.text = sighting.summary
                videoTitle.visibility = View.VISIBLE
                summary.visibility = View.GONE
            } else {
                videoTitle.text = video.title
                videoTitle.visibility = View.VISIBLE
                summary.text = sighting.city
                summary.visibility = if (sighting.city.isBlank()) View.GONE else View.VISIBLE
            }
            note.text = video.note
            note.visibility = if (video.note.isBlank()) View.GONE else View.VISIBLE
        }

        var isSaved = saved
        val save = view.findViewById<TextView>(R.id.detailSave)
        val paintSave = {
            save.text = if (isSaved) "★" else "☆"
            save.setTextColor(context.colorOf(if (isSaved) R.color.mint else R.color.dim))
        }
        paintSave()
        save.setOnClickListener {
            isSaved = !isSaved
            paintSave()
            onSaveToggle?.invoke(isSaved)
        }
        view.findViewById<TextView>(R.id.detailShare).setOnClickListener { share() }
        view.findViewById<TextView>(R.id.detailClose).setOnClickListener { dialog?.dismiss() }
    }

    private fun coordsChip(): TextView = chip(coordLabel(sighting.lat, sighting.lon)).apply {
        setOnLongClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Coordinates", "${sighting.lat}, ${sighting.lon}"))
            Toast.makeText(context, R.string.coords_copied, Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun openMaps() {
        val geo = Uri.parse(
            "geo:${sighting.lat},${sighting.lon}?q=${sighting.lat}%2C${sighting.lon}" +
                "(${Uri.encode(sighting.placeLabel())})",
        )
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, geo))
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(
                            "https://www.google.com/maps/search/?api=1&query=" +
                                "${sighting.lat}%2C${sighting.lon}",
                        ),
                    ),
                )
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    private fun share() {
        val body = buildString {
            append(formatDate(sighting.date))
            if (sighting.time.isNotBlank()) {
                append(" · ")
                append(sighting.time)
            }
            append("\n")
            append(sighting.placeLabel())
            if (sighting.country.isNotBlank()) {
                append(" · ")
                append(sighting.country)
            }
            if (sighting.video == null) {
                append("\n")
                append(shapeLabel(sighting.shape))
            }
            if (sighting.summary.isNotBlank()) {
                append("\n\n")
                append(sighting.summary.take(300))
            }
            append("\n\nSightings · sightings://report/").append(sighting.id)
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "${formatDate(sighting.date)} · Sightings")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            context.startActivity(Intent.createChooser(send, null))
        } catch (_: ActivityNotFoundException) {
        }
    }

    /** Plays the clip in an embedded player while keeping the user inside the app. */
    private fun startInlinePlayer(
        frame: FrameLayout,
        thumb: ImageView,
        play: ImageView,
        badge: TextView,
        video: VideoInfo,
    ) {
        if (webView != null || video.id.isBlank()) return
        thumb.visibility = View.GONE
        play.visibility = View.GONE
        badge.visibility = View.GONE

        val isTikTok = video.isTikTok
        if (!isTikTok) {
            val width = if (frame.width > 0) {
                frame.width
            } else {
                context.resources.displayMetrics.widthPixels - context.dp(40f)
            }
            frame.layoutParams = frame.layoutParams.apply {
                height = (width * 9 / 16).coerceAtLeast(context.dp(180f))
            }
        }
        frame.background = context.rounded(Color.BLACK, context.colorOf(R.color.line), 10f)

        val web = WebView(context)
        web.setBackgroundColor(Color.BLACK)
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.webViewClient = WebViewClient()

        val layout = if (isTikTok) {
            FrameLayout.LayoutParams(context.dp(330f), context.dp(578f), Gravity.CENTER)
        } else {
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
        }
        frame.addView(web, layout)

        if (video.isTikTok) {
            val html = """
                <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                <style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden;}iframe{width:100%;height:100%;border:0;}</style>
                </head><body>
                <iframe src="https://www.tiktok.com/embed/v2/${video.id}" allow="autoplay; encrypted-media; fullscreen" allowfullscreen></iframe>
                </body></html>
            """.trimIndent()
            web.loadDataWithBaseURL("https://www.tiktok.com/", html, "text/html", "UTF-8", null)
        } else {
            val html = """
                <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                <meta name="referrer" content="strict-origin-when-cross-origin">
                <style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden;}iframe{width:100%;height:100%;border:0;}</style>
                </head><body>
                <iframe src="https://www.youtube-nocookie.com/embed/${video.id}?autoplay=1&playsinline=1&rel=0"
                    referrerpolicy="strict-origin-when-cross-origin"
                    allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
                    allowfullscreen></iframe>
                </body></html>
            """.trimIndent()
            web.loadDataWithBaseURL("https://www.youtube-nocookie.com/", html, "text/html", "UTF-8", null)
        }
        webView = web
    }

    fun setSaved(saved: Boolean) {
        val save = dialog?.findViewById<TextView>(R.id.detailSave) ?: return
        save.text = if (saved) "★" else "☆"
        save.setTextColor(context.colorOf(if (saved) R.color.mint else R.color.dim))
    }

    fun pausePlayer() {
        webView?.onPause()
    }

    fun resumePlayer() {
        webView?.onResume()
    }

    private fun destroyPlayer() {
        val web = webView ?: return
        webView = null
        web.onPause()
        web.stopLoading()
        web.loadUrl("about:blank")
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
    }

    private fun chip(text: String, color: Int = context.colorOf(R.color.muted)): TextView {
        val tv = context.monoText(9f, color, 0.08f)
        tv.text = text.uppercase(Locale.US)
        tv.setBackgroundResource(R.drawable.bg_meta)
        tv.setPadding(context.dp(9f), context.dp(5f), context.dp(9f), context.dp(5f))
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        params.marginEnd = context.dp(6f)
        tv.layoutParams = params
        return tv
    }
}
