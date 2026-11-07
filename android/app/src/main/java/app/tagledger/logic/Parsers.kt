package app.tagledger.logic

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** What a parser could read; the user confirms it before it becomes an entry. */
data class Draft(
    val type: String = "expense",
    val amount: Double? = null,
    val title: String = "",
    val tags: List<String> = emptyList(),
    val time: Long = System.currentTimeMillis(),
    val payee: String = "",
    val ref: String = "",
    val raw: String = "",
)

private val zone: ZoneId get() = ZoneId.systemDefault()
fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
fun LocalDateTime.toMillis(): Long = atZone(zone).toInstant().toEpochMilli()

fun normTag(s: String): String =
    s.lowercase().trimStart('#').replace(Regex("[^\\p{L}\\p{N}_-]+"), "-").trim('-').take(40)

fun splitTags(s: String): List<String> = s.split(Regex("[\\s,]+")).map(::normTag).filter { it.isNotEmpty() }.distinct()

// ---------- quick add: "250 lunch #food @yesterday", "+50000 salary" ----------

private val AMOUNT_TOKEN = Regex("^([+-]?)(?:₹|\\$|€|£|rs\\.?|inr)?(\\d[\\d,]*(?:\\.\\d+)?|\\.\\d+)(k)?$", RegexOption.IGNORE_CASE)

fun parseDateToken(tok: String, today: LocalDate = LocalDate.now()): LocalDate? {
    val t = tok.lowercase()
    when (t) {
        "today", "t" -> return today
        "yesterday", "y", "yday" -> return today.minusDays(1)
    }
    Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(t)?.let { m ->
        return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
    }
    Regex("^(\\d{1,2})[/.-](\\d{1,2})(?:[/.-](\\d{2,4}))?$").find(t)?.let { m ->
        var y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: today.year
        if (y < 100) y += 2000
        var d = runCatching { LocalDate.of(y, m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull() ?: return null
        if (m.groupValues[3].isEmpty() && d.isAfter(today)) d = d.minusYears(1)
        return d
    }
    Regex("^(\\d+)d$").find(t)?.let { return today.minusDays(it.groupValues[1].toLong()) }
    if (t.length >= 3) {
        val dow = DayOfWeek.entries.firstOrNull { it.name.lowercase().startsWith(t.take(3)) }
        if (dow != null) return today.with(TemporalAdjusters.previous(dow))
    }
    return null
}

fun parseQuick(input: String, now: LocalDateTime = LocalDateTime.now()): Draft {
    var type = "expense"
    var amount: Double? = null
    var date = now.toLocalDate()
    val tags = mutableListOf<String>()
    val words = mutableListOf<String>()
    for (tok in input.trim().split(Regex("\\s+"))) {
        if (tok.isEmpty()) continue
        if (tok.startsWith("#") && tok.length > 1) { normTag(tok).takeIf { it.isNotEmpty() }?.let(tags::add); continue }
        if (tok.startsWith("@") && tok.length > 1) {
            val d = parseDateToken(tok.drop(1), now.toLocalDate())
            if (d != null) { date = d; continue }
        }
        if (amount == null) {
            val m = AMOUNT_TOKEN.find(tok)
            if (m != null) {
                amount = m.groupValues[2].replace(",", "").toDouble() * (if (m.groupValues[3].isNotEmpty()) 1000 else 1)
                if (m.groupValues[1] == "+") type = "income"
                continue
            }
        }
        words += tok
    }
    val uniq = tags.distinct()
    if ("income" in uniq || "salary" in uniq) type = "income"
    val title = words.joinToString(" ").ifEmpty { uniq.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: "" }
    return Draft(type, amount, title, uniq, LocalDateTime.of(date, now.toLocalTime()).toMillis())
}

// ---------- bank SMS and UPI notifications ----------

private val DEBIT_RE = Regex("\\b(debited|paid|sent|spent|withdrawn|purchase|transferred|txn of|payment of)\\b", RegexOption.IGNORE_CASE)
private val CREDIT_RE = Regex("\\b(credited|received|deposited|refund(?:ed)?)\\b", RegexOption.IGNORE_CASE)
private val SKIP_RE = Regex(
    "\\b(otp|will be debited|is due|due on|requested|request(?:ing)? money|collect request|mandate|autopay set|offer|pre-approved|apply now|failed|declined|reversed)\\b",
    RegexOption.IGNORE_CASE,
)
private val RUPEE_AMOUNT = Regex("(?:rs\\.?|inr|₹)\\s*([\\d,]+(?:\\.\\d{1,2})?)", RegexOption.IGNORE_CASE)
private val WORD_AMOUNT = Regex("\\b(?:debited|credited|paid|sent|received)\\s+(?:by|with|for|of)?\\s*([\\d,]+(?:\\.\\d{1,2})?)\\b", RegexOption.IGNORE_CASE)
private val REF_RE = Regex(
    "(?:ref(?:erence)?(?:\\s*no)?|refno|upi(?:\\s*ref)?(?:\\s*no)?|upi transaction id|transaction id|rrn|utr|txn\\s*id)[\\s.:#-]*(\\d{6,})",
    RegexOption.IGNORE_CASE,
)
private val UPI_PATH_REF = Regex("UPI/(?:P2[AM]/)?(\\d{6,})", RegexOption.IGNORE_CASE)
private const val STOP = "(?=\\s+(?:on|ref|refno|ref\\s*no|upi|via|avl|avbl|bal|not|if|dt|date|thru|using)\\b|\\.(?=\\s|$)|[;,(]|\\s*$)"

private fun badPayee(s: String) =
    s.isBlank() || Regex("\\b(a/c|acct|account|bank|card|xx\\d|\\*\\d|your|upi id)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s) || s.all { it.isDigit() }

private fun smsPayee(t: String, credit: Boolean): String {
    val tries = mutableListOf(
        Regex(";\\s*([A-Za-z][A-Za-z0-9 .&'-]{1,40}?)\\s+credited", RegexOption.IGNORE_CASE),
        Regex("UPI/(?:P2[AM]/)?\\d+/([^/]{2,40}?)(?:/|\\s+not\\b|\\s*$|\\.)", RegexOption.IGNORE_CASE),
        Regex("\\b(?:trf to|paid to|sent to|to vpa|to|at|towards|info:?)\\s+([A-Za-z0-9@._&' -]{2,40}?)$STOP", RegexOption.IGNORE_CASE),
    )
    if (credit) tries.add(0, Regex("\\b(?:from|by)\\s+(?:vpa\\s+)?([A-Za-z0-9@._&' -]{2,40}?)$STOP", RegexOption.IGNORE_CASE))
    for (re in tries) {
        val m = re.find(t) ?: continue
        val v = m.groupValues[1].trim()
            .replace(Regex("^(?:neft|imps|rtgs|upi|vpa)\\s+(?:from|to|by)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:from|to)\\s+", RegexOption.IGNORE_CASE), "")
        if (!badPayee(v)) return v
    }
    return ""
}

fun prettyPayee(p: String): String {
    if (p.isBlank()) return ""
    var s = p
    if ('@' in s) s = s.substringBefore('@').replace(Regex("[._-]+"), " ").replace(Regex("\\d{4,}"), "").trim().ifEmpty { p }
    return s.lowercase().split(' ').filter { it.isNotEmpty() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }.take(60)
}

/** Reads one bank SMS or UPI notification. Returns null for anything that is not a completed payment. */
fun parseBankSms(text: String, receivedAt: Long): Draft? {
    val t = text.replace(Regex("\\s+"), " ").trim()
    if (t.isEmpty() || SKIP_RE.containsMatchIn(t)) return null
    val d = DEBIT_RE.find(t)?.range?.first ?: -1
    val c = CREDIT_RE.find(t)?.range?.first ?: -1
    if (d < 0 && c < 0) return null
    val credit = c >= 0 && (d < 0 || c < d)
    val m = RUPEE_AMOUNT.find(t) ?: WORD_AMOUNT.find(t) ?: return null
    val amount = m.groupValues[1].replace(",", "").toDoubleOrNull()?.takeIf { it > 0 } ?: return null
    val ref = (REF_RE.find(t) ?: UPI_PATH_REF.find(t))?.groupValues?.get(1) ?: ""
    val payee = smsPayee(t, credit)
    return Draft(
        type = if (credit) "income" else "expense",
        amount = amount,
        title = prettyPayee(payee).ifEmpty { if (credit) "Money received" else "UPI payment" },
        time = receivedAt, payee = payee, ref = ref, raw = t,
    )
}

// ---------- Google Pay / UPI receipt screenshots (text from on-device OCR) ----------

private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
private val RECEIPT_DATE = Regex(
    "(\\d{1,2})\\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*,?\\s+(\\d{4})(?:[,\\s]+(?:at\\s+)?(\\d{1,2}):(\\d{2})\\s*([ap]\\.?m\\.?)?)?",
    RegexOption.IGNORE_CASE,
)

private fun cleanAmount(s: String): Double? {
    val m = Regex("([\\d,]+(?:\\.\\d{1,2})?)").find(s.replace(" ", "")) ?: return null
    return m.groupValues[1].replace(",", "").toDoubleOrNull()?.takeIf { it > 0 }
}

/**
 * Reads a payment receipt. [bigAmountLine] is the text of the largest line on the screenshot, which on
 * Google Pay / PhonePe / Paytm receipts is the amount.
 */
fun parseReceipt(text: String, bigAmountLine: String?, now: Long = System.currentTimeMillis()): Draft? {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val all = lines.joinToString("\n")

    // UPI apps print the amount as "₹250" in the largest type. OCR has no rupee glyph and often reads it as 7, 2, Z or *.
    val upiReceipt = Regex("upi transaction id|google transaction id|paid to|upi ref", RegexOption.IGNORE_CASE).containsMatchIn(all)
    val amount = bigAmountLine?.trim()?.takeIf { Regex("\\d").containsMatchIn(it) && it.replace(Regex("[^\\d]"), "").length <= 9 }
        ?.let { line ->
            val first = line.first()
            when {
                first == '₹' || first == 'Z' || first == '*' || first == 'F' -> cleanAmount(line.drop(1))
                upiReceipt && (first == '7' || first == '2') && Regex("^[72][\\d,]*(\\.\\d{1,2})?$").matches(line.replace(" ", "")) -> cleanAmount(line.drop(1))
                else -> cleanAmount(line)
            }
        }
        ?: RUPEE_AMOUNT.find(all)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
        ?: return null

    val credit = Regex("received from|money received|credited to you", RegexOption.IGNORE_CASE).containsMatchIn(all) &&
        !Regex("paid to|sent to", RegexOption.IGNORE_CASE).containsMatchIn(all)

    // "To: CHAI POINT" (with a colon) is printed in plain caps and reads more reliably than the large name at the top.
    var payee = Regex(if (credit) "^from:\\s*(.+)$" else "^to:\\s*(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        .find(all)?.groupValues?.get(1)?.trim()?.takeIf { !badPayee(it) && '(' !in it } ?: ""
    val label = if (credit) Regex("^(?:received from|from)\\s*:?\\s*(.*)$", RegexOption.IGNORE_CASE)
    else Regex("^(?:paid to|sent to|to)\\s*:?\\s*(.*)$", RegexOption.IGNORE_CASE)
    if (payee.isEmpty()) for ((i, line) in lines.withIndex()) {
        val m = label.find(line) ?: continue
        payee = m.groupValues[1].trim().ifEmpty { lines.getOrNull(i + 1) ?: "" }
        if (payee.isNotEmpty() && !badPayee(payee)) break else payee = ""
    }

    var ref = REF_RE.find(all)?.groupValues?.get(1) ?: ""
    if (ref.isEmpty()) {
        val i = lines.indexOfFirst { Regex("transaction id|upi ref|utr", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        ref = lines.getOrNull(i + 1)?.replace(" ", "")?.takeIf { i >= 0 && Regex("^\\d{8,}$").matches(it) } ?: ""
    }

    var time = now
    RECEIPT_DATE.find(all)?.let { m ->
        val (dd, mon, yyyy, hh, mm, ap) = m.destructured
        val date = runCatching { LocalDate.of(yyyy.toInt(), MONTHS.indexOf(mon.lowercase().take(3)) + 1, dd.toInt()) }.getOrNull()
        if (date != null) {
            var hour = hh.toIntOrNull() ?: 12
            if (ap.isNotEmpty()) { val pm = ap.lowercase().startsWith("p"); hour = hour % 12 + if (pm) 12 else 0 }
            time = LocalDateTime.of(date, LocalTime.of(hour.coerceIn(0, 23), mm.toIntOrNull() ?: 0)).toMillis()
        }
    }

    payee = fixWithVpa(payee, Regex("\\b([a-z0-9._-]{3,})@[a-z]{2,}\\b", RegexOption.IGNORE_CASE).find(all)?.groupValues?.get(1))

    return Draft(
        type = if (credit) "income" else "expense",
        amount = amount,
        title = prettyPayee(payee).ifEmpty { if (credit) "Money received" else "UPI payment" },
        time = time, payee = payee, ref = ref, raw = all.take(1500),
    )
}

/**
 * OCR can't tell capital I from lowercase l (or 0 from O) in most fonts. When the payee's letters match the
 * UPI ID shown on the same receipt except for those look-alikes, take the spelling from the UPI ID.
 */
internal fun fixWithVpa(payee: String, vpa: String?): String {
    if (payee.isEmpty() || vpa.isNullOrEmpty()) return payee
    val key = vpa.lowercase().filter { it.isLetterOrDigit() }
    val letters = payee.withIndex().filter { it.value.isLetterOrDigit() }
    if (letters.size != key.length) return payee
    val lookAlike = setOf(setOf('i', 'l'), setOf('i', '1'), setOf('l', '1'), setOf('o', '0'))
    val out = payee.toCharArray()
    for ((k, iv) in letters.withIndex()) {
        val have = iv.value.lowercaseChar(); val want = key[k]
        if (have == want) continue
        if (setOf(have, want) !in lookAlike) return payee
        out[iv.index] = if (iv.value.isUpperCase()) want.uppercaseChar() else want
    }
    return String(out)
}
