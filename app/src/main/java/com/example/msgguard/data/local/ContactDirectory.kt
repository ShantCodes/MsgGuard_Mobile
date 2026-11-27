package com.example.msgguard.data.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

data class ContactEntry(val name: String, val number: String)

/** Resolves a phone number to a contact name when the user has allowed contacts. */
class ContactDirectory(private val context: Context) {
    private val cache = mutableMapOf<String, String>()

    fun nameFor(address: String): String {
        if (address.isBlank()) return address
        cache[address]?.let { return it }
        val resolved = lookup(address) ?: address
        cache[address] = resolved
        return resolved
    }

    /** Returns a small, ordered set of phone contacts for the compose recipient search. */
    fun search(query: String, limit: Int = 12): List<ContactEntry> {
        val term = query.trim()
        if (term.isBlank() || context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        return runCatching {
            val results = linkedSetOf<ContactEntry>()
            val phone = ContactsContract.CommonDataKinds.Phone
            context.contentResolver.query(
                phone.CONTENT_URI,
                arrayOf(phone.DISPLAY_NAME, phone.NUMBER),
                "${phone.DISPLAY_NAME} LIKE ? OR ${phone.NUMBER} LIKE ?",
                arrayOf("%$term%", "%$term%"),
                "${phone.DISPLAY_NAME} COLLATE NOCASE ASC"
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow(phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndexOrThrow(phone.NUMBER)
                while (cursor.moveToNext() && results.size < limit) {
                    val name = cursor.getString(nameIndex).orEmpty().trim()
                    val number = cursor.getString(numberIndex).orEmpty().trim()
                    if (name.isNotBlank() && number.isNotBlank()) results += ContactEntry(name, number)
                }
            }
            results.toList()
        }.getOrDefault(emptyList())
    }

    private fun lookup(address: String): String? {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(address)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()
    }
}
