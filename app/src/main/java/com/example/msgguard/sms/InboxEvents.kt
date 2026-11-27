package com.example.msgguard.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.provider.Telephony
import androidx.core.content.ContextCompat

/** Tells open screens that a message or its spam classification changed. */
object InboxEvents {
    const val ACTION_CHANGED = "com.example.msgguard.action.MESSAGES_CHANGED"

    fun publish(context: Context) {
        val app = context.applicationContext
        app.sendBroadcast(Intent(ACTION_CHANGED).setPackage(app.packageName))
    }

    fun register(context: Context, receiver: BroadcastReceiver) {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }
}

object SmsDatabase {
    fun register(context: Context, observer: ContentObserver) {
        runCatching {
            context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        }
    }

    fun unregister(context: Context, observer: ContentObserver) {
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
    }
}
