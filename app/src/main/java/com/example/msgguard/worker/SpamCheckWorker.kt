package com.example.msgguard.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.msgguard.data.local.SmsHistoryStore
import com.example.msgguard.domain.usecase.IdentifySpamUseCase
import com.example.msgguard.domain.usecase.SmsMessage
import com.example.msgguard.data.repository.SmsRepositoryImpl

class SpamCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val content = inputData.getString("message_content") ?: return Result.success()
        val sender = inputData.getString("message_sender").orEmpty()

        val repository = SmsRepositoryImpl(applicationContext)
        val useCase = IdentifySpamUseCase(repository)

        val result = useCase.execute(SmsMessage(
            id = "temp_id",
            content = content,
            sender = sender
        ))

        if (result.isSuccess) {
            val spamResult = result.getOrThrow()
            SmsHistoryStore(applicationContext).save(sender, content, spamResult.isSpam)
        }

        return Result.success()
    }
}
