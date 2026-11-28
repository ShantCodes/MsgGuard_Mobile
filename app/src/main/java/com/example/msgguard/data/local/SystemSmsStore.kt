package com.example.msgguard.data.local

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.database.Cursor
import android.provider.Telephony
import com.example.msgguard.sms.SmsAddresses

data class SmsRecord(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int,
    val status: Int,
    val read: Boolean
) {
    fun isOutgoing(): Boolean = type != Telephony.Sms.MESSAGE_TYPE_INBOX
}

data class SmsThread(
    val address: String,
    val preview: String,
    val date: Long,
    val unreadCount: Int,
    val lastMessage: SmsRecord,
    val lastIncoming: SmsRecord?
)

/** Accesses Android's telephony provider. Writes require MsgGuard to be the default SMS app. */
class SystemSmsStore(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    fun loadThreads(): List<SmsThread> {
        val grouped = linkedMapOf<String, ThreadBuilder>()
        queryRecentSms(MAX_THREAD_SCAN)?.use { cursor ->
            while (cursor.moveToNext()) {
                val record = cursor.toRecord()
                val key = if (record.threadId > 0) "t:${record.threadId}" else "a:${record.address.lowercase()}"
                val existing = grouped[key]
                if (existing == null) {
                    grouped[key] = ThreadBuilder(record)
                } else {
                    existing.absorbOlder(record)
                }
            }
        }

        // THREAD_ID is supplied by Android and avoids an expensive O(n²) address-normalisation pass.
        return grouped.values.map { it.toThread() }.sortedByDescending { it.date }
    }

    fun loadConversation(address: String): List<SmsRecord> {
        if (address.isBlank()) return emptyList()
        val messages = mutableListOf<SmsRecord>()
        val threadId = threadIdFor(address)
        val selection = if (threadId != null) "${Telephony.Sms.THREAD_ID} = ?" else "${Telephony.Sms.ADDRESS} = ?"
        val selectionArgs = arrayOf((threadId ?: address).toString())
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            PROJECTION,
            queryArgs(selection, selectionArgs, "${Telephony.Sms.DATE} DESC", MAX_CONVERSATION_MESSAGES),
            null
        )?.use { cursor -> while (cursor.moveToNext()) messages += cursor.toRecord() }
        return messages.asReversed()
    }

    fun loadMessage(id: Long): SmsRecord? {
        resolver.query(
            ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id),
            PROJECTION,
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.toRecord()
        }
        return null
    }

    fun saveIncoming(address: String, body: String, sentAt: Long = System.currentTimeMillis()): Boolean {
        val storedAddress = address.ifBlank { "Unknown sender" }
        val receivedAt = System.currentTimeMillis()
        if (alreadyStored(storedAddress, body, receivedAt, Telephony.Sms.MESSAGE_TYPE_INBOX)) return false
        val values = contentValues(storedAddress, body, receivedAt).apply {
            put(Telephony.Sms.DATE_SENT, if (sentAt > 0) sentAt else receivedAt)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            putThreadId(storedAddress)
        }
        return resolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values) != null
    }

    fun saveOutgoing(address: String, body: String, date: Long = System.currentTimeMillis()): Long {
        val values = contentValues(address, body, date).apply {
            put(Telephony.Sms.DATE_SENT, date)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_NONE)
            putThreadId(address)
        }
        val uri = resolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
            ?: error("Couldn't save the message. Set MsgGuard as the default SMS app.")
        return ContentUris.parseId(uri)
    }

    fun markFailed(id: Long, errorCode: Int) {
        update(id, ContentValues().apply {
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
            put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_FAILED)
            put(Telephony.Sms.ERROR_CODE, errorCode)
        })
    }

    fun markDelivered(id: Long) {
        resolver.update(
            ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id),
            ContentValues().apply { put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_COMPLETE) },
            "${Telephony.Sms.TYPE} != ?",
            arrayOf(Telephony.Sms.MESSAGE_TYPE_FAILED.toString())
        )
    }

    fun markConversationRead(address: String): Int {
        if (address.isBlank()) return 0
        val threadId = threadIdFor(address)
        return resolver.update(
            Telephony.Sms.CONTENT_URI,
            readValues(),
            if (threadId != null) "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} = ? AND ${Telephony.Sms.READ} = 0" else "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.TYPE} = ? AND ${Telephony.Sms.READ} = 0",
            if (threadId != null) arrayOf(threadId.toString(), Telephony.Sms.MESSAGE_TYPE_INBOX.toString()) else arrayOf(address, Telephony.Sms.MESSAGE_TYPE_INBOX.toString())
        )
    }

    fun deleteMessage(id: Long): Boolean =
        resolver.delete(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), null, null) > 0

    fun deleteThread(address: String): Boolean {
        val threadId = threadIdFor(address) ?: return false
        var removed = 0
        val threadUri = ContentUris.withAppendedId(Telephony.Threads.CONTENT_URI, threadId)
        removed += runCatching { resolver.delete(threadUri, null, null) }.getOrDefault(0)
        removed += resolver.delete(Telephony.Sms.CONTENT_URI, "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()))
        if (removed == 0) {
            messagesForAddress(address).forEach { message ->
                if (deleteMessage(message.id)) removed += 1
            }
        }
        return removed > 0
    }

    private fun messagesForAddress(address: String): List<SmsRecord> = loadConversation(address)

    private fun threadIdFor(address: String): Long? = runCatching {
        Telephony.Threads.getOrCreateThreadId(context, address).takeIf { it > 0 }
    }.getOrNull()

    private fun queryRecentSms(limit: Int) = resolver.query(
        Telephony.Sms.CONTENT_URI,
        PROJECTION,
        queryArgs(null, null, "${Telephony.Sms.DATE} DESC", limit),
        null
    )

    private fun queryArgs(selection: String?, selectionArgs: Array<String>?, sort: String, limit: Int) = Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
        putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
        putString(ContentResolver.QUERY_ARG_SQL_LIMIT, limit.toString())
    }

    private fun alreadyStored(address: String, body: String, date: Long, type: Int): Boolean {
        val start = date - DUPLICATE_WINDOW_MS
        val end = date + DUPLICATE_WINDOW_MS
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
            "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} <= ? AND ${Telephony.Sms.TYPE} = ?",
            arrayOf(start.toString(), end.toString(), type.toString()),
            null
        )?.use { cursor ->
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            while (cursor.moveToNext()) {
                val sameBody = cursor.getString(bodyIndex).orEmpty() == body
                val sameAddress = SmsAddresses.same(context, address, cursor.getString(addressIndex).orEmpty())
                if (sameBody && sameAddress) return true
            }
        }
        return false
    }

    private fun contentValues(address: String, body: String, date: Long) = ContentValues().apply {
        put(Telephony.Sms.ADDRESS, address)
        put(Telephony.Sms.BODY, body)
        put(Telephony.Sms.DATE, date)
    }

    private fun ContentValues.putThreadId(address: String) {
        val threadId = runCatching { Telephony.Threads.getOrCreateThreadId(context, address) }.getOrNull()
        if (threadId != null && threadId > 0) put(Telephony.Sms.THREAD_ID, threadId)
    }

    private fun readValues() = ContentValues().apply {
        put(Telephony.Sms.READ, 1)
        put(Telephony.Sms.SEEN, 1)
    }

    private fun update(id: Long, values: ContentValues) {
        resolver.update(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), values, null, null)
    }

    private fun Cursor.toRecord(): SmsRecord {
        fun text(name: String) = getColumnIndex(name).let { index -> if (index < 0) "" else getString(index).orEmpty() }
        fun number(name: String) = getColumnIndex(name).let { index -> if (index < 0 || isNull(index)) 0L else getLong(index) }
        fun whole(name: String, default: Int) = getColumnIndex(name).let { index -> if (index < 0 || isNull(index)) default else getInt(index) }
        return SmsRecord(
            id = number(Telephony.Sms._ID),
            threadId = number(Telephony.Sms.THREAD_ID),
            address = text(Telephony.Sms.ADDRESS).ifBlank { "Unknown sender" },
            body = text(Telephony.Sms.BODY),
            date = number(Telephony.Sms.DATE),
            type = whole(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX),
            status = whole(Telephony.Sms.STATUS, Telephony.Sms.STATUS_NONE),
            read = whole(Telephony.Sms.READ, 0) == 1
        )
    }

    private class ThreadBuilder(record: SmsRecord) {
        var latest: SmsRecord = record
        var unread: Int = if (record.type == Telephony.Sms.MESSAGE_TYPE_INBOX && !record.read) 1 else 0
        var lastIncoming: SmsRecord? = if (record.type == Telephony.Sms.MESSAGE_TYPE_INBOX) record else null
        val address: String get() = latest.address

        fun absorbOlder(record: SmsRecord) {
            if (record.type == Telephony.Sms.MESSAGE_TYPE_INBOX && !record.read) unread += 1
            if (record.type == Telephony.Sms.MESSAGE_TYPE_INBOX && lastIncoming == null) lastIncoming = record
        }

        fun toThread() = SmsThread(address, latest.body, latest.date, unread, latest, lastIncoming)
    }

    private companion object {
        const val DUPLICATE_WINDOW_MS = 2_000L
        /** Bounded reads prevent a large device-wide SMS archive from blocking the UI. */
        const val MAX_THREAD_SCAN = 800
        const val MAX_CONVERSATION_MESSAGES = 20

        val PROJECTION = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.STATUS,
            Telephony.Sms.READ
        )
    }
}
