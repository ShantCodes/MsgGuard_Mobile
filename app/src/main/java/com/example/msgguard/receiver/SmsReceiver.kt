package com.example.msgguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.example.msgguard.worker.SpamCheckWorker

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val smsParts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            val message = smsParts
                .joinToString(separator = "") { it.messageBody.orEmpty() }
            if (message.isNotEmpty()) {
                val data = Data.Builder()
                    .putString("message_content", message)
                    .putString("message_sender", smsParts.firstOrNull()?.originatingAddress)
                    .build()

                val workRequest = OneTimeWorkRequest.Builder(SpamCheckWorker::class.java)
                    .setInputData(data)
                    .build()

                WorkManager.getInstance(context).enqueue(workRequest)
            }
        }
    }
}
