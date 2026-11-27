package com.example.msgguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.example.msgguard.data.local.SystemSmsStore
import com.example.msgguard.sms.InboxEvents
import com.example.msgguard.worker.SpamCheckWorker

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val smsParts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val message = smsParts.joinToString(separator = "") { it.messageBody.orEmpty() }
        if (message.isEmpty()) return
        val sender = smsParts.firstNotNullOfOrNull { part ->
            part.originatingAddress?.takeIf { it.isNotBlank() }
        }.orEmpty()
        val sentAt = smsParts.maxOfOrNull { it.timestampMillis } ?: 0L
        val appContext = context.applicationContext
        // The default SMS app is responsible for writing SMS_DELIVER messages into the provider.
        val inserted = runCatching {
            SystemSmsStore(appContext).saveIncoming(sender, message, sentAt)
        }.getOrDefault(false)
        if (!inserted) return
        InboxEvents.publish(appContext)

        val data = Data.Builder()
            .putString(SpamCheckWorker.KEY_CONTENT, message)
            .putString(SpamCheckWorker.KEY_SENDER, sender)
            .putBoolean(SpamCheckWorker.KEY_NOTIFY, true)
            .build()
        WorkManager.getInstance(appContext).enqueue(
            OneTimeWorkRequest.Builder(SpamCheckWorker::class.java).setInputData(data).build()
        )
    }
}
