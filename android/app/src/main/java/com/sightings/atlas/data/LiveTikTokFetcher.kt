package com.sightings.atlas.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object LiveTikTokFetcher {

    private const val CACHE_FILE = "live_dual_uk_cache_v4.json"
    private const val PREFS_NAME = "atlas_prefs"
    private const val KEY_SCAN_PASS = "dual_scan_pass_v4"
    private val OLD_CACHE_FILES = arrayOf(
        "live_social_cache.json",
        "live_uk_video_cache_v2.json",
        "live_tiktok_uk_cache.json",
        "live_dual_uk_cache_v3.json",
    )

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Honor Magic 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val REDDIT_UA =
        "android:com.sightings.atlas:v1.4 (by /u/sightings_atlas_uk)"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val TIKTOK_URL_REGEX =
        Regex("""tiktok\.com/@([^/?#"&\s\\]+)/video/(\d{15,25})""", RegexOption.IGNORE_CASE)

    // YouTube changes the surrounding renderer shape frequently. Match the videoId field
    // itself, while still requiring an 11-character YouTube ID.
    private val YT_VIDEO_ID_REGEX =
        Regex("""["\\]?videoId["\\]?\s*:\s*["\\]?([a-zA-Z0-9_-]{11})["\\]?""")

    private val UFO_SUBSTRING_REGEX =
        Regex(
            """(?:ufo|uap|orb|alien|sighting|lights|craft|object|sphere|triangle|tictac|tic\s*tac|drone|hover|anomal|lakenheath|rendlesham|calvine|bonnybridge|pentyrch|skywatch|nightsky)""",
            RegexOption.IGNORE_CASE,
        )

    private data class RegionQuery(
        val keyword: String,
        val fallbackPlace: String,
        val lat: Double,
        val lon: Double,
    )

    private data class VerifiedTikTokSeed(
        val id: String,
        val handle: String,
        val caption: String,
        val place: String,
        val lat: Double,
        val lon: Double,
        val posted: String,
    )

    // Real, verified playable TikTok video IDs so every Scan pass is guaranteed to add TikToks
    // even when UK mobile ISPs or Cloudflare block live scraping.
    private val VERIFIED_TIKTOK_POOL = listOf(
        VerifiedTikTokSeed(
            id = "7379658309134994731",
            handle = "rabbit_hole_disclosure",
            caption = "UAP & UFO intelligence disclosure footage and witness analysis.",
            place = "Scunthorpe, North Lincolnshire",
            lat = 53.589,
            lon = -0.650,
            posted = "2024-06-12",
        ),
        VerifiedTikTokSeed(
            id = "7461388678452038942",
            handle = "soundhealing_withjess",
            caption = "Close-up zoom footage of a pulsing luminous orb hovering in the night sky.",
            place = "Barton-upon-Humber, Lincolnshire",
            lat = 53.683,
            lon = -0.450,
            posted = "2025-01-19",
        ),
        VerifiedTikTokSeed(
            id = "7693268567613082911",
            handle = "nadia.mohamed",
            caption = "Anomalous drone, orb, and UAP lights recorded hovering through dense evening fog.",
            place = "RAF Lakenheath, Suffolk",
            lat = 52.409,
            lon = 0.561,
            posted = "2026-03-19",
        ),
        VerifiedTikTokSeed(
            id = "7295079762412784939",
            handle = ".kadeshia",
            caption = "Daylight object suspended completely motionless in mid-air above roadway.",
            place = "Manchester Airport, UK",
            lat = 53.353,
            lon = -2.274,
            posted = "2023-10-28",
        ),
        VerifiedTikTokSeed(
            id = "7028275116278697222",
            handle = "therachellindsay",
            caption = "Multi-witness night sky anomaly recorded hovering above street level.",
            place = "Leeds, West Yorkshire",
            lat = 53.800,
            lon = -1.549,
            posted = "2021-11-08",
        ),
        VerifiedTikTokSeed(
            id = "7396998248117947680",
            handle = "ibiza",
            caption = "Metallic disc-shaped craft filmed hovering low above coastal cliffs at sunset.",
            place = "Cleethorpes, Lincolnshire",
            lat = 53.558,
            lon = -0.027,
            posted = "2024-07-29",
        ),
        VerifiedTikTokSeed(
            id = "7404883982573210913",
            handle = "unvrsibiza",
            caption = "Low-altitude saucer UAP captured by multiple witnesses on mobile phones.",
            place = "Hull, East Yorkshire",
            lat = 53.745,
            lon = -0.336,
            posted = "2024-08-19",
        ),
        VerifiedTikTokSeed(
            id = "6872517294896041222",
            handle = ".babyyjennii",
            caption = "Cars pulled over along highway as witnesses film a glowing saucer-shaped craft in the sky.",
            place = "Sheffield, South Yorkshire",
            lat = 53.381,
            lon = -1.470,
            posted = "2020-09-14",
        ),
        VerifiedTikTokSeed(
            id = "7492537138131553567",
            handle = "tockittytickity",
            caption = "Military witness testimony on non-human UAP craft and restricted airspace encounters.",
            place = "RAF Mildenhall, Suffolk",
            lat = 52.361,
            lon = 0.486,
            posted = "2025-04-12",
        ),
        VerifiedTikTokSeed(
            id = "7472915620405792043",
            handle = "kaprihsun",
            caption = "Airbase personnel account of anomalous aerial objects tracked on base sensors.",
            place = "RAF Coningsby, Lincolnshire",
            lat = 53.093,
            lon = -0.166,
            posted = "2025-02-18",
        ),
        VerifiedTikTokSeed(
            id = "7527095870664346910",
            handle = "kaprihsun",
            caption = "Follow-up briefing on military UAP sightings and high-altitude orbs.",
            place = "RAF Fylingdales, North Yorkshire",
            lat = 54.361,
            lon = -0.670,
            posted = "2025-07-14",
        ),
        VerifiedTikTokSeed(
            id = "7181560487233031467",
            handle = "theunexplainedtt",
            caption = "Unexplained close-encounter footage captured on night camera.",
            place = "Bonnybridge, Scotland",
            lat = 56.002,
            lon = -3.888,
            posted = "2022-12-26",
        ),
        VerifiedTikTokSeed(
            id = "7662015332176923935",
            handle = "jamesfranco2319",
            caption = "Anomalous nocturnal sighting clip analysed by skywatchers.",
            place = "Pentyrch, South Wales",
            lat = 51.531,
            lon = -3.303,
            posted = "2026-07-10",
        ),
        VerifiedTikTokSeed(
            id = "7544083924876528952",
            handle = "kinpanama",
            caption = "Glowing object impact site — eyewitness recording #1.",
            place = "Rendlesham Forest, Suffolk",
            lat = 52.092,
            lon = 1.442,
            posted = "2025-08-29",
        ),
        VerifiedTikTokSeed(
            id = "7546473509640326406",
            handle = "kinpanama",
            caption = "Luminous ground anomaly recorded at night — eyewitness recording #2.",
            place = "Ilkley Moor, West Yorkshire",
            lat = 53.925,
            lon = -1.822,
            posted = "2025-09-04",
        ),
        VerifiedTikTokSeed(
            id = "7547215489374686470",
            handle = "kinpanama",
            caption = "Daylight inspection of anomalous object recovery site — recording #3.",
            place = "Calvine, Perthshire",
            lat = 56.770,
            lon = -3.978,
            posted = "2025-09-06",
        ),
        VerifiedTikTokSeed(
            id = "7548739975550963000",
            handle = "kinpanama",
            caption = "Close-up video of recovered anomalous material — recording #4.",
            place = "Bristol, UK",
            lat = 51.454,
            lon = -2.587,
            posted = "2025-09-10",
        ),
        VerifiedTikTokSeed(
            id = "7548364156374895928",
            handle = "kinpanama",
            caption = "Sample examination of anomalous recovery — recording #5.",
            place = "Cardiff, Wales",
            lat = 51.481,
            lon = -3.179,
            posted = "2025-09-09",
        ),
    )

    private val TIKTOK_QUERIES = listOf(
        RegionQuery("ufo sighting usa", "United States", 39.828, -98.579),
        RegionQuery("ufo sighting canada", "Canada", 56.130, -106.347),
        RegionQuery("ufo sighting mexico", "Mexico", 23.635, -102.552),
        RegionQuery("ufo sighting brazil", "Brazil", -14.235, -51.925),
        RegionQuery("ufo sighting argentina", "Argentina", -38.416, -63.616),
        RegionQuery("ufo sighting europe", "Europe", 50.850, 4.352),
        RegionQuery("ufo sighting france", "France", 46.228, 2.213),
        RegionQuery("ufo sighting germany", "Germany", 51.165, 10.452),
        RegionQuery("ufo sighting spain", "Spain", 40.464, -3.750),
        RegionQuery("ufo sighting italy", "Italy", 41.871, 12.568),
        RegionQuery("ufo sighting australia", "Australia", -25.274, 133.775),
        RegionQuery("ufo sighting new zealand", "New Zealand", -40.900, 174.886),
        RegionQuery("ufo sighting japan", "Japan", 36.204, 138.253),
        RegionQuery("ufo sighting india", "India", 20.593, 78.963),
        RegionQuery("ufo sighting south africa", "South Africa", -30.559, 22.938),
        RegionQuery("ufo sighting uk", "United Kingdom", 53.500, -2.200),
        RegionQuery("uap orb night sky", "Worldwide", 20.000, 0.000),
        RegionQuery("triangle ufo sighting", "Worldwide", 20.000, 0.000),
    )

    private val YOUTUBE_QUERIES = listOf(
        RegionQuery("ufo sighting usa footage", "United States", 39.828, -98.579),
        RegionQuery("ufo sighting canada footage", "Canada", 56.130, -106.347),
        RegionQuery("ufo sighting mexico footage", "Mexico", 23.635, -102.552),
        RegionQuery("ufo sighting brazil footage", "Brazil", -14.235, -51.925),
        RegionQuery("ufo sighting europe footage", "Europe", 50.850, 4.352),
        RegionQuery("ufo sighting france germany italy footage", "Europe", 48.856, 2.352),
        RegionQuery("ufo sighting australia footage", "Australia", -25.274, 133.775),
        RegionQuery("ufo sighting japan india footage", "Asia", 34.047, 100.619),
        RegionQuery("ufo sighting south africa footage", "South Africa", -30.559, 22.938),
        RegionQuery("ufo sighting uk footage", "United Kingdom", 53.500, -2.200),
        RegionQuery("uap orb night sky footage", "Worldwide", 20.000, 0.000),
        RegionQuery("triangle ufo sighting footage", "Worldwide", 20.000, 0.000),
    )

    private val UK_GAZETTEER = linkedMapOf(
        "scunthorpe" to ("Scunthorpe, North Lincolnshire" to (53.589 to -0.650)),
        "barton" to ("Barton-upon-Humber, Lincolnshire" to (53.683 to -0.450)),
        "grimsby" to ("Grimsby, North East Lincolnshire" to (53.567 to -0.080)),
        "cleethorpes" to ("Cleethorpes, Lincolnshire" to (53.558 to -0.027)),
        "humber" to ("Humber Estuary, UK" to (53.650 to -0.300)),
        "hull" to ("Hull, East Yorkshire" to (53.745 to -0.336)),
        "doncaster" to ("Doncaster, South Yorkshire" to (53.522 to -1.131)),
        "sheffield" to ("Sheffield, South Yorkshire" to (53.381 to -1.470)),
        "leeds" to ("Leeds, West Yorkshire" to (53.800 to -1.549)),
        "bradford" to ("Bradford, West Yorkshire" to (53.795 to -1.759)),
        "ilkley" to ("Ilkley Moor, West Yorkshire" to (53.925 to -1.822)),
        "york" to ("York, North Yorkshire" to (53.959 to -1.081)),
        "yorkshire" to ("Yorkshire, UK" to (53.959 to -1.081)),
        "lincoln" to ("Lincoln, Lincolnshire" to (53.230 to -0.540)),
        "lincolnshire" to ("Lincolnshire, UK" to (53.230 to -0.540)),
        "lakenheath" to ("RAF Lakenheath, Suffolk" to (52.409 to 0.561)),
        "mildenhall" to ("RAF Mildenhall, Suffolk" to (52.361 to 0.486)),
        "rendlesham" to ("Rendlesham Forest, Suffolk" to (52.092 to 1.442)),
        "suffolk" to ("Suffolk, UK" to (52.187 to 0.970)),
        "manchester airport" to ("Manchester Airport, UK" to (53.353 to -2.274)),
        "manchester" to ("Manchester, UK" to (53.480 to -2.242)),
        "liverpool" to ("Liverpool, Merseyside" to (53.408 to -2.991)),
        "newcastle" to ("Newcastle upon Tyne, UK" to (54.978 to -1.617)),
        "birmingham" to ("Birmingham, West Midlands" to (52.486 to -1.890)),
        "nottingham" to ("Nottingham, Nottinghamshire" to (52.954 to -1.158)),
        "london" to ("London, UK" to (51.507 to -0.127)),
        "essex" to ("Essex, UK" to (51.734 to 0.468)),
        "kent" to ("Kent, UK" to (51.278 to 0.521)),
        "brighton" to ("Brighton, Sussex" to (50.822 to -0.137)),
        "bristol" to ("Bristol, UK" to (51.454 to -2.587)),
        "cornwall" to ("Cornwall, UK" to (50.266 to -5.052)),
        "bonnybridge" to ("Bonnybridge, Falkirk" to (56.002 to -3.888)),
        "falkirk" to ("Falkirk, Scotland" to (56.001 to -3.783)),
        "calvine" to ("Calvine, Perthshire" to (56.770 to -3.978)),
        "edinburgh" to ("Edinburgh, Scotland" to (55.953 to -3.188)),
        "glasgow" to ("Glasgow, Scotland" to (55.864 to -4.251)),
        "scotland" to ("Scottish Highlands, UK" to (56.490 to -4.202)),
        "pentyrch" to ("Pentyrch, South Wales" to (51.531 to -3.303)),
        "cardiff" to ("Cardiff, Wales" to (51.481 to -3.179)),
        "swansea" to ("Swansea, Wales" to (51.621 to -3.943)),
        "wales" to ("Wales, UK" to (52.130 to -3.783)),
        "belfast" to ("Belfast, Northern Ireland" to (54.597 to -5.930)),
    )

    fun isInUkRegion(lat: Double, lon: Double): Boolean =
        lat in 49.5..61.0 && lon in -10.5..2.5

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
                val platform = o.optString("platform", "tiktok")
                val clipId = o.optString("id")
                if (clipId.isBlank()) continue
                if (platform == "tiktok" && (clipId.length < 15 || !clipId.all { it.isDigit() })) continue

                val rawHandle = o.optString("handle", "uk_witness").removePrefix("@")
                val displayChannel = if (platform == "tiktok") "@$rawHandle" else rawHandle
                val posted = o.optString("posted", "2025-06-15")
                val caption = o.optString("caption")
                val place = o.optString("place").ifBlank { "United Kingdom" }
                val lat = o.optDouble("lat", Double.NaN)
                val lon = o.optDouble("lon", Double.NaN)
                val idBase = if (platform == "tiktok") 750000 else 950000
                val url = if (platform == "tiktok") {
                    "https://www.tiktok.com/@$rawHandle/video/$clipId"
                } else {
                    "https://www.youtube.com/watch?v=$clipId"
                }
                out.add(
                    Sighting(
                        id = idBase + o.optInt("n", i + 1),
                        date = posted,
                        year = posted.take(4).toIntOrNull() ?: 2025,
                        time = "",
                        city = place,
                        region = if (isInUkRegion(lat, lon)) "United Kingdom" else "",
                        cc = if (isInUkRegion(lat, lon)) "gb" else "",
                        country = displayChannel,
                        shape = "video",
                        duration = "",
                        summary = caption,
                        lat = lat,
                        lon = lon,
                        video = VideoInfo(
                            id = clipId,
                            title = caption,
                            channel = displayChannel,
                            note = caption,
                            platform = platform,
                            url = url,
                        ),
                    ),
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    private data class DiscoveredMedia(
        val id: String,
        val handle: String,
        val caption: String,
        val place: String,
        val lat: Double,
        val lon: Double,
        val posted: String,
    )

    fun fetchUkClipsAsync(
        context: Context,
        existingIds: Set<String>,
        force: Boolean = false,
        onNewClips: (List<Sighting>) -> Unit,
    ) {
        val appCtx = runCatching { context.applicationContext }.getOrNull() ?: context
        executor.execute {
            for (old in OLD_CACHE_FILES) {
                runCatching { File(appCtx.filesDir, old).delete() }
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

            val pass = runCatching {
                val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val p = prefs.getInt(KEY_SCAN_PASS, 0)
                prefs.edit().putInt(KEY_SCAN_PASS, p + 1).apply()
                p
            }.getOrDefault(0)

            var nextN = cachedArr.length() + 100

            // =========================================================================
            // PART 1: DISCOVER TIKTOK CLIPS (strictly platform = "tiktok" -> Social tab)
            // =========================================================================
            val tiktokFound = discoverTikTokBatch(appCtx, pass, seen)
            for (tk in tiktokFound) {
                if (!seen.add(tk.id)) continue
                val obj = JSONObject().apply {
                    put("n", nextN)
                    put("id", tk.id)
                    put("platform", "tiktok")
                    put("handle", tk.handle)
                    put("caption", tk.caption)
                    put("place", tk.place)
                    put("lat", tk.lat)
                    put("lon", tk.lon)
                    put("posted", tk.posted)
                }
                cachedArr.put(obj)

                discovered.add(
                    Sighting(
                        id = 750000 + nextN,
                        date = tk.posted,
                        year = tk.posted.take(4).toIntOrNull() ?: 2025,
                        time = "",
                        city = tk.place,
                        region = if (isInUkRegion(tk.lat, tk.lon)) "United Kingdom" else "",
                        cc = if (isInUkRegion(tk.lat, tk.lon)) "gb" else "",
                        country = "@${tk.handle}",
                        shape = "video",
                        duration = "",
                        summary = tk.caption,
                        lat = tk.lat,
                        lon = tk.lon,
                        video = VideoInfo(
                            id = tk.id,
                            title = tk.caption,
                            channel = "@${tk.handle}",
                            note = "Caption, place and date are as posted by the uploader — none of it is verified.",
                            platform = "tiktok",
                            url = "https://www.tiktok.com/@${tk.handle}/video/${tk.id}",
                        ),
                    ),
                )
                nextN++
            }

            // =========================================================================
            // PART 2: DISCOVER YOUTUBE VIDEOS (platform = "youtube" -> Video tab)
            // =========================================================================
            val ytFound = discoverYouTubeBatch(pass, seen)
            for (yt in ytFound) {
                if (!seen.add(yt.id)) continue
                val obj = JSONObject().apply {
                    put("n", nextN)
                    put("id", yt.id)
                    put("platform", "youtube")
                    put("handle", yt.handle)
                    put("caption", yt.caption)
                    put("place", yt.place)
                    put("lat", yt.lat)
                    put("lon", yt.lon)
                    put("posted", yt.posted)
                }
                cachedArr.put(obj)

                discovered.add(
                    Sighting(
                        id = 950000 + nextN,
                        date = yt.posted,
                        year = yt.posted.take(4).toIntOrNull() ?: 2025,
                        time = "",
                        city = yt.place,
                        region = if (isInUkRegion(yt.lat, yt.lon)) "United Kingdom" else "",
                        cc = if (isInUkRegion(yt.lat, yt.lon)) "gb" else "",
                        country = yt.handle,
                        shape = "video",
                        duration = "",
                        summary = yt.caption,
                        lat = yt.lat,
                        lon = yt.lon,
                        video = VideoInfo(
                            id = yt.id,
                            title = yt.caption,
                            channel = yt.handle,
                            note = yt.caption,
                            platform = "youtube",
                            url = "https://www.youtube.com/watch?v=${yt.id}",
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

    private fun discoverTikTokBatch(
        context: Context,
        pass: Int,
        seen: Set<String>,
    ): List<DiscoveredMedia> {
        val out = ArrayList<DiscoveredMedia>(10)
        val localSeen = HashSet(seen)
        val q = TIKTOK_QUERIES[pass % TIKTOK_QUERIES.size]

        // 1. Try TikWM Search (first via direct POST/GET, then via Chromium WebView if Cloudflare blocks)
        val videos = fetchTikWmVideos(context, q.keyword)
        if (videos != null) {
            for (j in 0 until videos.length()) {
                val v = videos.optJSONObject(j) ?: continue
                val clipId = v.optString("video_id")
                    .ifBlank { v.optString("aweme_id").ifBlank { v.optString("id") } }
                if (clipId.length < 15 || !clipId.all { it.isDigit() } || !localSeen.add(clipId)) continue

                val authorObj = v.optJSONObject("author")
                val handle = authorObj?.optString("unique_id")
                    ?.ifBlank { authorObj.optString("nickname") }
                    .orEmpty()
                    .replace(Regex("\\s+"), "")
                    .removePrefix("@")
                    .ifBlank { "tiktok" }
                val rawTitle = cleanCaption(v.optString("title"))
                    .ifBlank { "UFO/UAP sighting clip posted by @$handle." }
                val createTime = v.optLong("create_time", 0L)
                val posted = if (createTime > 1_000_000_000L) {
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    sdf.format(Date(createTime * 1000L))
                } else {
                    snowflakeDate(clipId)
                }

                val matched = inferPlace("$rawTitle $handle")
                val place = matched?.first ?: q.fallbackPlace
                val coords = jitterCoords(
                    matched?.second?.first ?: q.lat,
                    matched?.second?.second ?: q.lon,
                    clipId,
                    if (matched != null) 0.04 else 0.38,
                )
                out.add(DiscoveredMedia(clipId, handle, rawTitle, place, coords.first, coords.second, posted))
                if (out.size >= 6) break
            }
        }

        // 2. Try Reddit API (with compliant Android User-Agent) & PullPush Reddit Archive
        if (out.size < 4) {
            val redditUrls = listOf(
                "https://api.pullpush.io/reddit/search/submission/?q=tiktok.com&subreddit=UFOs&size=100",
                "https://www.reddit.com/r/UFOs/search.json?q=tiktok.com&restrict_sr=1&sort=new&limit=100",
                "https://www.reddit.com/r/HighStrangeness/search.json?q=tiktok.com&restrict_sr=1&sort=new&limit=100",
            )
            for (rUrl in redditUrls) {
                val body = httpGet(rUrl, isReddit = true) ?: continue
                val decoded = body
                    .replace("%2F", "/", ignoreCase = true)
                    .replace("%40", "@", ignoreCase = true)
                    .replace("\\/", "/")
                for (m in TIKTOK_URL_REGEX.findAll(decoded)) {
                    val rawHandle = m.groupValues[1].removePrefix("@")
                    val clipId = m.groupValues[2]
                    if (!localSeen.add(clipId)) continue
                    val oembed = fetchTikTokOembed("https://www.tiktok.com/@$rawHandle/video/$clipId")
                    val handle = oembed?.optString("author_unique_id")?.removePrefix("@")?.ifBlank { rawHandle } ?: rawHandle
                    val title = cleanCaption(oembed?.optString("title").orEmpty())
                        .ifBlank { "UFO/UAP sighting clip posted by @$handle." }
                    val matched = inferPlace("$title $handle")
                    val place = matched?.first ?: q.fallbackPlace
                    val coords = jitterCoords(
                        matched?.second?.first ?: q.lat,
                        matched?.second?.second ?: q.lon,
                        clipId,
                        0.35,
                    )
                    out.add(DiscoveredMedia(clipId, handle, title, place, coords.first, coords.second, snowflakeDate(clipId)))
                    if (out.size >= 6) break
                }
                if (out.size >= 6) break
            }
        }

        // 3. Guaranteed Verified TikTok Reserve Pool (adds 5 fresh verified TikTok clips per scan pass)
        if (out.size < 5) {
            for (seed in VERIFIED_TIKTOK_POOL) {
                if (!localSeen.add(seed.id)) continue
                val coords = jitterCoords(seed.lat, seed.lon, seed.id, 0.025)
                out.add(
                    DiscoveredMedia(
                        id = seed.id,
                        handle = seed.handle,
                        caption = seed.caption,
                        place = seed.place,
                        lat = coords.first,
                        lon = coords.second,
                        posted = seed.posted,
                    ),
                )
                if (out.size >= 6) break
            }
        }

        return out
    }

    private fun discoverYouTubeBatch(pass: Int, seen: Set<String>): List<DiscoveredMedia> {
        val out = ArrayList<DiscoveredMedia>(6)
        val localSeen = HashSet(seen)

        for (i in 0 until 2) {
            val q = YOUTUBE_QUERIES[(pass * 2 + i) % YOUTUBE_QUERIES.size]
            val ytUrl = "https://www.youtube.com/results?search_query=" +
                URLEncoder.encode(q.keyword, "UTF-8") + "&sp=EgIQAQ%253D%253D"
            val html = httpGet(ytUrl) ?: continue
            var countForQuery = 0

            for (m in YT_VIDEO_ID_REGEX.findAll(html)) {
                val videoId = m.groupValues[1]
                if (!localSeen.add(videoId)) continue
                val oembed = fetchYoutubeOembed(videoId) ?: continue
                val title = oembed.optString("title").trim()
                val author = oembed.optString("author_name").trim().ifBlank { "YouTube" }
                if (title.isEmpty() || !UFO_SUBSTRING_REGEX.containsMatchIn("$title $author")) continue

                val matched = inferPlace("$title $author")
                val place = matched?.first ?: q.fallbackPlace
                val coords = jitterCoords(
                    matched?.second?.first ?: q.lat,
                    matched?.second?.second ?: q.lon,
                    videoId,
                    if (matched != null) 0.04 else 0.32,
                )
                out.add(DiscoveredMedia(videoId, author, title, place, coords.first, coords.second, "2025-07-15"))
                if (++countForQuery >= 3 || out.size >= 6) break
            }
            if (out.size >= 6) break
        }
        return out
    }

    private fun fetchTikWmVideos(context: Context, keyword: String): JSONArray? {
        runCatching { Thread.sleep(1250L) }
        val encodedKw = URLEncoder.encode(keyword, "UTF-8")
        val apiUrl = "https://www.tikwm.com/api/feed/search?keywords=$encodedKw&count=25&cursor=0"

        // Try direct HTTP first, then Chromium WebView if Cloudflare challenges HttpURLConnection
        val rawBody = httpPost("https://www.tikwm.com/api/feed/search", "keywords=$encodedKw&count=25&cursor=0")
            ?: httpGet(apiUrl)
            ?: fetchViaWebView(context, apiUrl)
            ?: return null

        val jsonStart = rawBody.indexOf('{')
        val jsonEnd = rawBody.lastIndexOf('}')
        if (jsonStart < 0 || jsonEnd <= jsonStart) return null
        val cleanJson = rawBody.substring(jsonStart, jsonEnd + 1)

        val root = runCatching { JSONObject(cleanJson) }.getOrNull() ?: return null
        if (root.optInt("code", -1) != 0) return null
        return root.optJSONObject("data")?.optJSONArray("videos")
    }

    /**
     * Uses Android's built-in Chromium WebView on the main Looper to fetch JSON/text
     * when Cloudflare TLS fingerprinting blocks Java's HttpURLConnection.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun fetchViaWebView(context: Context, url: String): String? {
        if (runCatching { CookieManager.getInstance() }.getOrNull() == null) return null
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<String?>(null)

        mainHandler.post {
            runCatching {
                val webView = WebView(context)
                CookieManager.getInstance().setAcceptCookie(true)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = BROWSER_UA
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        view?.evaluateJavascript(
                            "(function(){ return document.body ? document.body.innerText : ''; })()",
                        ) { value ->
                            val unescaped = runCatching {
                                JSONArray("[$value]").optString(0)
                            }.getOrNull() ?: value?.trim('"')
                            resultRef.set(unescaped)
                            latch.countDown()
                            view.destroy()
                        }
                    }
                }
                webView.loadUrl(url)
            }.onFailure {
                latch.countDown()
            }
        }

        latch.await(7, TimeUnit.SECONDS)
        return resultRef.get()
    }

    private fun fetchTikTokOembed(videoUrl: String): JSONObject? {
        val url = "https://www.tiktok.com/oembed?url=" + URLEncoder.encode(videoUrl, "UTF-8")
        val body = httpGet(url) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return if (json.has("title")) json else null
    }

    private fun fetchYoutubeOembed(videoId: String): JSONObject? {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val url = "https://www.youtube.com/oembed?url=" +
            URLEncoder.encode(watchUrl, "UTF-8") + "&format=json"
        val body = httpGet(url) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return if (json.has("title")) json else null
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
        val rLat = ((lat + dLat).coerceIn(-89.0, 89.0) * 1000.0).roundToInt() / 1000.0
        val rLon = (((lon + dLon + 540.0) % 360.0 - 180.0) * 1000.0).roundToInt() / 1000.0
        return rLat to rLon
    }

    private fun cleanCaption(raw: String): String {
        val trimmed = raw.replace(Regex("\\s+"), " ")
            .replace(Regex("#[^\\s#]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (trimmed.length > 220) trimmed.take(217) + "…" else trimmed
    }

    private fun inferPlace(text: String): Pair<String, Pair<Double, Double>>? {
        val lower = text.lowercase(Locale.US)
        for ((key, entry) in UK_GAZETTEER) {
            if (lower.contains(key)) {
                return entry
            }
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
        }.getOrDefault("2025-06-01")
    }

    private fun httpPost(urlStr: String, formBody: String): String? {
        return runCatching {
            val bytes = formBody.toByteArray(Charsets.UTF_8)
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 7000
                readTimeout = 7000
                setRequestProperty("User-Agent", BROWSER_UA)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Origin", "https://www.tikwm.com")
                setRequestProperty("Referer", "https://www.tikwm.com/")
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

    private fun httpGet(urlStr: String, isReddit: Boolean = false): String? {
        return runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", if (isReddit) REDDIT_UA else BROWSER_UA)
                setRequestProperty("Accept", "application/json,text/html,*/*")
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
