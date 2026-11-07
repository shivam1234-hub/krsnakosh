package app.tagledger.logic

import app.tagledger.data.Expense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class ExportTest {
    private fun at(y: Int, m: Int, d: Int) = LocalDateTime.of(y, m, d, 12, 30).toMillis()
    private val lunch = Expense(title = "Lunch", amount = 250.0, time = at(2026, 10, 2), tags = listOf("food", "office"), payee = "Cafe")
    private val rent = Expense(title = "Rent", amount = 20000.0, time = at(2026, 10, 1), tags = listOf("home"), photoUrl = "https://drive/x", source = "sms")
    private val salary = Expense(title = "Salary", amount = 90000.0, type = "income", time = at(2026, 9, 30))
    private val snack = Expense(title = "Chips & <dip>", amount = 40.5, time = at(2026, 8, 15), tags = listOf("food"), note = "late\u0001night")
    private val all = listOf(lunch, rent, salary, snack)

    private fun titles(spec: ExportSpec) = spec.select(all).map { it.title }

    @Test fun filters() {
        assertEquals(listOf("Lunch", "Rent", "Salary"), titles(ExportSpec(from = LocalDate.of(2026, 9, 1), to = LocalDate.of(2026, 10, 31))))
        assertEquals(listOf("Lunch", "Chips & <dip>"), titles(ExportSpec(include = setOf("food"))))
        assertEquals(listOf("Lunch"), titles(ExportSpec(include = setOf("food", "office"), matchAll = true)))
        assertEquals(listOf("Chips & <dip>"), titles(ExportSpec(include = setOf("food"), exclude = setOf("office"))))
        assertEquals(listOf("Salary"), titles(ExportSpec(include = setOf(UNTAGGED))))
        assertEquals(listOf("Rent", "Salary"), titles(ExportSpec(minAmount = 1000.0)))
        assertEquals(listOf("Lunch", "Chips & <dip>"), titles(ExportSpec(maxAmount = 300.0, type = "expense")))
        assertEquals(listOf("Rent"), titles(ExportSpec(proof = "with")))
        assertEquals(listOf("Rent"), titles(ExportSpec(sources = setOf("sms"))))
        assertEquals(listOf("Lunch"), titles(ExportSpec(text = "cafe")))
        assertEquals(listOf("Salary", "Rent", "Lunch", "Chips & <dip>"), titles(ExportSpec(sort = ExportSort.BIGGEST)))
        assertEquals(listOf("Chips & <dip>", "Salary", "Rent", "Lunch"), titles(ExportSpec(sort = ExportSort.OLDEST)))
    }

    @Test fun csvUsesChosenColumns() {
        val w = StringWriter()
        writeCsv(w, listOf(lunch), ExportSpec(columns = listOf(ExportColumn.DATE, ExportColumn.TITLE, ExportColumn.AMOUNT, ExportColumn.TAGS)), "INR")
        assertEquals("﻿Date,Title,Amount,Tags\n2026-10-02,Lunch,250,#food #office\n", w.toString())
    }

    @Test fun xlsxIsWellFormed() {
        val spec = ExportSpec(include = setOf("food"), minAmount = 10.0)
        val out = ByteArrayOutputStream()
        writeXlsx(out, buildWorkbook(spec.select(all), spec, "INR"))
        val parts = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) { val e = z.nextEntry ?: break; parts[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml",
            "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml", "xl/worksheets/sheet3.xml", "xl/worksheets/sheet4.xml"), parts.keys)
        val dbf = DocumentBuilderFactory.newInstance()
        parts.values.forEach { dbf.newDocumentBuilder().parse(ByteArrayInputStream(it.toByteArray())) } // throws if not valid XML
        val entries = parts["xl/worksheets/sheet1.xml"]!!
        assertTrue(entries.contains("Chips &amp; &lt;dip&gt;"))
        assertTrue(entries.contains("<v>46297</v>")) // 2026-10-02 as an Excel date
        assertTrue(parts["xl/worksheets/sheet2.xml"]!!.contains("At least 10"))
        assertTrue(parts["xl/workbook.xml"]!!.contains("'Entries'!\$A\$1:\$L\$3"))
    }
}
