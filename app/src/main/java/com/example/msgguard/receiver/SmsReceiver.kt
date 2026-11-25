package com.example.msgguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.example.msgguard.worker.SpamCheckWorker

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