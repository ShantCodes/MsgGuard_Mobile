package com.example.msgguard.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.app.TaskStackBuilder
import com.example.msgguard.R
import com.example.msgguard.data.local.ContactDirectory
import com.example.msgguard.data.local.SystemSmsStore
import com.example.msgguard.receiver.NotificationReplyReceiver
import com.example.msgguard.sms.ActiveThread
import com.example.msgguard.sms.SmsAddresses
import com.example.msgguard.ui.activities.ConversationActivity

/** Posts MsgGuard's notification for messages that are not classified as advertising. */
class SmsNotifier(private val context: Context) {
    fun notify(sender: String, content: String) {
        if (ActiveThread.matches(context, sender)) return
        val name = ContactDirectory(context).nameFor(sender).ifBlank { "New message" }
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build())
        val incoming = Person.Builder().setName(name).build()
        val history = runCatching { SystemSmsStore(context).loadConversation(sender).takeLast(6) }.getOrDefault(emptyList())
        if (history.isEmpty()) {
            style.addMessage(content, System.currentTimeMillis(), incoming)
        } else {
            history.forEach { message ->
                val author = if (message.isOutgoing()) null else incoming
                style.addMessage(message.body, message.date, author)
            }
        }
        post(notificationId(sender), name, content, sender, style)
    }

    fun notifySendFailed(recipient: String, reason: String) {
        val name = recipient.ifBlank { "Unknown recipient" }
        val title = ContactDirectory(context).nameFor(name).ifBlank { name }
        val notification = baseBuilder("Couldn't send", reason)
            .setContentIntent(openConversation(name))
            .build()
        deliver(failureId(name), notification)
    }

    fun cancel(sender: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(sender))
    }

    private fun post(
        id: Int,
        title: String,
        content: String,
        sender: String,
        style: NotificationCompat.MessagingStyle
    ) {
        val notification = baseBuilder(title, content)
            .setStyle(style)
            .setContentIntent(openConversation(sender))
            .addAction(replyAction(sender, id))
            .build()
        deliver(id, notification)
    }

    private fun baseBuilder(title: String, content: String): NotificationCompat.Builder {
        createChannel()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_msgguard_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(0xFF0B57D0.toInt())
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
    }

    private fun openConversation(sender: String): PendingIntent {
        val conversation = Intent(context, ConversationActivity::class.java)
            .putExtra(SmsAddresses.EXTRA_ADDRESS, sender)
        return TaskStackBuilder.create(context)
            .addNextIntentWithParentStack(conversation)
            .getPendingIntent(notificationId(sender), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            ?: PendingIntent.getActivity(
                context,
                notificationId(sender),
                conversation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }

    private fun replyAction(sender: String, notificationId: Int): NotificationCompat.Action {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val replyIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
            action = NotificationReplyReceiver.ACTION_REPLY
            data = Uri.parse("msgguard://reply").buildUpon().appendPath(sender).build()
        }
        val pending = PendingIntent.getBroadcast(context, notificationId xor REPLY_SALT, replyIntent, flags)
        val input = RemoteInput.Builder(NotificationReplyReceiver.KEY_REPLY).setLabel("Reply").build()
        return NotificationCompat.Action.Builder(R.drawable.ic_msgguard_notification, "Reply", pending)
            .addRemoteInput(input)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun deliver(id: Int, notification: android.app.Notification) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        runCatching { manager.notify(id, notification) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Text messages received by MsgGuard"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notificationId(sender: String): Int = SmsAddresses.key(sender).hashCode() and 0x7FFFFFFF

    private fun failureId(sender: String): Int = notificationId(sender) xor 0x5A5A5A5A

    private companion object {
        const val CHANNEL_ID = "messages"
        const val REPLY_SALT = 0x00A11
    }
}
