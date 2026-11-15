package com.sightings.atlas.data

import android.content.Context
import com.sightings.atlas.model.Sighting
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.roundToInt

data class ImportResult(
    val added: Int,
    val skippedDuplicates: Int,
    val totalCustom: Int,
)

object BulkImporter {

    private const val FILE_NAME = "imported_sightings.json"
    private const val ID_OFFSET = 1_000_000

    fun customFile(context: Context): File = File(context.filesDir, FILE_NAME)

    /** Reads persisted custom sightings JSON array from filesDir. */
    fun loadPersistedJson(context: Context): JSONArray {
        val file = customFile(context)
        if (!file.exists()) return JSONArray()
        return runCatching { JSONArray(file.readText()) }.getOrElse { JSONArray() }
    }

    /** Clears all runtime-imported sightings. */
    fun clearImported(context: Context) {
        customFile(context).delete()
    }

    /**
     * Parses a CSV, JSON array, or GeoJSON stream, deduplicates against [existing],
     * saves new items to `filesDir/imported_sightings.json`, and restores any
     * case/tag/note annotations into [caseStore].
     */
    fun importStream(
        context: Context,
        input: InputStream,
        existing: List<Sighting>,
        caseStore: CaseFileStore,
        parseSighting: (JSONObject) -> Sighting?,
        outNewSightings: MutableList<Sighting>,
    ): ImportResult {
        val text = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 32_768)
            .use { it.readText().trim().removePrefix("\uFEFF") }
        if (text.isEmpty()) return ImportResult(0, 0, 0)

        val persisted = loadPersistedJson(context)
        var nextId = ID_OFFSET
        val seenKeys = HashSet<String>(existing.size + persisted.length() + 1024)

        for (s in existing) {
            if (s.id >= nextId) nextId = s.id + 1
            seenKeys.add(dedupKey(s.date, s.placeLabel(), s.lat, s.lon))
        }
        for (i in 0 until persisted.length()) {
            val obj = persisted.optJSONObject(i) ?: continue
            val id = obj.optInt("id", 0)
            if (id >= nextId) nextId = id + 1
            val lat = obj.optDouble("lat", Double.NaN)
            val lon = obj.optDouble("lon", Double.NaN)
            if (lat.isFinite() && lon.isFinite()) {
                seenKeys.add(dedupKey(obj.optString("d"), obj.optString("c"), lat, lon))
            }
        }

        val rawItems: List<JSONObject> = when {
            text.startsWith("[") -> parseJsonArray(text)
            text.startsWith("{") -> parseGeoJson(text)
            else -> parseCsv(text)
        }

        var added = 0
        var skipped = 0

        for (raw in rawItems) {
            val normalized = normalizeRecord(raw, nextId) ?: continue
            val d = normalized.optString("d")
            val c = normalized.optString("c")
            val lat = normalized.optDouble("lat")
            val lon = normalized.optDouble("lon")
            val key = dedupKey(d, c, lat, lon)

            if (!seenKeys.add(key)) {
                skipped++
                continue
            }

            val assignedId = normalized.getInt("id")
            if (assignedId >= nextId) nextId = assignedId + 1

            val sighting = parseSighting(normalized) ?: continue
            persisted.put(normalized)
            outNewSightings.add(sighting)
            added++

            val casesStr = raw.optString("_cases", "")
            val tagsStr = raw.optString("_tags", "")
            val noteStr = raw.optString("_note", "")
            if (casesStr.isNotBlank()) {
                val caseIds = casesStr.split(';', ',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { caseName -> caseStore.createCase(caseName).id }
                    .toSet()
                if (caseIds.isNotEmpty()) {
                    caseStore.setReportCases(sighting.id, caseIds)
                }
            }
            if (tagsStr.isNotBlank() || noteStr.isNotBlank()) {
                val tags = tagsStr.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }
                caseStore.setAnnotation(sighting.id, noteStr, tags)
            }
        }

        if (added > 0) {
            val file = customFile(context)
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(persisted.toString())
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }

        return ImportResult(added, skipped, persisted.length())
    }

    private fun dedupKey(date: String, place: String, lat: Double, lon: Double): String {
        val latInt = (lat * 100).roundToInt()
        val lonInt = (lon * 100).roundToInt()
        return "${date.take(10)}|${place.lowercase(Locale.US).take(24)}|$latInt|$lonInt"
    }

    private fun normalizeRecord(raw: JSONObject, fallbackId: Int): JSONObject? {
        val rawDate = raw.optString("d").ifBlank {
            raw.optString("date").ifBlank { raw.optString("datetime") }
        }.trim()
        val d = rawDate.take(10)
        if (d.length < 4) return null
        val y = raw.optInt("y", 0).let {
            if (it in 1800..2100) it else d.take(4).toIntOrNull() ?: return null
        }

        val lat = when {
            raw.has("lat") -> raw.optDouble("lat", Double.NaN)
            raw.has("latitude") -> raw.optDouble("latitude", Double.NaN)
            else -> Double.NaN
        }
        val lon = when {
            raw.has("lon") -> raw.optDouble("lon", Double.NaN)
            raw.has("lng") -> raw.optDouble("lng", Double.NaN)
            raw.has("longitude") -> raw.optDouble("longitude", Double.NaN)
            else -> Double.NaN
        }
        if (!lat.isFinite() || !lon.isFinite()) return null

        val t = raw.optString("t").ifBlank {
            raw.optString("time").ifBlank {
                if (rawDate.length >= 16) rawDate.substring(11, 16) else "00:00"
            }
        }.take(5)

        val place = raw.optString("c").ifBlank {
            raw.optString("place").ifBlank { raw.optString("city").ifBlank { "Unknown" } }
        }.trim()
        val region = raw.optString("r").ifBlank {
            raw.optString("region").ifBlank { raw.optString("state") }
        }.trim().lowercase(Locale.US)
        val country = raw.optString("co").ifBlank {
            raw.optString("country").ifBlank { "United Kingdom" }
        }.trim()
        val cc = raw.optString("cc").ifBlank {
            raw.optString("country_code").ifBlank {
                when (country.lowercase(Locale.US)) {
                    "united states", "usa", "us" -> "us"
                    "canada", "ca" -> "ca"
                    "australia", "au" -> "au"
                    else -> "gb"
                }
            }
        }.trim().lowercase(Locale.US)

        val shape = raw.optString("s").ifBlank {
            raw.optString("shape").ifBlank { "unknown" }
        }.trim().lowercase(Locale.US)
        val duration = raw.optString("du").ifBlank { raw.optString("duration") }.trim()
        val summary = raw.optString("x").ifBlank {
            raw.optString("summary").ifBlank {
                raw.optString("description").ifBlank { raw.optString("note") }
            }
        }.trim()

        return JSONObject().apply {
            put("id", fallbackId)
            put("d", d)
            put("y", y)
            put("t", t)
            put("c", place)
            put("r", region)
            put("cc", cc)
            put("co", country)
            put("s", shape)
            put("du", duration)
            put("x", summary)
            put("lat", (lat * 1000.0).roundToInt() / 1000.0)
            put("lon", (lon * 1000.0).roundToInt() / 1000.0)
        }
    }

    private fun parseJsonArray(text: String): List<JSONObject> {
        val arr = JSONArray(text)
        val out = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.has("cases")) obj.put("_cases", jsonArrayText(obj.opt("cases")))
            if (obj.has("tags")) obj.put("_tags", jsonArrayText(obj.opt("tags")))
            if (obj.has("note")) obj.put("_note", obj.optString("note"))
            out.add(obj)
        }
        return out
    }

    private fun parseGeoJson(text: String): List<JSONObject> {
        val root = JSONObject(text)
        val features = root.optJSONArray("features") ?: return emptyList()
        val out = ArrayList<JSONObject>(features.length())
        for (i in 0 until features.length()) {
            val feat = features.optJSONObject(i) ?: continue
            val geom = feat.optJSONObject("geometry") ?: continue
            val coords = geom.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val props = feat.optJSONObject("properties") ?: JSONObject()
            val merged = JSONObject(props.toString())
            merged.put("lon", coords.optDouble(0))
            merged.put("lat", coords.optDouble(1))
            if (props.has("cases")) merged.put("_cases", jsonArrayText(props.opt("cases")))
            if (props.has("tags")) merged.put("_tags", jsonArrayText(props.opt("tags")))
            if (props.has("note")) merged.put("_note", props.optString("note"))
            out.add(merged)
        }
        return out
    }

    private fun jsonArrayText(value: Any?): String = when (value) {
        is JSONArray -> (0 until value.length()).map { value.optString(it) }.joinToString(";")
        null -> ""
        else -> value.toString()
    }

    /** RFC-4180 CSV parser supporting quoted fields and multi-line notes. */
    private fun parseCsv(text: String): List<JSONObject> {
        val rows = parseCsvRows(text)
        if (rows.size < 2) return emptyList()
        val headers = rows[0].map { it.trim().lowercase(Locale.US) }
        val out = ArrayList<JSONObject>(rows.size - 1)

        for (r in 1 until rows.size) {
            val cols = rows[r]
            if (cols.size < 2) continue
            val obj = JSONObject()
            for (c in headers.indices) {
                if (c >= cols.size) break
                val h = headers[c]
                val v = cols[c].trim()
                if (v.isEmpty()) continue
                when (h) {
                    "cases" -> obj.put("_cases", v)
                    "tags" -> obj.put("_tags", v)
                    "note", "notes" -> {
                        obj.put("_note", v)
                        if (!obj.has("x") && !obj.has("summary")) obj.put("x", v)
                    }
                    else -> obj.put(h, v)
                }
            }
            out.add(obj)
        }
        return out
    }

    private fun parseCsvRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var curRow = ArrayList<String>()
        val curField = StringBuilder()
        var inQuotes = false
        var i = 0
        val n = text.length

        while (i < n) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < n && text[i + 1] == '"') {
                        curField.append('"')
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                    }
                } else {
                    curField.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    ',' -> {
                        curRow.add(curField.toString())
                        curField.setLength(0)
                    }
                    '\r', '\n' -> {
                        if (ch == '\r' && i + 1 < n && text[i + 1] == '\n') i++
                        curRow.add(curField.toString())
                        curField.setLength(0)
                        if (curRow.size > 1 || curRow[0].isNotBlank()) {
                            rows.add(curRow)
                        }
                        curRow = ArrayList()
                    }
                    else -> curField.append(ch)
                }
            }
            i++
        }
        if (curField.isNotEmpty() || curRow.isNotEmpty()) {
            curRow.add(curField.toString())
            if (curRow.size > 1 || curRow[0].isNotBlank()) rows.add(curRow)
        }
        return rows
    }
}
