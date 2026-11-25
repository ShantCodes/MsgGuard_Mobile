package com.example.msgguard.domain.usecase

import com.example.msgguard.domain.repository.SmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Use case to classify a received message.
 */
class IdentifySpamUseCase(
    private val smsRepository: SmsRepository
) {
    suspend fun execute(message: SmsMessage): Result<SpamResult> = withContext(Dispatchers.Default) {
        // This will call the ML model to determine if the message is spam
        val isSpam = smsRepository.isSpam(message.content)

        Result.success(
            SpamResult(
                isSpam = isSpam,
                actionTaken = if (isSpam) "Classified as advertisement" else "Classified as other"
            )
        )
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
