package com.example.msgguard.domain.usecase

import com.example.msgguard.domain.model.SpamResult
import com.example.msgguard.domain.model.SmsMessage
import com.example.msgguard.domain.repository.SmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Use case to identify if a message is spam and handle it accordingly.
 */
class IdentifySpamUseCase(
    private val smsRepository: SmsRepository
) {
    suspend fun execute(message: SmsMessage): Result<SpamResult> = withContext(Dispatchers.Default) {
        // This will call the ML model to determine if the message is spam
        val isSpam = smsRepository.isSpam(message.content)

        if (isSpam) {
            // Mark as spam/delete
            smsRepository.markAsSpam(message.id)
            return@withContext Result.success(SpamResult(isSpam = true, actionTaken = "Moved to Spam"))
        }

        Result.success(SpamResult(isSpam = false, actionTaken = "None"))
    }
}

data class SmsMessage(
    val id: String,
    val content: String,
    val sender: String
)

data class SpamResult(
    val isSpam: Boolean,
    val actionTaken: String
)
