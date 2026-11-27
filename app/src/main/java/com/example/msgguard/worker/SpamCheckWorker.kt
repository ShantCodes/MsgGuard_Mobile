package com.example.msgguard.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.msgguard.data.local.SmsHistoryStore
import com.example.msgguard.data.local.StoredSms
import com.example.msgguard.data.repository.SmsRepositoryImpl
import com.example.msgguard.domain.usecase.IdentifySpamUseCase
import com.example.msgguard.domain.usecase.SmsMessage
import com.example.msgguard.notifications.SmsNotifier
import com.example.msgguard.sms.InboxEvents
import com.example.msgguard.sms.SmsAddresses

class SpamCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val content = inputData.getString(KEY_CONTENT) ?: return Result.success()
        val sender = inputData.getString(KEY_SENDER).orEmpty()
        val notifyUser = inputData.getBoolean(KEY_NOTIFY, true)
        val history = SmsHistoryStore(applicationContext)
        if (alreadyClassified(history.load(), sender, content)) return Result.success()

        val result = IdentifySpamUseCase(SmsRepositoryImpl(applicationContext)).execute(
            SmsMessage(id = "temp_id", content = content, sender = sender)
        )
        result.onSuccess { spamResult ->
            history.save(sender, content, spamResult.isSpam)
            if (notifyUser && !spamResult.isSpam) SmsNotifier(applicationContext).notify(sender, content)
        }.onFailure { error ->
            history.save(sender, content, null, error.message ?: error.javaClass.simpleName)
            if (notifyUser) SmsNotifier(applicationContext).notify(sender, content)
        }
        InboxEvents.publish(applicationContext)
        return Result.success()
    }

    private fun alreadyClassified(history: List<StoredSms>, sender: String, content: String): Boolean =
        history.any {
            it.content.trim() == content.trim() && SmsAddresses.same(applicationContext, it.sender, sender)
        }

    companion object {
        const val KEY_CONTENT = "message_content"
        const val KEY_SENDER = "message_sender"
        const val KEY_NOTIFY = "notify_user"
    }
}
