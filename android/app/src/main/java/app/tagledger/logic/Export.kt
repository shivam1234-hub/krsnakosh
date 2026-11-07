package app.tagledger.logic

import app.tagledger.data.Expense
import java.io.OutputStream
import java.io.Writer
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Pseudo-tag for entries without tags, so "untagged" can be included or excluded like any tag. */
const val UNTAGGED = "untagged"

enum class ExportColumn(val label: String, val width: Int) {
    DATE("Date", 12), TIME("Time", 8), TITLE("Title", 28), TYPE("Type", 9), AMOUNT("Amount", 13), SIGNED("Signed amount", 15),
    TAGS("Tags", 24), NOTE("Note", 30), PAYEE("Payee", 22), REF("UPI ref", 16), PROOF("Proof photo", 40), SOURCE("Source", 16),
    CURRENCY("Currency", 9);

    companion object {
        val DEFAULT = listOf(DATE, TIME, TITLE, TYPE, AMOUNT, SIGNED, TAGS, NOTE, PAYEE, REF, PROOF, CURRENCY)
    }
}

enum class ExportSort(val label: String) { NEWEST("Newest first"), OLDEST("Oldest first"), BIGGEST("Biggest amount") }

/** Everything the export sheet lets you choose. Null bounds mean "no limit". */
data class ExportSpec(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    /** "all", "expense" or "income". */
    val type: String = "all",
    val include: Set<String> = emptySet(),
    val exclude: Set<String> = emptySet(),
    val matchAll: Boolean = false,
    val minAmount: Double? = null,
    val maxAmount: Double? = null,
    /** Words that must all appear in the title, note or payee. */
    val text: String = "",
    /** "any", "with" or "without" a proof photo. */
    val proof: String = "any",
    /** Empty means every source. */
    val sources: Set<String> = emptySet(),
    val columns: List<ExportColumn> = ExportColumn.DEFAULT,
    val sort: ExportSort = ExportSort.NEWEST,
    val xlsx: Boolean = true,
    /** Adds Summary, By tag and By month sheets to the workbook. */
    val summary: Boolean = true,
)

val SOURCES = listOf("manual" to "Typed in", "sms" to "Bank SMS", "share" to "Google Pay share", "import" to "Imported")
private fun sourceLabel(s: String) = SOURCES.firstOrNull { it.first == s }?.second ?: "Typed in"

fun ExportSpec.matches(e: Expense): Boolean {
    val d = e.time.toLocalDate()
    if (from != null && d < from) return false
    if (to != null && d > to) return false
    if (type != "all" && e.type != type) return false
    if (minAmount != null && e.amount < minAmount) return false
    if (maxAmount != null && e.amount > maxAmount) return false
    val tags = e.tags.ifEmpty { listOf(UNTAGGED) }
    if (include.isNotEmpty() && !(if (matchAll) include.all { it in tags } else include.any { it in tags })) return false
    if (exclude.any { it in tags }) return false
    val hasProof = e.photoPath.isNotEmpty() || e.photoUrl.isNotEmpty()
    if (proof == "with" && !hasProof || proof == "without" && hasProof) return false
    if (sources.isNotEmpty() && e.source.ifEmpty { "manual" } !in sources) return false
    val words = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isNotEmpty()) { val hay = "${e.title} ${e.note} ${e.payee}".lowercase(); if (!words.all { it in hay }) return false }
    return true
}

fun ExportSpec.select(all: List<Expense>): List<Expense> = all.filter { matches(it) }.let { l ->
    when (sort) {
        ExportSort.NEWEST -> l.sortedByDescending { it.time }
        ExportSort.OLDEST -> l.sortedBy { it.time }
        ExportSort.BIGGEST -> l.sortedWith(compareByDescending<Expense> { it.amount }.thenByDescending { it.time })
    }
}

private val dayFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private fun num(d: Double) = BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()

/** Human-readable list of the filters in use, for the Summary sheet. */
fun ExportSpec.describe(): List<Pair<String, String>> = buildList {
    add("Dates" to when {
        from == null && to == null -> "All dates"
        from == null -> "Up to ${dayFmt.format(to)}"
        to == null -> "From ${dayFmt.format(from)}"
        else -> "${dayFmt.format(from)} to ${dayFmt.format(to)}"
    })
    add("Type" to when (type) { "expense" -> "Spent only"; "income" -> "Income only"; else -> "Spent and income" })
    if (include.isNotEmpty()) add("Tags" to (if (matchAll) "All of " else "Any of ") + include.sorted().joinToString(" ") { "#$it" })
    if (exclude.isNotEmpty()) add("Excluding tags" to exclude.sorted().joinToString(" ") { "#$it" })
    if (minAmount != null || maxAmount != null) add("Amount" to when {
        minAmount != null && maxAmount != null -> "${num(minAmount)} to ${num(maxAmount)}"
        minAmount != null -> "At least ${num(minAmount)}"
        else -> "At most ${num(maxAmount!!)}"
    })
    if (text.isNotBlank()) add("Text contains" to text.trim())
    if (proof != "any") add("Proof photo" to if (proof == "with") "With proof only" else "Without proof only")
    if (sources.isNotEmpty()) add("Source" to SOURCES.filter { it.first in sources }.joinToString(", ") { it.second })
    add("Sorted" to sort.label)
}

fun ExportColumn.value(e: Expense, currency: String): Any = when (this) {
    ExportColumn.DATE -> e.time.toLocalDate()
    ExportColumn.TIME -> DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(e.time).atZone(ZoneId.systemDefault()))
    ExportColumn.TITLE -> e.title
    ExportColumn.TYPE -> if (e.isIncome) "Income" else "Expense"
    ExportColumn.AMOUNT -> e.amount
    ExportColumn.SIGNED -> e.signed
    ExportColumn.TAGS -> e.tags.joinToString(" ") { "#$it" }
    ExportColumn.NOTE -> e.note
    ExportColumn.PAYEE -> e.payee
    ExportColumn.REF -> e.ref
    ExportColumn.PROOF -> e.photoUrl
    ExportColumn.SOURCE -> sourceLabel(e.source)
    ExportColumn.CURRENCY -> currency
}

// ---------- CSV ----------

fun writeCsv(w: Writer, list: List<Expense>, spec: ExportSpec, currency: String) {
    fun cell(v: Any): String {
        val s = if (v is Double) num(v) else v.toString()
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
    w.write("﻿" + spec.columns.joinToString(",") { cell(it.label) } + "\n")
    for (e in list) w.write(spec.columns.joinToString(",") { cell(it.value(e, currency)) } + "\n")
}

// ---------- Excel workbook ----------

/** A bold cell in a summary sheet. */
data class Bold(val text: String)

class XlsxSheet(val name: String, val rows: List<List<Any?>>, val widths: List<Int>, val header: Boolean = true, val autoFilter: Boolean = false)

fun buildWorkbook(list: List<Expense>, spec: ExportSpec, currency: String, now: LocalDateTime = LocalDateTime.now()): List<XlsxSheet> {
    val cols = spec.columns
    val entries = XlsxSheet("Entries", listOf(cols.map { it.label }) + list.map { e -> cols.map { it.value(e, currency) } }, cols.map { it.width }, autoFilter = true)
    if (!spec.summary) return listOf(entries)

    val totalSpent = list.filter { !it.isIncome }.sumOf { it.amount }
    val totalIncome = list.filter { it.isIncome }.sumOf { it.amount }
    val summary = XlsxSheet("Summary", buildList {
        add(listOf(Bold("KrsnaKosh export")))
        add(listOf("Created", DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH).format(now)))
        add(listOf("Currency", currency))
        add(emptyList())
        add(listOf(Bold("Filters")))
        spec.describe().forEach { (k, v) -> add(listOf(k, v)) }
        add(emptyList())
        add(listOf(Bold("Totals")))
        add(listOf("Entries", list.size))
        add(listOf("Spent", totalSpent))
        add(listOf("Income", totalIncome))
        add(listOf("Net", totalIncome - totalSpent))
    }, listOf(16, 48), header = false)

    class Agg { var spent = 0.0; var income = 0.0; var n = 0
        fun add(e: Expense) { if (e.isIncome) income += e.amount else spent += e.amount; n++ } }
    val byTag = LinkedHashMap<String, Agg>()
    for (e in list) for (t in e.tags.ifEmpty { listOf(UNTAGGED) }) byTag.getOrPut(t) { Agg() }.add(e)
    val tagSheet = XlsxSheet("By tag",
        listOf(listOf("Tag", "Spent", "Income", "Entries")) +
            byTag.entries.sortedWith(compareByDescending<Map.Entry<String, Agg>> { it.value.spent }.thenBy { it.key })
                .map { (t, a) -> listOf("#$t", a.spent, a.income, a.n) },
        listOf(22, 14, 14, 9), autoFilter = true)

    val byMonth = java.util.TreeMap<String, Agg>()
    for (e in list) byMonth.getOrPut(DateTimeFormatter.ofPattern("yyyy-MM").format(e.time.toLocalDate())) { Agg() }.add(e)
    val monthSheet = XlsxSheet("By month",
        listOf(listOf("Month", "Spent", "Income", "Net", "Entries")) + byMonth.map { (m, a) -> listOf(m, a.spent, a.income, a.income - a.spent, a.n) },
        listOf(10, 14, 14, 14, 9))

    return listOf(entries, summary, tagSheet, monthSheet)
}

private const val S_HEADER = 1
private const val S_DATE = 2
private const val S_MONEY = 3
private const val S_BOLD = 4

private fun colName(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { sb.insert(0, 'A' + (n - 1) % 26); n = (n - 1) / 26 }; return sb.toString() }

private fun esc(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) when {
        c == '&' -> sb.append("&amp;"); c == '<' -> sb.append("&lt;"); c == '>' -> sb.append("&gt;"); c == '"' -> sb.append("&quot;")
        c < ' ' && c != '\t' && c != '\n' && c != '\r' -> {} // not allowed in XML
        else -> sb.append(c)
    }
    return sb.toString()
}

private fun cellXml(ref: String, v: Any?, header: Boolean): String = when {
    v == null -> ""
    header -> """<c r="$ref" t="inlineStr" s="$S_HEADER"><is><t xml:space="preserve">${esc(v.toString())}</t></is></c>"""
    v is Bold -> """<c r="$ref" t="inlineStr" s="$S_BOLD"><is><t xml:space="preserve">${esc(v.text)}</t></is></c>"""
    v is LocalDate -> """<c r="$ref" s="$S_DATE"><v>${v.toEpochDay() + 25569}</v></c>""" // Excel day 0 = 1899-12-30
    v is Double -> """<c r="$ref" s="$S_MONEY"><v>${num(v)}</v></c>"""
    v is Number -> """<c r="$ref"><v>$v</v></c>"""
    v.toString().isEmpty() -> ""
    else -> """<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${esc(v.toString())}</t></is></c>"""
}

private fun lastCol(s: XlsxSheet) = colName(maxOf(1, s.rows.maxOfOrNull { it.size } ?: 1, s.widths.size) - 1)
private fun lastRow(s: XlsxSheet) = maxOf(1, s.rows.size)

private fun sheetXml(s: XlsxSheet): String = buildString {
    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
    append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
    if (s.header) append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
    if (s.widths.isNotEmpty()) {
        append("<cols>")
        s.widths.forEachIndexed { i, w -> append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
        append("</cols>")
    }
    append("<sheetData>")
    s.rows.forEachIndexed { r, row ->
        append("""<row r="${r + 1}">""")
        row.forEachIndexed { c, v -> append(cellXml(colName(c) + (r + 1), v, s.header && r == 0)) }
        append("</row>")
    }
    append("</sheetData>")
    if (s.autoFilter) append("""<autoFilter ref="A1:${lastCol(s)}${lastRow(s)}"/>""")
    append("</worksheet>")
}

private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<numFmts count="2"><numFmt numFmtId="164" formatCode="yyyy-mm-dd"/><numFmt numFmtId="165" formatCode="#,##0.00"/></numFmts>
<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFEDEFF2"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="5">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""

/** Writes a minimal .xlsx (inline strings, no shared-strings table), so no spreadsheet library is needed. */
fun writeXlsx(out: OutputStream, sheets: List<XlsxSheet>) {
    val n = sheets.size
    ZipOutputStream(out).use { z ->
        fun put(path: String, body: String) { z.putNextEntry(ZipEntry(path)); z.write(body.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""" +
            """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
            """<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
            (1..n).joinToString("") { """<Override PartName="/xl/worksheets/sheet$it.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" } +
            "</Types>")
        put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        val filters = sheets.mapIndexedNotNull { i, s ->
            if (!s.autoFilter) null
            else """<definedName name="_xlnm._FilterDatabase" localSheetId="$i" hidden="1">'${esc(s.name)}'!${'$'}A${'$'}1:${'$'}${lastCol(s)}${'$'}${lastRow(s)}</definedName>"""
        }
        put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
            sheets.mapIndexed { i, s -> """<sheet name="${esc(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("") + "</sheets>" +
            (if (filters.isEmpty()) "" else "<definedNames>" + filters.joinToString("") + "</definedNames>") + "</workbook>")
        put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            (1..n).joinToString("") { """<Relationship Id="rId$it" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$it.xml"/>""" } +
            """<Relationship Id="rId${n + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
        put("xl/styles.xml", STYLES)
        sheets.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(s)) }
    }
}
