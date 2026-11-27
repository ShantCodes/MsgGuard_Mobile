package com.example.msgguard.sms

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.PhoneNumberUtils

/** Compares and normalizes the phone numbers stored by Android's SMS provider. */
object SmsAddresses {
    fun same(context: Context, left: String, right: String): Boolean {
        val a = left.trim()
        val b = right.trim()
        if (a.equals(b, ignoreCase = true)) return true
        if (a.isEmpty() || b.isEmpty()) return false
        // Loose comparison treats digit-free names as equal, so require a real number first.
        if (a.count { it.isDigit() } < 4 || b.count { it.isDigit() } < 4) return false
        return PhoneNumberUtils.compare(context, a, b)
    }

    fun key(address: String): String {
        val digits = address.filter { it.isDigit() }
        return if (digits.length >= 4) digits.takeLast(10) else address.trim().lowercase()
    }

    fun forSending(address: String): String {
        val trimmed = address.trim()
        val numbers = trimmed.filter { it.isDigit() }
        if (numbers.isEmpty()) return trimmed
        return if (trimmed.startsWith("+")) "+$numbers" else numbers
    }

    fun fromIntent(intent: Intent?): String {
        if (intent == null) return ""
        val data = intent.data
        val scheme = data?.scheme?.lowercase().orEmpty()
        if (scheme.startsWith("sms") || scheme.startsWith("mms")) {
            val number = Uri.decode(data?.schemeSpecificPart.orEmpty())
                .removePrefix("//")
                .substringBefore("?")
                .substringBefore(";")
                .trim()
            if (number.isNotBlank()) return number
        }
        return intent.getStringExtra(EXTRA_ADDRESS).orEmpty().trim()
    }

    fun bodyFromIntent(intent: Intent?): String {
        if (intent == null) return ""
        val direct = listOf(EXTRA_BODY, "sms_body", Intent.EXTRA_TEXT)
            .firstNotNullOfOrNull { key -> intent.getStringExtra(key)?.takeIf { it.isNotBlank() } }
        return direct ?: intent.data?.getQueryParameter("body").orEmpty()
    }

    const val EXTRA_ADDRESS = "address"
    const val EXTRA_BODY = "body"
}
