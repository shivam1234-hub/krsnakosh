package app.tagledger.ui

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tagledger.TagLedgerApp
import app.tagledger.data.Expense
import app.tagledger.data.Money
import app.tagledger.data.Status
import app.tagledger.logic.Draft
import app.tagledger.logic.ExportColumn
import app.tagledger.logic.ExportSpec
import app.tagledger.logic.select
import app.tagledger.logic.normTag
import app.tagledger.logic.parseQuick
import app.tagledger.logic.toLocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Period(val label: String) { MONTH("This month"), LAST("Last month"), YEAR("This year"), ALL("All time") }

data class Filters(
    val query: String = "",
    val period: Period = Period.MONTH,
    val type: String = "all",
    val tags: Set<String> = emptySet(),
    val matchAll: Boolean = false,
)

data class Summary(val spent: Double, val income: Double, val count: Int, val byTag: List<Pair<String, Double>>, val label: String)

data class ViewState(
    val list: List<Expense> = emptyList(),
    /** Every confirmed entry, unfiltered; the export sheet filters these itself. */
    val all: List<Expense> = emptyList(),
    val pending: List<Expense> = emptyList(),
    val periodTags: List<Pair<String, Int>> = emptyList(),
    val allTags: List<String> = emptyList(),
    val summary: Summary = Summary(0.0, 0.0, 0, emptyList(), ""),
    val totalCount: Int = 0,
)

data class EditorState(
    val id: String? = null,
    val type: String = "expense",
    val amount: String = "",
    val title: String = "",
    val time: Long = System.currentTimeMillis(),
    val tags: List<String> = emptyList(),
    val note: String = "",
    val photoPath: String = "",
    val photoUrl: String = "",
    val payee: String = "",
    val ref: String = "",
    val raw: String = "",
    val source: String = "manual",
    val createdAt: Long = System.currentTimeMillis(),
    val syncedAt: Long = 0,
    val reading: Boolean = false,
    val error: String? = null,
    val fromShare: Boolean = false,
) {
    val isNew get() = id == null
}

/** A message for the snackbar, with an optional action such as Undo. */
data class Note(val text: String, val action: String? = null, val run: (() -> Unit)? = null)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TagLedgerApp
    private val repo = app.repo
    val prefs = repo.prefs

    val filters = MutableStateFlow(Filters())
    var editor by mutableStateOf<EditorState?>(null)
        private set
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set
    var currency by mutableStateOf(prefs.currency)
        private set
    var themeMode by mutableStateOf(prefs.theme)
        private set

    fun setTheme(mode: String) { prefs.theme = mode; themeMode = mode }
    val syncStatus = repo.syncStatus

    private val notes = Channel<Note>(Channel.BUFFERED)
    val noteFlow = notes.receiveAsFlow()
    /** Fires after a shared receipt is saved, so the screen can return to Google Pay. */
    private val finishes = Channel<Unit>(Channel.CONFLATED)
    val finishFlow = finishes.receiveAsFlow()

    val view = combine(repo.done, repo.pending, filters) { done, pending, f -> buildView(done, pending, f) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ViewState())

    init { sync() }

    fun money(n: Double) = Money.format(n, currency)
    private fun say(text: String, action: String? = null, run: (() -> Unit)? = null) { notes.trySend(Note(text, action, run)) }

    // ---------- filtering ----------

    private fun range(p: Period, today: LocalDate = LocalDate.now()): ClosedRange<LocalDate> = when (p) {
        Period.MONTH -> today.withDayOfMonth(1)..today.withDayOfMonth(today.lengthOfMonth())
        Period.LAST -> today.minusMonths(1).let { it.withDayOfMonth(1)..it.withDayOfMonth(it.lengthOfMonth()) }
        Period.YEAR -> today.withDayOfYear(1)..today.withDayOfYear(today.lengthOfYear())
        Period.ALL -> LocalDate.MIN..LocalDate.MAX
    }

    private fun periodLabel(p: Period): String {
        val t = LocalDate.now()
        val mf = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
        return when (p) {
            Period.MONTH -> mf.format(t)
            Period.LAST -> mf.format(t.minusMonths(1))
            Period.YEAR -> t.year.toString()
            Period.ALL -> "All time"
        }
    }

    private fun buildView(done: List<Expense>, pending: List<Expense>, f: Filters): ViewState {
        val r = range(f.period)
        val inPeriod = done.filter { it.time.toLocalDate() in r }
        val qTokens = f.query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val qTags = qTokens.filter { it.startsWith("#") && it.length > 1 }.map(::normTag)
        val words = qTokens.filterNot { it.startsWith("#") }.map { it.lowercase() }
        val want = f.tags + qTags
        val list = inPeriod.filter { e ->
            (f.type == "all" || e.type == f.type) &&
                (want.isEmpty() || if (f.matchAll || (qTags.isNotEmpty() && f.tags.isEmpty())) want.all { it in e.tags } else want.any { it in e.tags }) &&
                (words.isEmpty() || "${e.title} ${e.note} ${e.payee} ${e.tags.joinToString(" ")} ${e.amount}".lowercase().let { hay -> words.all { it in hay } })
        }
        val byTag = HashMap<String, Double>()
        for (e in list) if (!e.isIncome) for (t in e.tags.ifEmpty { listOf("untagged") }) byTag[t] = (byTag[t] ?: 0.0) + e.amount
        val periodTags = inPeriod.flatMap { it.tags }.groupingBy { it }.eachCount().toList().sortedWith(compareBy({ -it.second }, { it.first }))
        val allTags = done.flatMap { it.tags }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }.map { it.first }
        val filtered = f.tags.isNotEmpty() || f.query.isNotBlank() || f.type != "all"
        return ViewState(
            list = list, all = done, pending = pending, periodTags = periodTags, allTags = allTags, totalCount = done.size,
            summary = Summary(
                spent = list.filter { !it.isIncome }.sumOf { it.amount }, income = list.filter { it.isIncome }.sumOf { it.amount },
                count = list.size, byTag = byTag.toList().sortedByDescending { it.second }.take(8),
                label = periodLabel(f.period) + if (filtered) " · filtered" else "",
            ),
        )
    }

    fun setQuery(q: String) { filters.value = filters.value.copy(query = q) }
    fun setPeriod(p: Period) { filters.value = filters.value.copy(period = p) }
    fun setType(t: String) { filters.value = filters.value.copy(type = t) }
    fun toggleTag(t: String) { if (t.isEmpty() || t == "untagged") return; val s = filters.value.tags; filters.value = filters.value.copy(tags = if (t in s) s - t else s + t) }
    fun toggleMatchAll() { filters.value = filters.value.copy(matchAll = !filters.value.matchAll) }
    fun clearFilters() { filters.value = Filters(period = filters.value.period) }

    private fun visible(e: Expense): Boolean = view.value.let { v -> v.list.any { it.id == e.id } }

    // ---------- adding and editing ----------

    fun quickAdd(text: String): Boolean {
        if (text.isBlank()) { openNew(); return true }
        val d = parseQuick(text)
        if (d.amount == null || d.amount <= 0) { openDraft(d); return true }
        val e = Expense(title = d.title.ifBlank { "Untitled" }, amount = d.amount, type = d.type, time = d.time, tags = d.tags)
        viewModelScope.launch { repo.save(e); announce(e, "Added ${sign(e)}${money(e.amount)} ${e.title}"); sync() }
        return true
    }

    private fun sign(e: Expense) = if (e.isIncome) "+" else "−"

    private fun announce(e: Expense, msg: String) {
        viewModelScope.launch {
            kotlinx.coroutines.delay(150)
            if (visible(e)) say(msg, "Edit") { openEdit(e.id) }
            else say("$msg. Your filters hide it.", "Show it") {
                val d = e.time.toLocalDate()
                filters.value = Filters(period = if (d in range(filters.value.period)) filters.value.period else Period.ALL)
            }
        }
    }

    fun openNew() { editor = EditorState(tags = filters.value.tags.toList()) }

    private fun openDraft(d: Draft) {
        editor = EditorState(type = d.type, amount = d.amount?.let(::fmtAmount) ?: "", title = d.title, time = d.time, tags = d.tags)
    }

    fun openEdit(id: String) {
        viewModelScope.launch {
            val e = repo.get(id) ?: return@launch
            editor = EditorState(
                id = e.id, type = e.type, amount = fmtAmount(e.amount), title = e.title, time = e.time, tags = e.tags, note = e.note,
                photoPath = e.photoPath, photoUrl = e.photoUrl, payee = e.payee, ref = e.ref, raw = e.raw, source = e.source,
                createdAt = e.createdAt, syncedAt = e.syncedAt,
            )
        }
    }

    fun edit(change: (EditorState) -> EditorState) { editor = editor?.let(change)?.copy(error = null) }
    fun closeEditor(discardPhoto: Boolean = true) {
        val ed = editor ?: return
        // A photo attached to a new entry that was never saved is not needed any more.
        if (discardPhoto && ed.isNew && ed.photoPath.isNotEmpty()) repo.deletePhotoFile(ed.photoPath)
        editor = null
    }

    private fun readAmount(s: String): Double? {
        val raw = s.replace(Regex("[,\\s₹$€£]"), "")
        Regex("^\\d*\\.?\\d+(k)?$", RegexOption.IGNORE_CASE).find(raw)?.let { return raw.trimEnd('k', 'K').toDoubleOrNull()?.times(if (raw.endsWith("k", true)) 1000 else 1) }
        if (Regex("^\\d*\\.?\\d+([+-]\\d*\\.?\\d+)+$").matches(raw)) return Regex("[+-]?\\d*\\.?\\d+").findAll(raw).sumOf { it.value.toDouble() }
        return null
    }

    private fun editorToExpense(ed: EditorState, id: String? = ed.id): Expense? {
        val amount = readAmount(ed.amount)
        if (amount == null || amount <= 0) { editor = ed.copy(error = "Enter an amount above zero, like 250 or 120+80"); return null }
        return Expense(
            id = id ?: java.util.UUID.randomUUID().toString(), title = ed.title.trim().ifEmpty { ed.payee.ifEmpty { "Untitled" } },
            amount = Math.round(amount * 100) / 100.0, type = ed.type, time = ed.time, tags = ed.tags.map(::normTag).filter { it.isNotEmpty() }.distinct(),
            note = ed.note.trim(), payee = ed.payee, ref = ed.ref, photoPath = ed.photoPath, photoUrl = ed.photoUrl, raw = ed.raw,
            status = Status.DONE, source = ed.source, createdAt = ed.createdAt, syncedAt = ed.syncedAt,
        )
    }

    fun saveEditor() {
        val ed = editor ?: return
        val e = editorToExpense(ed) ?: return
        editor = null
        viewModelScope.launch {
            repo.save(e)
            if (ed.fromShare) { say("Saved ${money(e.amount)} ${e.title}"); finishes.trySend(Unit) }
            else if (ed.isNew) announce(e, "Added ${sign(e)}${money(e.amount)} ${e.title}") else say("Saved")
            sync()
        }
    }

    fun duplicateEditor() {
        val ed = editor ?: return
        val e = editorToExpense(ed.copy(photoPath = "", photoUrl = "", ref = "", syncedAt = 0, createdAt = System.currentTimeMillis()), id = null)?.copy(time = nowOnSameClock(ed.time)) ?: return
        editor = null
        viewModelScope.launch { repo.save(e); announce(e, "Duplicated for today"); sync() }
    }

    fun deleteEditor() {
        val id = editor?.id ?: return
        editor = null
        viewModelScope.launch { repo.get(id)?.let { deleteWithUndo(listOf(it)) } }
    }

    private suspend fun deleteWithUndo(items: List<Expense>) {
        repo.delete(items)
        selected = selected - items.map { it.id }.toSet()
        say(if (items.size == 1) "Deleted ${items[0].title}" else "Deleted ${items.size} entries", "Undo") {
            viewModelScope.launch { repo.restore(items); sync() }
        }
        sync()
    }

    fun repeatToday(e: Expense) {
        val copy = e.copy(id = java.util.UUID.randomUUID().toString(), time = System.currentTimeMillis(), ref = "", photoPath = "", photoUrl = "",
            raw = "", source = "manual", createdAt = System.currentTimeMillis(), syncedAt = 0)
        viewModelScope.launch { repo.save(copy); announce(copy, "Added ${e.title} again for today"); sync() }
    }

    private fun nowOnSameClock(@Suppress("UNUSED_PARAMETER") t: Long) = System.currentTimeMillis()

    // ---------- photos, shared receipts ----------

    fun attachPhoto(uri: Uri) {
        val ed = editor ?: return
        editor = ed.copy(reading = true)
        viewModelScope.launch {
            val file = runCatching { repo.importPhoto(uri) }.getOrNull()
            if (file == null) { edit { it.copy(reading = false) }; say("That image could not be opened"); return@launch }
            if (ed.photoPath.isNotEmpty() && ed.isNew) repo.deletePhotoFile(ed.photoPath)
            edit { it.copy(photoPath = file.path, photoUrl = "") }
            // Fill empty fields from the receipt; never overwrite what the user typed.
            val d = runCatching { repo.readReceipt(file, null) }.getOrNull()
            edit { cur -> if (d == null) cur.copy(reading = false) else fillFrom(cur, d, onlyEmpty = true).copy(reading = false) }
        }
    }

    fun removePhoto() = edit { it.copy(photoPath = "", photoUrl = "") }

    /** Called when the user shares a Google Pay receipt (image and/or text) to KrsnaKosh. */
    fun onShared(uri: Uri?, text: String?) {
        editor = EditorState(source = "share", fromShare = true, reading = true, note = "")
        viewModelScope.launch {
            val file = uri?.let { runCatching { repo.importPhoto(it) }.getOrNull() }
            val d = runCatching { repo.readReceipt(file, text) }.getOrNull()
            var st = (editor ?: EditorState(source = "share", fromShare = true)).copy(photoPath = file?.path ?: "", reading = false)
            if (d != null) {
                st = fillFrom(st, d, onlyEmpty = false)
                if (repo.isDuplicate(d)) say("You may have added this payment already. Check before saving.")
            } else if (file != null) {
                st = st.copy(error = "Couldn't read the amount from this image. Type it in.")
            } else if (uri != null) {
                st = st.copy(error = "Couldn't open the shared image. Fill in the details, or attach the screenshot below.")
            }
            editor = st
        }
    }

    private suspend fun learned(payee: String) = repo.learned(payee)

    private fun fillFrom(cur: EditorState, d: Draft, onlyEmpty: Boolean): EditorState {
        var s = cur
        if (!onlyEmpty || s.amount.isBlank()) d.amount?.let { s = s.copy(amount = fmtAmount(it), type = d.type) }
        if (!onlyEmpty || s.title.isBlank()) if (d.title.isNotBlank()) s = s.copy(title = d.title)
        if (!onlyEmpty || s.isNew) s = s.copy(time = d.time)
        s = s.copy(payee = s.payee.ifEmpty { d.payee }, ref = s.ref.ifEmpty { d.ref }, raw = s.raw.ifEmpty { d.raw })
        // Reuse title and tags from the last payment to the same payee.
        if (d.payee.isNotEmpty()) viewModelScope.launch {
            learned(d.payee)?.let { prev -> edit { c -> c.copy(tags = c.tags.ifEmpty { prev.tags }, title = if (c.title == d.title) prev.title else c.title) } }
        }
        return s
    }

    // ---------- pending (from SMS) ----------

    fun accept(e: Expense, title: String, tags: List<String>) {
        viewModelScope.launch {
            repo.save(e.copy(title = title.ifBlank { e.title }, tags = tags, status = Status.DONE))
            say("Added ${sign(e)}${money(e.amount)} ${title.ifBlank { e.title }}")
            sync()
        }
    }

    fun acceptAll(edits: Map<String, Pair<String, List<String>>>) {
        viewModelScope.launch {
            val list = view.value.pending
            for (e in list) { val (t, tags) = edits[e.id] ?: (e.title to e.tags); repo.save(e.copy(title = t.ifBlank { e.title }, tags = tags, status = Status.DONE)) }
            say("Added ${list.size} payments"); sync()
        }
    }

    fun ignore(e: Expense) {
        // Kept as an ignored marker so the same SMS isn't queued again by a later scan.
        viewModelScope.launch { repo.save(e.copy(status = "ignored")) }
    }

    // ---------- selection ----------

    fun toggleSelect(id: String) { selected = if (id in selected) selected - id else selected + id }
    fun clearSelection() { selected = emptySet() }

    fun bulkTag(tag: String, add: Boolean) {
        val t = normTag(tag)
        if (t.isEmpty()) { say("Type a tag first"); return }
        viewModelScope.launch {
            var n = 0
            for (id in selected) {
                val e = repo.get(id) ?: continue
                val tags = if (add) (e.tags + t).distinct() else e.tags - t
                if (tags != e.tags) { repo.save(e.copy(tags = tags)); n++ }
            }
            say("Updated $n ${if (n == 1) "entry" else "entries"}"); sync()
        }
    }

    fun bulkDelete() { viewModelScope.launch { deleteWithUndo(selected.mapNotNull { repo.get(it) }) } }

    // ---------- export, settings, sync ----------

    /** Export choices to start from: what the home screen shows now, plus the columns and format used last time. */
    fun exportDefaults(): ExportSpec {
        val f = filters.value
        val r = range(f.period)
        val tokens = f.query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val cols = prefs.exportColumns.split(',').mapNotNull { n -> ExportColumn.entries.firstOrNull { it.name == n } }
        return ExportSpec(
            from = r.start.takeIf { f.period != Period.ALL }, to = r.endInclusive.takeIf { f.period != Period.ALL }, type = f.type,
            include = f.tags + tokens.filter { it.startsWith("#") && it.length > 1 }.map(::normTag), matchAll = f.matchAll,
            text = tokens.filterNot { it.startsWith("#") }.joinToString(" "),
            columns = cols.ifEmpty { ExportColumn.DEFAULT }, xlsx = prefs.exportXlsx, summary = prefs.exportSummary,
        )
    }

    fun export(spec: ExportSpec, onReady: (Uri, String) -> Unit) {
        val list = spec.select(view.value.all)
        if (list.isEmpty()) { say("Nothing matches these filters"); return }
        if (spec.columns.isEmpty()) { say("Pick at least one column"); return }
        prefs.exportColumns = spec.columns.joinToString(",") { it.name }; prefs.exportXlsx = spec.xlsx; prefs.exportSummary = spec.summary
        val dates = when {
            spec.from == null && spec.to == null -> "all-time"
            else -> listOfNotNull(spec.from, spec.to).distinct().joinToString("-to-")
        }
        val name = "krsnakosh-$dates-" + LocalDate.now()
        viewModelScope.launch {
            runCatching { repo.export(list, spec, name) }
                .onSuccess { onReady(it, if (spec.xlsx) XLSX_MIME else "text/csv") }
                .onFailure { say("Couldn't create the file: ${it.message}") }
        }
    }

    /** Saves an exported file into the phone's Downloads folder (Android 10 and later need no permission for this). */
    fun saveToDownloads(src: Uri, mime: String) {
        val r = getApplication<Application>().contentResolver
        val name = src.lastPathSegment ?: "krsnakosh-export"
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val pending = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime); put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val dest = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending) ?: error("Downloads folder isn't available")
                    copy(src, dest)
                    r.update(dest, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                    dest
                }
            }.onSuccess { dest -> say("Saved $name in Downloads", "Open") { openFile(dest, mime) } }
                .onFailure { say("Couldn't save the file: ${it.message}") }
        }
    }

    /** Copies an exported file to a place picked with the system "Save as" screen (older Android versions). */
    fun saveTo(src: Uri, dest: Uri, mime: String) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { copy(src, dest) } }
                .onSuccess { say("Saved ${src.lastPathSegment}", "Open") { openFile(dest, mime) } }
                .onFailure { say("Couldn't save the file: ${it.message}") }
        }
    }

    private fun copy(src: Uri, dest: Uri) {
        val r = getApplication<Application>().contentResolver
        (r.openOutputStream(dest) ?: error("Can't write there")).use { out ->
            (r.openInputStream(src) ?: error("Export file is missing")).use { it.copyTo(out) }
        }
    }

    private fun openFile(uri: Uri, mime: String) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { getApplication<Application>().startActivity(i) }.onFailure { say("No app on this phone can open this file") }
    }

    fun photoUri(path: String) = repo.photoUri(path)

    fun saveSettings(url: String, secret: String, cur: String) {
        prefs.scriptUrl = url.trim(); prefs.secret = secret.trim(); prefs.currency = cur; currency = cur
        if (prefs.remoteConfigured) testConnection()
    }

    fun testConnection() {
        viewModelScope.launch {
            runCatching { repo.testConnection() }
                .onSuccess { say("Connected to $it"); sync() }
                .onFailure { say("Couldn't connect: ${it.message}") }
        }
    }

    val smsEnabled get() = prefs.smsEnabled
    fun setSms(on: Boolean) { repo.setSmsReceiverEnabled(on); if (on) scanInbox(7) }

    fun scanInbox(days: Int) {
        viewModelScope.launch { val n = repo.scanInbox(days); say(if (n == 0) "No new payments in the last $days days" else "Found $n payments to review") }
    }

    fun sync() { app.appScope.launch { repo.sync() } }

    companion object {
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        fun fmtAmount(a: Double): String = if (a % 1.0 == 0.0) a.toLong().toString() else "%.2f".format(Locale.US, a)
        fun dayLabel(d: LocalDate): String {
            val t = LocalDate.now()
            return when (d) {
                t -> "Today"
                t.minusDays(1) -> "Yesterday"
                else -> DateTimeFormatter.ofPattern(if (d.year == t.year) "EEE, d MMM" else "EEE, d MMM yyyy", Locale.ENGLISH).format(d)
            }
        }
        fun timeLabel(t: Long): String = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH).format(
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(t), java.time.ZoneId.systemDefault()),
        )
    }
}
