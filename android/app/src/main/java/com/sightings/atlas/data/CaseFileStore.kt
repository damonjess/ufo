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

data class CaseEvidence(
    val id: String,
    var kind: String,
    var title: String,
    var url: String = "",
    var reliability: String = "Unknown",
    var notes: String = "",
    val addedAt: Long = System.currentTimeMillis(),
)

data class CaseHypothesis(
    val id: String,
    var label: String,
    var status: String = "Untested",
    var confidence: Int = 50,
    var supporting: String = "",
    var contradicting: String = "",
    var updatedAt: Long = System.currentTimeMillis(),
)

data class CaseFile(
    val id: String,
    var name: String,
    val reportIds: LinkedHashSet<Int> = LinkedHashSet(),
    val createdAt: Long = System.currentTimeMillis(),
    var status: String = "Open",
    var priority: String = "Normal",
    var summary: String = "",
    var nextAction: String = "",
    var conclusion: String = "",
    val evidence: MutableList<CaseEvidence> = mutableListOf(),
    val hypotheses: MutableList<CaseHypothesis> = mutableListOf(),
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

    fun updateCaseMeta(
        caseId: String,
        status: String = "",
        priority: String = "",
        summary: String? = null,
        nextAction: String? = null,
        conclusion: String? = null,
    ) {
        val c = cases[caseId] ?: return
        if (status.isNotBlank()) c.status = status
        if (priority.isNotBlank()) c.priority = priority
        summary?.let { c.summary = it.trim() }
        nextAction?.let { c.nextAction = it.trim() }
        conclusion?.let { c.conclusion = it.trim() }
        save()
    }

    fun addEvidence(
        caseId: String,
        kind: String,
        title: String,
        url: String,
        reliability: String,
        notes: String,
    ): CaseEvidence? {
        val c = cases[caseId] ?: return null
        val item = CaseEvidence(
            id = UUID.randomUUID().toString().take(8),
            kind = kind,
            title = title.trim().ifEmpty { "Untitled evidence" },
            url = url.trim(),
            reliability = reliability,
            notes = notes.trim(),
        )
        c.evidence.add(item)
        save()
        return item
    }

    fun updateEvidence(
        caseId: String,
        evidenceId: String,
        kind: String,
        title: String,
        url: String,
        reliability: String,
        notes: String,
    ) {
        val c = cases[caseId] ?: return
        val item = c.evidence.firstOrNull { it.id == evidenceId } ?: return
        item.kind = kind
        item.title = title.trim().ifEmpty { "Untitled evidence" }
        item.url = url.trim()
        item.reliability = reliability
        item.notes = notes.trim()
        save()
    }

    fun deleteEvidence(caseId: String, evidenceId: String) {
        val c = cases[caseId] ?: return
        c.evidence.removeAll { it.id == evidenceId }
        save()
    }

    fun addHypothesis(
        caseId: String,
        label: String,
        status: String,
        confidence: Int,
        supporting: String,
        contradicting: String,
    ): CaseHypothesis? {
        val c = cases[caseId] ?: return null
        val item = CaseHypothesis(
            id = UUID.randomUUID().toString().take(8),
            label = label.trim().ifEmpty { "Untitled hypothesis" },
            status = status,
            confidence = confidence.coerceIn(0, 100),
            supporting = supporting.trim(),
            contradicting = contradicting.trim(),
        )
        c.hypotheses.add(item)
        save()
        return item
    }

    fun updateHypothesis(
        caseId: String,
        hypothesisId: String,
        label: String,
        status: String,
        confidence: Int,
        supporting: String,
        contradicting: String,
    ) {
        val c = cases[caseId] ?: return
        val item = c.hypotheses.firstOrNull { it.id == hypothesisId } ?: return
        item.label = label.trim().ifEmpty { "Untitled hypothesis" }
        item.status = status
        item.confidence = confidence.coerceIn(0, 100)
        item.supporting = supporting.trim()
        item.contradicting = contradicting.trim()
        item.updatedAt = System.currentTimeMillis()
        save()
    }

    fun deleteHypothesis(caseId: String, hypothesisId: String) {
        val c = cases[caseId] ?: return
        c.hypotheses.removeAll { it.id == hypothesisId }
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
        sb.append("- **Status:** ").append(caseFile.status).append('\n')
        sb.append("- **Priority:** ").append(caseFile.priority).append('\n')
        sb.append("- **Reports:** ").append(caseFile.reportIds.size).append('\n')
        if (caseFile.nextAction.isNotBlank()) {
            sb.append("- **Next action:** ").append(caseFile.nextAction).append('\n')
        }
        if (caseFile.summary.isNotBlank()) {
            sb.append("\n## Brief\n\n").append(caseFile.summary).append("\n")
        }
        if (caseFile.evidence.isNotEmpty()) {
            sb.append("\n## Evidence Locker\n\n")
            for (e in caseFile.evidence) {
                sb.append("- **[").append(e.kind).append("] ").append(e.title)
                sb.append("** — ").append(e.reliability)
                if (e.url.isNotBlank()) sb.append(" — ").append(e.url)
                sb.append('\n')
                if (e.notes.isNotBlank()) {
                    sb.append("  > ").append(e.notes.replace("\n", "\n  > ")).append('\n')
                }
            }
        }
        if (caseFile.hypotheses.isNotEmpty()) {
            sb.append("\n## Hypotheses\n\n")
            for (h in caseFile.hypotheses) {
                sb.append("- **").append(h.label).append("** — ").append(h.status)
                sb.append(" · ").append(h.confidence).append("%\n")
                if (h.supporting.isNotBlank()) sb.append("  - Supporting: ").append(h.supporting).append('\n')
                if (h.contradicting.isNotBlank()) sb.append("  - Contradicting: ").append(h.contradicting).append('\n')
            }
        }
        if (caseFile.conclusion.isNotBlank()) {
            sb.append("\n## Conclusion\n\n").append(caseFile.conclusion).append("\n")
        }
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
        root.put("status", caseFile.status)
        root.put("priority", caseFile.priority)
        root.put("summary", caseFile.summary)
        root.put("nextAction", caseFile.nextAction)
        root.put("conclusion", caseFile.conclusion)
        val evidenceArr = JSONArray()
        for (e in caseFile.evidence) {
            evidenceArr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("kind", e.kind)
                    put("title", e.title)
                    put("url", e.url)
                    put("reliability", e.reliability)
                    put("notes", e.notes)
                    put("addedAt", e.addedAt)
                },
            )
        }
        root.put("evidence", evidenceArr)
        val hypothesesArr = JSONArray()
        for (h in caseFile.hypotheses) {
            hypothesesArr.put(
                JSONObject().apply {
                    put("id", h.id)
                    put("label", h.label)
                    put("status", h.status)
                    put("confidence", h.confidence)
                    put("supporting", h.supporting)
                    put("contradicting", h.contradicting)
                    put("updatedAt", h.updatedAt)
                },
            )
        }
        root.put("hypotheses", hypothesesArr)
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
                val evidence = ArrayList<CaseEvidence>()
                val evidenceArr = obj.optJSONArray("evidence") ?: JSONArray()
                for (j in 0 until evidenceArr.length()) {
                    val e = evidenceArr.getJSONObject(j)
                    evidence.add(
                        CaseEvidence(
                            id = e.optString("id").ifBlank { UUID.randomUUID().toString().take(8) },
                            kind = e.optString("kind", "Other"),
                            title = e.optString("title", "Untitled evidence"),
                            url = e.optString("url", ""),
                            reliability = e.optString("reliability", "Unknown"),
                            notes = e.optString("notes", ""),
                            addedAt = e.optLong("addedAt", System.currentTimeMillis()),
                        ),
                    )
                }
                val hypotheses = ArrayList<CaseHypothesis>()
                val hypothesesArr = obj.optJSONArray("hypotheses") ?: JSONArray()
                for (j in 0 until hypothesesArr.length()) {
                    val h = hypothesesArr.getJSONObject(j)
                    hypotheses.add(
                        CaseHypothesis(
                            id = h.optString("id").ifBlank { UUID.randomUUID().toString().take(8) },
                            label = h.optString("label", "Untitled hypothesis"),
                            status = h.optString("status", "Untested"),
                            confidence = h.optInt("confidence", 50).coerceIn(0, 100),
                            supporting = h.optString("supporting", ""),
                            contradicting = h.optString("contradicting", ""),
                            updatedAt = h.optLong("updatedAt", System.currentTimeMillis()),
                        ),
                    )
                }
                cases[id] = CaseFile(
                    id = id,
                    name = name,
                    reportIds = ids,
                    createdAt = createdAt,
                    status = obj.optString("status", "Open"),
                    priority = obj.optString("priority", "Normal"),
                    summary = obj.optString("summary", ""),
                    nextAction = obj.optString("nextAction", ""),
                    conclusion = obj.optString("conclusion", ""),
                    evidence = evidence,
                    hypotheses = hypotheses,
                )
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
                obj.put("status", c.status)
                obj.put("priority", c.priority)
                obj.put("summary", c.summary)
                obj.put("nextAction", c.nextAction)
                obj.put("conclusion", c.conclusion)
                val evidenceArr = JSONArray()
                for (e in c.evidence) {
                    evidenceArr.put(
                        JSONObject().apply {
                            put("id", e.id)
                            put("kind", e.kind)
                            put("title", e.title)
                            put("url", e.url)
                            put("reliability", e.reliability)
                            put("notes", e.notes)
                            put("addedAt", e.addedAt)
                        },
                    )
                }
                obj.put("evidence", evidenceArr)
                val hypothesesArr = JSONArray()
                for (h in c.hypotheses) {
                    hypothesesArr.put(
                        JSONObject().apply {
                            put("id", h.id)
                            put("label", h.label)
                            put("status", h.status)
                            put("confidence", h.confidence)
                            put("supporting", h.supporting)
                            put("contradicting", h.contradicting)
                            put("updatedAt", h.updatedAt)
                        },
                    )
                }
                obj.put("hypotheses", hypothesesArr)
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
