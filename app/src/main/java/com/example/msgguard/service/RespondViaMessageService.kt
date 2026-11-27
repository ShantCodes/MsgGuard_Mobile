package com.example.msgguard.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.example.msgguard.data.local.SmsGateway
import com.example.msgguard.notifications.SmsNotifier
import com.example.msgguard.sms.InboxEvents
import com.example.msgguard.sms.SmsAddresses

/** Handles Android's quick-reply action when MsgGuard is the default SMS handler. */
class RespondViaMessageService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recipient = SmsAddresses.fromIntent(intent)
        val message = SmsAddresses.bodyFromIntent(intent)
        if (recipient.isNotBlank() && message.isNotBlank()) {
            SmsGateway(applicationContext).send(recipient, message)
                .onSuccess { InboxEvents.publish(applicationContext) }
                .onFailure { error ->
                    SmsNotifier(applicationContext).notifySendFailed(
                        recipient,
                        error.message ?: "The message could not be sent."
                    )
                }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
