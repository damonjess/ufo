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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Dynamically discovers UFO/UAP TikTok clips for the UK using public search +
 * TikTok's page hydration metadata & official oEmbed endpoint, geocodes 110+ UK
 * place names, and caches discovered clips locally on device.
 */
object LiveTikTokFetcher {

    private const val CACHE_FILE = "live_social_cache.json"
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"

    private val executor = Executors.newSingleThreadExecutor()
    private val fetchedRegions = ConcurrentHashMap<String, Long>()
    private val queryCursor = AtomicInteger(0)

    private val TIKTOK_VIDEO_ID_REGEX =
        Regex("""tiktok\.com/@([^/?#"&\s]+)/video/(\d{15,25})""", RegexOption.IGNORE_CASE)
    private val DESC_REGEX = Regex(""""desc"\s*:\s*"((?:\\.|[^"\\])*)"""")
    private val POI_REGEX = Regex(""""poi"\s*:\s*\{[^}]*"name"\s*:\s*"((?:\\.|[^"\\])*)"""")
    private val LOC_CREATED_REGEX = Regex(""""locationCreated"\s*:\s*"([A-Z]{2})"""")
    private val UFO_KEYWORD_REGEX =
        Regex(
            """\b(?:ufo|uap|ufos|uaps|orb|orbs|alien|aliens|sighting|sky|lights|craft|object|sphere|triangle|tic\s*tac|drone|drones|hovering)\b""",
            RegexOption.IGNORE_CASE,
        )

    private val UK_SEARCH_QUERIES = listOf(
        "site:tiktok.com \"video\" ufo uk",
        "site:tiktok.com \"video\" ufo sighting england OR britain",
        "site:tiktok.com \"video\" ufo london OR essex OR kent",
        "site:tiktok.com \"video\" ufo manchester OR liverpool OR lancashire",
        "site:tiktok.com \"video\" ufo yorkshire OR leeds OR sheffield OR hull",
        "site:tiktok.com \"video\" ufo lincolnshire OR scunthorpe OR grimsby OR doncaster",
        "site:tiktok.com \"video\" uap lakenheath OR mildenhall OR suffolk",
        "site:tiktok.com \"video\" ufo scotland OR glasgow OR edinburgh OR bonnybridge",
        "site:tiktok.com \"video\" ufo wales OR cardiff OR swansea OR blackwood",
        "site:tiktok.com \"video\" ufo birmingham OR midlands OR nottingham",
        "site:tiktok.com \"video\" ufo bristol OR cornwall OR devon OR wiltshire",
        "site:tiktok.com \"video\" ufo newcastle OR sunderland OR durham",
        "site:tiktok.com \"video\" ufo belfast OR northern ireland",
        "site:tiktok.com \"video\" orb uk night sky",
        "site:tiktok.com \"video\" triangle ufo uk",
    )

    /** Built-in UK & Ireland gazetteer (most specific places first). */
    private val UK_GAZETTEER = linkedMapOf(
        // Yorkshire, Humber & Lincolnshire
        "scunthorpe" to ("Scunthorpe, North Lincolnshire" to (53.589 to -0.650)),
        "barton upon humber" to ("Barton-upon-Humber, Lincolnshire" to (53.683 to -0.450)),
        "barton-upon-humber" to ("Barton-upon-Humber, Lincolnshire" to (53.683 to -0.450)),
        "grimsby" to ("Grimsby, North East Lincolnshire" to (53.567 to -0.080)),
        "cleethorpes" to ("Cleethorpes, Lincolnshire" to (53.558 to -0.027)),
        "humberside" to ("Humberside, UK" to (53.576 to -0.350)),
        "humber" to ("Humber Estuary, UK" to (53.650 to -0.300)),
        "hull" to ("Hull, East Yorkshire" to (53.745 to -0.336)),
        "kingston upon hull" to ("Hull, East Yorkshire" to (53.745 to -0.336)),
        "doncaster" to ("Doncaster, South Yorkshire" to (53.522 to -1.131)),
        "rotherham" to ("Rotherham, South Yorkshire" to (53.432 to -1.356)),
        "barnsley" to ("Barnsley, South Yorkshire" to (53.552 to -1.479)),
        "sheffield" to ("Sheffield, South Yorkshire" to (53.381 to -1.470)),
        "leeds" to ("Leeds, West Yorkshire" to (53.800 to -1.549)),
        "bradford" to ("Bradford, West Yorkshire" to (53.795 to -1.759)),
        "wakefield" to ("Wakefield, West Yorkshire" to (53.683 to -1.497)),
        "huddersfield" to ("Huddersfield, West Yorkshire" to (53.645 to -1.785)),
        "halifax" to ("Halifax, West Yorkshire" to (53.722 to -1.860)),
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
        "gainsborough" to ("Gainsborough, Lincolnshire" to (53.401 to -0.773)),
        "lincoln" to ("Lincoln, Lincolnshire" to (53.230 to -0.540)),
        "lincolnshire" to ("Lincolnshire, UK" to (53.230 to -0.540)),

        // Military / Airbases & Airports
        "lakenheath" to ("RAF Lakenheath, Suffolk" to (52.409 to 0.561)),
        "mildenhall" to ("RAF Mildenhall, Suffolk" to (52.361 to 0.486)),
        "feltwell" to ("RAF Feltwell, Norfolk" to (52.476 to 0.519)),
        "rendlesham" to ("Rendlesham Forest, Suffolk" to (52.092 to 1.442)),
        "bentwaters" to ("RAF Bentwaters, Suffolk" to (52.128 to 1.439)),
        "brize norton" to ("RAF Brize Norton, Oxfordshire" to (51.750 to -1.583)),
        "lossiemouth" to ("RAF Lossiemouth, Scotland" to (57.705 to -3.339)),
        "manchester airport" to ("Manchester Airport, UK" to (53.353 to -2.274)),
        "heathrow" to ("Heathrow Airport, London" to (51.470 to -0.454)),
        "gatwick" to ("Gatwick Airport, Sussex" to (51.153 to -0.182)),
        "stansted" to ("Stansted Airport, Essex" to (51.886 to 0.238)),
        "crystal palace" to ("Crystal Palace, London" to (51.420 to -0.070)),

        // North West & North East
        "manchester" to ("Manchester, UK" to (53.480 to -2.242)),
        "salford" to ("Salford, Greater Manchester" to (53.487 to -2.290)),
        "bolton" to ("Bolton, Greater Manchester" to (53.576 to -2.428)),
        "wigan" to ("Wigan, Greater Manchester" to (53.545 to -2.632)),
        "stockport" to ("Stockport, Greater Manchester" to (53.410 to -2.157)),
        "liverpool" to ("Liverpool, Merseyside" to (53.408 to -2.991)),
        "wirral" to ("Wirral, Merseyside" to (53.372 to -3.073)),
        "warrington" to ("Warrington, Cheshire" to (53.390 to -2.597)),
        "chester" to ("Chester, Cheshire" to (53.193 to -2.893)),
        "cheshire" to ("Cheshire, UK" to (53.193 to -2.597)),
        "preston" to ("Preston, Lancashire" to (53.763 to -2.703)),
        "blackpool" to ("Blackpool, Lancashire" to (53.817 to -3.055)),
        "lancaster" to ("Lancaster, Lancashire" to (54.046 to -2.800)),
        "lancashire" to ("Lancashire, UK" to (53.763 to -2.703)),
        "carlisle" to ("Carlisle, Cumbria" to (54.892 to -2.932)),
        "lake district" to ("Lake District, Cumbria" to (54.460 to -3.088)),
        "cumbria" to ("Cumbria, UK" to (54.460 to -3.088)),
        "newcastle" to ("Newcastle upon Tyne, UK" to (54.978 to -1.617)),
        "gateshead" to ("Gateshead, Tyne and Wear" to (54.952 to -1.603)),
        "sunderland" to ("Sunderland, Tyne and Wear" to (54.906 to -1.383)),
        "middlesbrough" to ("Middlesbrough, Teesside" to (54.574 to -1.235)),
        "durham" to ("Durham, County Durham" to (54.775 to -1.584)),
        "northumberland" to ("Northumberland, UK" to (55.208 to -2.078)),

        // Midlands & East Anglia
        "birmingham" to ("Birmingham, West Midlands" to (52.486 to -1.890)),
        "coventry" to ("Coventry, West Midlands" to (52.406 to -1.519)),
        "wolverhampton" to ("Wolverhampton, West Midlands" to (52.586 to -2.128)),
        "dudley" to ("Dudley, West Midlands" to (52.512 to -2.081)),
        "cannock chase" to ("Cannock Chase, Staffordshire" to (52.738 to -2.002)),
        "stoke" to ("Stoke-on-Trent, Staffordshire" to (53.002 to -2.179)),
        "staffordshire" to ("Staffordshire, UK" to (52.806 to -2.116)),
        "nottingham" to ("Nottingham, Nottinghamshire" to (52.954 to -1.158)),
        "derby" to ("Derby, Derbyshire" to (52.922 to -1.474)),
        "peak district" to ("Peak District, Derbyshire" to (53.350 to -1.780)),
        "leicester" to ("Leicester, Leicestershire" to (52.636 to -1.139)),
        "northampton" to ("Northampton, UK" to (52.240 to -0.902)),
        "peterborough" to ("Peterborough, Cambridgeshire" to (52.569 to -0.240)),
        "cambridge" to ("Cambridge, Cambridgeshire" to (52.205 to 0.121)),
        "norwich" to ("Norwich, Norfolk" to (52.630 to 1.297)),
        "norfolk" to ("Norfolk, UK" to (52.630 to 1.297)),
        "ipswich" to ("Ipswich, Suffolk" to (52.056 to 1.148)),
        "suffolk" to ("Suffolk, UK" to (52.187 to 0.970)),

        // London, South East & South West
        "london" to ("London, UK" to (51.507 to -0.127)),
        "chelmsford" to ("Chelmsford, Essex" to (51.735 to 0.468)),
        "southend" to ("Southend-on-Sea, Essex" to (51.545 to 0.707)),
        "essex" to ("Essex, UK" to (51.734 to 0.468)),
        "maidstone" to ("Maidstone, Kent" to (51.270 to 0.522)),
        "canterbury" to ("Canterbury, Kent" to (51.280 to 1.078)),
        "dover" to ("Dover, Kent" to (51.127 to 1.313)),
        "kent" to ("Kent, UK" to (51.278 to 0.521)),
        "guildford" to ("Guildford, Surrey" to (51.236 to -0.570)),
        "surrey" to ("Surrey, UK" to (51.310 to -0.560)),
        "brighton" to ("Brighton, Sussex" to (50.822 to -0.137)),
        "sussex" to ("Sussex, UK" to (50.909 to -0.461)),
        "portsmouth" to ("Portsmouth, Hampshire" to (50.819 to -1.088)),
        "southampton" to ("Southampton, Hampshire" to (50.909 to -1.404)),
        "hampshire" to ("Hampshire, UK" to (51.057 to -1.308)),
        "reading" to ("Reading, Berkshire" to (51.454 to -0.978)),
        "oxford" to ("Oxford, Oxfordshire" to (51.752 to -1.257)),
        "milton keynes" to ("Milton Keynes, Buckinghamshire" to (52.040 to -0.759)),
        "bournemouth" to ("Bournemouth, Dorset" to (50.719 to -1.880)),
        "dorset" to ("Dorset, UK" to (50.748 to -2.344)),
        "warminster" to ("Warminster, Wiltshire" to (51.204 to -2.179)),
        "stonehenge" to ("Stonehenge, Wiltshire" to (51.178 to -1.826)),
        "swindon" to ("Swindon, Wiltshire" to (51.555 to -1.779)),
        "wiltshire" to ("Wiltshire, UK" to (51.349 to -1.992)),
        "glastonbury" to ("Glastonbury, Somerset" to (51.149 to -2.714)),
        "somerset" to ("Somerset, UK" to (51.105 to -2.926)),
        "bath" to ("Bath, Somerset" to (51.381 to -2.359)),
        "bristol" to ("Bristol, UK" to (51.454 to -2.587)),
        "gloucester" to ("Gloucester, Gloucestershire" to (51.864 to -2.238)),
        "cheltenham" to ("Cheltenham, Gloucestershire" to (51.899 to -2.078)),
        "exeter" to ("Exeter, Devon" to (50.718 to -3.533)),
        "plymouth" to ("Plymouth, Devon" to (50.375 to -4.142)),
        "dartmoor" to ("Dartmoor, Devon" to (50.571 to -3.920)),
        "devon" to ("Devon, UK" to (50.718 to -3.533)),
        "newquay" to ("Newquay, Cornwall" to (50.415 to -5.073)),
        "truro" to ("Truro, Cornwall" to (50.263 to -5.051)),
        "cornwall" to ("Cornwall, UK" to (50.266 to -5.052)),

        // Scotland
        "bonnybridge" to ("Bonnybridge, Falkirk" to (56.002 to -3.888)),
        "falkirk" to ("Falkirk, Scotland" to (56.001 to -3.783)),
        "calvine" to ("Calvine, Perthshire" to (56.770 to -3.978)),
        "pitlochry" to ("Pitlochry, Perthshire" to (56.704 to -3.734)),
        "livingston" to ("Livingston, West Lothian" to (55.883 to -3.516)),
        "edinburgh" to ("Edinburgh, Scotland" to (55.953 to -3.188)),
        "glasgow" to ("Glasgow, Scotland" to (55.864 to -4.251)),
        "stirling" to ("Stirling, Scotland" to (56.116 to -3.936)),
        "dundee" to ("Dundee, Scotland" to (56.462 to -2.970)),
        "aberdeen" to ("Aberdeen, Scotland" to (57.149 to -2.094)),
        "inverness" to ("Inverness, Scottish Highlands" to (57.477 to -4.224)),
        "loch ness" to ("Loch Ness, Scotland" to (57.322 to -4.424)),
        "scotland" to ("Scottish Highlands, UK" to (56.490 to -4.202)),

        // Wales & Northern Ireland
        "pentyrch" to ("Pentyrch, South Wales" to (51.531 to -3.303)),
        "oakdale" to ("Oakdale, Caerphilly" to (51.681 to -3.181)),
        "blackwood" to ("Blackwood, South Wales" to (51.668 to -3.195)),
        "caerphilly" to ("Caerphilly, South Wales" to (51.574 to -3.218)),
        "pontypool" to ("Pontypool, South Wales" to (51.703 to -3.041)),
        "newport" to ("Newport, South Wales" to (51.584 to -2.997)),
        "cardiff" to ("Cardiff, Wales" to (51.481 to -3.179)),
        "swansea" to ("Swansea, Wales" to (51.621 to -3.943)),
        "brecon" to ("Brecon Beacons, Wales" to (51.883 to -3.433)),
        "broad haven" to ("Broad Haven, Pembrokeshire" to (51.778 to -5.101)),
        "berwyn" to ("Berwyn Mountains, North Wales" to (52.893 to -3.385)),
        "snowdonia" to ("Snowdonia, North Wales" to (52.949 to -3.886)),
        "wrexham" to ("Wrexham, North Wales" to (53.046 to -2.992)),
        "wales" to ("Wales, UK" to (52.130 to -3.783)),
        "belfast" to ("Belfast, Northern Ireland" to (54.597 to -5.930)),
        "derry" to ("Derry, Northern Ireland" to (54.996 to -7.308)),
        "antrim" to ("County Antrim, Northern Ireland" to (54.719 to -6.207)),
        "northern ireland" to ("Northern Ireland, UK" to (54.650 to -6.350)),

        // Country-level fallbacks
        "england" to ("England, UK" to (52.800 to -1.500)),
        "british" to ("England, UK" to (52.800 to -1.500)),
        "britain" to ("United Kingdom" to (53.500 to -2.200)),
        "united kingdom" to ("United Kingdom" to (53.500 to -2.200)),
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
     * Fetches UK TikTok UFO clips in the background, cycling through 15 regional UK
     * search queries and using TikTok's official oEmbed API when page HTML is gated.
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

            // Pick 5 rotating queries per pass so each scan discovers deeper UK regions
            val batchSize = if (force) 7 else 4
            val startIdx = queryCursor.getAndAdd(batchSize)
            val queries = ArrayList<String>(batchSize)
            for (i in 0 until batchSize) {
                queries.add(UK_SEARCH_QUERIES[(startIdx + i) % UK_SEARCH_QUERIES.size])
            }

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

                // 1. Try fast oEmbed JSON first (never blocked by JS challenges)
                val oembedJson = fetchOembed(canonicalUrl)
                val oembedTitle = oembedJson?.optString("title").orEmpty()
                val oembedAuthor = oembedJson?.optString("author_unique_id")
                    ?.ifBlank { oembedJson.optString("author_name") }
                    .orEmpty()

                // 2. Supplement with page HTML for POI / locationCreated if needed
                val pageHtml = if (oembedTitle.isBlank()) httpGet(canonicalUrl) else null
                val rawDesc = oembedTitle.ifBlank {
                    pageHtml?.let { DESC_REGEX.find(it)?.groupValues?.get(1) }
                        ?.let { unescapeJson(it) }
                        .orEmpty()
                }
                val poiName = pageHtml?.let { POI_REGEX.find(it)?.groupValues?.get(1) }
                    ?.let { unescapeJson(it) }
                    .orEmpty()
                val locCreated = pageHtml?.let { LOC_CREATED_REGEX.find(it)?.groupValues?.get(1) }
                    .orEmpty()

                val handle = rawHandle.removePrefix("@").ifBlank { oembedAuthor.ifBlank { "tiktok" } }
                if (rawDesc.isNotBlank() && !UFO_KEYWORD_REGEX.containsMatchIn("$rawDesc $handle")) {
                    continue
                }

                // Since all queries are UK-scoped, default to UK if no specific town is in the caption
                val (place, baseCoords) = inferUkPlace("$poiName $rawDesc $handle", locCreated)
                    ?: ("United Kingdom" to (53.50 to -2.20))

                // Apply deterministic ±0.022° micro-offset so multiple clips in one city don't overlap
                val coords = jitterCoords(baseCoords.first, baseCoords.second, clipId)
                val caption = cleanCaption(rawDesc).ifBlank { "UK UFO/UAP sighting clip posted by @$handle." }
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
                    val f = File(context.filesDir, CACHE_FILE)
                    val tmp = File(context.filesDir, "$CACHE_FILE.tmp")
                    tmp.writeText(cachedArr.toString(2))
                    tmp.copyTo(f, overwrite = true)
                    tmp.delete()
                }
                onNewClips(discovered)
            }
        }
    }

    private fun fetchOembed(videoUrl: String): JSONObject? {
        val url = "https://www.tiktok.com/oembed?url=" + URLEncoder.encode(videoUrl, "UTF-8")
        val body = httpGet(url) ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private fun jitterCoords(lat: Double, lon: Double, seedStr: String): Pair<Double, Double> {
        val h = seedStr.hashCode()
        val dLat = (((h and 0xFF) - 128) / 128.0) * 0.022
        val dLon = ((((h shr 8) and 0xFF) - 128) / 128.0) * 0.032
        val rLat = ((lat + dLat) * 1000.0).roundToInt() / 1000.0
        val rLon = ((lon + dLon) * 1000.0).roundToInt() / 1000.0
        return rLat to rLon
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
                setRequestProperty("Accept", "application/json,text/html")
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
