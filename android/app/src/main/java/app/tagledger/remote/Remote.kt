package app.tagledger.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import app.tagledger.data.Expense
import app.tagledger.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Talks to the user's own Google Apps Script web app (see apps-script/Code.gs). */
class Remote(private val prefs: Prefs) {

    suspend fun info(): JSONObject = post(JSONObject().put("action", "info"))

    /** Uploads a proof photo to the Drive folder (link-viewable) and returns its link. */
    suspend fun uploadPhoto(file: File, name: String): String {
        val jpeg = withContext(Dispatchers.Default) { shrink(file) }
        val res = post(
            JSONObject().put("action", "photo").put("name", name).put("mime", "image/jpeg")
                .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)),
        )
        return res.optString("url").ifEmpty { throw IOException("Drive did not return a link") }
    }

    suspend fun upsert(list: List<Expense>) {
        if (list.isEmpty()) return
        val arr = JSONArray()
        val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        for (e in list) {
            val at = Instant.ofEpochMilli(e.time).atZone(ZoneId.systemDefault())
            arr.put(
                JSONObject()
                    .put("id", e.id).put("date", dayFmt.format(at)).put("time", timeFmt.format(at))
                    .put("title", e.title).put("type", if (e.isIncome) "Income" else "Expense")
                    .put("amount", e.amount).put("signed", e.signed)
                    .put("tags", e.tags.joinToString(" ") { "#$it" }).put("note", e.note)
                    .put("payee", e.payee).put("ref", e.ref).put("photo", e.photoUrl)
                    .put("currency", prefs.currency).put("updated", e.updatedAt),
            )
        }
        post(JSONObject().put("action", "upsert").put("entries", arr))
    }

    suspend fun delete(ids: Collection<String>) {
        if (ids.isEmpty()) return
        post(JSONObject().put("action", "delete").put("ids", JSONArray(ids.toList())))
    }

    private suspend fun post(body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        if (!prefs.remoteConfigured) throw IOException("Add your Apps Script URL and secret in Settings")
        body.put("token", prefs.secret)
        val c = (URL(prefs.scriptUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 90_000
            // Apps Script answers a POST with a redirect to the result, which must be fetched with GET.
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "text/plain;charset=utf-8")
        }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val text = when (val code = c.responseCode) {
                in 300..399 -> {
                    val loc = c.getHeaderField("Location") ?: throw IOException("Redirect without a location")
                    val g = URL(loc).openConnection() as HttpURLConnection
                    try { g.inputStream.bufferedReader().readText() } finally { g.disconnect() }
                }
                in 200..299 -> c.inputStream.bufferedReader().readText()
                else -> throw IOException("Apps Script answered HTTP $code")
            }
            val json = runCatching { JSONObject(text) }.getOrElse {
                throw IOException("Got a web page instead of data. Check the URL ends in /exec and access is set to Anyone.")
            }
            if (!json.optBoolean("ok")) {
                val err = json.optString("error")
                throw IOException(if (err == "bad token") "The secret doesn't match the one in your Apps Script" else err.ifEmpty { "Apps Script returned an error" })
            }
            json
        } finally {
            c.disconnect()
        }
    }

    /** Scales the photo to at most 1600px and re-encodes as JPEG so uploads stay small. */
    private fun shrink(file: File): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1600 || bounds.outHeight / (sample * 2) >= 1600) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return file.readBytes()
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
    }
}
