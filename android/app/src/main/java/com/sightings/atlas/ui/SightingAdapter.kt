package com.sightings.atlas.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sightings.atlas.R
import com.sightings.atlas.model.Sighting
import com.sightings.atlas.model.distanceKm
import com.sightings.atlas.model.eraOf
import com.sightings.atlas.model.shapeLabel
import com.sightings.atlas.model.shortDate
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class SightingAdapter(
    private val videoStyle: Boolean,
    private val onClick: (Sighting) -> Unit,
) : RecyclerView.Adapter<SightingAdapter.Holder>() {

    private var items: List<Sighting> = emptyList()

    /** When set as [lat, lon], report rows append the distance from this point. */
    var origin: DoubleArray? = null

    fun submit(list: List<Sighting>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = if (videoStyle) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = if (viewType == 1) R.layout.item_video else R.layout.item_sighting
        return Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false), viewType == 1)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], origin, onClick)
    }

    class Holder(view: View, private val video: Boolean) : RecyclerView.ViewHolder(view) {
        private val eraBar: View? = view.findViewById(R.id.eraBar)
        private val dayText: TextView? = view.findViewById(R.id.dayText)
        private val monthYearText: TextView? = view.findViewById(R.id.monthYearText)
        private val placeText: TextView? = view.findViewById(R.id.placeText)
        private val subText: TextView? = view.findViewById(R.id.subText)
        private val thumb: ImageView? = view.findViewById(R.id.videoThumb)
        private val badge: TextView? = view.findViewById(R.id.videoBadge)
        private val videoTitle: TextView? = view.findViewById(R.id.videoTitle)
        private val videoSub: TextView? = view.findViewById(R.id.videoSub)
        private val videoChannel: TextView? = view.findViewById(R.id.videoChannel)

        fun bind(sighting: Sighting, from: DoubleArray?, onClick: (Sighting) -> Unit) {
            val root = itemView
            root.setOnClickListener { onClick(sighting) }
            if (video) bindVideo(sighting) else bindReport(sighting, from)
        }

        private fun bindReport(sighting: Sighting, from: DoubleArray?) {
            val date = shortDate(sighting.date)
            dayText?.text = date.day
            monthYearText?.text = "${date.month} ${date.year}".trim()
            placeText?.text = sighting.placeLabel()
            val shape = shapeLabel(sighting.shape)
            val duration = sighting.duration.trim()
            subText?.text = buildString {
                append(shape)
                append(" · ")
                append(sighting.country)
                if (duration.isNotEmpty()) {
                    append(" · ")
                    append(duration)
                }
                if (from != null && sighting.hasPosition) {
                    append(" · ")
                    append(distanceKm(from[0], from[1], sighting.lat, sighting.lon).roundToInt())
                    append(" km")
                }
            }
            eraBar?.setBackgroundColor(eraOf(sighting.year).color)
        }

        private fun bindVideo(sighting: Sighting) {
            val videoInfo = sighting.video
            val isTikTok = videoInfo?.isTikTok == true
            videoTitle?.text = if (isTikTok) sighting.summary else videoInfo?.title.orEmpty()
            videoSub?.text = listOf(
                com.sightings.atlas.model.formatDate(sighting.date),
                sighting.city,
            ).filter { it.isNotBlank() }.joinToString(" · ")
            videoChannel?.text = if (isTikTok) {
                "TIKTOK · ${sighting.country}".uppercase(Locale.US)
            } else {
                (videoInfo?.channel ?: sighting.country).uppercase(Locale.US)
            }

            if (videoInfo != null && !isTikTok) {
                thumb?.visibility = View.VISIBLE
                thumb?.tag = videoInfo.id
                if (thumb != null) ThumbCache.load(videoInfo.id, thumb)
                badge?.visibility = View.GONE
            } else {
                thumb?.tag = null
                thumb?.setImageDrawable(null)
                thumb?.visibility = View.GONE
                badge?.visibility = View.VISIBLE
                badge?.text = "TIKTOK"
            }
        }
    }
}

/** Small shared YouTube thumbnail cache; failures silently leave the frame empty. */
object ThumbCache {
    private val cache = object : LruCache<String, Bitmap>(80) {}
    private val executor = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())

    fun load(videoId: String, view: ImageView) {
        if (videoId.isBlank()) return
        val hit = cache.get(videoId)
        if (hit != null) {
            view.setImageBitmap(hit)
            return
        }
        view.setImageDrawable(null)
        executor.execute {
            val bitmap = fetch(videoId)
            if (bitmap != null) {
                cache.put(videoId, bitmap)
                handler.post {
                    if (view.tag == videoId) view.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun fetch(videoId: String): Bitmap? = try {
        val connection = URL("https://img.youtube.com/vi/$videoId/hqdefault.jpg").openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 6000
        connection.doInput = true
        val bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it) }
        connection.disconnect()
        bitmap
    } catch (_: Exception) {
        null
    }
}
