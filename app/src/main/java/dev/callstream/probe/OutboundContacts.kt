package dev.callstream.probe

import org.json.JSONArray
import org.json.JSONObject

/** A contact for outbound calling. [number] holds only digits and an optional leading +. */
data class Contact(val name: String, val number: String)

/** Phone numbers, CSV rows and the contact JSON the helper takes. Lead files are read by LeadFile. */
object OutboundContacts {
    /** Strips spaces, dashes, dots and brackets; keeps a leading +. Null if it isn't a phone number. */
    fun normalizeNumber(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.any { !it.isDigit() && it !in " -.()+/" }) return null
        if (trimmed.drop(1).contains('+')) return null
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length !in 3..15) return null
        return (if (trimmed.startsWith("+")) "+" else "") + digits
    }

    /** RFC 4180 fields: quoted fields may contain the delimiter, newlines and doubled quotes. */
    fun parseRows(text: String, delimiter: Char = ','): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' && (quoted || field.isEmpty()) -> quoted = !quoted
                quoted -> field.append(c)
                c == delimiter -> { row += field.toString(); field.clear() }
                c == '\n' || c == '\r' -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += field.toString(); field.clear(); rows += row; row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
        return rows
    }

    fun toJson(contacts: List<Contact>): String = JSONArray().apply {
        contacts.forEach { put(JSONObject().put("name", it.name).put("number", it.number)) }
    }.toString()

    fun fromJson(json: String): List<Contact> {
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getJSONObject(it).let { o -> Contact(o.optString("name"), o.getString("number")) } }
    }
}
