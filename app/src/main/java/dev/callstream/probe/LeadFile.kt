package dev.callstream.probe

import dev.callstream.probe.leads.LeadRules
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/** One sheet of a lead file: its rows as text, before any column is chosen. */
data class LeadTable(val sheet: String, val rows: List<List<String>>) {
    val width get() = rows.maxOfOrNull { it.size } ?: 0
    fun cell(row: Int, column: Int) = rows.getOrNull(row)?.getOrNull(column)?.trim().orEmpty()
}

/** Which columns hold the leads. [header] is the header row (-1: none); [name] is empty, one column, or first + last name. */
data class LeadColumns(val header: Int, val number: Int, val name: List<Int>)

/** What an upload adds, for the preview. [namesDropped]: names that weren't usable (too long, digits), left blank so the agent asks. */
data class LeadUpload(
    val contacts: List<Contact>, val skipped: Int, val repeats: Int, val namesDropped: Int,
    /** +1 numbers whose ten digits are a Pakistani mobile (03xx): a wrong country code on the lead form. */
    val plusOne: Int, val foreign: Int,
)

/**
 * Lead files as clients have them: Excel (.xlsx) or CSV in any layout. Meta lead-form exports (`full_name`, `phone`,
 * Excel or UTF-16 CSV with `p:` before numbers), sheets with title rows, renamed or Urdu headers.
 * The phone column is found by its contents; the name column by its header, else by its contents. The person checks
 * both in the preview (LeadImportSheet) before anything is added.
 */
object LeadFile {
    fun isXlsx(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
        bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

    /** The sheet with the most phone numbers (Excel), or the CSV's one. */
    fun read(bytes: ByteArray): LeadTable {
        val tables = if (isXlsx(bytes)) Xlsx.sheets(bytes) else listOf(LeadTable("", csvRows(decode(bytes))))
        return tables.maxByOrNull { t -> t.rows.sumOf { row -> row.count { looksLikePhone(it) } } } ?: error("The file has no sheets")
    }

    /** UTF-8 or, by its byte-order mark, UTF-16 (Meta's CSV download). */
    fun decode(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> String(bytes, Charsets.UTF_8).removePrefix("﻿")
    }

    /** Comma, semicolon (some locales) or tab (Meta), whichever the first line has most of. */
    fun csvRows(text: String): List<List<String>> {
        val first = text.lineSequence().firstOrNull().orEmpty()
        val delimiter = listOf(',', ';', '\t').maxBy { d -> first.count { it == d } }
        return OutboundContacts.parseRows(text, delimiter)
    }

    fun detect(table: LeadTable): LeadColumns? {
        val rows = table.rows
        val width = table.width
        val phones = (0 until width).map { c -> rows.indices.count { looksLikePhone(table.cell(it, c)) } }
        val best = phones.maxOrNull() ?: 0
        if (best == 0) return null
        val firstData = { c: Int -> rows.indices.first { looksLikePhone(table.cell(it, c)) } }
        // Above the first number: title rows, then the header. The row with the most known headers wins; else the row just above.
        val topOf = (0 until width).filter { phones[it] == best }.minOf { firstData(it) }
        val header = (0 until topOf).map { r -> r to rows[r].count { kindOf(it) != null } }.filter { it.second > 0 }
            .maxWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })?.first
            ?: (topOf - 1).takeIf { it >= 0 && rows[it].any { c -> c.isNotBlank() } } ?: -1
        val heads = (0 until width).map { normalizeHeader(if (header >= 0) table.cell(header, it) else "") }
        val number = (0 until width).filter { phones[it] == best }.let { tied -> tied.firstOrNull { kindOf(heads[it]) == Kind.NUMBER } ?: tied.first() }
        val data = (header + 1 until rows.size)
        val others = (0 until width).filter { it != number }
        val name = others.firstOrNull { heads[it] in FULL_NAME }?.let(::listOf)
            ?: listOfNotNull(others.firstOrNull { heads[it] in FIRST_NAME }, others.firstOrNull { heads[it] in LAST_NAME }).takeIf { it.isNotEmpty() }
            ?: others.firstOrNull { kindOf(heads[it]) == Kind.NAME }?.let(::listOf)
            ?: others.filter { kindOf(heads[it]) == null }.map { c ->
                val cells = data.map { table.cell(it, c) }.filter { it.isNotEmpty() }
                val names = cells.count { looksLikeName(it) }
                val distinct = cells.toSet().size.toDouble() / cells.size.coerceAtLeast(1)
                // Mostly name-shaped and mostly different from each other (a city column is name-shaped but repeats).
                c to if (names >= cells.size * 0.6 && distinct >= 0.6) names * distinct else 0.0
            }.filter { it.second > 0 }.maxByOrNull { it.second }?.first?.let(::listOf)
            ?: emptyList()
        return LeadColumns(header, number, name)
    }

    /** The leads [columns] give. [fixPlusOne]: call +1 numbers that are Pakistani mobiles as 03xx. */
    fun upload(table: LeadTable, columns: LeadColumns, fixPlusOne: Boolean): LeadUpload {
        val contacts = mutableListOf<Contact>()
        val seen = HashSet<String>()
        var skipped = 0; var repeats = 0; var dropped = 0; var plusOne = 0; var foreign = 0
        for (r in columns.header + 1 until table.rows.size) {
            if (table.rows[r].all { it.isBlank() }) continue
            val written = phone(table.cell(r, columns.number)) ?: run { skipped++; null } ?: continue
            val fixed = pakistaniBehindPlusOne(written)
            if (fixed != null) plusOne++
            val number = if (fixed != null && fixPlusOne) fixed else written
            if (!seen.add(LeadRules.canonicalNumber(number))) { repeats++; continue }
            if (!isPakistani(number)) foreign++
            val name = cleanName(columns.name.joinToString(" ") { table.cell(r, it) })
            if (name == null) dropped++
            contacts += Contact(name.orEmpty(), number)
        }
        return LeadUpload(contacts, skipped, repeats, dropped, plusOne, foreign)
    }

    /** "B · phone" for the column pickers. */
    fun columnLabel(table: LeadTable, header: Int, column: Int): String {
        val head = if (header >= 0) table.cell(header, column) else ""
        return letter(column) + if (head.isNotEmpty()) " · $head" else ""
    }

    fun letter(column: Int): String {
        var n = column + 1
        val sb = StringBuilder()
        while (n > 0) { sb.append('A' + (n - 1) % 26); n = (n - 1) / 26 }
        return sb.reverse().toString()
    }

    /** A number from a cell: `p:` (Meta) dropped, the first of several (`0300… / 0321…`), Excel's lost leading 0 put back. */
    fun phone(cell: String): String? {
        val text = cell.trim().removePrefix("p:").removePrefix("tel:").trim()
        for (part in text.split('/', ',', ';', '|', '\n').flatMap { it.split(Regex("\\s+or\\s+", RegexOption.IGNORE_CASE)) }) {
            val n = OutboundContacts.normalizeNumber(part) ?: continue
            // A number column in Excel drops the 0: 3001234567.
            return if (n.length == 10 && n.startsWith("3")) "0$n" else n
        }
        return null
    }

    /** Stricter than [phone], for finding the column: a Pakistani mobile, or a number written with its country code. */
    fun looksLikePhone(cell: String): Boolean {
        val n = phone(cell) ?: return false
        return PK_MOBILE.matches(LeadRules.canonicalNumber(n)) || (n.startsWith("+") && n.length in 9..16)
    }

    fun pakistaniBehindPlusOne(number: String): String? =
        if (number.startsWith("+1") && number.length == 12 && PK_MOBILE.matches("0" + number.drop(2))) "0" + number.drop(2) else null

    private fun isPakistani(number: String) = LeadRules.canonicalNumber(number).startsWith("0")

    /**
     * The name to greet the lead with: spaces tidied, a doubled name halved ("M Akram M Akram", Meta's first + last
     * repeated), "" if blank. Null if it isn't usable as a name (a pasted paragraph, digits, an email): left blank.
     */
    fun cleanName(raw: String): String? {
        val s = raw.trim().replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return ""
        val words = s.split(' ')
        if (s.length > MAX_NAME || words.size > 5 || s.any { it.isDigit() } || '@' in s) return null
        val half = words.size / 2
        return if (words.size % 2 == 0 && words.take(half).joinToString(" ").equals(words.drop(half).joinToString(" "), ignoreCase = true))
            words.take(half).joinToString(" ") else s
    }

    private fun looksLikeName(cell: String) = cleanName(cell)?.let { n -> n.isNotEmpty() && n.all { it.isLetter() || it in " .'-" } } == true

    private enum class Kind { NUMBER, NAME, OTHER }

    private fun normalizeHeader(text: String) = text.trim().lowercase().replace(Regex("[_\\-.#:()]+"), " ").replace(Regex("\\s+"), " ").trim()

    private fun kindOf(header: String): Kind? {
        val h = normalizeHeader(header)
        if (h.isEmpty()) return null
        val words = h.split(' ')
        return when {
            h in FULL_NAME || h in FIRST_NAME || h in LAST_NAME -> Kind.NAME
            words.any { it in NUMBER_WORDS } || h in NUMBER_HEADERS -> Kind.NUMBER
            words.any { it in OTHER_WORDS } -> Kind.OTHER
            "name" in words || "naam" in words || "نام" in words -> Kind.NAME
            else -> null
        }
    }

    private const val MAX_NAME = 40
    private val PK_MOBILE = Regex("03\\d{9}")
    private val FULL_NAME = setOf("name", "full name", "fullname", "naam", "نام", "customer", "customer name", "client", "client name",
        "lead name", "contact name", "your name", "person", "پورا نام")
    private val FIRST_NAME = setOf("first name", "firstname", "fname", "given name")
    private val LAST_NAME = setOf("last name", "lastname", "lname", "surname", "family name")
    private val NUMBER_WORDS = setOf("phone", "mobile", "cell", "number", "no", "contact", "whatsapp", "tel", "telephone", "ph",
        "فون", "موبائل", "نمبر", "رابطہ")
    private val NUMBER_HEADERS = setOf("mob", "cell#", "phone#")
    // Columns that are never the name, though their cells look like names.
    private val OTHER_WORDS = setOf("city", "address", "street", "area", "location", "email", "e", "mail", "status", "father",
        "company", "business", "project", "society", "plot", "size", "source", "campaign", "ad", "form", "platform", "date",
        "time", "created", "id", "cnic", "remarks", "notes", "comment", "comments", "شہر", "پتہ")
}

/** Reads an .xlsx workbook's sheets (Office Open XML: a zip of XML parts) without a library. */
object Xlsx {
    private const val MAX_BYTES = 32 shl 20

    fun sheets(bytes: ByteArray): List<LeadTable> {
        val parts = HashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.removePrefix("/")
                if (name != "xl/workbook.xml" && name != "xl/_rels/workbook.xml.rels" && name != "xl/sharedStrings.xml" &&
                    !(name.startsWith("xl/worksheets/") && name.endsWith(".xml"))) continue
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    total += n
                    check(total <= MAX_BYTES) { "That workbook is too large for a lead list" }
                    out.write(buffer, 0, n)
                }
                parts[name] = out.toByteArray()
            }
        }
        val workbook = parts["xl/workbook.xml"] ?: error("This isn't an Excel workbook")
        val strings = parts["xl/sharedStrings.xml"]?.let(::sharedStrings).orEmpty()
        val targets = HashMap<String, String>()
        parts["xl/_rels/workbook.xml.rels"]?.let { rels ->
            parse(rels) { local, a -> if (local == "Relationship") targets[a.getValue("Id").orEmpty()] = a.getValue("Target").orEmpty() }
        }
        val listed = mutableListOf<Pair<String, String>>()
        parse(workbook) { local, a ->
            if (local == "sheet") listed += a.getValue("name").orEmpty() to (attr(a, "id") ?: "")
        }
        val paths = listed.mapNotNull { (name, id) ->
            targets[id]?.let { t -> name to if (t.startsWith("/")) t.drop(1) else "xl/" + t.removePrefix("./") }
        }.ifEmpty { parts.keys.filter { it.startsWith("xl/worksheets/") }.sorted().map { it.substringAfterLast('/') to it } }
        return paths.mapNotNull { (name, path) -> parts[path]?.let { LeadTable(name, rows(it, strings)) } }
    }

    /** Shared strings: each <si>'s text runs, without phonetic guides (<rPh>). */
    private fun sharedStrings(xml: ByteArray): List<String> {
        val out = mutableListOf<String>()
        val text = StringBuilder()
        var inT = false; var phonetic = 0
        parse(xml, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes) {
                when (local(localName, qName)) { "si" -> text.clear(); "t" -> inT = phonetic == 0; "rPh" -> phonetic++ }
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (local(localName, qName)) { "si" -> out += text.toString(); "t" -> inT = false; "rPh" -> phonetic-- }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (inT) text.appendRange(ch, start, start + length) }
        })
        return out
    }

    private fun rows(xml: ByteArray, strings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        var column = 0; var type = ""
        val value = StringBuilder()
        var collecting = false
        parse(xml, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes) {
                when (local(localName, qName)) {
                    "row" -> { row = mutableListOf(); column = 0 }
                    "c" -> {
                        column = a.getValue("r")?.let(::columnOf) ?: column
                        type = a.getValue("t").orEmpty()
                        value.clear()
                    }
                    "v", "t" -> collecting = true
                }
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (local(localName, qName)) {
                    "v", "t" -> collecting = false
                    "c" -> {
                        val raw = value.toString()
                        val text = when (type) {
                            "s" -> raw.trim().toIntOrNull()?.let { strings.getOrNull(it) }.orEmpty()
                            "b" -> if (raw == "1") "TRUE" else "FALSE"
                            "inlineStr", "str", "e" -> raw
                            else -> plainNumber(raw)
                        }
                        while (row.size < column) row += ""
                        if (row.size == column) row += text else row[column] = text
                        column++
                    }
                    "row" -> rows += row
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (collecting) value.appendRange(ch, start, start + length) }
        })
        return rows
    }

    /** Excel stores numbers as decimals: "3001234567", "3.001234567E9", "923001234567.0" → digits as typed. */
    fun plainNumber(raw: String): String = raw.trim().let { t ->
        if (t.isEmpty() || t.none { it == 'E' || it == 'e' || it == '.' }) t
        else runCatching { BigDecimal(t).stripTrailingZeros().toPlainString() }.getOrDefault(t)
    }

    /** "AB12" → 27. */
    fun columnOf(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch !in 'A'..'Z') break; n = n * 26 + (ch - 'A' + 1) }
        return n - 1
    }

    private fun attr(a: Attributes, local: String): String? = (0 until a.length).firstOrNull { i ->
        a.getLocalName(i).ifEmpty { a.getQName(i).substringAfter(':') } == local && a.getQName(i).contains(':')
    }?.let { a.getValue(it) }

    private fun local(localName: String?, qName: String?) = localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')

    private fun parse(xml: ByteArray, onStart: (String, Attributes) -> Unit) = parse(xml, object : DefaultHandler() {
        override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes) = onStart(local(localName, qName), a)
    })

    private fun parse(xml: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        // Workbooks never have a DTD; refuse entities from one (best effort: Android's parser lacks some features).
        listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities")
            .forEach { runCatching { factory.setFeature(it, false) } }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        factory.newSAXParser().parse(ByteArrayInputStream(xml), handler)
    }
}
