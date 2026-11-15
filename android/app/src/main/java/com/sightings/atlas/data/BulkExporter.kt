package com.sightings.atlas.data

import com.sightings.atlas.model.Sighting
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.Writer

object BulkExporter {

    fun writeCsv(
        writer: Writer,
        sightings: List<Sighting>,
        caseStore: CaseFileStore,
        defaultSource: String,
    ) {
        val out = if (writer is BufferedWriter) writer else BufferedWriter(writer, 32_768)
        out.write("id,date,year,time,shape,place,country,lat,lon,source,cases,tags,note\n")

        for (s in sightings) {
            val cases = caseStore.casesForReport(s.id).joinToString(";") { it.name }
            val ann = caseStore.annotationFor(s.id)
            val tags = ann?.tags?.joinToString(";") ?: ""
            val note = ann?.note ?: ""
            val src = if (s.video != null) "video:${s.video.id}" else defaultSource

            out.write(s.id.toString())
            out.write(",")
            out.write(csvEscape(s.date))
            out.write(",")
            out.write(s.year.toString())
            out.write(",")
            out.write(csvEscape(s.time))
            out.write(",")
            out.write(csvEscape(s.shape))
            out.write(",")
            out.write(csvEscape(s.placeLabel()))
            out.write(",")
            out.write(csvEscape(s.country))
            out.write(",")
            if (s.hasPosition) {
                out.write(s.lat.toString())
                out.write(",")
                out.write(s.lon.toString())
            } else {
                out.write(",")
            }
            out.write(",")
            out.write(csvEscape(src))
            out.write(",")
            out.write(csvEscape(cases))
            out.write(",")
            out.write(csvEscape(tags))
            out.write(",")
            out.write(csvEscape(note))
            out.write("\n")
        }
        out.flush()
    }

    fun writeGeoJson(
        writer: Writer,
        sightings: List<Sighting>,
        caseStore: CaseFileStore,
        defaultSource: String,
    ) {
        val out = if (writer is BufferedWriter) writer else BufferedWriter(writer, 32_768)
        out.write("{\"type\":\"FeatureCollection\",\"features\":[\n")
        var first = true

        for (s in sightings) {
            if (!s.hasPosition) continue
            if (!first) out.write(",\n")
            first = false

            val cases = caseStore.casesForReport(s.id).map { it.name }
            val ann = caseStore.annotationFor(s.id)
            val src = if (s.video != null) "video:${s.video.id}" else defaultSource

            val props = JSONObject()
            props.put("id", s.id)
            props.put("date", s.date)
            props.put("year", s.year)
            props.put("time", s.time)
            props.put("shape", s.shape)
            props.put("place", s.placeLabel())
            props.put("country", s.country)
            props.put("source", src)
            if (cases.isNotEmpty()) props.put("cases", cases.joinToString(";"))
            if (ann != null) {
                if (ann.tags.isNotEmpty()) props.put("tags", ann.tags.joinToString(";"))
                if (ann.note.isNotBlank()) props.put("note", ann.note)
            }

            out.write("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            out.write(s.lon.toString())
            out.write(",")
            out.write(s.lat.toString())
            out.write("]},\"properties\":")
            out.write(props.toString())
            out.write("}")
        }
        out.write("\n]}\n")
        out.flush()
    }

    private fun csvEscape(value: String): String {
        if (value.isEmpty()) return ""
        val needsQuotes = value.indexOf(',') >= 0 ||
            value.indexOf('"') >= 0 ||
            value.indexOf('\n') >= 0 ||
            value.indexOf('\r') >= 0
        return if (!needsQuotes) {
            value
        } else {
            "\"" + value.replace("\"", "\"\"") + "\""
        }
    }
}
