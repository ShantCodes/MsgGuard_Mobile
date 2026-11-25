package com.example.msgguard.domain.repository

/**
 * Repository for handling SMS data.
 */
interface SmsRepository {
    suspend fun isSpam(content: String): Boolean
}
