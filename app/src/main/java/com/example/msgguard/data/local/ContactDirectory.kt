package com.example.msgguard.data.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

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
