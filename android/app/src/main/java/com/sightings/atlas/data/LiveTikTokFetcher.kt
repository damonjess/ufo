package com.sightings.atlas.data

import android.content.Context
import com.sightings.atlas.model.Sighting
import com.sightings.atlas.model.VideoInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Dynamically discovers UFO/UAP TikTok clips for a geographic region (such as the UK)
 * using public search + TikTok's page hydration metadata & official oEmbed endpoint,
 * geocodes UK place names, and caches discovered clips locally on device.
 */
object LiveTikTokFetcher {

    private const val CACHE_FILE = "live_social_cache.json"
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"

    private val executor = Executors.newSingleThreadExecutor()
    private val fetchedRegions = ConcurrentHashMap<String, Long>()

    private val TIKTOK_VIDEO_ID_REGEX =
        Regex("""tiktok\.com/@([^/?#"&\s]+)/video/(\d{15,25})""", RegexOption.IGNORE_CASE)
    private val DESC_REGEX = Regex(""""desc"\s*:\s*"((?:\\.|[^"\\])*)"""")
    private val POI_REGEX = Regex(""""poi"\s*:\s*\{[^}]*"name"\s*:\s*"((?:\\.|[^"\\])*)"""")
    private val LOC_CREATED_REGEX = Regex(""""locationCreated"\s*:\s*"([A-Z]{2})"""")
    private val UFO_KEYWORD_REGEX =
        Regex("""\b(?:ufo|uap|ufos|uaps|orb|orbs|alien|aliens|sighting|sky|lights|craft|object)\b""", RegexOption.IGNORE_CASE)

    /** Built-in UK & Ireland gazetteer for instant zero-latency coordinate lookup. */
    private val UK_GAZETTEER = linkedMapOf(
        "crystal palace" to ("Crystal Palace, London" to (51.42 to -0.07)),
        "manchester airport" to ("Manchester Airport, UK" to (53.35 to -2.27)),
        "london" to ("London, UK" to (51.51 to -0.13)),
        "manchester" to ("Manchester, UK" to (53.48 to -2.24)),
        "birmingham" to ("Birmingham, UK" to (52.49 to -1.90)),
        "leeds" to ("Leeds, UK" to (53.80 to -1.55)),
        "glasgow" to ("Glasgow, Scotland" to (55.86 to -4.25)),
        "edinburgh" to ("Edinburgh, Scotland" to (55.95 to -3.19)),
        "liverpool" to ("Liverpool, UK" to (53.41 to -2.99)),
        "bristol" to ("Bristol, UK" to (51.45 to -2.59)),
        "sheffield" to ("Sheffield, UK" to (53.38 to -1.47)),
        "cardiff" to ("Cardiff, Wales" to (51.48 to -3.18)),
        "belfast" to ("Belfast, Northern Ireland" to (54.60 to -5.93)),
        "newcastle" to ("Newcastle, UK" to (54.98 to -1.61)),
        "nottingham" to ("Nottingham, UK" to (52.95 to -1.15)),
        "cornwall" to ("Cornwall, UK" to (50.27 to -5.05)),
        "devon" to ("Devon, UK" to (50.72 to -3.53)),
        "yorkshire" to ("Yorkshire, UK" to (53.96 to -1.08)),
        "york" to ("York, UK" to (53.96 to -1.08)),
        "lakenheath" to ("RAF Lakenheath, Suffolk" to (52.41 to 0.56)),
        "mildenhall" to ("RAF Mildenhall, Suffolk" to (52.36 to 0.49)),
        "rendlesham" to ("Rendlesham, Suffolk" to (52.09 to 1.43)),
        "suffolk" to ("Suffolk, UK" to (52.19 to 0.99)),
        "norfolk" to ("Norfolk, UK" to (52.63 to 1.30)),
        "essex" to ("Essex, UK" to (51.73 to 0.47)),
        "kent" to ("Kent, UK" to (51.28 to 0.52)),
        "surrey" to ("Surrey, UK" to (51.31 to -0.56)),
        "sussex" to ("Sussex, UK" to (50.91 to -0.46)),
        "brighton" to ("Brighton, UK" to (50.82 to -0.14)),
        "hampshire" to ("Hampshire, UK" to (51.06 to -1.31)),
        "dorset" to ("Dorset, UK" to (50.75 to -2.34)),
        "somerset" to ("Somerset, UK" to (51.10 to -2.93)),
        "glastonbury" to ("Glastonbury, UK" to (51.15 to -2.71)),
        "wiltshire" to ("Wiltshire, UK" to (51.35 to -1.99)),
        "stonehenge" to ("Stonehenge, Wiltshire" to (51.18 to -1.83)),
        "oxford" to ("Oxford, UK" to (51.75 to -1.26)),
        "cambridge" to ("Cambridge, UK" to (52.21 to 0.12)),
        "peak district" to ("Peak District, UK" to (53.35 to -1.78)),
        "lake district" to ("Lake District, UK" to (54.46 to -3.09)),
        "lancashire" to ("Lancashire, UK" to (53.76 to -2.70)),
        "blackpool" to ("Blackpool, UK" to (53.82 to -3.05)),
        "aberdeen" to ("Aberdeen, Scotland" to (57.15 to -2.09)),
        "inverness" to ("Inverness, Scotland" to (57.48 to -4.22)),
        "calvine" to ("Calvine, Scotland" to (56.77 to -3.85)),
        "bonnybridge" to ("Bonnybridge, Scotland" to (56.00 to -3.88)),
        "falkirk" to ("Falkirk, Scotland" to (56.00 to -3.78)),
        "scotland" to ("Scottish Highlands, UK" to (56.49 to -4.20)),
        "swansea" to ("Swansea, Wales" to (51.62 to -3.94)),
        "pontypool" to ("Pontypool, Wales" to (51.70 to -3.04)),
        "snowdonia" to ("Snowdonia, Wales" to (52.90 to -3.85)),
        "wales" to ("Wales, UK" to (52.13 to -3.78)),
        "england" to ("England, UK" to (52.80 to -1.50)),
        "british" to ("England, UK" to (52.80 to -1.50)),
        "britain" to ("United Kingdom" to (53.50 to -2.20)),
        "united kingdom" to ("United Kingdom" to (53.50 to -2.20)),
    )

    fun isInUkRegion(lat: Double, lon: Double): Boolean =
        lat in 49.5..61.0 && lon in -10.5..2.5

    /** Load any previously discovered TikTok clips saved on device. */
    fun loadCached(context: Context): List<Sighting> {
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            val out = ArrayList<Sighting>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val handle = o.optString("handle", "tiktok")
                val clipId = o.optString("id")
                if (clipId.isBlank()) continue
                val posted = o.optString("posted")
                val caption = o.optString("caption")
                val place = o.optString("place").ifBlank { "United Kingdom" }
                val lat = o.optDouble("lat", Double.NaN)
                val lon = o.optDouble("lon", Double.NaN)
                out.add(
                    Sighting(
                        id = 750000 + o.optInt("n", i + 1),
                        date = posted,
                        year = posted.take(4).toIntOrNull() ?: 2026,
                        time = "",
                        city = place,
                        region = "United Kingdom",
                        cc = "gb",
                        country = "@$handle",
                        shape = "video",
                        duration = "",
                        summary = caption,
                        lat = lat,
                        lon = lon,
                        video = VideoInfo(
                            id = clipId,
                            title = caption,
                            channel = "@$handle",
                            note = "Caption, place and date are as posted by the uploader — none of it is verified.",
                            platform = "tiktok",
                            url = "https://www.tiktok.com/@$handle/video/$clipId",
                        ),
                    ),
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    /**
     * Fetches UK TikTok UFO clips in the background when the user hovers over the UK,
     * extracts caption and POI metadata from TikTok, and invokes [onNewClips] when new
     * sightings are discovered.
     */
    fun fetchUkClipsAsync(
        context: Context,
        existingIds: Set<String>,
        onNewClips: (List<Sighting>) -> Unit,
    ) {
        val now = System.currentTimeMillis()
        val last = fetchedRegions["uk"] ?: 0L
        if (now - last < 5 * 60_000L) return
        fetchedRegions["uk"] = now

        executor.execute {
            val discovered = ArrayList<Sighting>()
            val seen = HashSet(existingIds)
            val cachedArr = runCatching {
                val f = File(context.filesDir, CACHE_FILE)
                if (f.exists()) JSONArray(f.readText()) else JSONArray()
            }.getOrDefault(JSONArray())
            for (i in 0 until cachedArr.length()) {
                seen.add(cachedArr.optJSONObject(i)?.optString("id").orEmpty())
            }

            val queries = listOf(
                "site:tiktok.com \"video\" ufo uk",
                "site:tiktok.com \"video\" ufo london OR manchester OR scotland",
                "site:tiktok.com \"video\" uap lakenheath OR england OR wales",
            )

            val candidates = LinkedHashMap<String, String>()
            for (q in queries) {
                val searchUrl = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8")
                val html = httpGet(searchUrl) ?: continue
                val decoded = html
                    .replace("%2F", "/", ignoreCase = true)
                    .replace("%40", "@", ignoreCase = true)
                    .replace("%3A", ":", ignoreCase = true)
                for (m in TIKTOK_VIDEO_ID_REGEX.findAll(decoded)) {
                    val handle = m.groupValues[1]
                    val id = m.groupValues[2]
                    if (id !in seen) {
                        candidates.putIfAbsent(id, handle)
                    }
                }
            }

            var nextN = cachedArr.length() + 100
            for ((clipId, rawHandle) in candidates) {
                if (!seen.add(clipId)) continue
                val canonicalUrl = "https://www.tiktok.com/@$rawHandle/video/$clipId"
                val pageHtml = httpGet(canonicalUrl)
                val rawDesc = pageHtml?.let { DESC_REGEX.find(it)?.groupValues?.get(1) }
                    ?.let { unescapeJson(it) }
                    .orEmpty()
                val poiName = pageHtml?.let { POI_REGEX.find(it)?.groupValues?.get(1) }
                    ?.let { unescapeJson(it) }
                    .orEmpty()
                val locCreated = pageHtml?.let { LOC_CREATED_REGEX.find(it)?.groupValues?.get(1) }
                    .orEmpty()

                if (rawDesc.isNotBlank() && !UFO_KEYWORD_REGEX.containsMatchIn("$rawDesc $rawHandle")) {
                    continue
                }

                val handle = rawHandle.removePrefix("@").ifBlank { "tiktok" }
                val caption = cleanCaption(rawDesc).ifBlank { "UK UFO/UAP sighting clip posted by @$handle." }
                val (place, coords) = inferUkPlace("$poiName $rawDesc", locCreated) ?: continue
                val posted = snowflakeDate(clipId)

                val obj = JSONObject().apply {
                    put("n", nextN)
                    put("id", clipId)
                    put("handle", handle)
                    put("caption", caption)
                    put("place", place)
                    put("lat", coords.first)
                    put("lon", coords.second)
                    put("posted", posted)
                }
                cachedArr.put(obj)

                discovered.add(
                    Sighting(
                        id = 750000 + nextN,
                        date = posted,
                        year = posted.take(4).toIntOrNull() ?: 2026,
                        time = "",
                        city = place,
                        region = "United Kingdom",
                        cc = "gb",
                        country = "@$handle",
                        shape = "video",
                        duration = "",
                        summary = caption,
                        lat = coords.first,
                        lon = coords.second,
                        video = VideoInfo(
                            id = clipId,
                            title = caption,
                            channel = "@$handle",
                            note = "Caption, place and date are as posted by the uploader — none of it is verified.",
                            platform = "tiktok",
                            url = "https://www.tiktok.com/@$handle/video/$clipId",
                        ),
                    ),
                )
                nextN++
            }

            if (discovered.isNotEmpty()) {
                runCatching {
                    File(context.filesDir, CACHE_FILE).writeText(cachedArr.toString(2))
                }
                onNewClips(discovered)
            }
        }
    }

    private fun unescapeJson(raw: String): String =
        runCatching { JSONObject("{\"v\":\"$raw\"}").optString("v", raw) }.getOrDefault(raw)

    private fun cleanCaption(raw: String): String {
        val trimmed = raw.replace(Regex("\\s+"), " ")
            .replace(Regex("#[^\\s#]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (trimmed.length > 220) trimmed.take(217) + "…" else trimmed
    }

    private fun inferUkPlace(text: String, locCreated: String): Pair<String, Pair<Double, Double>>? {
        val lower = text.lowercase(Locale.US)
        for ((key, entry) in UK_GAZETTEER) {
            if (lower.contains(key)) {
                return entry
            }
        }
        if (locCreated == "GB" || lower.contains("uk") || lower.contains("🇬🇧")) {
            return "United Kingdom" to (53.50 to -2.20)
        }
        return null
    }

    private fun snowflakeDate(id: String): String {
        return runCatching {
            val ms = (id.toULong() shr 32).toLong() * 1000L
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            sdf.format(Date(ms))
        }.getOrDefault("2026-01-01")
    }

    private fun httpGet(urlStr: String): String? {
        return runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/json")
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            text
        }.getOrNull()
    }
}
