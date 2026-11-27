package com.example.msgguard.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import com.example.msgguard.data.local.SystemSmsStore
import com.example.msgguard.notifications.SmsNotifier
import com.example.msgguard.sms.InboxEvents

/** Updates a stored outgoing message after the radio reports sent or delivered. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val segments = intent.data?.pathSegments.orEmpty()
        val id = segments.getOrNull(0)?.toLongOrNull() ?: return
        val kind = segments.getOrNull(2) ?: return
        val appContext = context.applicationContext
        val store = SystemSmsStore(appContext)
        when (kind) {
            "sent" -> if (resultCode != Activity.RESULT_OK) {
                store.markFailed(id, resultCode)
                val recipient = store.loadMessage(id)?.address.orEmpty()
                SmsNotifier(appContext).notifySendFailed(recipient, reason(resultCode))
            }
            "delivered" -> if (resultCode == Activity.RESULT_OK) store.markDelivered(id)
        }
        InboxEvents.publish(appContext)
    }

    private fun reason(resultCode: Int): String = when (resultCode) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> "No cellular service."
        SmsManager.RESULT_ERROR_RADIO_OFF -> "The radio is turned off."
        SmsManager.RESULT_ERROR_NULL_PDU -> "The carrier rejected the message."
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "Too many messages were sent."
        else -> "The message could not be sent."
    }

    companion object {
        const val ACTION_SENT = "com.example.msgguard.action.SMS_SENT"
        const val ACTION_DELIVERED = "com.example.msgguard.action.SMS_DELIVERED"
    }
}
