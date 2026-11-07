package app.tagledger.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import app.tagledger.TagLedgerApp
import kotlinx.coroutines.launch

/** Reads incoming bank SMS on the phone and queues payments for review. Nothing is uploaded from here. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val text = parts.joinToString("") { it.messageBody ?: "" }
        val at = parts.first().timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        val app = context.applicationContext as TagLedgerApp
        val done = goAsync()
        app.appScope.launch {
            try { app.repo.ingestSms(text, at, notify = true) } finally { done.finish() }
        }
    }
}
