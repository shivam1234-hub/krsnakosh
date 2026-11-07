@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.tagledger.ui

import android.app.DatePickerDialog
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tagledger.logic.ExportColumn
import app.tagledger.logic.ExportSort
import app.tagledger.logic.ExportSpec
import app.tagledger.logic.SOURCES
import app.tagledger.logic.UNTAGGED
import app.tagledger.logic.select
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Preset(val label: String) { MONTH("This month"), LAST("Last month"), QUARTER("Last 3 months"), YEAR("This year"), ALL("All time") }

private fun Preset.range(t: LocalDate = LocalDate.now()): Pair<LocalDate?, LocalDate?> = when (this) {
    Preset.MONTH -> t.withDayOfMonth(1) to t.withDayOfMonth(t.lengthOfMonth())
    Preset.LAST -> t.minusMonths(1).let { it.withDayOfMonth(1) to it.withDayOfMonth(it.lengthOfMonth()) }
    Preset.QUARTER -> t.minusMonths(3).plusDays(1) to t
    Preset.YEAR -> t.withDayOfYear(1) to t.withDayOfYear(t.lengthOfYear())
    Preset.ALL -> null to null
}

private val dayFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/** Choose dates, filters and columns, then build an Excel or CSV file from them. */
@Composable
fun ExportSheet(vm: MainViewModel, shareFile: (Uri, String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val v by vm.view.collectAsState()
    var spec by remember { mutableStateOf(vm.exportDefaults()) }
    var minText by remember { mutableStateOf(spec.minAmount?.let(MainViewModel::fmtAmount) ?: "") }
    var maxText by remember { mutableStateOf(spec.maxAmount?.let(MainViewModel::fmtAmount) ?: "") }
    val rows = remember(spec, v.all) { spec.select(v.all) }
    val badRange = spec.minAmount != null && spec.maxAmount != null && spec.minAmount!! > spec.maxAmount!!
    // Android 9 and older have no shared Downloads API without a storage permission, so Download asks where to save instead.
    var pendingSave by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(if (spec.xlsx) MainViewModel.XLSX_MIME else "text/csv")) { dest ->
        val p = pendingSave
        if (dest != null && p != null) { vm.saveTo(p.first, dest, p.second); onDismiss() }
        pendingSave = null
    }

    fun pickDate(start: LocalDate?, set: (LocalDate) -> Unit) {
        val d = start ?: LocalDate.now()
        DatePickerDialog(ctx, { _, y, m, day -> set(LocalDate.of(y, m + 1, day)) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Export", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Starts from the filters on the home screen. Change anything below.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(true to "Excel (.xlsx)", false to "CSV").forEachIndexed { i, (x, l) ->
                    SegmentedButton(spec.xlsx == x, { spec = spec.copy(xlsx = x) }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
                }
            }

            Heading("Dates")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Preset.entries.forEach { p ->
                    val (a, b) = p.range()
                    FilterChip(spec.from == a && spec.to == b, { spec = spec.copy(from = a, to = b) }, { Text(p.label) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickDate(spec.from) { d -> spec = spec.copy(from = d, to = spec.to?.let { maxOf(it, d) }) } }) {
                    Text("From " + (spec.from?.let(dayFmt::format) ?: "the start"))
                }
                OutlinedButton(onClick = { pickDate(spec.to) { d -> spec = spec.copy(to = d, from = spec.from?.let { minOf(it, d) }) } }) {
                    Text("To " + (spec.to?.let(dayFmt::format) ?: "today"))
                }
            }

            Heading("Type")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("all" to "All", "expense" to "Spent", "income" to "Income").forEach { (k, l) ->
                    FilterChip(spec.type == k, { spec = spec.copy(type = k) }, { Text(l) })
                }
            }

            Heading("Tags")
            Text("Tap once to include a tag, twice to leave it out.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (v.allTags + UNTAGGED).distinct().forEach { t ->
                    val inc = t in spec.include; val exc = t in spec.exclude
                    FilterChip(
                        selected = inc || exc,
                        onClick = {
                            spec = when {
                                inc -> spec.copy(include = spec.include - t, exclude = spec.exclude + t)
                                exc -> spec.copy(exclude = spec.exclude - t)
                                else -> spec.copy(include = spec.include + t)
                            }
                        },
                        label = { Text((if (exc) "not " else "") + "#$t", fontFamily = Ledger.mono, fontSize = 13.sp) },
                        colors = if (exc) FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer) else FilterChipDefaults.filterChipColors(),
                    )
                }
            }
            if (spec.include.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(!spec.matchAll, { spec = spec.copy(matchAll = false) }, { Text("Has any of these") })
                FilterChip(spec.matchAll, { spec = spec.copy(matchAll = true) }, { Text("Has all of these") })
            }

            Heading("Amount")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(minText, { s -> minText = s; spec = spec.copy(minAmount = s.replace(",", "").toDoubleOrNull()) }, Modifier.weight(1f),
                    label = { Text("At least") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(maxText, { s -> maxText = s; spec = spec.copy(maxAmount = s.replace(",", "").toDoubleOrNull()) }, Modifier.weight(1f),
                    label = { Text("At most") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = badRange)
            }
            if (badRange) Text("\"At least\" is more than \"At most\", so nothing matches.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

            OutlinedTextField(spec.text, { spec = spec.copy(text = it) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Title, note or payee contains") })

            Heading("More filters")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("any" to "Any proof", "with" to "With proof", "without" to "No proof").forEach { (k, l) ->
                    FilterChip(spec.proof == k, { spec = spec.copy(proof = k) }, { Text(l) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SOURCES.forEach { (k, l) ->
                    FilterChip(k in spec.sources, { spec = spec.copy(sources = if (k in spec.sources) spec.sources - k else spec.sources + k) }, { Text(l) })
                }
            }
            Text("Leave every source off to include them all.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Heading("Sort")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExportSort.entries.forEach { s -> FilterChip(spec.sort == s, { spec = spec.copy(sort = s) }, { Text(s.label) }) }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Heading("Columns", Modifier.weight(1f))
                TextButton(onClick = { spec = spec.copy(columns = ExportColumn.entries.toList()) }) { Text("All") }
                TextButton(onClick = { spec = spec.copy(columns = ExportColumn.DEFAULT) }) { Text("Default") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExportColumn.entries.forEach { c ->
                    val on = c in spec.columns
                    // Keep the fixed column order whatever order they are tapped in.
                    FilterChip(on, { spec = spec.copy(columns = ExportColumn.entries.filter { if (it == c) !on else it in spec.columns }) }, { Text(c.label) })
                }
            }

            if (spec.xlsx) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Add summary sheets", fontWeight = FontWeight.Medium)
                    Text("Totals and filters used, spending by tag, and by month.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(spec.summary, { spec = spec.copy(summary = it) })
            }

            HorizontalDivider()
            val spent = rows.filter { !it.isIncome }.sumOf { it.amount }
            val income = rows.filter { it.isIncome }.sumOf { it.amount }
            Text("${rows.size} ${if (rows.size == 1) "entry" else "entries"} · spent ${vm.money(spent)} · income ${vm.money(income)}",
                fontFamily = Ledger.mono, style = MaterialTheme.typography.bodyMedium)
            val ready = rows.isNotEmpty() && spec.columns.isNotEmpty()
            if (!ready) Text(if (spec.columns.isEmpty()) "Pick at least one column" else "Nothing matches these filters",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        vm.export(spec) { uri, mime ->
                            if (Build.VERSION.SDK_INT >= 29) { vm.saveToDownloads(uri, mime); onDismiss() }
                            else { pendingSave = uri to mime; saveAs.launch(uri.lastPathSegment ?: "krsnakosh-export") }
                        }
                    },
                    enabled = ready, modifier = Modifier.weight(1f),
                ) { Text("⬇  Download") }
                Button(
                    onClick = { vm.export(spec) { uri, mime -> onDismiss(); shareFile(uri, mime) } },
                    enabled = ready, modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
            }
            Text(if (Build.VERSION.SDK_INT >= 29) "Download saves the file in your phone's Downloads folder. Share sends it to Drive, Gmail, WhatsApp and other apps."
                else "Download lets you choose where to save the file. Share sends it to Drive, Gmail, WhatsApp and other apps.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
