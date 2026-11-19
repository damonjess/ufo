package com.sightings.atlas.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.sightings.atlas.model.Sighting
import com.sightings.atlas.model.VideoInfo
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val REDDIT_UA =
        "android:com.sightings.atlas:v1.5 (by /u/sightings_atlas_uk)"

    // Deep scan targets (when user taps Scan in OPS or Settings)
    private const val DEEP_TIKTOK_TARGET = 220
    private const val DEEP_YOUTUBE_TARGET = 240
    private const val LIGHT_TIKTOK_TARGET = 18
    private const val LIGHT_YOUTUBE_TARGET = 24

    private val scanExecutor = Executors.newSingleThreadExecutor()
    private val netPool = Executors.newFixedThreadPool(6)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanningFlag = AtomicBoolean(false)

    private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                if (cookies.isEmpty()) return
                val host = url.host
                val merged = LinkedHashMap<String, Cookie>()
                cookieStore[host]?.forEach { merged[it.name] = it }
                cookies.forEach { merged[it.name] = it }
                cookieStore[host] = ArrayList(merged.values)
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val now = System.currentTimeMillis()
                return cookieStore[url.host]?.filter { it.expiresAt >= now } ?: emptyList()
            }
        })
        .build()

    private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()

    val isScanning: Boolean get() = scanningFlag.get()

    private val URLEBIRD_CARD_REGEX = Regex(
        """<div class="author-name"><a[^>]+/user/([^/"']+)/[^>]*>.*?</a></div>\s*<a[^>]+/video/[^"']*?(\d{15,25})/?[^>]*><span>(.*?)</span></a>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private val URLEBIRD_BTN_REGEX =
        Regex("""<button[^>]*id="hash_load_more"[^>]*>""", RegexOption.IGNORE_CASE)

    private val URLEBIRD_ATTR_REGEX =
        Regex("""data-([a-z-]+)="([^"]*)"""", RegexOption.IGNORE_CASE)

    private val YT_INITIAL_DATA_REGEX =
        Regex("""var\s+ytInitialData\s*=\s*(\{.*?\});\s*</script>""", RegexOption.DOT_MATCHES_ALL)

    private val YT_VIDEO_ID_REGEX =
        Regex("""["\\]?videoId["\\]?\s*:\s*["\\]?([a-zA-Z0-9_-]{11})["\\]?""")

    private val YT_URL_ID_REGEX =
        Regex("""(?:youtube\.com/watch\?v=|youtu\.be/|youtube\.com/shorts/)([a-zA-Z0-9_-]{11})""")

    private val RELATIVE_TIME_REGEX =
        Regex("""(\d+)\s*(s|sec|second|m|min|minute|h|hr|hour|d|day|w|wk|week|mo|month|y|yr|year)s?""", RegexOption.IGNORE_CASE)

    private val HTML_ENTITY_REGEX = Regex("""&#(\d{1,6});|&#x([0-9a-fA-F]{1,6});|&([a-zA-Z]{2,8});""")

    private val UFO_SUBSTRING_REGEX =
        Regex(
            """ufo|uap|orb|orbs|alien|aliens|sighting|sightings|lights|craft|object|sphere|triangle|tictac|tic\s*tac|drone|drones|hover|hovering|anomal|lakenheath|rendlesham|calvine|bonnybridge|pentyrch|skywatch|nightsky|saucer|disc|disk|pentagon|nimitz|roswell|area\s*51|skinwalker|nhi|disclosure|extraterrestrial|mothership|jellyfish|flir|unidentified|aerial|phenomen""",
            RegexOption.IGNORE_CASE,
        )

    private data class RegionQuery(
        val keyword: String,
        val fallbackPlace: String,
        val lat: Double,
        val lon: Double,
    )

    private data class TikTokTag(
        val tag: String,
        val requireKeyword: Boolean = false,
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

    // Real, verified playable TikTok video IDs as offline/fallback reserve.
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

    private val TIKTOK_HASHTAGS = listOf(
        TikTokTag("ufosighting"),
        TikTokTag("uap"),
        TikTokTag("ufo"),
        TikTokTag("ufosightings"),
        TikTokTag("aliensighting"),
        TikTokTag("ufofootage"),
        TikTokTag("uapsighting"),
        TikTokTag("ufodisclosure"),
        TikTokTag("uapdisclosure"),
        TikTokTag("ufovideo"),
        TikTokTag("ufohunter"),
        TikTokTag("ufospotted"),
        TikTokTag("ufocaughtoncamera"),
        TikTokTag("aliensightings"),
        TikTokTag("njdrones"),
        TikTokTag("tictacufo"),
        TikTokTag("orbsinthesky"),
        TikTokTag("ufotok"),
        TikTokTag("uaptok"),
        TikTokTag("ufology"),
        TikTokTag("flyingsaucer"),
        TikTokTag("pentagonufo"),
        TikTokTag("skywatch", requireKeyword = true),
        TikTokTag("strangelights", requireKeyword = true),
    )

    private val TIKTOK_QUERIES = listOf(
        RegionQuery("ufo sighting usa", "United States", 39.828, -98.579),
        RegionQuery("ufo sighting uk", "United Kingdom", 53.500, -2.200),
        RegionQuery("uap orb night sky", "United States", 36.170, -115.140),
        RegionQuery("ufo sighting canada", "Canada", 56.130, -106.347),
        RegionQuery("ufo sighting mexico", "Mexico", 23.635, -102.552),
        RegionQuery("ufo sighting brazil", "Brazil", -14.235, -51.925),
        RegionQuery("ufo sighting europe", "Europe", 50.850, 4.352),
        RegionQuery("ufo sighting australia", "Australia", -25.274, 133.775),
        RegionQuery("triangle ufo sighting", "United Kingdom", 52.409, 0.561),
        RegionQuery("nj drones uap sighting", "New Jersey, USA", 40.058, -74.406),
    )

    private val YOUTUBE_QUERIES = listOf(
        RegionQuery("ufo sighting footage", "United States", 39.828, -98.579),
        RegionQuery("uap sighting caught on camera", "United States", 34.052, -118.244),
        RegionQuery("ufo sighting uk footage", "United Kingdom", 53.500, -2.200),
        RegionQuery("ufo orb night sky footage", "Arizona, USA", 33.448, -112.074),
        RegionQuery("triangle ufo sighting footage", "Belgium", 50.610, 5.750),
        RegionQuery("ufo sighting usa 2025 2026", "United States", 38.907, -77.037),
        RegionQuery("ufo sighting canada footage", "Canada", 45.421, -75.697),
        RegionQuery("ufo sighting mexico footage", "Mexico", 19.433, -99.133),
        RegionQuery("ufo sighting brazil footage", "Brazil", -23.550, -46.633),
        RegionQuery("ufo sighting argentina chile footage", "South America", -33.449, -70.669),
        RegionQuery("ufo sighting peru colombia footage", "Peru", -12.046, -77.043),
        RegionQuery("ufo sighting europe footage", "Europe", 50.850, 4.352),
        RegionQuery("ufo sighting france germany footage", "France", 48.856, 2.352),
        RegionQuery("ufo sighting spain italy footage", "Spain", 40.417, -3.704),
        RegionQuery("ufo sighting australia footage", "Australia", -33.869, 151.209),
        RegionQuery("ufo sighting new zealand footage", "New Zealand", -36.849, 174.763),
        RegionQuery("ufo sighting japan korea footage", "Japan", 35.676, 139.650),
        RegionQuery("ufo sighting india asia footage", "India", 28.614, 77.209),
        RegionQuery("ufo sighting south africa footage", "South Africa", -26.204, 28.047),
        RegionQuery("ufo sighting russia turkey footage", "Turkey", 41.008, 28.978),
        RegionQuery("pentagon uap navy flir footage", "Off San Diego, California", 32.716, -117.161),
        RegionQuery("tic tac ufo footage military", "California, USA", 33.400, -118.400),
        RegionQuery("ufo fleet lights night sky", "Texas, USA", 31.969, -99.902),
        RegionQuery("new jersey drones uap footage", "New Jersey, USA", 40.058, -74.406),
        RegionQuery("raf lakenheath uap drones uk", "RAF Lakenheath, Suffolk", 52.409, 0.561),
        RegionQuery("pilot ufo sighting cockpit video", "North Atlantic", 42.360, -71.059),
        RegionQuery("night vision ufo orb sighting", "Nevada, USA", 37.243, -115.793),
        RegionQuery("jellyfish ufo uap footage", "Middle East", 33.315, 44.366),
        RegionQuery("mass ufo sighting witnesses video", "Phoenix, Arizona", 33.448, -112.074),
        RegionQuery("ufo sighting florida miami footage", "Florida, USA", 25.762, -80.192),
        RegionQuery("ufo sighting california los angeles", "Los Angeles, California", 34.052, -118.244),
        RegionQuery("ufo sighting new york city footage", "New York, USA", 40.713, -74.006),
        RegionQuery("ufo sighting london manchester uk", "London, UK", 51.507, -0.127),
        RegionQuery("ufo sighting scotland wales ireland", "Bonnybridge, Scotland", 56.002, -3.888),
        RegionQuery("ufo sighting texas houston austin", "Austin, Texas", 30.267, -97.743),
        RegionQuery("ufo sighting ohio michigan illinois", "Chicago, Illinois", 41.878, -87.630),
        RegionQuery("ufo sighting washington oregon", "Seattle, Washington", 47.606, -122.332),
        RegionQuery("ufo sighting colorado utah skinwalker", "Uinta Basin, Utah", 40.258, -109.888),
        RegionQuery("uso underwater ufo ocean sighting", "Puerto Rico", 18.466, -66.106),
        RegionQuery("real ufo caught on camera raw", "Las Vegas, Nevada", 36.170, -115.140),
        RegionQuery("ufo hovering over city lights", "Toronto, Canada", 43.653, -79.383),
        RegionQuery("uap anomalous craft radar video", "Washington, D.C.", 38.907, -77.037),
    )

    // Fallback global sighting hubs so unlocated global hashtag clips distribute realistically
    // across active sighting corridors rather than stacking on one single coordinate.
    private val WORLD_HOTSPOTS = listOf(
        Triple("Phoenix, Arizona", 33.448, -112.074),
        Triple("London, UK", 51.507, -0.127),
        Triple("New Jersey, USA", 40.058, -74.406),
        Triple("Los Angeles, California", 34.052, -118.244),
        Triple("Manchester, UK", 53.480, -2.242),
        Triple("Austin, Texas", 30.267, -97.743),
        Triple("Mexico City, Mexico", 19.433, -99.133),
        Triple("Toronto, Canada", 43.653, -79.383),
        Triple("Miami, Florida", 25.762, -80.192),
        Triple("Bonnybridge, Scotland", 56.002, -3.888),
        Triple("Las Vegas, Nevada", 36.170, -115.140),
        Triple("São Paulo, Brazil", -23.550, -46.633),
        Triple("Sydney, Australia", -33.869, 151.209),
        Triple("Paris, France", 48.856, 2.352),
        Triple("Berlin, Germany", 52.520, 13.405),
        Triple("Tokyo, Japan", 35.676, 139.650),
        Triple("Seattle, Washington", 47.606, -122.332),
        Triple("RAF Lakenheath, Suffolk", 52.409, 0.561),
        Triple("Santiago, Chile", -33.449, -70.669),
        Triple("Madrid, Spain", 40.417, -3.704),
        Triple("Rome, Italy", 41.903, 12.496),
        Triple("Lima, Peru", -12.046, -77.043),
        Triple("Chicago, Illinois", 41.878, -87.630),
        Triple("Johannesburg, South Africa", -26.204, 28.047),
        Triple("Istanbul, Turkey", 41.008, 28.978),
        Triple("Dublin, Ireland", 53.350, -6.260),
        Triple("Auckland, New Zealand", -36.849, 174.763),
        Triple("Denver, Colorado", 39.739, -104.990),
    )

    // Ordered from specific/multi-word to broader names to prevent false substring matches
    // (e.g. "new york" matches before "york", "new jersey" before "jersey").
    private val WORLD_GAZETTEER = linkedMapOf(
        // UK & Ireland
        "manchester airport" to ("Manchester Airport, UK" to (53.353 to -2.274)),
        "raf lakenheath" to ("RAF Lakenheath, Suffolk" to (52.409 to 0.561)),
        "lakenheath" to ("RAF Lakenheath, Suffolk" to (52.409 to 0.561)),
        "raf mildenhall" to ("RAF Mildenhall, Suffolk" to (52.361 to 0.486)),
        "mildenhall" to ("RAF Mildenhall, Suffolk" to (52.361 to 0.486)),
        "rendlesham" to ("Rendlesham Forest, Suffolk" to (52.092 to 1.442)),
        "scunthorpe" to ("Scunthorpe, North Lincolnshire" to (53.589 to -0.650)),
        "barton" to ("Barton-upon-Humber, Lincolnshire" to (53.683 to -0.450)),
        "grimsby" to ("Grimsby, North East Lincolnshire" to (53.567 to -0.080)),
        "cleethorpes" to ("Cleethorpes, Lincolnshire" to (53.558 to -0.027)),
        "blackpool" to ("Blackpool, Lancashire" to (53.817 to -3.036)),
        "humber" to ("Humber Estuary, UK" to (53.650 to -0.300)),
        "hull" to ("Hull, East Yorkshire" to (53.745 to -0.336)),
        "doncaster" to ("Doncaster, South Yorkshire" to (53.522 to -1.131)),
        "sheffield" to ("Sheffield, South Yorkshire" to (53.381 to -1.470)),
        "leeds" to ("Leeds, West Yorkshire" to (53.800 to -1.549)),
        "bradford" to ("Bradford, West Yorkshire" to (53.795 to -1.759)),
        "ilkley" to ("Ilkley Moor, West Yorkshire" to (53.925 to -1.822)),
        "yorkshire" to ("Yorkshire, UK" to (53.959 to -1.081)),
        "lincolnshire" to ("Lincolnshire, UK" to (53.230 to -0.540)),
        "suffolk" to ("Suffolk, UK" to (52.187 to 0.970)),
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
        "devon" to ("Devon, UK" to (50.716 to -3.534)),
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
        "dublin" to ("Dublin, Ireland" to (53.350 to -6.260)),
        "ireland" to ("Ireland" to (53.142 to -7.692)),
        "united kingdom" to ("United Kingdom" to (53.500 to -2.200)),
        "england" to ("England, UK" to (52.355 to -1.174)),

        // United States (specific cities & states before shorter tokens)
        "new york" to ("New York, USA" to (40.713 to -74.006)),
        "nyc" to ("New York City, USA" to (40.713 to -74.006)),
        "new jersey" to ("New Jersey, USA" to (40.058 to -74.406)),
        "njdrones" to ("New Jersey, USA" to (40.058 to -74.406)),
        "njdrone" to ("New Jersey, USA" to (40.058 to -74.406)),
        "los angeles" to ("Los Angeles, California" to (34.052 to -118.244)),
        "san diego" to ("San Diego, California" to (32.716 to -117.161)),
        "san francisco" to ("San Francisco, California" to (37.775 to -122.419)),
        "las vegas" to ("Las Vegas, Nevada" to (36.170 to -115.140)),
        "area 51" to ("Area 51, Nevada" to (37.243 to -115.793)),
        "area51" to ("Area 51, Nevada" to (37.243 to -115.793)),
        "skinwalker" to ("Skinwalker Ranch, Utah" to (40.258 to -109.888)),
        "roswell" to ("Roswell, New Mexico" to (33.394 to -104.523)),
        "new mexico" to ("New Mexico, USA" to (34.520 to -105.870)),
        "phoenix" to ("Phoenix, Arizona" to (33.448 to -112.074)),
        "arizona" to ("Arizona, USA" to (34.049 to -111.094)),
        "california" to ("California, USA" to (36.778 to -119.418)),
        "nevada" to ("Nevada, USA" to (38.803 to -116.419)),
        "houston" to ("Houston, Texas" to (29.760 to -95.370)),
        "dallas" to ("Dallas, Texas" to (32.777 to -96.797)),
        "austin" to ("Austin, Texas" to (30.267 to -97.743)),
        "texas" to ("Texas, USA" to (31.969 to -99.902)),
        "miami" to ("Miami, Florida" to (25.762 to -80.192)),
        "orlando" to ("Orlando, Florida" to (28.538 to -81.379)),
        "florida" to ("Florida, USA" to (27.665 to -81.516)),
        "chicago" to ("Chicago, Illinois" to (41.878 to -87.630)),
        "illinois" to ("Illinois, USA" to (40.633 to -89.399)),
        "seattle" to ("Seattle, Washington" to (47.606 to -122.332)),
        "washington" to ("Washington, D.C." to (38.907 to -77.037)),
        "pentagon" to ("The Pentagon, Virginia" to (38.872 to -77.056)),
        "denver" to ("Denver, Colorado" to (39.739 to -104.990)),
        "colorado" to ("Colorado, USA" to (39.550 to -105.782)),
        "pennsylvania" to ("Pennsylvania, USA" to (41.203 to -77.195)),
        "philadelphia" to ("Philadelphia, Pennsylvania" to (39.953 to -75.165)),
        "boston" to ("Boston, Massachusetts" to (42.360 to -71.059)),
        "atlanta" to ("Atlanta, Georgia" to (33.749 to -84.388)),
        "ohio" to ("Ohio, USA" to (40.417 to -82.907)),
        "michigan" to ("Michigan, USA" to (44.315 to -85.602)),
        "oregon" to ("Oregon, USA" to (43.804 to -120.554)),
        "utah" to ("Utah, USA" to (39.321 to -111.094)),
        "alaska" to ("Alaska, USA" to (64.201 to -149.494)),
        "hawaii" to ("Hawaii, USA" to (19.897 to -155.583)),
        "virginia" to ("Virginia, USA" to (37.432 to -78.657)),
        "carolina" to ("North Carolina, USA" to (35.759 to -79.019)),
        "tennessee" to ("Tennessee, USA" to (35.517 to -86.580)),
        "kentucky" to ("Kentucky, USA" to (37.839 to -84.270)),
        "minnesota" to ("Minnesota, USA" to (46.729 to -94.686)),
        "wisconsin" to ("Wisconsin, USA" to (43.784 to -88.788)),
        "montana" to ("Montana, USA" to (46.880 to -110.363)),
        "puerto rico" to ("Puerto Rico" to (18.221 to -66.590)),

        // Americas
        "toronto" to ("Toronto, Canada" to (43.653 to -79.383)),
        "vancouver" to ("Vancouver, Canada" to (49.283 to -123.121)),
        "montreal" to ("Montreal, Canada" to (45.502 to -73.567)),
        "alberta" to ("Alberta, Canada" to (53.933 to -116.576)),
        "ontario" to ("Ontario, Canada" to (51.254 to -85.323)),
        "canada" to ("Canada" to (56.130 to -106.347)),
        "mexico city" to ("Mexico City, Mexico" to (19.433 to -99.133)),
        "monterrey" to ("Monterrey, Mexico" to (25.687 to -100.316)),
        "tijuana" to ("Tijuana, Mexico" to (32.515 to -117.038)),
        "mexico" to ("Mexico" to (23.635 to -102.552)),
        "brazil" to ("Brazil" to (-14.235 to -51.925)),
        "brasil" to ("Brazil" to (-14.235 to -51.925)),
        "argentina" to ("Argentina" to (-38.416 to -63.616)),
        "chile" to ("Chile" to (-35.675 to -71.543)),
        "peru" to ("Peru" to (-9.190 to -75.015)),
        "colombia" to ("Colombia" to (4.571 to -74.297)),

        // Europe
        "paris" to ("Paris, France" to (48.856 to 2.352)),
        "france" to ("France" to (46.228 to 2.213)),
        "berlin" to ("Berlin, Germany" to (52.520 to 13.405)),
        "germany" to ("Germany" to (51.165 to 10.452)),
        "madrid" to ("Madrid, Spain" to (40.417 to -3.704)),
        "barcelona" to ("Barcelona, Spain" to (41.387 to 2.169)),
        "spain" to ("Spain" to (40.464 to -3.750)),
        "italy" to ("Italy" to (41.871 to 12.568)),
        "rome" to ("Rome, Italy" to (41.903 to 12.496)),
        "belgium" to ("Belgium" to (50.850 to 4.352)),
        "netherlands" to ("Netherlands" to (52.133 to 5.291)),
        "amsterdam" to ("Amsterdam, Netherlands" to (52.368 to 4.904)),
        "sweden" to ("Sweden" to (60.128 to 18.644)),
        "norway" to ("Norway" to (60.472 to 8.469)),
        "hessdalen" to ("Hessdalen, Norway" to (62.816 to 11.183)),
        "poland" to ("Poland" to (51.919 to 19.145)),
        "ukraine" to ("Ukraine" to (48.380 to 31.166)),
        "russia" to ("Russia" to (55.756 to 37.617)),
        "turkey" to ("Turkey" to (41.008 to 28.978)),
        "greece" to ("Greece" to (39.074 to 21.824)),
        "portugal" to ("Portugal" to (39.400 to -8.224)),
        "switzerland" to ("Switzerland" to (46.818 to 8.228)),

        // Asia-Pacific & Africa
        "sydney" to ("Sydney, Australia" to (-33.869 to 151.209)),
        "melbourne" to ("Melbourne, Australia" to (-37.814 to 144.963)),
        "australia" to ("Australia" to (-25.274 to 133.775)),
        "new zealand" to ("New Zealand" to (-40.900 to 174.886)),
        "auckland" to ("Auckland, New Zealand" to (-36.849 to 174.763)),
        "tokyo" to ("Tokyo, Japan" to (35.676 to 139.650)),
        "japan" to ("Japan" to (36.204 to 138.253)),
        "korea" to ("South Korea" to (35.908 to 127.767)),
        "china" to ("China" to (35.862 to 104.195)),
        "india" to ("India" to (20.593 to 78.963)),
        "philippines" to ("Philippines" to (12.880 to 121.774)),
        "south africa" to ("South Africa" to (-30.559 to 22.938)),
        "egypt" to ("Egypt" to (26.821 to 30.802)),
        "dubai" to ("Dubai, UAE" to (25.205 to 55.271)),
        "israel" to ("Israel" to (31.046 to 34.852)),
        "iraq" to ("Iraq" to (33.223 to 43.679)),
        "york" to ("York, North Yorkshire" to (53.959 to -1.081)),
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

                val rawHandle = o.optString("handle", "witness").removePrefix("@")
                val displayChannel = if (platform == "tiktok") "@$rawHandle" else rawHandle
                val posted = o.optString("posted", "2025-06-15")
                val caption = o.optString("caption")
                val place = o.optString("place").ifBlank { "Worldwide" }
                val duration = o.optString("dur", "")
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
                        duration = duration,
                        summary = caption,
                        lat = lat,
                        lon = lon,
                        video = VideoInfo(
                            id = clipId,
                            title = caption,
                            channel = displayChannel,
                            note = if (platform == "tiktok") {
                                "Caption, place and date are as posted by the uploader — none of it is verified."
                            } else {
                                caption
                            },
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
        val platform: String,
        val handle: String,
        val caption: String,
        val place: String,
        val lat: Double,
        val lon: Double,
        val posted: String,
        val duration: String = "",
    )

    /**
     * Scans live TikTok and YouTube feeds in the background.
     *
     * When [force] is true (OPS / Settings Scan button), runs a parallel deep scan targeting
     * hundreds of TikTok and YouTube clips in a single pass and streams batches progressively
     * via [onBatch] before invoking [onNewClips] with the complete list.
     */
    fun fetchUkClipsAsync(
        context: Context,
        existingIds: Set<String>,
        force: Boolean = false,
        onBatch: ((List<Sighting>) -> Unit)? = null,
        onNewClips: (List<Sighting>) -> Unit,
    ) {
        if (!scanningFlag.compareAndSet(false, true)) {
            if (force) onNewClips(emptyList())
            return
        }
        val appCtx = runCatching { context.applicationContext }.getOrNull() ?: context
        scanExecutor.execute {
            try {
                for (old in OLD_CACHE_FILES) {
                    runCatching { File(appCtx.filesDir, old).delete() }
                }

                val seen = Collections.synchronizedSet(HashSet(existingIds))
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

                val nextN = AtomicInteger(cachedArr.length() + 100)
                val cacheLock = Any()
                val allDiscovered = Collections.synchronizedList(ArrayList<Sighting>(512))

                fun acceptBatch(items: List<DiscoveredMedia>) {
                    if (items.isEmpty()) return
                    val batchSightings = ArrayList<Sighting>(items.size)
                    synchronized(cacheLock) {
                        for (item in items) {
                            if (!seen.add(item.id)) continue
                            val n = nextN.getAndIncrement()
                            val obj = JSONObject().apply {
                                put("n", n)
                                put("id", item.id)
                                put("platform", item.platform)
                                put("handle", item.handle)
                                put("caption", item.caption)
                                put("place", item.place)
                                put("lat", item.lat)
                                put("lon", item.lon)
                                put("posted", item.posted)
                                if (item.duration.isNotEmpty()) put("dur", item.duration)
                            }
                            cachedArr.put(obj)
                            val sighting = toSighting(item, n)
                            batchSightings.add(sighting)
                            allDiscovered.add(sighting)
                        }
                    }
                    if (batchSightings.isNotEmpty()) {
                        onBatch?.invoke(batchSightings)
                    }
                }

                val tiktokTarget = if (force) DEEP_TIKTOK_TARGET else LIGHT_TIKTOK_TARGET
                val youtubeTarget = if (force) DEEP_YOUTUBE_TARGET else LIGHT_YOUTUBE_TARGET

                // Run TikTok deep harvest & YouTube deep harvest concurrently!
                val tiktokFuture = netPool.submit(Callable {
                    discoverTikTokBatch(
                        context = appCtx,
                        pass = pass,
                        seen = seen,
                        targetCount = tiktokTarget,
                        deep = force,
                        onChunk = ::acceptBatch,
                    )
                })
                val youtubeFuture = netPool.submit(Callable {
                    discoverYouTubeBatch(
                        pass = pass,
                        seen = seen,
                        targetCount = youtubeTarget,
                        deep = force,
                        onChunk = ::acceptBatch,
                    )
                })

                runCatching { tiktokFuture.get(45, TimeUnit.SECONDS) }
                runCatching { youtubeFuture.get(45, TimeUnit.SECONDS) }

                val finalList = synchronized(allDiscovered) { ArrayList(allDiscovered) }
                if (finalList.isNotEmpty()) {
                    runCatching {
                        val jsonText = synchronized(cacheLock) { cachedArr.toString() }
                        val f = File(appCtx.filesDir, CACHE_FILE)
                        val tmp = File(appCtx.filesDir, "$CACHE_FILE.tmp")
                        tmp.writeText(jsonText)
                        tmp.copyTo(f, overwrite = true)
                        tmp.delete()
                    }
                }
                onNewClips(finalList)
            } finally {
                scanningFlag.set(false)
            }
        }
    }

    private fun toSighting(item: DiscoveredMedia, n: Int): Sighting {
        val isTk = item.platform == "tiktok"
        val displayChannel = if (isTk) "@${item.handle}" else item.handle
        val idBase = if (isTk) 750000 else 950000
        val url = if (isTk) {
            "https://www.tiktok.com/@${item.handle}/video/${item.id}"
        } else {
            "https://www.youtube.com/watch?v=${item.id}"
        }
        return Sighting(
            id = idBase + n,
            date = item.posted,
            year = item.posted.take(4).toIntOrNull() ?: 2025,
            time = "",
            city = item.place,
            region = if (isInUkRegion(item.lat, item.lon)) "United Kingdom" else "",
            cc = if (isInUkRegion(item.lat, item.lon)) "gb" else "",
            country = displayChannel,
            shape = "video",
            duration = item.duration,
            summary = item.caption,
            lat = item.lat,
            lon = item.lon,
            video = VideoInfo(
                id = item.id,
                title = item.caption,
                channel = displayChannel,
                note = if (isTk) {
                    "Caption, place and date are as posted by the uploader — none of it is verified."
                } else {
                    item.caption
                },
                platform = item.platform,
                url = url,
            ),
        )
    }

    // =========================================================================
    // PART 1: BULK TIKTOK DISCOVERY (Urlebird AJAX pagination + TikWM + Reserve)
    // =========================================================================
    private fun discoverTikTokBatch(
        context: Context,
        pass: Int,
        seen: Set<String>,
        targetCount: Int,
        deep: Boolean,
        onChunk: (List<DiscoveredMedia>) -> Unit,
    ): Int {
        val localSeen = HashSet(seen)
        var collected = 0

        // 1. Primary source: Urlebird hashtag index + rolling-token AJAX pagination.
        //    Each hashtag page yields ~18 real playable TikTok video IDs with @handle & caption.
        val maxTags = if (deep) 6 else 1
        val maxPagesPerTag = if (deep) 8 else 1
        val startIdx = (pass * (if (deep) 4 else 1)) % TIKTOK_HASHTAGS.size

        for (tOffset in 0 until maxTags) {
            if (collected >= targetCount) break
            val tagSpec = TIKTOK_HASHTAGS[(startIdx + tOffset) % TIKTOK_HASHTAGS.size]
            val tagUrl = "https://urlebird.com/hash/${tagSpec.tag}/"
            val html = httpGet(tagUrl, useDesktopUa = true) ?: continue

            val firstPage = parseUrlebirdCards(html, tagSpec.requireKeyword, localSeen)
            if (firstPage.isNotEmpty()) {
                collected += firstPage.size
                onChunk(firstPage)
            }
            if (collected >= targetCount || maxPagesPerTag <= 1) continue

            val btnMatch = URLEBIRD_BTN_REGEX.find(html) ?: continue
            val attrs = HashMap<String, String>()
            for (m in URLEBIRD_ATTR_REGEX.findAll(btnMatch.value)) {
                attrs[m.groupValues[1].lowercase(Locale.US)] = m.groupValues[2]
            }
            var cursor = attrs["cursor"] ?: "0"
            var page = attrs["page"]?.toIntOrNull() ?: 2
            var xToken = attrs["x"].orEmpty()
            val hashId = attrs["id"].orEmpty()
            val hashName = attrs["hash"].orEmpty().ifBlank { tagSpec.tag }
            val lang = attrs["lang"].orEmpty()
            if (hashId.isBlank() || xToken.isBlank()) continue

            var pagesFetched = 1
            while (pagesFetched < maxPagesPerTag && collected < targetCount) {
                pagesFetched++
                // Polite spacing keeps Urlebird responsive without triggering rate limits.
                runCatching { Thread.sleep(220L) }
                val payload = JSONObject().apply {
                    put("hash", hashName)
                    put("id", hashId)
                    put("cursor", cursor)
                    put("lang", lang)
                    put("page", page.toString())
                    put("x", xToken)
                }.toString()
                val formBody = "action=hash&data=${URLEncoder.encode(payload, "UTF-8")}"
                val resp = httpPost(
                    urlStr = "https://urlebird.com/ajax/",
                    formBody = formBody,
                    origin = "https://urlebird.com",
                    referer = tagUrl,
                    xhr = true,
                    useDesktopUa = true,
                ) ?: break

                val json = runCatching { JSONObject(resp) }.getOrNull() ?: break
                val thumbsHtml = json.optString("thumbs")
                if (thumbsHtml.isBlank()) break

                val pageItems = parseUrlebirdCards(thumbsHtml, tagSpec.requireKeyword, localSeen)
                if (pageItems.isNotEmpty()) {
                    collected += pageItems.size
                    onChunk(pageItems)
                }

                val hasMore = json.optBoolean("has_more", true)
                val nextCursorObj = json.opt("cursor")
                if (!hasMore || nextCursorObj == null || nextCursorObj == JSONObject.NULL) break
                cursor = nextCursorObj.toString()
                val nextX = json.optString("x")
                if (nextX.isNotBlank()) xToken = nextX
                page = json.optInt("page", page + 1)
            }
            if (tOffset + 1 < maxTags && collected < targetCount) {
                runCatching { Thread.sleep(300L) }
            }
        }

        // 1b. Android Chromium WebView fallback for Urlebird (auto-solves Cloudflare JS challenge if a mobile ISP challenges OkHttp)
        if (collected < if (deep) 80 else 10) {
            val webTags = (0 until (if (deep) 4 else 1)).map { i ->
                TIKTOK_HASHTAGS[(startIdx + i) % TIKTOK_HASHTAGS.size]
            }
            val addedViaWeb = harvestUrlebirdViaWebView(
                context = context,
                tags = webTags,
                maxPagesPerTag = if (deep) 7 else 1,
                targetRemaining = (targetCount - collected).coerceAtLeast(18),
                localSeen = localSeen,
                onChunk = onChunk,
            )
            collected += addedViaWeb
        }

        // 2. Secondary fallback: TikWM Search (HTTP + Cloudflare-aware WebView) if Urlebird came up short
        if (collected < if (deep) 60 else 6) {
            val queriesToTry = if (deep) 4 else 1
            for (qi in 0 until queriesToTry) {
                if (collected >= targetCount) break
                val q = TIKTOK_QUERIES[(pass + qi) % TIKTOK_QUERIES.size]
                val videos = fetchTikWmVideos(context, q.keyword) ?: continue
                val chunk = ArrayList<DiscoveredMedia>(videos.length())
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
                    val rawCaption = unescapeHtml(v.optString("title"))
                    val caption = cleanCaption(rawCaption)
                        .ifBlank { "UFO/UAP sighting clip posted by @$handle." }
                    val createTime = v.optLong("create_time", 0L)
                    val posted = if (createTime > 1_000_000_000L) {
                        formatUtcDate(createTime * 1000L)
                    } else {
                        snowflakeDate(clipId)
                    }
                    val (place, lat, lon) = locateClip("$rawCaption $handle", clipId, q)
                    chunk.add(DiscoveredMedia(clipId, "tiktok", handle, caption, place, lat, lon, posted))
                }
                if (chunk.isNotEmpty()) {
                    collected += chunk.size
                    onChunk(chunk)
                }
            }
        }

        // 3. Tertiary reserve: Verified TikTok Pool so every fresh install always gets playable TikToks
        if (collected < 10) {
            val reserve = ArrayList<DiscoveredMedia>(VERIFIED_TIKTOK_POOL.size)
            for (seed in VERIFIED_TIKTOK_POOL) {
                if (!localSeen.add(seed.id)) continue
                val coords = jitterCoords(seed.lat, seed.lon, seed.id, 0.025)
                reserve.add(
                    DiscoveredMedia(
                        id = seed.id,
                        platform = "tiktok",
                        handle = seed.handle,
                        caption = seed.caption,
                        place = seed.place,
                        lat = coords.first,
                        lon = coords.second,
                        posted = seed.posted,
                    ),
                )
            }
            if (reserve.isNotEmpty()) {
                collected += reserve.size
                onChunk(reserve)
            }
        }

        return collected
    }

    private fun parseUrlebirdCards(
        html: String,
        requireKeyword: Boolean,
        localSeen: MutableSet<String>,
    ): List<DiscoveredMedia> {
        val out = ArrayList<DiscoveredMedia>(20)
        for (m in URLEBIRD_CARD_REGEX.findAll(html)) {
            val handle = m.groupValues[1].trim().removePrefix("@").ifBlank { "tiktok" }
            val clipId = m.groupValues[2].trim()
            if (clipId.length !in 15..25 || !clipId.all { it.isDigit() }) continue
            val rawCaption = unescapeHtml(m.groupValues[3]).trim()
            if (requireKeyword && !UFO_SUBSTRING_REGEX.containsMatchIn("$rawCaption $handle")) continue
            if (!localSeen.add(clipId)) continue

            val cleaned = cleanCaption(rawCaption)
                .ifBlank {
                    // Fall back to readable hashtag summary when caption was hashtags-only
                    rawCaption.replace("#", " ").replace(Regex("\\s+"), " ").trim()
                        .take(180)
                        .ifBlank { "UFO/UAP sighting clip posted by @$handle." }
                }
            val (place, lat, lon) = locateClip("$rawCaption $handle", clipId, fallback = null)
            val posted = snowflakeDate(clipId)
            out.add(
                DiscoveredMedia(
                    id = clipId,
                    platform = "tiktok",
                    handle = handle,
                    caption = cleaned,
                    place = place,
                    lat = lat,
                    lon = lon,
                    posted = posted,
                ),
            )
        }
        return out
    }

    // =========================================================================
    // PART 2: BULK YOUTUBE DISCOVERY (Parallel Desktop ytInitialData + Archive)
    // =========================================================================
    private fun discoverYouTubeBatch(
        pass: Int,
        seen: Set<String>,
        targetCount: Int,
        deep: Boolean,
        onChunk: (List<DiscoveredMedia>) -> Unit,
    ): Int {
        val localSeen = Collections.synchronizedSet(HashSet(seen))
        val collected = AtomicInteger(0)

        val queryCount = if (deep) 18 else 2
        val startIdx = (pass * queryCount) % YOUTUBE_QUERIES.size
        val selectedQueries = (0 until queryCount).map { i ->
            YOUTUBE_QUERIES[(startIdx + i) % YOUTUBE_QUERIES.size]
        }

        // Fetch Desktop YouTube search pages concurrently (parses ytInitialData JSON directly —
        // ~20-25 videos per query with titles, channels, dates, and durations, zero oEmbed calls).
        val workerCount = if (deep) 5 else 2
        val ytPool = Executors.newFixedThreadPool(workerCount)
        try {
            val futures = selectedQueries.map { q ->
                ytPool.submit(Callable {
                    if (collected.get() >= targetCount) return@Callable 0
                    val items = fetchYouTubeSearchQuery(q, localSeen)
                    if (items.isNotEmpty()) {
                        collected.addAndGet(items.size)
                        onChunk(items)
                    }
                    items.size
                })
            }
            for (f in futures) {
                runCatching { f.get(12, TimeUnit.SECONDS) }
            }
        } finally {
            ytPool.shutdownNow()
        }

        // Bonus pass on deep scans: Arctic Shift Reddit r/UFOs YouTube submission archive (~80-95 videos)
        if (deep && collected.get() < targetCount) {
            val archiveItems = fetchArcticShiftYouTube(pass, localSeen)
            if (archiveItems.isNotEmpty()) {
                collected.addAndGet(archiveItems.size)
                onChunk(archiveItems)
            }
        }

        return collected.get()
    }

    private fun fetchYouTubeSearchQuery(
        q: RegionQuery,
        localSeen: MutableSet<String>,
    ): List<DiscoveredMedia> {
        val ytUrl = "https://www.youtube.com/results?search_query=" +
            URLEncoder.encode(q.keyword, "UTF-8") + "&sp=EgIQAQ%253D%253D"
        val html = httpGet(ytUrl, useDesktopUa = true) ?: return emptyList()

        val out = ArrayList<DiscoveredMedia>(24)
        val dataMatch = YT_INITIAL_DATA_REGEX.find(html)
        if (dataMatch != null) {
            val root = runCatching { JSONObject(dataMatch.groupValues[1]) }.getOrNull()
            if (root != null) {
                val renderers = ArrayList<JSONObject>(30)
                collectVideoRenderers(root, renderers)
                for (vr in renderers) {
                    val videoId = vr.optString("videoId").trim()
                    if (videoId.length != 11) continue
                    val title = extractYtText(vr.optJSONObject("title")).trim()
                    val channel = extractYtText(vr.optJSONObject("ownerText"))
                        .ifBlank { extractYtText(vr.optJSONObject("longBylineText")) }
                        .trim()
                        .ifBlank { "YouTube" }
                    if (title.isEmpty() || !UFO_SUBSTRING_REGEX.containsMatchIn("$title $channel")) continue
                    if (!localSeen.add(videoId)) continue

                    val pubText = extractYtText(vr.optJSONObject("publishedTimeText"))
                    val duration = extractYtText(vr.optJSONObject("lengthText"))
                    val posted = parseRelativeDate(pubText)
                    val (place, lat, lon) = locateClip("$title $channel", videoId, q)
                    out.add(
                        DiscoveredMedia(
                            id = videoId,
                            platform = "youtube",
                            handle = channel,
                            caption = title,
                            place = place,
                            lat = lat,
                            lon = lon,
                            posted = posted,
                            duration = duration,
                        ),
                    )
                }
            }
        }

        // Fallback to oEmbed only if YouTube changed ytInitialData wrapper shape
        if (out.isEmpty()) {
            var checked = 0
            for (m in YT_VIDEO_ID_REGEX.findAll(html)) {
                val videoId = m.groupValues[1]
                if (!localSeen.add(videoId)) continue
                val oembed = fetchYoutubeOembed(videoId) ?: continue
                val title = oembed.optString("title").trim()
                val author = oembed.optString("author_name").trim().ifBlank { "YouTube" }
                if (title.isEmpty() || !UFO_SUBSTRING_REGEX.containsMatchIn("$title $author")) continue
                val (place, lat, lon) = locateClip("$title $author", videoId, q)
                out.add(DiscoveredMedia(videoId, "youtube", author, title, place, lat, lon, formatUtcDate(System.currentTimeMillis())))
                if (++checked >= 12) break
            }
        }
        return out
    }

    private fun fetchArcticShiftYouTube(
        pass: Int,
        localSeen: MutableSet<String>,
    ): List<DiscoveredMedia> {
        val subs = arrayOf("UFOs", "HighStrangeness", "aliens", "UFOB")
        val sub = subs[pass % subs.size]
        val url = "https://arctic-shift.photon-reddit.com/api/posts/search" +
            "?subreddit=$sub&url=https://www.youtube.com/watch&limit=100"
        val body = httpGet(url, useDesktopUa = true) ?: return emptyList()
        val data = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull() ?: return emptyList()

        val out = ArrayList<DiscoveredMedia>(data.length())
        for (i in 0 until data.length()) {
            val post = data.optJSONObject(i) ?: continue
            val postUrl = post.optString("url")
            val match = YT_URL_ID_REGEX.find(postUrl) ?: continue
            val videoId = match.groupValues[1]
            val title = unescapeHtml(post.optString("title")).trim()
            if (title.length < 6) continue
            if (!localSeen.add(videoId)) continue

            val author = post.optString("author").takeIf { it.isNotBlank() && it != "[deleted]" }
                ?.let { "r/$sub · u/$it" } ?: "r/$sub"
            val createdUtc = post.optLong("created_utc", 0L)
            val posted = if (createdUtc > 1_000_000_000L) {
                formatUtcDate(createdUtc * 1000L)
            } else {
                "2025-07-15"
            }
            val (place, lat, lon) = locateClip(title, videoId, fallback = null)
            out.add(DiscoveredMedia(videoId, "youtube", author, title, place, lat, lon, posted))
        }
        return out
    }

    private fun collectVideoRenderers(node: Any?, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                val vr = node.optJSONObject("videoRenderer")
                if (vr != null) {
                    out.add(vr)
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key == "videoRenderer") continue
                    collectVideoRenderers(node.opt(key), out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectVideoRenderers(node.opt(i), out)
                }
            }
        }
    }

    private fun extractYtText(obj: JSONObject?): String {
        if (obj == null) return ""
        val simple = obj.optString("simpleText")
        if (simple.isNotEmpty()) return simple
        val runs = obj.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return sb.toString()
    }

    private fun parseRelativeDate(text: String): String {
        val nowMs = System.currentTimeMillis()
        val m = RELATIVE_TIME_REGEX.find(text) ?: return formatUtcDate(nowMs)
        val n = m.groupValues[1].toLongOrNull() ?: return formatUtcDate(nowMs)
        val unit = m.groupValues[2].lowercase(Locale.US)
        val deltaMs = when {
            unit.startsWith("y") -> n * 365L * 86_400_000L
            unit.startsWith("mo") -> n * 30L * 86_400_000L
            unit.startsWith("w") -> n * 7L * 86_400_000L
            unit.startsWith("d") -> n * 86_400_000L
            unit.startsWith("h") -> n * 3_600_000L
            unit.startsWith("m") -> n * 60_000L
            else -> n * 1000L
        }
        return formatUtcDate((nowMs - deltaMs).coerceAtLeast(1_000_000_000_000L))
    }

    private fun fetchTikWmVideos(context: Context, keyword: String): JSONArray? {
        runCatching { Thread.sleep(400L) }
        val encodedKw = URLEncoder.encode(keyword, "UTF-8")
        val apiUrl = "https://www.tikwm.com/api/feed/search?keywords=$encodedKw&count=30&cursor=0"

        val rawBody = httpPost(
            urlStr = "https://www.tikwm.com/api/feed/search",
            formBody = "keywords=$encodedKw&count=30&cursor=0",
            origin = "https://www.tikwm.com",
            referer = "https://www.tikwm.com/",
        )
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
     * when Cloudflare TLS/JS challenges block Java's HttpURLConnection.
     * Polls briefly so Cloudflare's automatic JS challenge can complete and redirect.
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
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = BROWSER_UA

                var done = false
                fun finish(text: String?) {
                    if (done) return
                    done = true
                    resultRef.set(text)
                    latch.countDown()
                    runCatching {
                        webView.stopLoading()
                        webView.destroy()
                    }
                }

                fun pollJson(attempt: Int) {
                    if (done) return
                    webView.evaluateJavascript(
                        "(function(){ return document.body ? document.body.innerText : ''; })()",
                    ) { value ->
                        if (done) return@evaluateJavascript
                        val unescaped = runCatching {
                            JSONArray("[$value]").optString(0)
                        }.getOrNull() ?: value?.trim('"').orEmpty()
                        if (unescaped.contains("\"code\"") && unescaped.contains("{")) {
                            finish(unescaped)
                        } else if (attempt < 14) {
                            mainHandler.postDelayed({ pollJson(attempt + 1) }, 450L)
                        } else {
                            finish(null)
                        }
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        pollJson(0)
                    }
                }
                webView.loadUrl(url)
            }.onFailure {
                latch.countDown()
            }
        }

        latch.await(9, TimeUnit.SECONDS)
        return resultRef.get()
    }

    private fun fetchYoutubeOembed(videoId: String): JSONObject? {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val url = "https://www.youtube.com/oembed?url=" +
            URLEncoder.encode(watchUrl, "UTF-8") + "&format=json"
        val body = httpGet(url, useDesktopUa = true) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return if (json.has("title")) json else null
    }

    private fun locateClip(
        text: String,
        seedId: String,
        fallback: RegionQuery?,
    ): Triple<String, Double, Double> {
        val matched = inferPlace(text)
        if (matched != null) {
            val coords = jitterCoords(matched.second.first, matched.second.second, seedId, 0.06)
            return Triple(matched.first, coords.first, coords.second)
        }
        if (fallback != null) {
            val coords = jitterCoords(fallback.lat, fallback.lon, seedId, 1.8)
            return Triple(fallback.fallbackPlace, coords.first, coords.second)
        }
        val idx = (seedId.hashCode() and 0x7FFFFFFF) % WORLD_HOTSPOTS.size
        val hub = WORLD_HOTSPOTS[idx]
        val coords = jitterCoords(hub.second, hub.third, seedId, 1.4)
        return Triple(hub.first, coords.first, coords.second)
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

    private fun unescapeHtml(raw: String): String {
        if ('&' !in raw) return raw
        return HTML_ENTITY_REGEX.replace(raw) { match ->
            val dec = match.groupValues[1]
            val hex = match.groupValues[2]
            val named = match.groupValues[3]
            when {
                dec.isNotEmpty() -> dec.toIntOrNull()
                    ?.takeIf { it in 32..0x10FFFF }
                    ?.let { String(Character.toChars(it)) } ?: match.value
                hex.isNotEmpty() -> hex.toIntOrNull(16)
                    ?.takeIf { it in 32..0x10FFFF }
                    ?.let { String(Character.toChars(it)) } ?: match.value
                else -> when (named.lowercase(Locale.US)) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "nbsp" -> " "
                    else -> match.value
                }
            }
        }
    }

    private fun inferPlace(text: String): Pair<String, Pair<Double, Double>>? {
        val lower = text.lowercase(Locale.US)
        for ((key, entry) in WORLD_GAZETTEER) {
            if (lower.contains(key)) {
                return entry
            }
        }
        return null
    }

    private fun snowflakeDate(id: String): String {
        return runCatching {
            val ms = (id.toULong() shr 32).toLong() * 1000L
            if (ms < 1_450_000_000_000L || ms > 1_900_000_000_000L) return "2025-06-01"
            formatUtcDate(ms)
        }.getOrDefault("2025-06-01")
    }

    private fun formatUtcDate(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return sdf.format(Date(epochMs))
    }

    /**
     * Runs an offscreen Chromium WebView harvester against Urlebird when a mobile ISP triggers
     * a Cloudflare JS challenge on OkHttp. Once Cloudflare clears on the first tag page,
     * executes in-page fetch() calls across hashtags and AJAX pagination pages and streams
     * HTML chunks directly back via a JavascriptInterface bridge.
     */
    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun harvestUrlebirdViaWebView(
        context: Context,
        tags: List<TikTokTag>,
        maxPagesPerTag: Int,
        targetRemaining: Int,
        localSeen: MutableSet<String>,
        onChunk: (List<DiscoveredMedia>) -> Unit,
    ): Int {
        if (tags.isEmpty()) return 0
        if (runCatching { CookieManager.getInstance() }.getOrNull() == null) return 0
        val latch = CountDownLatch(1)
        val totalAdded = AtomicInteger(0)
        val tagRequireMap = tags.associate { it.tag to it.requireKeyword }

        val tagsJson = JSONArray().apply { tags.forEach { put(it.tag) } }.toString()

        mainHandler.post {
            runCatching {
                val webView = WebView(context)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = DESKTOP_UA

                val finished = AtomicBoolean(false)
                fun finishAll() {
                    if (!finished.compareAndSet(false, true)) return
                    latch.countDown()
                    mainHandler.post {
                        runCatching {
                            webView.stopLoading()
                            webView.removeJavascriptInterface("AtlasTkBridge")
                            webView.destroy()
                        }
                    }
                }

                val bridge = object {
                    @JavascriptInterface
                    fun onHtml(tag: String, html: String) {
                        if (finished.get() || html.isBlank()) return
                        val requireKw = tagRequireMap[tag] ?: false
                        val parsed = parseUrlebirdCards(html, requireKw, localSeen)
                        if (parsed.isNotEmpty()) {
                            val nowTotal = totalAdded.addAndGet(parsed.size)
                            onChunk(parsed)
                            if (nowTotal >= targetRemaining) {
                                finishAll()
                            }
                        }
                    }

                    @JavascriptInterface
                    fun onDone() {
                        finishAll()
                    }
                }
                webView.addJavascriptInterface(bridge, "AtlasTkBridge")

                val startedHarvester = AtomicBoolean(false)
                fun startJsHarvest() {
                    if (!startedHarvester.compareAndSet(false, true)) return
                    val js = """
                        (async function() {
                            const tags = $tagsJson;
                            const maxPages = $maxPagesPerTag;
                            const sleep = ms => new Promise(r => setTimeout(r, ms));
                            const parseBtn = (html) => {
                                const m = html.match(/<button[^>]*id="hash_load_more"[^>]*>/i);
                                if (!m) return null;
                                const attrs = {};
                                for (const am of m[0].matchAll(/data-([a-z-]+)="([^"]*)"/gi)) {
                                    attrs[am[1].toLowerCase()] = am[2];
                                }
                                return attrs;
                            };
                            try {
                                for (let ti = 0; ti < tags.length; ti++) {
                                    const tag = tags[ti];
                                    const tagUrl = 'https://urlebird.com/hash/' + tag + '/';
                                    let html = ti === 0 ? document.documentElement.innerHTML : '';
                                    if (ti > 0) {
                                        const r = await fetch(tagUrl, { credentials: 'include' });
                                        if (!r.ok) continue;
                                        html = await r.text();
                                    }
                                    window.AtlasTkBridge.onHtml(tag, html);
                                    const attrs = parseBtn(html);
                                    if (!attrs || !attrs.id || !attrs.x) continue;
                                    let cursor = attrs.cursor || '0';
                                    let page = parseInt(attrs.page || '2', 10);
                                    let xTok = attrs.x;
                                    const hashName = attrs.hash || tag;
                                    const lang = attrs.lang || '';
                                    for (let p = 1; p < maxPages; p++) {
                                        await sleep(220);
                                        const payload = JSON.stringify({
                                            hash: hashName, id: attrs.id, cursor: String(cursor),
                                            lang: lang, page: String(page), x: xTok
                                        });
                                        const body = 'action=hash&data=' + encodeURIComponent(payload);
                                        const resp = await fetch('https://urlebird.com/ajax/', {
                                            method: 'POST',
                                            credentials: 'include',
                                            headers: {
                                                'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                                                'X-Requested-With': 'XMLHttpRequest'
                                            },
                                            body
                                        });
                                        if (!resp.ok) break;
                                        const j = await resp.json();
                                        if (!j || !j.thumbs) break;
                                        window.AtlasTkBridge.onHtml(tag, j.thumbs);
                                        if (j.has_more === false || j.cursor === null || j.cursor === undefined) break;
                                        cursor = String(j.cursor);
                                        if (j.x) xTok = j.x;
                                        page = j.page ? parseInt(j.page, 10) : (page + 1);
                                    }
                                    await sleep(250);
                                }
                            } catch (e) {
                            } finally {
                                window.AtlasTkBridge.onDone();
                            }
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(js, null)
                }

                fun pollReady(attempt: Int) {
                    if (finished.get() || startedHarvester.get()) return
                    webView.evaluateJavascript(
                        "(function(){ return !!document.querySelector('.author-name'); })()",
                    ) { res ->
                        if (finished.get() || startedHarvester.get()) return@evaluateJavascript
                        if (res == "true") {
                            startJsHarvest()
                        } else if (attempt < 16) {
                            mainHandler.postDelayed({ pollReady(attempt + 1) }, 450L)
                        } else {
                            finishAll()
                        }
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        pollReady(0)
                    }
                }
                webView.loadUrl("https://urlebird.com/hash/${tags.first().tag}/")
            }.onFailure {
                latch.countDown()
            }
        }

        latch.await(24, TimeUnit.SECONDS)
        return totalAdded.get()
    }

    private fun httpPost(
        urlStr: String,
        formBody: String,
        origin: String = "https://www.tikwm.com",
        referer: String = "https://www.tikwm.com/",
        xhr: Boolean = false,
        useDesktopUa: Boolean = false,
    ): String? {
        return runCatching {
            val reqBuilder = Request.Builder()
                .url(urlStr)
                .post(formBody.toRequestBody(FORM_MEDIA_TYPE))
                .header("User-Agent", if (useDesktopUa) DESKTOP_UA else BROWSER_UA)
                .header("Accept", "application/json,text/html,*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Origin", origin)
                .header("Referer", referer)
            if (xhr) reqBuilder.header("X-Requested-With", "XMLHttpRequest")
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            }
        }.getOrNull()
    }

    private fun httpGet(
        urlStr: String,
        isReddit: Boolean = false,
        useDesktopUa: Boolean = false,
    ): String? {
        return runCatching {
            val req = Request.Builder()
                .url(urlStr)
                .get()
                .header(
                    "User-Agent",
                    when {
                        isReddit -> REDDIT_UA
                        useDesktopUa -> DESKTOP_UA
                        else -> BROWSER_UA
                    },
                )
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            }
        }.getOrNull()
    }
}
