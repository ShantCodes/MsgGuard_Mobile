package com.example.msgguard.ui.activities

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.msgguard.data.local.SmsHistoryStore
import java.text.DateFormat

/**
 * Requests the dangerous SMS permissions required before the receiver can inspect messages.
 */
class MainActivityActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissions = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(permissions, REQUEST_SMS_PERMISSIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        showHistory()
    }

    private fun showHistory() {
        val padding = dp(20)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(248, 250, 252))
        }
        content.addView(textView("MsgGuard", 28, Color.rgb(15, 23, 42), Typeface.BOLD))
        content.addView(textView("Your classified SMS history stays on this device", 15, Color.rgb(71, 85, 105)))

        val messages = SmsHistoryStore(this).load()
        if (messages.isEmpty()) {
            content.addView(textView("No messages classified yet. New SMS messages will appear here after you grant permission.", 16, Color.rgb(71, 85, 105)).apply {
                setPadding(0, dp(36), 0, 0)
            })
        } else {
            messages.forEach { message ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    setBackgroundColor(Color.WHITE)
                }
                val status = if (message.isAdvertisement) "ADVERTISEMENT" else "OTHER"
                val statusColor = if (message.isAdvertisement) Color.rgb(185, 28, 28) else Color.rgb(21, 128, 61)
                card.addView(textView(status, 12, statusColor, Typeface.BOLD))
                card.addView(textView(message.sender, 16, Color.rgb(15, 23, 42), Typeface.BOLD))
                card.addView(textView(message.content, 15, Color.rgb(51, 65, 85)))
                card.addView(textView(DateFormat.getDateTimeInstance().format(message.receivedAt), 12, Color.rgb(100, 116, 139)))
                content.addView(card, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) })
            }
        }

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun textView(text: String, size: Int, color: Int, style: Int = Typeface.NORMAL) = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        setTypeface(typeface, style)
        gravity = Gravity.START
        setLineSpacing(0f, 1.15f)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_SMS_PERMISSIONS = 100
    }
}
