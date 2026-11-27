package com.example.msgguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.RemoteInput
import com.example.msgguard.data.local.SmsGateway
import com.example.msgguard.notifications.SmsNotifier
import com.example.msgguard.sms.InboxEvents

/** Sends the reply typed in a message notification. */
class NotificationReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val recipient = Uri.decode(intent.data?.lastPathSegment.orEmpty())
        val message = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY)
            ?.toString()
            .orEmpty()
        if (recipient.isBlank() || message.isBlank()) return
        val appContext = context.applicationContext
        SmsGateway(appContext).send(recipient, message)
            .onSuccess {
                SmsNotifier(appContext).cancel(recipient)
                InboxEvents.publish(appContext)
            }
            .onFailure {
                SmsNotifier(appContext).notifySendFailed(recipient, it.message ?: "The reply could not be sent.")
            }
    }

    companion object {
        const val ACTION_REPLY = "com.example.msgguard.action.REPLY"
        const val KEY_REPLY = "reply_text"
    }
}
