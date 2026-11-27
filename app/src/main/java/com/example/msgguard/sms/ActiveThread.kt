package com.example.msgguard.sms

import android.content.Context

/** The conversation currently on screen, so incoming alerts can stay quiet there. */
object ActiveThread {
    @Volatile
    var address: String? = null

    fun matches(context: Context, other: String): Boolean {
        val open = address ?: return false
        return SmsAddresses.same(context, open, other)
    }
}
