package com.sightings.atlas.model

import androidx.annotation.ColorInt
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class VideoInfo(
    val id: String,
    val title: String,
    val channel: String,
    val note: String,
    val platform: String = "youtube",
    val url: String? = null,
) {
    val isTikTok: Boolean get() = platform == "tiktok"

    val watchUrl: String
        get() = url ?: if (isTikTok) {
            "https://www.tiktok.com/video/$id"
        } else {
            "https://www.youtube.com/watch?v=$id"
        }
}

data class Sighting(
    val id: Int,
    /** ISO date, YYYY-MM-DD. */
    val date: String,
    val year: Int,
    /** Local time, HH:MM; may be empty. */
    val time: String,
    val city: String,
    val region: String,
    val cc: String,
    val country: String,
    val shape: String,
    val duration: String,
    val summary: String,
    val lat: Double,
    val lon: Double,
    val video: VideoInfo? = null,
) {
    val hasPosition: Boolean get() = lat.isFinite() && lon.isFinite()

    /** Pre-computed lowercase search haystack to avoid per-item string allocation and lowercase on every filter pass. */
    val searchHaystack: String = (
        "$city $region $country $shape $summary $date"
        ).lowercase(Locale.US)

    fun placeLabel(): String = city.ifBlank { region.ifBlank { country } }

    /** "Pinehurst, North Carolina, United States" without duplicate segments. */
    fun fullPlace(): String {
        val seen = HashSet<String>()
        return listOf(city, region, country)
            .filter { it.isNotBlank() }
            .filter { seen.add(it.lowercase(Locale.US)) }
            .joinToString(", ")
    }
}

enum class Era(
    val label: String,
    val short: String,
    val from: Int,
    val to: Int,
    @ColorInt val color: Int,
) {
    OLD("Before 1970", "pre-1970", 1900, 1969, 0xFFFF9F52.toInt()),
    MID("1970–1999", "1970–99", 1970, 1999, 0xFFF2D276.toInt()),
    MODERN("2000–2014", "2000–14", 2000, 2014, 0xFF6FC9F2.toInt()),
    RECENT("2015 onwards", "2015+", 2015, 2100, 0xFF6EE7B7.toInt()),
}

fun eraOf(year: Int): Era = when {
    year < 1970 -> Era.OLD
    year < 2000 -> Era.MID
    year < 2015 -> Era.MODERN
    else -> Era.RECENT
}

private val SHAPE_LABELS = mapOf(
    "unknown" to "Unclassified",
    "other" to "Other",
    "light" to "Light",
    "disc" to "Disc",
    "sphere" to "Sphere",
    "oval" to "Oval",
    "cylinder" to "Cylinder",
    "rectangle" to "Rectangle",
    "triangle" to "Triangle",
    "diamond" to "Diamond",
    "chevron" to "Chevron",
    "cone" to "Cone",
    "cross" to "Cross",
    "teardrop" to "Teardrop",
    "fireball" to "Fireball",
    "formation" to "Formation",
    "changing" to "Changing",
    "star" to "Star-like",
)

fun shapeLabel(key: String): String = SHAPE_LABELS[key] ?: key

private val MONTHS = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

fun formatDate(iso: String): String {
    val parts = iso.split("-")
    if (parts.size < 3) return iso
    val year = parts[0].toIntOrNull() ?: return iso
    val month = parts[1].toIntOrNull()?.let { MONTHS.getOrNull(it - 1) } ?: return iso
    val day = parts[2].toIntOrNull() ?: return iso
    return "$day $month $year"
}

data class ShortDate(val day: String, val month: String, val year: String)

fun shortDate(iso: String): ShortDate {
    val parts = iso.split("-")
    if (parts.size < 3) return ShortDate(iso, "", "")
    val month = parts[1].toIntOrNull()?.let { MONTHS.getOrNull(it - 1)?.take(3) } ?: ""
    val day = parts[2].toIntOrNull()?.toString() ?: parts[2]
    return ShortDate(day, month, parts[0])
}

/** Great-circle distance in kilometres. */
fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * 6371.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

fun coordLabel(lat: Double, lon: Double): String {
    val ns = if (lat >= 0) "N" else "S"
    val ew = if (lon >= 0) "E" else "W"
    return String.format(Locale.US, "%.2f° %s, %.2f° %s", kotlin.math.abs(lat), ns, kotlin.math.abs(lon), ew)
}
