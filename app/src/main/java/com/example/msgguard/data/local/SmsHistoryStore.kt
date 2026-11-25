package com.example.msgguard.data.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class StoredSms(
    val id: String,
    val sender: String,
    val content: String,
    val isAdvertisement: Boolean?,
    val classificationError: String?,
    val receivedAt: Long
)

/** Stores a private, on-device history of messages classified by MsgGuard. */
class SmsHistoryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun save(
        sender: String,
        content: String,
        isAdvertisement: Boolean?,
        classificationError: String? = null
    ) {
        val messages = load().toMutableList()
        messages.add(
            0,
            StoredSms(
                id = UUID.randomUUID().toString(),
                sender = sender.ifBlank { "Unknown sender" },
                content = content,
                isAdvertisement = isAdvertisement,
                classificationError = classificationError,
                receivedAt = System.currentTimeMillis()
            )
        )

        val entries = JSONArray()
        messages.take(MAX_HISTORY_ITEMS).forEach { message ->
            entries.put(
                JSONObject()
                    .put("id", message.id)
                    .put("sender", message.sender)
                    .put("content", message.content)
                    .put("isAdvertisement", message.isAdvertisement)
                    .put("classificationError", message.classificationError)
                    .put("receivedAt", message.receivedAt)
            )
        }
        preferences.edit().putString(HISTORY_KEY, entries.toString()).apply()
    }

    fun load(): List<StoredSms> = try {
        val rawHistory = preferences.getString(HISTORY_KEY, "[]") ?: "[]"
        val entries = JSONArray(rawHistory)
        buildList {
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                add(
                    StoredSms(
                        id = entry.optString("id"),
                        sender = entry.optString("sender", "Unknown sender"),
                        content = entry.optString("content"),
                        isAdvertisement = if (entry.isNull("isAdvertisement")) {
                            null
                        } else {
                            entry.optBoolean("isAdvertisement")
                        },
                        classificationError = entry.optString("classificationError").ifBlank { null },
                        receivedAt = entry.optLong("receivedAt")
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private companion object {
        const val PREFERENCES_NAME = "sms_history"
        const val HISTORY_KEY = "messages"
        const val MAX_HISTORY_ITEMS = 500
    }
}
