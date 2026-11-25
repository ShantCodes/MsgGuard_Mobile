package com.example.msgguard.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.msgguard.R
import com.example.msgguard.ui.activities.MainActivityActivity

/** Posts MsgGuard's replacement notification for messages classified as non-advertising. */
class SmsNotifier(private val context: Context) {
    fun notify(sender: String, content: String) {
        createChannel()

        val openHistoryIntent = Intent(context, MainActivityActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openHistory = PendingIntent.getActivity(
            context,
            NOTIFICATION_REQUEST_CODE,
            openHistoryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_msgguard_notification)
            .setContentTitle(sender.ifBlank { "New SMS" })
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(openHistory)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(
            (System.currentTimeMillis() and 0x7FFFFFFF).toInt(),
            notification
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Allowed SMS",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Non-advertising SMS messages classified by MsgGuard"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private companion object {
        const val CHANNEL_ID = "allowed_sms"
        const val NOTIFICATION_REQUEST_CODE = 101
    }
}
