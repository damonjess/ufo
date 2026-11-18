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
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Dynamically discovers real, playable TikTok UFO/UAP clips using TikWM's
 * TikTok search API with persistent cursor pagination across 24 regional &
 * UFO queries, geocodes 110+ UK places, and caches discovered clips on device.
 */
object LiveTikTokFetcher {

    private const val CACHE_FILE = "live_tiktok_uk_cache.json"
    private const val PREFS_NAME = "atlas_prefs"
    private const val KEY_SCAN_PASS = "tiktok_scan_pass"
    private val OLD_CACHE_FILES = arrayOf("live_social_cache.json", "live_uk_video_cache_v2.json")

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Magic 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    private val executor = Executors.newSingleThreadExecutor()
    private val fetchedRegions = ConcurrentHashMap<String, Long>()

    private val TIKTOK_VIDEO_ID_REGEX =
        Regex("""tiktok\.com/@([^/?#"&\s]+)/video/(\d{15,25})""", RegexOption.IGNORE_CASE)

    // Substring match (no trailing \b) so #ufosighting, #ufotok, #ufovideo, #uapsighting all match
    private val UFO_KEYWORD_REGEX =
        Regex(
            """(?:ufo|uap|orb|alien|sighting|lights|craft|object|sphere|triangle|tictac|tic\s*tac|drone|hover|anomal|lakenheath|rendlesham|calvine|bonnybridge|pentyrch|skywatch|nightsky)""",
            RegexOption.IGNORE_CASE,
        )

    private data class SearchQuery(
        val keyword: String,
        val fallbackPlace: String,
        val fallbackLat: Double,
        val fallbackLon: Double,
    )

    private val TIKTOK_QUERIES = listOf(
        SearchQuery("ufo sighting uk", "United Kingdom", 53.500, -2.200),
        SearchQuery("ufo uk", "England, UK", 52.800, -1.500),
        SearchQuery("uap uk", "United Kingdom", 53.230, -0.540),
        SearchQuery("ufo london", "London, UK", 51.507, -0.127),
        SearchQuery("ufo manchester", "Manchester, UK", 53.480, -2.242),
        SearchQuery("ufo yorkshire", "Yorkshire, UK", 53.959, -1.081),
        SearchQuery("ufo lincolnshire", "Lincolnshire, UK", 53.589, -0.650),
        SearchQuery("ufo scotland", "Scotland, UK", 56.002, -3.888),
        SearchQuery("ufo wales", "Wales, UK", 51.668, -3.195),
        SearchQuery("ufo lakenheath", "RAF Lakenheath, Suffolk", 52.409, 0.561),
        SearchQuery("ufo birmingham", "Birmingham, West Midlands", 52.486, -1.890),
        SearchQuery("ufo liverpool", "Liverpool, Merseyside", 53.408, -2.991),
        SearchQuery("ufo bristol", "Bristol, UK", 51.454, -2.587),
        SearchQuery("ufo newcastle", "Newcastle upon Tyne, UK", 54.978, -1.617),
        SearchQuery("ufoglasgow", "Glasgow, Scotland", 55.864, -4.251),
        SearchQuery("ufo edinburgh", "Edinburgh, Scotland", 55.953, -3.188),
        SearchQuery("ufo cardiff", "Cardiff, Wales", 51.481, -3.179),
        SearchQuery("ufo cornwall", "Cornwall, UK", 50.266, -5.052),
        SearchQuery("ufo essex", "Essex, UK", 51.734, 0.468),
        SearchQuery("ufo kent", "Kent, UK", 51.278, 0.521),
        SearchQuery("ufo sighting night sky", "United Kingdom", 53.400, -1.800),
        SearchQuery("orange orb ufo", "United Kingdom", 53.550, -0.650),
        SearchQuery("triangle ufo sighting", "United Kingdom", 52.950, -1.150),
        SearchQuery("metallic orb ufo", "United Kingdom", 53.353, -2.274),
    )

    private val UK_GAZETTEER = linkedMapOf(
        "scunthorpe" to ("Scunthorpe, North Lincolnshire" to (53.589 to -0.650)),
        "barton" to ("Barton-upon-Humber, Lincolnshire" to (53.683 to -0.450)),
        "grimsby" to ("Grimsby, North East Lincolnshire" to (53.567 to -0.080)),
        "cleethorpes" to ("Cleethorpes, Lincolnshire" to (53.558 to -0.027)),
        "humberside" to ("Humberside, UK" to (53.576 to -0.350)),
        "humber" to ("Humber Estuary, UK" to (53.650 to -0.300)),
        "hull" to ("Hull, East Yorkshire" to (53.745 to -0.336)),
        "doncaster" to ("Doncaster, South Yorkshire" to (53.522 to -1.131)),
        "rotherham" to ("Rotherham, South Yorkshire" to (53.432 to -1.356)),
        "barnsley" to ("Barnsley, South Yorkshire" to (53.552 to -1.479)),
        "sheffield" to ("Sheffield, South Yorkshire" to (53.381 to -1.470)),
        "leeds" to ("Leeds, West Yorkshire" to (53.800 to -1.549)),
        "bradford" to ("Bradford, West Yorkshire" to (53.795 to -1.759)),
        "wakefield" to ("Wakefield, West Yorkshire" to (53.683 to -1.497)),
        "huddersfield" to ("Huddersfield, West Yorkshire" to (53.645 to -1.785)),
        "todmorden" to ("Todmorden, West Yorkshire" to (53.713 to -2.096)),
        "ilkley" to ("Ilkley Moor, West Yorkshire" to (53.925 to -1.822)),
        "harrogate" to ("Harrogate, North Yorkshire" to (53.992 to -1.541)),
        "scarborough" to ("Scarborough, North Yorkshire" to (54.283 to -0.404)),
        "whitby" to ("Whitby, North Yorkshire" to (54.486 to -0.613)),
        "fylingdales" to ("RAF Fylingdales, North Yorkshire" to (54.361 to -0.670)),
        "menwith hill" to ("RAF Menwith Hill, North Yorkshire" to (54.008 to -1.688)),
        "york" to ("York, North Yorkshire" to (53.959 to -1.081)),
        "yorkshire" to ("Yorkshire, UK" to (53.959 to -1.081)),
        "coningsby" to ("RAF Coningsby, Lincolnshire" to (53.093 to -0.166)),
        "waddington" to ("RAF Waddington, Lincolnshire" to (53.166 to -0.524)),
        "skegness" to ("Skegness, Lincolnshire" to (53.144 to 0.339)),
        "louth" to ("Louth, Lincolnshire" to (53.367 to -0.004)),
        "lincoln" to ("Lincoln, Lincolnshire" to (53.230 to -0.540)),
        "lincolnshire" to ("Lincolnshire, UK" to (53.230 to -0.540)),
        "lakenheath" to ("RAF Lakenheath, Suffolk" to (52.409 to 0.561)),
        "mildenhall" to ("RAF Mildenhall, Suffolk" to (52.361 to 0.486)),
        "feltwell" to ("RAF Feltwell, Norfolk" to (52.476 to 0.519)),
        "rendlesham" to ("Rendlesham Forest, Suffolk" to (52.092 to 1.442)),
        "bentwaters" to ("RAF Bentwaters, Suffolk" to (52.128 to 1.439)),
        "brize norton" to ("RAF Brize Norton, Oxfordshire" to (51.750 to -1.583)),
        "manchester airport" to ("Manchester Airport, UK" to (53.353 to -2.274)),
        "heathrow" to ("Heathrow Airport, London" to (51.470 to -0.454)),
        "gatwick" to ("Gatwick Airport, Sussex" to (51.153 to -0.182)),
        "stansted" to ("Stansted Airport, Essex" to (51.886 to 0.238)),
        "crystal palace" to ("Crystal Palace, London" to (51.420 to -0.070)),
        "manchester" to ("Manchester, UK" to (53.480 to -2.242)),
        "salford" to ("Salford, Greater Manchester" to (53.487 to -2.290)),
        "liverpool" to ("Liverpool, Merseyside" to (53.408 to -2.991)),
        "chester" to ("Chester, Cheshire" to (53.193 to -2.893)),
        "preston" to ("Preston, Lancashire" to (53.763 to -2.703)),
        "blackpool" to ("Blackpool, Lancashire" to (53.817 to -3.055)),
        "lancashire" to ("Lancashire, UK" to (53.763 to -2.703)),
        "lake district" to ("Lake District, Cumbria" to (54.460 to -3.088)),
        "cumbria" to ("Cumbria, UK" to (54.460 to -3.088)),
        "newcastle" to ("Newcastle upon Tyne, UK" to (54.978 to -1.617)),
        "sunderland" to ("Sunderland, Tyne and Wear" to (54.906 to -1.383)),
        "middlesbrough" to ("Middlesbrough, Teesside" to (54.574 to -1.235)),
        "durham" to ("Durham, County Durham" to (54.775 to -1.584)),
        "birmingham" to ("Birmingham, West Midlands" to (52.486 to -1.890)),
        "coventry" to ("Coventry, West Midlands" to (52.406 to -1.519)),
        "wolverhampton" to ("Wolverhampton, West Midlands" to (52.586 to -2.128)),
        "cannock chase" to ("Cannock Chase, Staffordshire" to (52.738 to -2.002)),
        "stoke" to ("Stoke-on-Trent, Staffordshire" to (53.002 to -2.179)),
        "nottingham" to ("Nottingham, Nottinghamshire" to (52.954 to -1.158)),
        "derby" to ("Derby, Derbyshire" to (52.922 to -1.474)),
        "peak district" to ("Peak District, Derbyshire" to (53.350 to -1.780)),
        "leicester" to ("Leicester, Leicestershire" to (52.636 to -1.139)),
        "cambridge" to ("Cambridge, Cambridgeshire" to (52.205 to 0.121)),
        "norwich" to ("Norwich, Norfolk" to (52.630 to 1.297)),
        "norfolk" to ("Norfolk, UK" to (52.630 to 1.297)),
        "ipswich" to ("Ipswich, Suffolk" to (52.056 to 1.148)),
        "suffolk" to ("Suffolk, UK" to (52.187 to 0.970)),
        "london" to ("London, UK" to (51.507 to -0.127)),
        "essex" to ("Essex, UK" to (51.734 to 0.468)),
        "kent" to ("Kent, UK" to (51.278 to 0.521)),
        "surrey" to ("Surrey, UK" to (51.310 to -0.560)),
        "brighton" to ("Brighton, Sussex" to (50.822 to -0.137)),
        "sussex" to ("Sussex, UK" to (50.909 to -0.461)),
        "portsmouth" to ("Portsmouth, Hampshire" to (50.819 to -1.088)),
        "southampton" to ("Southampton, Hampshire" to (50.909 to -1.404)),
        "hampshire" to ("Hampshire, UK" to (51.057 to -1.308)),
        "oxford" to ("Oxford, Oxfordshire" to (51.752 to -1.257)),
        "dorset" to ("Dorset, UK" to (50.748 to -2.344)),
        "warminster" to ("Warminster, Wiltshire" to (51.204 to -2.179)),
        "stonehenge" to ("Stonehenge, Wiltshire" to (51.178 to -1.826)),
        "wiltshire" to ("Wiltshire, UK" to (51.349 to -1.992)),
        "glastonbury" to ("Glastonbury, Somerset" to (51.149 to -2.714)),
        "somerset" to ("Somerset, UK" to (51.105 to -2.926)),
        "bristol" to ("Bristol, UK" to (51.454 to -2.587)),
        "exeter" to ("Exeter, Devon" to (50.718 to -3.533)),
        "plymouth" to ("Plymouth, Devon" to (50.375 to -4.142)),
        "devon" to ("Devon, UK" to (50.718 to -3.533)),
        "cornwall" to ("Cornwall, UK" to (50.266 to -5.052)),
        "bonnybridge" to ("Bonnybridge, Falkirk" to (56.002 to -3.888)),
        "falkirk" to ("Falkirk, Scotland" to (56.001 to -3.783)),
        "calvine" to ("Calvine, Perthshire" to (56.770 to -3.978)),
        "pitlochry" to ("Pitlochry, Perthshire" to (56.704 to -3.734)),
        "livingston" to ("Livingston, West Lothian" to (55.883 to -3.516)),
        "edinburgh" to ("Edinburgh, Scotland" to (55.953 to -3.188)),
        "glasgow" to ("Glasgow, Scotland" to (55.864 to -4.251)),
        "aberdeen" to ("Aberdeen, Scotland" to (57.149 to -2.094)),
        "inverness" to ("Inverness, Scottish Highlands" to (57.477 to -4.224)),
        "scotland" to ("Scottish Highlands, UK" to (56.490 to -4.202)),
        "pentyrch" to ("Pentyrch, South Wales" to (51.531 to -3.303)),
        "oakdale" to ("Oakdale, Caerphilly" to (51.681 to -3.181)),
        "blackwood" to ("Blackwood, South Wales" to (51.668 to -3.195)),
        "cardiff" to ("Cardiff, Wales" to (51.481 to -3.179)),
        "swansea" to ("Swansea, Wales" to (51.621 to -3.943)),
        "broad haven" to ("Broad Haven, Pembrokeshire" to (51.778 to -5.101)),
        "snowdonia" to ("Snowdonia, North Wales" to (52.949 to -3.886)),
        "wales" to ("Wales, UK" to (52.130 to -3.783)),
        "belfast" to ("Belfast, Northern Ireland" to (54.597 to -5.930)),
        "northern ireland" to ("Northern Ireland, UK" to (54.650 to -6.350)),
        "england" to ("England, UK" to (52.800 to -1.500)),
        "british" to ("England, UK" to (52.800 to -1.500)),
        "britain" to ("United Kingdom" to (53.500 to -2.200)),
        "united kingdom" to ("United Kingdom" to (53.500 to -2.200)),
    )

    fun isInUkRegion(lat: Double, lon: Double): Boolean =
        lat in 49.5..61.0 && lon in -10.5..2.5

    private data class CandidateClip(
        val id: String,
        val handle: String,
        val caption: String,
        val regionCode: String,
        val postedDate: String,
        val query: SearchQuery,
        val fromTikWm: Boolean,
    )

    /** Load previously discovered TikTok clips saved on device. */
    fun loadCached(context: Context): List<Sighting> {
        for (old in OLD_CACHE_FILES) {
            File(context.filesDir, old).delete()
        }
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            val out = ArrayList<Sighting>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val handle = o.optString("handle", "tiktok").removePrefix("@")
                val clipId = o.optString("id")
                if (clipId.length < 15 || !clipId.all { it.isDigit() }) continue
                val posted = o.optString("posted")
                val caption = o.optString("caption")
                val place = o.optString("place").ifBlank { "United Kingdom" }
                val lat = o.optDouble("lat", Double.NaN)
                val lon = o.optDouble("lon", Double.NaN)
                out.add(
                    Sighting(
                        id = 750000 + o.optInt("n", i + 1),
                        date = posted,
                        year = posted.take(4).toIntOrNull() ?: 2025,
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
     * Discovers fresh UK TikTok UFO clips using persistent cursor pagination across
     * 24 queries so every manual scan pulls new TikTok videos not yet in [existingIds].
     */
    fun fetchUkClipsAsync(
        context: Context,
        existingIds: Set<String>,
        force: Boolean = false,
        onNewClips: (List<Sighting>) -> Unit,
    ) {
        val now = System.currentTimeMillis()
        val last = fetchedRegions["uk"] ?: 0L
        if (!force && now - last < 90_000L) return
        fetchedRegions["uk"] = now

        val appCtx = context.applicationContext
        executor.execute {
            for (old in OLD_CACHE_FILES) {
                File(appCtx.filesDir, old).delete()
            }
            val discovered = ArrayList<Sighting>()
            val seen = HashSet(existingIds)
            val cachedArr = runCatching {
                val f = File(appCtx.filesDir, CACHE_FILE)
                if (f.exists()) JSONArray(f.readText()) else JSONArray()
            }.getOrDefault(JSONArray())
            for (i in 0 until cachedArr.length()) {
                seen.add(cachedArr.optJSONObject(i)?.optString("id").orEmpty())
            }

            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var pass = prefs.getInt(KEY_SCAN_PASS, 0)

            val candidates = LinkedHashMap<String, CandidateClip>()

            // Try up to 4 query/cursor combinations until we have at least 12 unseen TikTok clips
            for (attempt in 0 until 4) {
                if (attempt > 0) runCatching { Thread.sleep(1150L) }
                val currentPass = pass++
                val qIdx = currentPass % TIKTOK_QUERIES.size
                val query = TIKTOK_QUERIES[qIdx]
                // Advance cursor (0, 30, 60, 90...) on each cycle through the query list
                val cursor = ((currentPass / TIKTOK_QUERIES.size) * 30) % 240

                val videos = queryTikWm(query.keyword, count = 30, cursor = cursor)
                var unseenInBatch = 0
                if (videos != null) {
                    for (j in 0 until videos.length()) {
                        val v = videos.optJSONObject(j) ?: continue
                        val clipId = v.optString("video_id").ifBlank { v.optString("id") }
                        if (clipId.length < 15 || !clipId.all { it.isDigit() } || clipId in seen) continue
                        val authorObj = v.optJSONObject("author")
                        val handle = authorObj?.optString("unique_id")
                            ?.ifBlank { authorObj.optString("nickname") }
                            .orEmpty()
                            .replace(Regex("\\s+"), "")
                            .removePrefix("@")
                            .ifBlank { "tiktok" }
                        val rawTitle = v.optString("title")
                        val regionCode = v.optString("region").uppercase(Locale.US)
                        val createTime = v.optLong("create_time", 0L)
                        val posted = if (createTime > 1_000_000_000L) {
                            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                                timeZone = TimeZone.getTimeZone("UTC")
                            }
                            sdf.format(Date(createTime * 1000L))
                        } else {
                            snowflakeDate(clipId)
                        }
                        if (candidates.putIfAbsent(
                                clipId,
                                CandidateClip(
                                    id = clipId,
                                    handle = handle,
                                    caption = rawTitle,
                                    regionCode = regionCode,
                                    postedDate = posted,
                                    query = query,
                                    fromTikWm = true,
                                ),
                            ) == null
                        ) {
                            unseenInBatch++
                        }
                    }
                }
                if (candidates.size >= 15) break
            }

            prefs.edit().putInt(KEY_SCAN_PASS, pass).apply()

            // Fallback to web search if TikWM was unreachable
            if (candidates.isEmpty()) {
                val fallbackQuery = TIKTOK_QUERIES[pass % TIKTOK_QUERIES.size]
                val webQ = "site:tiktok.com ${fallbackQuery.keyword}"
                val searchUrl = "https://search.brave.com/search?q=" + URLEncoder.encode(webQ, "UTF-8")
                val html = httpGet(searchUrl)
                if (html != null) {
                    val decoded = html
                        .replace("%2F", "/", ignoreCase = true)
                        .replace("%40", "@", ignoreCase = true)
                    for (m in TIKTOK_VIDEO_ID_REGEX.findAll(decoded)) {
                        val handle = m.groupValues[1].removePrefix("@")
                        val id = m.groupValues[2]
                        if (id !in seen) {
                            candidates.putIfAbsent(
                                id,
                                CandidateClip(
                                    id = id,
                                    handle = handle,
                                    caption = "",
                                    regionCode = "GB",
                                    postedDate = snowflakeDate(id),
                                    query = fallbackQuery,
                                    fromTikWm = false,
                                ),
                            )
                        }
                    }
                }
            }

            var nextN = cachedArr.length() + 100
            for ((clipId, cand) in candidates) {
                if (discovered.size >= 20) break
                if (!seen.add(clipId)) continue

                var handle = cand.handle
                var rawDesc = cand.caption

                // Only call oEmbed if we scraped a raw URL with no caption
                if (!cand.fromTikWm || rawDesc.isBlank()) {
                    val canonicalUrl = "https://www.tiktok.com/@$handle/video/$clipId"
                    val oembed = fetchTikTokOembed(canonicalUrl)
                    if (oembed != null) {
                        val oTitle = oembed.optString("title")
                        if (oTitle.isNotBlank()) rawDesc = oTitle
                        val oAuthor = oembed.optString("author_unique_id")
                        if (oAuthor.isNotBlank()) handle = oAuthor.removePrefix("@")
                    } else if (!cand.fromTikWm) {
                        continue
                    }
                }

                // Filter by UFO/UAP/sky substring keywords
                if (rawDesc.isNotBlank() && !UFO_KEYWORD_REGEX.containsMatchIn("$rawDesc $handle")) {
                    continue
                }

                // Pin to specific UK town if mentioned in caption, otherwise use the query's UK region with scatter
                val matchedUk = inferUkPlace("$rawDesc $handle", cand.regionCode)
                val place: String
                val coords: Pair<Double, Double>
                if (matchedUk != null) {
                    place = matchedUk.first
                    coords = jitterCoords(matchedUk.second.first, matchedUk.second.second, clipId, spreadDeg = 0.04)
                } else {
                    place = cand.query.fallbackPlace
                    coords = jitterCoords(cand.query.fallbackLat, cand.query.fallbackLon, clipId, spreadDeg = 0.45)
                }

                val caption = cleanCaption(rawDesc).ifBlank {
                    "UFO/UAP sighting clip (${cand.query.fallbackPlace}) posted by @$handle."
                }
                val posted = cand.postedDate

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
                        year = posted.take(4).toIntOrNull() ?: 2025,
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
                    val f = File(appCtx.filesDir, CACHE_FILE)
                    val tmp = File(appCtx.filesDir, "$CACHE_FILE.tmp")
                    tmp.writeText(cachedArr.toString(2))
                    tmp.copyTo(f, overwrite = true)
                    tmp.delete()
                }
            }
            onNewClips(discovered)
        }
    }

    /** Queries TikWM via POST (preferred by TikWM) with GET fallback. */
    private fun queryTikWm(keyword: String, count: Int, cursor: Int): JSONArray? {
        val bodyParams = "keywords=${URLEncoder.encode(keyword, "UTF-8")}&count=$count&cursor=$cursor"
        val postText = httpPost("https://www.tikwm.com/api/feed/search", bodyParams)
            ?: httpGet("https://www.tikwm.com/api/feed/search?$bodyParams")
            ?: return null
        val root = runCatching { JSONObject(postText) }.getOrNull() ?: return null
        if (root.optInt("code", -1) != 0) return null
        return root.optJSONObject("data")?.optJSONArray("videos")
    }

    private fun fetchTikTokOembed(videoUrl: String): JSONObject? {
        val url = "https://www.tiktok.com/oembed?url=" + URLEncoder.encode(videoUrl, "UTF-8")
        val body = httpGet(url) ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private fun jitterCoords(
        lat: Double,
        lon: Double,
        seedStr: String,
        spreadDeg: Double,
    ): Pair<Double, Double> {
        val h = seedStr.hashCode()
        val angle = ((h and 0xFFFF) / 65535.0) * (2.0 * Math.PI)
        val dist = (((h ushr 16) and 0xFF) / 255.0) * spreadDeg
        val dLat = sin(angle) * dist
        val dLon = cos(angle) * dist * 1.4
        val rLat = ((lat + dLat).coerceIn(50.0, 58.6) * 1000.0).roundToInt() / 1000.0
        val rLon = ((lon + dLon).coerceIn(-6.2, 1.7) * 1000.0).roundToInt() / 1000.0
        return rLat to rLon
    }

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
        }.getOrDefault("2025-01-01")
    }

    private fun httpPost(urlStr: String, formBody: String): String? {
        return runCatching {
            val bytes = formBody.toByteArray(Charsets.UTF_8)
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(bytes) }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            text
        }.getOrNull()
    }

    private fun httpGet(urlStr: String): String? {
        return runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json,text/html")
                setRequestProperty("Accept-Language", "en-GB,en;q=0.9")
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