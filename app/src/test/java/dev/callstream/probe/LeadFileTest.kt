package dev.callstream.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LeadFileTest {
    /** A workbook laid out like Excel writes one: shared strings, numbers as <v>, cell refs. */
    private fun xlsx(vararg sheets: Pair<String, List<List<Any>>>): ByteArray {
        val strings = mutableListOf<String>()
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            sheets.forEachIndexed { i, (_, rows) ->
                put("xl/worksheets/sheet${i + 1}.xml", buildString {
                    append("<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
                    rows.forEachIndexed { r, row ->
                        append("<row r=\"${r + 1}\">")
                        row.forEachIndexed { c, v ->
                            val ref = LeadFile.letter(c) + (r + 1)
                            when (v) {
                                is Number -> append("<c r=\"$ref\"><v>$v</v></c>")
                                "" -> {}
                                else -> { strings += v.toString(); append("<c r=\"$ref\" t=\"s\"><v>${strings.size - 1}</v></c>") }
                            }
                        }
                        append("</row>")
                    }
                    append("</sheetData></worksheet>")
                })
            }
            put("xl/sharedStrings.xml", "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                strings.joinToString("") { "<si><t>${esc(it)}</t><rPh><t>x</t></rPh></si>" } + "</sst>")
            put("xl/workbook.xml", "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" +
                sheets.indices.joinToString("") { "<sheet name=\"${sheets[it].first}\" sheetId=\"${it + 1}\" r:id=\"rId${it + 1}\"/>" } + "</sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } + "</Relationships>")
        }
        return out.toByteArray()
    }

    private fun parse(bytes: ByteArray, fixPlusOne: Boolean = true): LeadUpload {
        val table = LeadFile.read(bytes)
        return LeadFile.upload(table, LeadFile.detect(table)!!, fixPlusOne)
    }

    // Two Meta lead-form layouts (made-up people).
    private val metaAlpine = listOf(
        listOf("required_plot_size", "full_name", "phone", "email", "city", "street_address", "lead_status"),
        listOf("3_marla", "Ali Raza", "+923001234567", "ali@example.com", "Lahore", "Model Town", "complete"),
        listOf("5_marla", "Sara Khan", "+13254221162", "sara@example.com", "lahore", "Johar Town", "complete"),
    )

    @Test fun metaExportWithPlotSizeFirst() {
        val table = LeadFile.read(xlsx("Sheet1" to metaAlpine))
        assertEquals(LeadColumns(0, 2, listOf(1)), LeadFile.detect(table))
        val up = LeadFile.upload(table, LeadFile.detect(table)!!, fixPlusOne = true)
        assertEquals(listOf(Contact("Ali Raza", "+923001234567"), Contact("Sara Khan", "03254221162")), up.contacts)
        assertEquals(1, up.plusOne)
        assertEquals(0, up.foreign)
    }

    @Test fun plusOneKeptWhenAsked() {
        val up = parse(xlsx("Sheet1" to metaAlpine), fixPlusOne = false)
        assertEquals("+13254221162", up.contacts[1].number)
        assertEquals(1, up.foreign)
    }

    @Test fun messyNamesAndCells() {
        val up = parse(xlsx("Sheet1" to listOf(
            listOf("full_name", "phone", "email", "city", "street_address", "lead_status"),
            listOf("Ali Ali", "+923001110001", "a@example.com", "Lahore", "Model Town", "complete"),
            listOf("Posting for self Age 30 Job Clerk Education Graduate Lahore City", "+923001110002", "n@example.com", "Laher", "x", "complete"),
            listOf("Bilal Ahmed", "03001110003", "b@example.com", "Lahore", 1234567890123L, "complete"),
            listOf("Hamza", "+923001110004", "h@example.com", 100000, "حمزہ", "complete"),
            listOf("Twice", "0300-1110001", "t@example.com", "Lahore", "", "complete"),
        )))
        assertEquals(listOf(Contact("Ali", "+923001110001"), Contact("", "+923001110002"), Contact("Bilal Ahmed", "03001110003"),
            Contact("Hamza", "+923001110004")), up.contacts)
        assertEquals(1, up.namesDropped)
        assertEquals(1, up.repeats)
    }

    @Test fun titleRowsUrduHeadersAndExcelNumbers() {
        val table = LeadFile.read(xlsx("Leads" to listOf(
            listOf("Alpine Homes — October leads"),
            listOf(""),
            listOf("Sr", "نام", "شہر", "موبائل نمبر"),
            listOf(1, "احمد علی", "لاہور", 3001234567L),
            listOf(2, "Bilal", "لاہور", 3.211234567E9),
            listOf(3, "Usman", "لاہور", "0333 1234567 / 0300 7654321"),
        )))
        val columns = LeadFile.detect(table)!!
        assertEquals(LeadColumns(2, 3, listOf(1)), columns)
        assertEquals(listOf(Contact("احمد علی", "03001234567"), Contact("Bilal", "03211234567"), Contact("Usman", "03331234567")),
            LeadFile.upload(table, columns, true).contacts)
    }

    @Test fun noHeadersAtAllFindsNameByContent() {
        val table = LeadFile.read("Lahore,Ali Raza,0300 1234567\nLahore,Sara Khan,0301 7654321\nKarachi,Omar,0302 1112223\n".toByteArray())
        assertEquals(LeadColumns(-1, 2, listOf(1)), LeadFile.detect(table))
    }

    @Test fun firstAndLastNameColumns() {
        val up = parse("First Name,Last Name,Mobile\nAli,Raza,03001234567\n".toByteArray())
        assertEquals(listOf(Contact("Ali Raza", "03001234567")), up.contacts)
    }

    @Test fun metaCsvIsUtf16WithTabsAndPPrefix() {
        val text = "full_name\tphone_number\tcity\nAli Raza\tp:+923001234567\tLahore\n"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        assertEquals(listOf(Contact("Ali Raza", "+923001234567")), parse(bytes).contacts)
    }

    @Test fun picksTheSheetWithNumbers() {
        val table = LeadFile.read(xlsx("Notes" to listOf(listOf("call after 5")), "Leads" to listOf(listOf("name", "phone"), listOf("Ali", "03001234567"))))
        assertEquals("Leads", table.sheet)
    }

    @Test fun nothingPhoneLike() {
        assertNull(LeadFile.detect(LeadFile.read("name,city\nAli,Lahore\n".toByteArray())))
    }

    @Test fun cleanName() {
        assertEquals("Rana Saleem", LeadFile.cleanName("Rana  Saleem Rana Saleem"))
        assertEquals("Shahzad", LeadFile.cleanName("Shahzad Shahzad"))
        assertEquals("", LeadFile.cleanName("  "))
        assertNull(LeadFile.cleanName("ali@example.com"))
        assertNull(LeadFile.cleanName("House 54"))
    }

    @Test fun plainNumber() {
        assertEquals("3001234567", Xlsx.plainNumber("3.001234567E9"))
        assertEquals("923001234567", Xlsx.plainNumber("923001234567.0"))
        assertEquals("27", Xlsx.columnOf("AB12").toString())
    }

    /** The real sheets, when given: LEAD_SAMPLES=dir ./gradlew test. Not in the repo (people's numbers). */
    @Test fun realSamples() {
        val dir = System.getenv("LEAD_SAMPLES")?.let(::File)
        assumeTrue(dir?.isDirectory == true)
        dir!!.listFiles { f -> f.extension in setOf("xlsx", "csv") }!!.sorted().forEach { f ->
            val table = LeadFile.read(f.readBytes())
            val columns = LeadFile.detect(table)!!
            val up = LeadFile.upload(table, columns, true)
            println("${f.name}: number=${LeadFile.columnLabel(table, columns.header, columns.number)} " +
                "name=${columns.name.map { LeadFile.columnLabel(table, columns.header, it) }} leads=${up.contacts.size} skipped=${up.skipped} " +
                "repeats=${up.repeats} namesDropped=${up.namesDropped} plusOne=${up.plusOne} foreign=${up.foreign}")
            up.contacts.forEach { println("  ${it.name.ifEmpty { "(no name)" }} | ${it.number}") }
        }
    }
}
