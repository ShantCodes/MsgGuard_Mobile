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
    suspend fun execute(message: SmsMessage): Result<SpamResult> = runCatching {
        withContext(Dispatchers.Default) {
            val isSpam = smsRepository.isSpam(message.content)
            SpamResult(
                isSpam = isSpam,
                actionTaken = if (isSpam) "Classified as advertisement" else "Classified as other"
            )
        }
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
