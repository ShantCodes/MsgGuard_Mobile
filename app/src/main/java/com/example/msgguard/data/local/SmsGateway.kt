package com.example.msgguard.data.local

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import com.example.msgguard.receiver.SmsStatusReceiver
import com.example.msgguard.sms.DefaultSmsRole
import com.example.msgguard.sms.SmsAddresses

/** Sends text SMS through the default SMS subscription and stores the outgoing message. */
class SmsGateway(context: Context) {
    private val appContext = context.applicationContext

    fun send(destination: String, message: String): Result<Unit> = try {
        val address = SmsAddresses.forSending(destination)
        val body = message.trim()
        require(address.isNotBlank()) { "Choose a recipient first." }
        require(body.isNotBlank()) { "Message cannot be empty." }
        if (!DefaultSmsRole.isDefault(appContext)) {
            error("Set MsgGuard as the default SMS app before sending.")
        }
        val id = SystemSmsStore(appContext).saveOutgoing(address, body)
        try {
            dispatch(address, body, id)
            Result.success(Unit)
        } catch (error: Exception) {
            SystemSmsStore(appContext).markFailed(id, SmsManager.RESULT_ERROR_GENERIC_FAILURE)
            throw error
        }
    } catch (error: SecurityException) {
        Result.failure(IllegalStateException("Set MsgGuard as the default SMS app before sending."))
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun dispatch(destination: String, message: String, id: Long) {
        val manager = smsManager()
        val parts = manager.divideMessage(message)
        if (parts == null || parts.size <= 1) {
            manager.sendTextMessage(destination, null, message, statusIntent(id, "sent"), statusIntent(id, "delivered"))
        } else {
            val sent = ArrayList<PendingIntent>(parts.size)
            val delivered = ArrayList<PendingIntent>(parts.size)
            parts.indices.forEach { index ->
                sent += statusIntent(id, "sent", index)
                delivered += statusIntent(id, "delivered", index)
            }
            manager.sendMultipartTextMessage(destination, null, parts, sent, delivered)
        }
    }

    private fun statusIntent(id: Long, kind: String, part: Int = 0): PendingIntent {
        // The platform fills in the result code, so this PendingIntent has to be mutable on API 31+.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(appContext, SmsStatusReceiver::class.java).apply {
            action = if (kind == "delivered") SmsStatusReceiver.ACTION_DELIVERED else SmsStatusReceiver.ACTION_SENT
            data = Uri.parse("msgguard://sms-status/$id/$part/$kind")
        }
        val requestCode = (id xor (part.toLong() shl 16) xor kind.hashCode()).toInt()
        return PendingIntent.getBroadcast(appContext, requestCode, intent, flags)
    }

    private fun smsManager(): SmsManager {
        val subscriptionId = SubscriptionManager.getDefaultSmsSubscriptionId()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = appContext.getSystemService(SmsManager::class.java)
            if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) manager
            else manager.createForSubscriptionId(subscriptionId)
        } else if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }
    }
}
