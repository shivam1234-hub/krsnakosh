package app.tagledger.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.tagledger.R
import app.tagledger.logic.Draft
import app.tagledger.logic.ExportSpec
import app.tagledger.logic.buildWorkbook
import app.tagledger.logic.writeCsv
import app.tagledger.logic.writeXlsx
import app.tagledger.logic.parseBankSms
import app.tagledger.logic.parseReceipt
import app.tagledger.remote.Remote
import app.tagledger.sms.SmsReceiver
import app.tagledger.ui.MainActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class Repository(private val ctx: Context, private val db: AppDb, val prefs: Prefs) {
    private val dao = db.expenses()
    private val remote = Remote(prefs)
    private val syncLock = Mutex()

    val done = dao.observeDone()
    val pending = dao.observePending()

    private val _syncStatus = MutableStateFlow("")
    val syncStatus = _syncStatus.asStateFlow()

    suspend fun get(id: String) = dao.get(id)

    suspend fun save(e: Expense) = dao.upsert(e.copy(updatedAt = System.currentTimeMillis()))

    suspend fun delete(items: List<Expense>) {
        dao.delete(items.map { it.id })
        val synced = items.filter { it.syncedAt > 0 }.map { it.id }
        if (synced.isNotEmpty()) prefs.pendingDeletes = prefs.pendingDeletes + synced
    }

    /** Puts back entries removed by [delete] (Undo). */
    suspend fun restore(items: List<Expense>) {
        prefs.pendingDeletes = prefs.pendingDeletes - items.map { it.id }.toSet()
        dao.upsertAll(items.map { it.copy(updatedAt = System.currentTimeMillis()) })
    }

    /** Tags and title from the last confirmed payment to the same payee. */
    suspend fun learned(payee: String): Expense? = if (payee.isBlank()) null else dao.lastForPayee(payee)

    // ---------- SMS ----------

    /** Turns a bank SMS into a pending entry. Returns it, or null if it isn't a new payment. */
    suspend fun ingestSms(text: String, receivedAt: Long, notify: Boolean): Expense? {
        val d = parseBankSms(text, receivedAt) ?: return null
        if (isDuplicate(d)) return null
        val prev = learned(d.payee)
        val e = Expense(
            title = prev?.title ?: d.title, amount = d.amount!!, type = d.type, time = d.time,
            tags = prev?.tags ?: emptyList(), payee = d.payee, ref = d.ref, raw = d.raw,
            status = Status.PENDING, source = "sms",
        )
        dao.upsert(e)
        if (notify) notifyPending(e)
        return e
    }

    suspend fun isDuplicate(d: Draft): Boolean {
        if (d.ref.isNotEmpty() && dao.countRef(d.ref) > 0) return true
        val amount = d.amount ?: return false
        return dao.countSimilar(d.type, amount, d.time - 120_000, d.time + 120_000) > 0
    }

    /** Reads the SMS inbox for the last [days] days and queues payments not seen yet. */
    suspend fun scanInbox(days: Int): Int = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return@withContext 0
        val since = System.currentTimeMillis() - days * 86_400_000L
        var n = 0
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} > ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            while (c.moveToNext()) if (ingestSms(c.getString(0) ?: "", c.getLong(1), notify = false) != null) n++
        }
        n
    }

    fun setSmsReceiverEnabled(on: Boolean) {
        prefs.smsEnabled = on
        ctx.packageManager.setComponentEnabledSetting(
            ComponentName(ctx, SmsReceiver::class.java),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun notifyPending(e: Expense) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Payments to review", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val sign = if (e.isIncome) "+" else "−"
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_tag)
            .setContentTitle("$sign${Money.format(e.amount, prefs.currency)} · ${e.title}")
            .setContentText(if (e.tags.isEmpty()) "Tap to add tags and confirm" else "Tap to confirm " + e.tags.joinToString(" ") { "#$it" })
            .setContentIntent(open).setAutoCancel(true).build()
        try { nm.notify(e.id.hashCode(), n) } catch (_: SecurityException) { }
    }

    // ---------- photos and receipts ----------

    /** Copies a shared or picked image into app storage (it may not stay readable later). */
    suspend fun importPhoto(uri: Uri): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.filesDir, "proofs").apply { mkdirs() }
        val out = File(dir, "${UUID.randomUUID()}.jpg")
        val bmp = ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
        if (bmp != null) {
            val scale = minOf(1f, 2000f / maxOf(bmp.width, bmp.height))
            val sized = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            FileOutputStream(out).use { sized.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        } else {
            ctx.contentResolver.openInputStream(uri)!!.use { input -> FileOutputStream(out).use { input.copyTo(it) } }
        }
        out
    }

    /** Reads the payment details off a receipt screenshot, on the phone (no upload). */
    suspend fun readReceipt(file: File?, sharedText: String?): Draft? {
        sharedText?.takeIf { it.isNotBlank() }?.let { t -> (parseBankSms(t, System.currentTimeMillis()) ?: parseReceipt(t, null))?.let { return it } }
        if (file == null) return null
        val result = runCatching {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(InputImage.fromFilePath(ctx, Uri.fromFile(file))).await()
        }.getOrNull() ?: return null
        val lines = result.textBlocks.flatMap { it.lines }
        val big = lines.filter { l -> l.text.any(Char::isDigit) }.maxByOrNull { it.boundingBox?.height() ?: 0 }?.text
        return parseReceipt(result.text, big)
    }

    fun deletePhotoFile(path: String) { if (path.isNotEmpty()) File(path).delete() }

    // ---------- Google Sheet + Drive sync ----------

    suspend fun testConnection(): String {
        val info = remote.info()
        prefs.sheetUrl = info.optString("sheetUrl")
        return info.optString("sheetName", "your sheet")
    }

    /** Uploads new photos, then sends changed and deleted entries to the Sheet. Safe to call often. */
    suspend fun sync(): Boolean = syncLock.withLock {
        if (!prefs.remoteConfigured) return false
        try {
            _syncStatus.value = "Syncing…"
            for (e in dao.photosToUpload()) {
                val f = File(e.photoPath)
                if (!f.exists()) continue
                val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm").format(Instant.ofEpochMilli(e.time).atZone(ZoneId.systemDefault()))
                dao.setPhotoUrl(e.id, remote.uploadPhoto(f, "$stamp ${e.title}.jpg"))
            }
            val startedAt = System.currentTimeMillis()
            val changed = dao.unsynced()
            changed.chunked(200).forEach { remote.upsert(it) }
            dao.markSynced(changed.map { it.id }, startedAt)
            val dels = prefs.pendingDeletes
            if (dels.isNotEmpty()) { remote.delete(dels); prefs.pendingDeletes = prefs.pendingDeletes - dels }
            _syncStatus.value = "Synced " + DateTimeFormatter.ofPattern("HH:mm").format(Instant.now().atZone(ZoneId.systemDefault()))
            true
        } catch (t: Throwable) {
            _syncStatus.value = "Not synced: " + (t.message ?: "network error")
            false
        }
    }

    // ---------- export ----------

    /** Writes [list] as .xlsx or .csv using the columns in [spec], and returns a shareable link to the file. */
    suspend fun export(list: List<Expense>, spec: ExportSpec, name: String): Uri = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only the latest export is kept
        val f = File(dir, name + if (spec.xlsx) ".xlsx" else ".csv")
        if (spec.xlsx) f.outputStream().buffered().use { writeXlsx(it, buildWorkbook(list, spec, prefs.currency)) }
        else f.bufferedWriter().use { writeCsv(it, list, spec, prefs.currency) }
        FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    }

    fun photoUri(path: String): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(path))

    companion object { const val CHANNEL = "payments" }
}

object Money {
    fun format(n: Double, currency: String): String {
        val f = java.text.NumberFormat.getCurrencyInstance(if (currency == "INR") java.util.Locale("en", "IN") else java.util.Locale.US)
        f.currency = java.util.Currency.getInstance(currency)
        f.maximumFractionDigits = 2
        f.minimumFractionDigits = if (n % 1.0 == 0.0) 0 else 2
        return f.format(kotlin.math.abs(n))
    }
}
