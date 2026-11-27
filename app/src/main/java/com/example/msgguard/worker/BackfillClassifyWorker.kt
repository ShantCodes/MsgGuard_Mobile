package com.example.msgguard.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.msgguard.data.local.SmsHistoryStore
import com.example.msgguard.data.local.StoredSms
import com.example.msgguard.data.local.SystemSmsStore
import com.example.msgguard.data.repository.SmsRepositoryImpl
import com.example.msgguard.domain.usecase.IdentifySpamUseCase
import com.example.msgguard.domain.usecase.SmsMessage
import com.example.msgguard.sms.InboxEvents
import com.example.msgguard.sms.SmsAddresses

/** Classifies recent existing conversations once, so the Safe and Filtered views work before a new text arrives. */
class BackfillClassifyWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val threads = runCatching { SystemSmsStore(applicationContext).loadThreads() }.getOrElse {
            return if (runAttemptCount >= 3) {
                markDone()
                Result.success()
            } else {
                Result.retry()
            }
        }
        val history = SmsHistoryStore(applicationContext)
        val known = history.load().toMutableList()
        val useCase = IdentifySpamUseCase(SmsRepositoryImpl(applicationContext))
        var classified = 0
        val pending = threads.mapNotNull { it.lastIncoming }.filter { it.body.isNotBlank() }.take(MAX_THREADS)
        for (message in pending) {
            if (isStopped) return Result.success()
            if (alreadyClassified(known, message.address, message.body)) continue
            val result = useCase.execute(SmsMessage("backfill", message.body, message.address))
            result.onSuccess { spamResult ->
                history.save(message.address, message.body, spamResult.isSpam)
            }.onFailure { error ->
                history.save(message.address, message.body, null, error.message?.take(200))
            }
            known.add(0, StoredSms("", message.address, message.body, null, null, message.date))
            classified += 1
            if (classified % 8 == 0) InboxEvents.publish(applicationContext)
        }
        if (classified > 0) InboxEvents.publish(applicationContext)
        markDone()
        return Result.success()
    }

    private fun alreadyClassified(history: List<StoredSms>, sender: String, content: String): Boolean =
        history.any {
            it.content.trim() == content.trim() && SmsAddresses.same(applicationContext, it.sender, sender)
        }

    private fun markDone() {
        applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(DONE, true)
            .apply()
    }

    companion object {
        const val WORK_NAME = "classify-existing-inbox"
        private const val PREFS = "ui_preferences"
        private const val DONE = "classification_backfill_done"
        private const val MAX_THREADS = 100

        fun isDone(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(DONE, false)
    }
}
