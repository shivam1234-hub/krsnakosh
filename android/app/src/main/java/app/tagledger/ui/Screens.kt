@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package app.tagledger.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tagledger.data.Expense
import app.tagledger.logic.normTag
import app.tagledger.logic.parseQuick
import app.tagledger.logic.splitTags
import app.tagledger.logic.toLocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@Composable
fun AppRoot(
    vm: MainViewModel,
    onShareSaved: () -> Unit,
    onShareCancelled: () -> Unit,
    shareFile: (Uri, String) -> Unit,
    open: (String, String?) -> Unit,
) {
    val snack = remember { SnackbarHostState() }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.noteFlow.collect { n ->
            launch {
                val r = snack.showSnackbar(n.text, n.action, withDismissAction = n.action == null,
                    duration = if (n.action != null) SnackbarDuration.Long else SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) n.run?.invoke()
            }
        }
    }
    LaunchedEffect(Unit) { vm.finishFlow.collect { onShareSaved() } }

    if (showSettings) SettingsScreen(vm, snack, open, onBack = { showSettings = false })
    else HomeScreen(vm, snack, onSettings = { showSettings = true }, shareFile = shareFile)

    vm.editor?.let { ed ->
        EditorSheet(vm, ed, open, onDismiss = {
            val share = ed.fromShare
            vm.closeEditor()
            if (share) onShareCancelled()
        })
    }
}

// ---------------- home ----------------

@Composable
private fun HomeScreen(vm: MainViewModel, snack: SnackbarHostState, onSettings: () -> Unit, shareFile: (Uri, String) -> Unit) {
    val v by vm.view.collectAsState()
    val f by vm.filters.collectAsState()
    val sel = vm.selected
    var showExport by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = sel.isNotEmpty()) { vm.clearSelection() }
    if (showExport) ExportSheet(vm, shareFile, onDismiss = { showExport = false })

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("KrsnaKosh", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showExport = true }) { Icon(Icons.Default.Share, "Export") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            if (sel.isEmpty()) ExtendedFloatingActionButton(onClick = { vm.openNew() }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New entry") })
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "quick") { QuickAdd(vm, v.allTags) }
            if (sel.isNotEmpty()) item(key = "bulk") { BulkBar(vm, sel.size) }
            if (v.pending.isNotEmpty()) item(key = "pending") { PendingSection(vm, v.pending, v.allTags) }
            item(key = "summary") { SummaryCard(vm, v.summary, f.tags) }
            item(key = "filters") { FilterBar(vm, f, v.periodTags) }
            if (v.list.isEmpty()) item(key = "empty") { EmptyState(vm, v.totalCount > 0) }
            // One card per day, like a bank statement, so entries read as a list instead of a pile of boxes.
            for ((day, es) in v.list.groupBy { it.time.toLocalDate() }) {
                item(key = "d$day") { DayGroup(vm, day, es, sel) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

/** Title of a tinted home section: a small accent-coloured icon badge, then the name. */
@Composable
private fun SectionHeader(icon: ImageVector, text: String, tint: Ledger.Tint, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(tint.accent), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.surface)
        }
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = tint.accent)
    }
}

/** Text field that stays white (or dark) on top of a tinted section. */
@Composable
private fun fieldOnTint() = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface)

@Composable
private fun QuickAdd(vm: MainViewModel, allTags: List<String>) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = { if (vm.quickAdd(text)) text = "" }
    val tint = Ledger.addTint
    Surface(shape = RoundedCornerShape(16.dp), color = tint.bg) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader(Icons.Default.Add, "Quick add", tint)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(12.dp),
                    placeholder = { Text("250 lunch #food") }, colors = fieldOnTint(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                Button(onClick = submit, enabled = text.isNotBlank()) { Text("Add") }
            }
            val last = text.substringAfterLast(' ')
            if (last.startsWith("#")) {
                val pre = normTag(last)
                val opts = allTags.filter { it.startsWith(pre) && it != pre }.take(10)
                if (opts.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    opts.forEach { t -> TagSuggestion(t) { text = text.dropLast(last.length) + "#$t " } }
                }
            }
            if (text.isBlank()) {
                Text("Type the amount, what it was for, and #tags. Start with + for money received. Add @yesterday or @12/10 for another day.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val p = parseQuick(text)
                if (p.amount == null) Text("Add an amount, like 250 or 1.2k", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Will add", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.title.ifBlank { "Untitled" }, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Amount(vm, p.amount, p.type == "income")
                        }
                        Text(MainViewModel.dayLabel(p.time.toLocalDate()) + if (p.type == "income") " · Money in" else " · Spent",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (p.tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            p.tags.forEach { TagLabel(it) }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- tags ----------------
// A tag plays three roles, and each has its own look so they never get mixed up:
// TagLabel sits on an entry (quiet grey pill), TagFilter narrows the list (Material chip, ✓ when on),
// and TagSuggestion adds a tag (outlined, with +). The tag's own colour only shows as a small dot.

@Composable
private fun TagDot(tag: String, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Ledger.tagDot(tag)))
}

@Composable
fun TagLabel(tag: String, onRemove: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(start = 8.dp, end = if (onRemove != null) 2.dp else 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            TagDot(tag)
            Text(tag, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = if (onRemove != null) 0.dp else 4.dp))
            if (onRemove != null) Box(Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, "Remove $tag", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TagFilter(tag: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick,
        label = {
            Text(tag)
            if (count > 0) Text("  $count", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        },
        leadingIcon = { if (selected) Icon(Icons.Default.Check, null, Modifier.size(FilterChipDefaults.IconSize)) else TagDot(tag) },
    )
}

@Composable
private fun TagSuggestion(tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Surface(shape = shape, color = Color.Transparent, contentColor = MaterialTheme.colorScheme.primary,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)), modifier = Modifier.clip(shape).clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(Icons.Default.Add, null, Modifier.size(14.dp))
            Text(tag, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ---------------- entries ----------------

@Composable
private fun Amount(vm: MainViewModel, amount: Double, income: Boolean, big: Boolean = false) {
    Text((if (income) "+" else "−") + vm.money(amount), fontFamily = Ledger.mono, fontWeight = FontWeight.SemiBold,
        style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
        color = if (income) Ledger.earn else MaterialTheme.colorScheme.onSurface)
}

private val clock = java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH)

private fun sourceLabel(source: String) = when (source) {
    "sms" -> "Bank SMS"
    "share" -> "Google Pay"
    "import" -> "Imported"
    else -> null
}

@Composable
private fun DayGroup(vm: MainViewModel, day: java.time.LocalDate, es: List<Expense>, sel: Set<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(MainViewModel.dayLabel(day), Modifier.weight(1f))
            val spent = es.filter { !it.isIncome }.sumOf { it.amount }
            if (spent > 0) Text("Spent " + vm.money(spent), style = MaterialTheme.typography.labelMedium, fontFamily = Ledger.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column {
                es.forEachIndexed { i, e ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 66.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    EntryRow(vm, e, e.id in sel, sel.isNotEmpty())
                }
            }
        }
    }
}

@Composable
private fun EntryAvatar(e: Expense, selected: Boolean) {
    val (bg, fg) = when {
        selected -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        e.isIncome -> Ledger.earn.copy(alpha = 0.16f) to Ledger.earn
        e.tags.isNotEmpty() -> Ledger.tagColors(e.tags.first())
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(Modifier.size(40.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        if (selected) Icon(Icons.Default.Check, "Selected", tint = fg, modifier = Modifier.size(20.dp))
        else Text(e.title.trim().firstOrNull()?.uppercase() ?: "?", color = fg, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EntryRow(vm: MainViewModel, e: Expense, selected: Boolean, selecting: Boolean) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .combinedClickable(
                onClick = { if (selecting) vm.toggleSelect(e.id) else vm.openEdit(e.id) },
                onLongClick = { vm.toggleSelect(e.id) },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EntryAvatar(e, selected)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
                Amount(vm, e.amount, e.isIncome)
            }
            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(e.time), ZoneId.systemDefault())
            val meta = listOfNotNull(clock.format(at), sourceLabel(e.source),
                if (e.photoPath.isNotEmpty() || e.photoUrl.isNotEmpty()) "📎 Proof" else null).joinToString("  ·  ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (e.tags.isNotEmpty()) FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                e.tags.forEach { TagLabel(it) }
            }
        }
    }
}

@Composable
private fun BulkBar(vm: MainViewModel, count: Int) {
    var tag by rememberSaveable { mutableStateOf("") }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("$count selected", Modifier.weight(1f))
                TextButton(onClick = { vm.clearSelection() }) { Text("Cancel") }
            }
            OutlinedTextField(tag, { tag = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                label = { Text("Tag") }, placeholder = { Text("e.g. food") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.bulkTag(tag, true) }, enabled = tag.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Add tag") }
                OutlinedButton(onClick = { vm.bulkTag(tag, false) }, enabled = tag.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Remove tag") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            TextButton(onClick = { vm.bulkDelete() }) { Text("Delete $count selected", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun PendingSection(vm: MainViewModel, pending: List<Expense>, allTags: List<String>) {
    val edits = remember { mutableStateMapOf<String, Pair<String, String>>() }
    val tint = Ledger.reviewTint
    Surface(shape = RoundedCornerShape(16.dp), color = tint.bg) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionHeader(Icons.Default.Notifications, "${pending.size} payment${if (pending.size == 1) "" else "s"} to review", tint)
                    Text("Found in your bank SMS. Check the title and tags, then add.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (pending.size > 1) TextButton(onClick = {
                    vm.acceptAll(pending.associate { e -> e.id to (edits[e.id]?.let { it.first to splitTags(it.second) } ?: (e.title to e.tags)) })
                }) { Text("Add all") }
            }
            pending.forEach { e ->
                val cur = edits[e.id] ?: (e.title to e.tags.joinToString(" ") { "#$it" })
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Amount(vm, e.amount, e.isIncome, big = true)
                                Text((if (e.isIncome) "Money in" else "Spent") + " · " + MainViewModel.timeLabel(e.time),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        OutlinedTextField(cur.first, { edits[e.id] = it to cur.second }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Title") })
                        OutlinedTextField(cur.second, { edits[e.id] = cur.first to it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Tags") },
                            placeholder = { Text("#food #office") })
                        val typed = cur.second.substringAfterLast(' ').trimStart('#').lowercase()
                        val chosen = splitTags(cur.second)
                        val sugg = allTags.filter { it !in chosen && (typed.isEmpty() || it.startsWith(typed)) }.take(8)
                        if (sugg.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            sugg.forEach { t -> TagSuggestion(t) {
                                val base = if (typed.isNotEmpty() && !cur.second.endsWith(" ")) cur.second.dropLast(cur.second.substringAfterLast(' ').length) else cur.second
                                edits[e.id] = cur.first to (base.trimEnd() + " #$t ").trimStart()
                            } }
                        }
                        if (e.raw.isNotBlank()) Text("SMS: " + e.raw, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.ignore(e) }, modifier = Modifier.weight(1f)) { Text("Ignore") }
                            Button(onClick = { vm.accept(e, cur.first, splitTags(cur.second)) }, modifier = Modifier.weight(1f)) { Text("Add") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(vm: MainViewModel, s: Summary, active: Set<String>) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val tint = Ledger.summaryTint
    Surface(shape = RoundedCornerShape(16.dp), color = tint.bg) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(Icons.Default.ShoppingCart, "Overview", tint)
            Column {
                Text(if (s.label.isEmpty()) "Spent" else "Spent in ${s.label}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(vm.money(s.spent), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val net = s.income - s.spent
                StatTile("Money in", "+" + vm.money(s.income), Ledger.earn, Modifier.weight(1f))
                StatTile("Left over", (if (net < 0) "−" else "+") + vm.money(net), if (net < 0) Ledger.spend else Ledger.earn, Modifier.weight(1f))
                StatTile("Entries", s.count.toString(), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            }
            if (s.byTag.isNotEmpty()) {
                HorizontalDivider(color = tint.accent.copy(alpha = 0.25f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Where it went", Modifier.weight(1f))
                    Text("Tap a tag to filter", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val max = s.byTag.first().second.coerceAtLeast(1.0)
                val rows = if (showAll) s.byTag else s.byTag.take(5)
                rows.forEach { (t, v) ->
                    val on = t in active
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (on) MaterialTheme.colorScheme.surface else Color.Transparent)
                        .clickable { vm.toggleTag(t) }.padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (on) Icon(Icons.Default.Check, "Filtering by $t", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary) else TagDot(t, 10.dp)
                            Text(t, style = MaterialTheme.typography.bodyMedium, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                            if (s.spent > 0) Text("${Math.round(v / s.spent * 100)}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(vm.money(v), fontFamily = Ledger.mono, style = MaterialTheme.typography.bodyMedium)
                        }
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))) {
                            Box(Modifier.fillMaxWidth((v / max).toFloat().coerceIn(0.02f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Ledger.tagDot(t)))
                        }
                    }
                }
                if (s.byTag.size > 5) TextButton(onClick = { showAll = !showAll }) {
                    Text(if (showAll) "Show fewer" else "Show all ${s.byTag.size} tags")
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, color: Color, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), modifier = modifier) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontFamily = Ledger.mono, color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FilterGroup(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun FilterBar(vm: MainViewModel, f: Filters, tags: List<Pair<String, Int>>) {
    var showAllTags by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(f.query, vm::setQuery, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface),
            leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search entries") },
            trailingIcon = { if (f.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Default.Close, "Clear search") } })
        val tint = Ledger.filterTint
        Surface(shape = RoundedCornerShape(16.dp), color = tint.bg) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(Icons.Default.List, "Filters", tint)
                FilterGroup("Period") {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Period.entries.forEach { p -> FilterChip(f.period == p, { vm.setPeriod(p) }, { Text(p.label) }) }
                    }
                }
                FilterGroup("Show") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("all" to "Everything", "expense" to "Spent", "income" to "Money in").forEach { (k, l) -> FilterChip(f.type == k, { vm.setType(k) }, { Text(l) }) }
                    }
                }
                val shown = tags.map { it.first }.toSet()
                val list = tags + f.tags.filter { it !in shown }.map { it to 0 }
                if (list.isNotEmpty()) FilterGroup(if (f.tags.isEmpty()) "Tags" else "Tags · ${f.tags.size} on") {
                    val first = list.take(12)
                    val visible = if (showAllTags) list else first + list.drop(12).filter { it.first in f.tags }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        visible.forEach { (t, n) -> TagFilter(t, n, t in f.tags) { vm.toggleTag(t) } }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (list.size > 12) TextButton(onClick = { showAllTags = !showAllTags }) { Text(if (showAllTags) "Show fewer" else "Show all ${list.size}") }
                        Spacer(Modifier.weight(1f))
                        if (f.tags.isNotEmpty()) TextButton(onClick = { vm.clearFilters() }) { Text("Clear tags") }
                    }
                    if (f.tags.size > 1) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Entries with", style = MaterialTheme.typography.bodySmall)
                        FilterChip(!f.matchAll, { if (f.matchAll) vm.toggleMatchAll() }, { Text("any of them") })
                        FilterChip(f.matchAll, { if (!f.matchAll) vm.toggleMatchAll() }, { Text("all of them") })
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(vm: MainViewModel, hasAny: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (hasAny) "No entries match" else "Your ledger is empty", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            if (hasAny) "Try another period, or clear the search and tag filters."
            else "Type 120 chai #food above and tap Add. Or share a Google Pay receipt to KrsnaKosh.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hasAny) OutlinedButton(onClick = { vm.clearFilters(); vm.setPeriod(Period.ALL) }) { Text("Reset filters") }
    }
}

// ---------------- editor ----------------

@Composable
private fun EditorSheet(vm: MainViewModel, ed: EditorState, open: (String, String?) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::attachPhoto) }
    var tagInput by remember(ed.id) { mutableStateOf("") }
    val allTags = vm.view.collectAsState().value.allTags

    fun commitTags() {
        val add = splitTags(tagInput)
        if (add.isNotEmpty()) vm.edit { it.copy(tags = (it.tags + add).distinct()) }
        tagInput = ""
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (ed.fromShare) "New entry from receipt" else if (ed.isNew) "New entry" else "Edit entry", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (ed.reading) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Reading the receipt…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("expense" to "Spent", "income" to "Money in").forEachIndexed { i, (k, l) ->
                    SegmentedButton(ed.type == k, { vm.edit { it.copy(type = k) } }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
                }
            }
            OutlinedTextField(ed.amount, { s -> vm.edit { it.copy(amount = s) } }, label = { Text("Amount") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = Ledger.mono),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next))
            if (ed.fromShare && ed.amount.isNotBlank()) Text("Read from the receipt. Check the amount before saving.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(ed.title, { s -> vm.edit { it.copy(title = s) } }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("What was it for?") })

            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(ed.time), ZoneId.systemDefault())
            fun setTime(t: LocalDateTime) = vm.edit { it.copy(time = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    DatePickerDialog(ctx, { _, y, m, d -> setTime(at.withYear(y).withMonth(m + 1).withDayOfMonth(d)) }, at.year, at.monthValue - 1, at.dayOfMonth).show()
                }) { Text(MainViewModel.dayLabel(at.toLocalDate())) }
                OutlinedButton(onClick = {
                    TimePickerDialog(ctx, { _, h, mi -> setTime(at.withHour(h).withMinute(mi)) }, at.hour, at.minute, false).show()
                }) { Text(java.time.format.DateTimeFormatter.ofPattern("h:mm a").format(at)) }
                TextButton(onClick = { setTime(LocalDateTime.now()) }) { Text("Now") }
                TextButton(onClick = { setTime(at.with(java.time.LocalDate.now().minusDays(1))) }) { Text("Yesterday") }
            }

            Text("Tags", style = MaterialTheme.typography.labelLarge)
            if (ed.tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ed.tags.forEach { t -> TagLabel(t, onRemove = { vm.edit { it.copy(tags = it.tags - t) } }) }
            }
            OutlinedTextField(tagInput, { s -> if (s.endsWith(" ") || s.endsWith(",")) { tagInput = s; commitTags() } else tagInput = s },
                modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("type a tag and press space") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Ledger.mono),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { commitTags() }))
            val pre = normTag(tagInput)
            val sugg = allTags.filter { it !in ed.tags && (pre.isEmpty() || it.startsWith(pre)) }.take(12)
            if (sugg.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                sugg.forEach { t -> TagSuggestion(t) { vm.edit { it.copy(tags = it.tags + t) }; tagInput = "" } }
            }

            OutlinedTextField(ed.note, { s -> vm.edit { it.copy(note = s) } }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth(), minLines = 2)

            // Proof photo
            Text("Proof photo", style = MaterialTheme.typography.labelLarge)
            if (ed.photoPath.isNotEmpty() && File(ed.photoPath).exists()) {
                ProofImage(ed.photoPath, Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable {
                    open(vm.photoUri(ed.photoPath).toString(), "image/jpeg")
                })
                Text(
                    when {
                        ed.photoUrl.isNotEmpty() -> "Saved in your Google Drive."
                        vm.prefs.remoteConfigured -> "Uploads to your Google Drive when you save."
                        else -> "Kept on this phone. Connect Google in Settings to back it up to Drive."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ed.photoUrl.isNotEmpty()) TextButton(onClick = { open(ed.photoUrl, null) }) { Text("Open in Drive") }
                    TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Replace") }
                    TextButton(onClick = { vm.removePhoto() }) { Text("Remove") }
                }
            } else if (ed.photoUrl.isNotEmpty()) {
                TextButton(onClick = { open(ed.photoUrl, null) }) { Text("Open proof in Drive") }
            } else {
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Attach proof photo")
                }
                Text("Pick a payment screenshot. Empty fields are filled in from it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ed.ref.isNotEmpty() || ed.payee.isNotEmpty()) Text(
                listOfNotNull(ed.payee.takeIf { it.isNotEmpty() }?.let { "Payee: $it" }, ed.ref.takeIf { it.isNotEmpty() }?.let { "UPI ref: $it" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ed.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!ed.isNew) {
                    TextButton(onClick = { vm.deleteEditor() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = { commitTags(); vm.duplicateEditor() }) { Text("Add again today") }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { commitTags(); vm.saveEditor() }, enabled = !ed.reading) { Text("Save") }
            }
        }
    }
}

@Composable
private fun ProofImage(path: String, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            var s = 1
            while (o.outWidth / (s * 2) >= 900) s *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
        }
    }
    val b = bmp
    if (b != null) Image(b, "Proof photo", modifier, contentScale = ContentScale.Fit)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
}

// ---------------- settings ----------------

@Composable
private fun SettingsScreen(vm: MainViewModel, snack: SnackbarHostState, open: (String, String?) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(vm.prefs.scriptUrl) }
    var secret by rememberSaveable { mutableStateOf(vm.prefs.secret) }
    var cur by rememberSaveable { mutableStateOf(vm.currency) }
    var smsOn by remember { mutableStateOf(vm.smsEnabled) }
    val sync by vm.syncStatus.collectAsState()

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECEIVE_SMS] == true) { vm.setSms(true); smsOn = true }
        else scope.launch { snack.showSnackbar("SMS permission is needed to read bank messages") }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Section("Appearance") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("system" to "Match phone", "light" to "Light", "dark" to "Dark").forEach { (k, l) ->
                        FilterChip(vm.themeMode == k, { vm.setTheme(k) }, { Text(l) })
                    }
                }
            }

            Section("Bank SMS") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Read bank SMS on this phone", fontWeight = FontWeight.Medium)
                        Text("Each UPI debit or credit shows up as a payment to review. Messages are read on the phone and never uploaded.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(smsOn, { on ->
                        if (on) {
                            val p = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
                            if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
                            perms.launch(p.toTypedArray())
                        } else { vm.setSms(false); smsOn = false }
                    })
                }
                if (smsOn) OutlinedButton(onClick = { vm.scanInbox(30) }) { Text("Find payments from the last 30 days") }
            }

            Section("Google Sheet and Drive") {
                Text("Entries are copied to an \"Expenses\" tab in your Google Sheet, and proof photos to a Drive folder where anyone with the link can view them. Follow SETUP.md to create the Apps Script.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(url, { url = it }, label = { Text("Apps Script web app URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://script.google.com/macros/s/…/exec") })
                OutlinedTextField(secret, { secret = it }, label = { Text("Secret") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.saveSettings(url, secret, cur) }) { Text("Save and test") }
                    if (vm.prefs.remoteConfigured) OutlinedButton(onClick = { vm.sync() }) { Text("Sync now") }
                }
                if (sync.isNotEmpty()) Text(sync, style = MaterialTheme.typography.bodySmall,
                    color = if (sync.startsWith("Not")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (vm.prefs.sheetUrl.isNotEmpty()) TextButton(onClick = { open(vm.prefs.sheetUrl, null) }) { Text("Open Google Sheet") }
            }

            Section("Currency") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("INR", "USD", "EUR", "GBP", "AED", "SGD").forEach { c ->
                        FilterChip(cur == c, { cur = c; vm.saveSettings(url, secret, c) }, { Text(c) })
                    }
                }
            }

            Section("Add from Google Pay") {
                Text("Open a payment in Google Pay, tap Share, and choose KrsnaKosh. The amount, payee, time and UPI reference are read from the receipt, and the screenshot is kept as proof.",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}
