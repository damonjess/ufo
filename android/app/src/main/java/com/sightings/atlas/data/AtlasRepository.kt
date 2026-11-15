package com.sightings.atlas.data

import android.content.Context
import com.sightings.atlas.model.Sighting
import com.sightings.atlas.model.VideoInfo
import org.json.JSONArray
import org.json.JSONObject

data class GeoLabel(
    val name: String,
    val lon: Double,
    val lat: Double,
    /** Country area in square degrees, or sighting count for a city. */
    val weight: Int,
)

class WorldData(
    /** Flat [lon, lat, …] rings. */
    val land110: List<FloatArray>,
    val land50: List<FloatArray>,
    val borders110: List<FloatArray>,
    val borders50: List<FloatArray>,
    val countries: List<GeoLabel>,
    val cities: List<GeoLabel>,
)

class Dataset(
    val reports: List<Sighting>,
    val videos: List<Sighting>,
    val social: MutableList<Sighting>,
    val world: WorldData,
) {
    val minYear: Int
    val maxYear: Int
    val countries: Int
    val shapes: List<Pair<String, Int>>

    init {
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        for (list in arrayOf(reports, videos, social)) {
            for (sighting in list) {
                if (sighting.year < min) min = sighting.year
                if (sighting.year > max) max = sighting.year
            }
        }
        minYear = if (min == Int.MAX_VALUE) 1910 else min
        maxYear = if (max == Int.MIN_VALUE) 2026 else max
        countries = reports.map { it.country }.distinct().size
        shapes = reports.groupingBy { it.shape }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    }
}

object AtlasRepository {

    @Volatile
    private var cachedDataset: Dataset? = null

    private val ENTITY = Regex("&#(\\d{1,6});?|&([a-zA-Z]{2,6});")

    /** Source data still carries a few HTML entities such as `&#44;`. */
    private fun unescape(raw: String): String {
        if ('&' !in raw) return raw
        return ENTITY.replace(raw) { match ->
            val numeric = match.groupValues[1]
            if (numeric.isNotEmpty()) {
                numeric.toIntOrNull()
                    ?.takeIf { it in 32..0x10FFFF }
                    ?.let { String(Character.toChars(it)) }
                    ?: match.value
            } else {
                when (match.groupValues[2]) {
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

    fun load(context: Context): Dataset {
        cachedDataset?.let { return it }
        val descCmp = compareByDescending<Sighting> { it.date }.thenByDescending { it.id }
        val reports = parseReports(JSONArray(read(context, "data/sightings.json"))).toMutableList()
        val custom = BulkImporter.loadPersistedJson(context)
        for (i in 0 until custom.length()) {
            custom.optJSONObject(i)?.let { reports.add(parseReportObject(it)) }
        }
        reports.sortWith(descCmp)
        val videos = parseVideos(JSONArray(read(context, "data/videos.json"))).sortedWith(descCmp)
        val social = parseSocial(JSONArray(read(context, "data/social.json"))).toMutableList()
        val knownClipIds = social.mapNotNull { it.video?.id }.toHashSet()
        for (cached in LiveTikTokFetcher.loadCached(context)) {
            val id = cached.video?.id ?: continue
            if (knownClipIds.add(id)) {
                social.add(cached)
            }
        }
        social.sortWith(descCmp)
        val world = parseWorld(JSONObject(read(context, "data/world.json")))
        return Dataset(reports, videos, social, world).also { cachedDataset = it }
    }

    private fun read(context: Context, path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    /** Parses one report object; also used by the runtime importer. */
    fun parseReportObject(o: JSONObject): Sighting = Sighting(
        id = o.optInt("id"),
        date = o.optString("d"),
        year = o.optInt("y"),
        time = o.optString("t"),
        city = unescape(o.optString("c")),
        region = unescape(o.optString("r")),
        cc = o.optString("cc"),
        country = unescape(o.optString("co")),
        shape = unescape(o.optString("s")).ifBlank { "unknown" },
        duration = unescape(o.optString("du")),
        summary = unescape(o.optString("x")),
        lat = o.optDouble("lat", Double.NaN),
        lon = o.optDouble("lon", Double.NaN),
    )

    private fun parseReports(json: JSONArray): List<Sighting> {
        val out = ArrayList<Sighting>(json.length())
        for (i in 0 until json.length()) {
            val o = json.getJSONObject(i)
            out.add(parseReportObject(o).copy(id = o.optInt("id", i + 1)))
        }
        return out
    }

    /** Keep the process cache aligned after a runtime import. */
    fun cache(dataset: Dataset) {
        cachedDataset = dataset
    }

    private fun parseVideos(json: JSONArray): List<Sighting> {
        val out = ArrayList<Sighting>(json.length())
        for (i in 0 until json.length()) {
            val o = json.getJSONObject(i)
            val date = o.optString("date")
            val channel = unescape(o.optString("channel"))
            out.add(
                Sighting(
                    id = 900000 + o.optInt("n", i + 1),
                    date = date,
                    year = date.take(4).toIntOrNull() ?: 0,
                    time = "",
                    city = unescape(o.optString("place")),
                    region = "",
                    cc = "",
                    country = channel,
                    shape = "video",
                    duration = "",
                    summary = unescape(o.optString("note")),
                    lat = o.optDouble("lat", Double.NaN),
                    lon = o.optDouble("lon", Double.NaN),
                    video = VideoInfo(
                        id = o.optString("videoId"),
                        title = unescape(o.optString("title")),
                        channel = channel,
                        note = unescape(o.optString("note")),
                    ),
                ),
            )
        }
        return out
    }

    private fun parseSocial(json: JSONArray): List<Sighting> {
        val out = ArrayList<Sighting>(json.length())
        for (i in 0 until json.length()) {
            val o = json.getJSONObject(i)
            val handle = o.optString("handle")
            val clipId = o.optString("id")
            val posted = o.optString("posted")
            val lat = if (o.isNull("lat")) Double.NaN else o.optDouble("lat", Double.NaN)
            val lon = if (o.isNull("lon")) Double.NaN else o.optDouble("lon", Double.NaN)
            val caption = unescape(o.optString("caption"))
            out.add(
                Sighting(
                    id = 700000 + o.optInt("n", i + 1),
                    date = posted,
                    year = posted.take(4).toIntOrNull() ?: 0,
                    time = "",
                    city = unescape(o.optString("place")).ifBlank { "Location not given" },
                    region = "",
                    cc = "",
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
        return out
    }

    private fun parseWorld(json: JSONObject): WorldData {
        fun rings(key: String): List<FloatArray> {
            val array = json.optJSONArray(key) ?: return emptyList()
            val out = ArrayList<FloatArray>(array.length())
            for (i in 0 until array.length()) {
                val ring = array.optJSONArray(i) ?: continue
                if (ring.length() < 6) continue
                val flat = FloatArray(ring.length())
                for (j in 0 until ring.length()) flat[j] = ring.optDouble(j).toFloat()
                out.add(flat)
            }
            return out
        }

        fun labels(key: String, weightKey: String): List<GeoLabel> {
            val array = json.optJSONArray(key) ?: return emptyList()
            val out = ArrayList<GeoLabel>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val name = o.optString("n").trim()
                if (name.isEmpty()) continue
                out.add(
                    GeoLabel(
                        name = name,
                        lon = o.optDouble("x", Double.NaN),
                        lat = o.optDouble("y", Double.NaN),
                        weight = o.optInt(weightKey, 1),
                    ),
                )
            }
            return out
        }

        return WorldData(
            land110 = rings("land110"),
            land50 = rings("land50"),
            borders110 = rings("borders110"),
            borders50 = rings("borders50"),
            countries = labels("countries", "a").filter { it.lon.isFinite() && it.lat.isFinite() },
            cities = labels("cities", "w").filter { it.lon.isFinite() && it.lat.isFinite() },
        )
    }
}
