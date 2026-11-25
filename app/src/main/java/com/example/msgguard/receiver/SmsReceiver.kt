package com.example.msgguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.example.msgguard.worker.SpamCheckWorker
import androidx.work.Data
import android.provider.TelephonyManager

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.sms.SMS_RECEIVED") {
            val message = intent.getStringExtra("EXTRA_TEXT")
            if (message != null) {
                val data = Data.Builder()
                    .putString("message_content", message)
                    .build()
                val workRequest = OneTimeWorkRequest.Builder(SpamCheckWorker::class.java)
                    .setInputData(data)
                    .build()
                WorkManager.getInstance(context).enqueue(workRequest)
            }
        }
    }
}
import android.provider.SMSUri
import android.net.Uri

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.sms.SMS_RECEIVED") {
            val messages = intent.getStringExtra("EXTRA_TEXT")
            if (messages != null) {
                val workRequest = OneTimeWorkRequest.Builder(SpamCheckWorker::class.java)
                    .setInputData(android.app.Application.contentValuesToWorkData(
                        android.app.Application.CONTENT_URI,
                        android.app.Application.putCharSequence(android.app.Application.ContentResolver.buildCharSequence("message"), messages)
                    ))
                    .build()
                WorkManager.getInstance(context).enqueue(workRequest)
            }
        }
    }
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.sms.sysmessagereceived" || intent.action == "android.sms.smsmtoss") {
            // This is a simplified check; for real production, you'd extract the SMS content
            // and pass it to the worker.
            val workRequest = OneTimeWorkRequest.Builder(SpamCheckWorker::class.java).build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }
}
