package com.sightings.atlas.data

import android.content.Context
import com.sightings.atlas.model.Sighting
import com.sightings.atlas.model.coordLabel
import com.sightings.atlas.model.formatDate
import com.sightings.atlas.model.shapeLabel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

data class CaseFile(
    val id: String,
    var name: String,
    val reportIds: LinkedHashSet<Int> = LinkedHashSet(),
    val createdAt: Long = System.currentTimeMillis(),
)

data class ReportAnnotation(
    val reportId: Int,
    var note: String = "",
    val tags: LinkedHashSet<String> = LinkedHashSet(),
    var updatedAt: Long = System.currentTimeMillis(),
) {
    val isEmpty: Boolean get() = note.isBlank() && tags.isEmpty()
}

class CaseFileStore(private val context: Context) {

    private val file = File(context.filesDir, "case_files.json")
    private val cases = LinkedHashMap<String, CaseFile>()
    private val annotations = HashMap<Int, ReportAnnotation>()
    private val allSavedCache = HashSet<Int>()

    init {
        load()
    }

    fun allCases(): List<CaseFile> = cases.values.toList()

    fun getCase(id: String): CaseFile? = cases[id]

    fun allSavedIds(): Set<Int> = allSavedCache

    fun isSaved(reportId: Int): Boolean = reportId in allSavedCache

    fun casesForReport(reportId: Int): List<CaseFile> =
        cases.values.filter { reportId in it.reportIds }

    fun annotationFor(reportId: Int): ReportAnnotation? = annotations[reportId]

    fun createCase(name: String): CaseFile {
        val trimmed = name.trim().ifEmpty { "Untitled Case" }
        val existing = cases.values.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        if (existing != null) return existing
        val created = CaseFile(
            id = UUID.randomUUID().toString().take(8),
            name = trimmed,
        )
        cases[created.id] = created
        save()
        return created
    }

    fun renameCase(caseId: String, newName: String) {
        val c = cases[caseId] ?: return
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        c.name = trimmed
        save()
    }

    fun deleteCase(caseId: String) {
        if (cases.remove(caseId) != null) {
            rebuildSavedCache()
            save()
        }
    }

    fun setReportCases(reportId: Int, selectedCaseIds: Set<String>) {
        for (c in cases.values) {
            if (c.id in selectedCaseIds) c.reportIds.add(reportId)
            else c.reportIds.remove(reportId)
        }
        rebuildSavedCache()
        save()
    }

    fun setAnnotation(reportId: Int, note: String, rawTags: Collection<String>) {
        val cleanNote = note.trim()
        val cleanTags = LinkedHashSet<String>()
        for (t in rawTags) {
            val normalized = t.trim().lowercase(Locale.US).removePrefix("#")
            if (normalized.isNotEmpty()) cleanTags.add(normalized)
        }
        if (cleanNote.isEmpty() && cleanTags.isEmpty()) {
            annotations.remove(reportId)
        } else {
            annotations[reportId] = ReportAnnotation(
                reportId = reportId,
                note = cleanNote,
                tags = cleanTags,
                updatedAt = System.currentTimeMillis(),
            )
        }
        save()
    }

    /** Matches search queries against user notes and #tags for a sighting. */
    fun matchesAnnotation(reportId: Int, queryLower: String): Boolean {
        val ann = annotations[reportId] ?: return false
        val q = queryLower.removePrefix("#")
        if (ann.note.lowercase(Locale.US).contains(q)) return true
        return ann.tags.any { it.contains(q) }
    }

    fun exportCaseMarkdown(caseFile: CaseFile, allSightingsById: Map<Int, Sighting>): String {
        val sb = StringBuilder(4096)
        sb.append("# Case File: ").append(caseFile.name).append("\n\n")
        sb.append("- **Reports:** ").append(caseFile.reportIds.size).append('\n')
        sb.append("\n---\n\n")

        for (id in caseFile.reportIds) {
            val s = allSightingsById[id] ?: continue
            val ann = annotations[id]
            sb.append("## ").append(s.placeLabel()).append(" — ").append(formatDate(s.date)).append('\n')
            sb.append("- **ID:** `").append(s.id).append("`\n")
            sb.append("- **Shape:** ").append(shapeLabel(s.shape)).append('\n')
            if (s.time.isNotBlank()) sb.append("- **Time:** ").append(s.time).append('\n')
            if (s.hasPosition) {
                sb.append("- **Coordinates:** ").append(coordLabel(s.lat, s.lon))
                    .append(" (`").append(s.lat).append(", ").append(s.lon).append("`)\n")
            }
            if (ann != null && ann.tags.isNotEmpty()) {
                sb.append("- **Tags:** ").append(ann.tags.joinToString(" ") { "#$it" }).append('\n')
            }
            if (ann != null && ann.note.isNotBlank()) {
                sb.append("\n> **Investigator Note:** ").append(ann.note.replace("\n", "\n> ")).append("\n")
            }
            sb.append("\n---\n\n")
        }
        return sb.toString()
    }

    fun exportCaseJson(caseFile: CaseFile, allSightingsById: Map<Int, Sighting>): String {
        val root = JSONObject()
        root.put("id", caseFile.id)
        root.put("name", caseFile.name)
        root.put("createdAt", caseFile.createdAt)
        val items = JSONArray()
        for (id in caseFile.reportIds) {
            val s = allSightingsById[id] ?: continue
            val ann = annotations[id]
            val obj = JSONObject()
            obj.put("id", s.id)
            obj.put("date", s.date)
            obj.put("year", s.year)
            obj.put("time", s.time)
            obj.put("place", s.placeLabel())
            obj.put("country", s.country)
            obj.put("shape", s.shape)
            if (s.hasPosition) {
                obj.put("lat", s.lat)
                obj.put("lon", s.lon)
            }
            if (ann != null) {
                if (ann.note.isNotBlank()) obj.put("note", ann.note)
                if (ann.tags.isNotEmpty()) obj.put("tags", JSONArray(ann.tags.toList()))
            }
            items.put(obj)
        }
        root.put("reports", items)
        return root.toString(2)
    }

    private fun rebuildSavedCache() {
        allSavedCache.clear()
        for (c in cases.values) {
            allSavedCache.addAll(c.reportIds)
        }
    }

    private fun load() {
        cases.clear()
        annotations.clear()
        if (!file.exists()) {
            seedDefaultsAndMigrate()
            return
        }
        runCatching {
            val root = JSONObject(file.readText())
            val casesArr = root.optJSONArray("cases") ?: JSONArray()
            for (i in 0 until casesArr.length()) {
                val obj = casesArr.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                val ids = LinkedHashSet<Int>()
                val idArr = obj.optJSONArray("reportIds") ?: JSONArray()
                for (j in 0 until idArr.length()) ids.add(idArr.getInt(j))
                cases[id] = CaseFile(id, name, ids, createdAt)
            }
            val annArr = root.optJSONArray("annotations") ?: JSONArray()
            for (i in 0 until annArr.length()) {
                val obj = annArr.getJSONObject(i)
                val reportId = obj.getInt("reportId")
                val note = obj.optString("note", "")
                val updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                val tags = LinkedHashSet<String>()
                val tagArr = obj.optJSONArray("tags") ?: JSONArray()
                for (j in 0 until tagArr.length()) tags.add(tagArr.getString(j))
                if (note.isNotBlank() || tags.isNotEmpty()) {
                    annotations[reportId] = ReportAnnotation(reportId, note, tags, updatedAt)
                }
            }
        }.onFailure {
            seedDefaultsAndMigrate()
        }
        if (cases.isEmpty()) seedDefaultsAndMigrate()
        rebuildSavedCache()
    }

    private fun seedDefaultsAndMigrate() {
        val prefs = context.getSharedPreferences("atlas_prefs", Context.MODE_PRIVATE)
        val legacySaved = prefs.getStringSet("saved_ids", emptySet())
            .orEmpty()
            .mapNotNull { it.toIntOrNull() }

        val readLater = CaseFile("read_later", "Read later", LinkedHashSet(legacySaved))
        val bestEvidence = CaseFile("best_evidence", "Best evidence")
        val uk2024 = CaseFile("uk_2024", "UK 2024")

        cases[readLater.id] = readLater
        cases[bestEvidence.id] = bestEvidence
        cases[uk2024.id] = uk2024
        rebuildSavedCache()
        save()
    }

    private fun save() {
        runCatching {
            val root = JSONObject()
            val casesArr = JSONArray()
            for (c in cases.values) {
                val obj = JSONObject()
                obj.put("id", c.id)
                obj.put("name", c.name)
                obj.put("createdAt", c.createdAt)
                val idArr = JSONArray()
                for (id in c.reportIds) idArr.put(id)
                obj.put("reportIds", idArr)
                casesArr.put(obj)
            }
            val annArr = JSONArray()
            for (ann in annotations.values) {
                if (ann.isEmpty) continue
                val obj = JSONObject()
                obj.put("reportId", ann.reportId)
                obj.put("note", ann.note)
                obj.put("updatedAt", ann.updatedAt)
                val tagArr = JSONArray()
                for (t in ann.tags) tagArr.put(t)
                obj.put("tags", tagArr)
                annArr.put(obj)
            }
            root.put("cases", casesArr)
            root.put("annotations", annArr)
            val tmp = File(context.filesDir, "case_files.json.tmp")
            tmp.writeText(root.toString())
            tmp.copyTo(file, overwrite = true)
            tmp.delete()

            // Keep legacy saved_ids synced for any callers still reading SharedPreferences
            context.getSharedPreferences("atlas_prefs", Context.MODE_PRIVATE)
                .edit()
                .putStringSet("saved_ids", allSavedCache.map { it.toString() }.toSet())
                .apply()
        }
    }
}
