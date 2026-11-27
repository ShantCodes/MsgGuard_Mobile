package com.example.msgguard.ui.activities

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
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

        val permissions = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(permissions.toTypedArray(), REQUEST_APP_PERMISSIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        showHistory()
    }

    private fun showHistory() {
        val padding = dp(24)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.WHITE)
        }

        // Header Section
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(32))
        }
        headerLayout.addView(textView("MsgGuard", 32, Color.BLACK, Typeface.BOLD))
        headerLayout.addView(textView("Your classified SMS history stays on this device", 14, Color.GRAY))
        content.addView(headerLayout)

        val messages = SmsHistoryStore(this).load()
        if (messages.isEmpty()) {
            val emptyState = TextView(this).apply {
                text = "No messages classified yet. New SMS messages will appear here after you grant permission."
                textSize = 16f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                setPadding(0, dp(48), 0, 0)
            }
            content.addView(emptyState)
        } else {
            messages.forEach { message ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                    setBackgroundColor(Color.parseColor("#F3F4F6"))
                }

                // Header Layout for message (Status + Sender)
                val messageHeader = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }

                val status = when (message.isAdvertisement) {
                    true -> "ADVERTISEMENT"
                    false -> "OTHER"
                    null -> "ERROR"
                }

                val statusColor = when (message.isAdvertisement) {
                    true -> Color.parseColor("#DC2626") // Red
                    false -> Color.parseColor("#16A34A") // Green
                    null -> Color.parseColor("#EA580C") // Orange
                }

                // Status Text
                val statusText = TextView(this).apply {
                    text = status
                    textSize = 12f
                    setTextColor(statusColor)
                    setTypeface(TypeFace.DEFAULT, TypeFace.BOLD)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                }

                val senderText = TextView(this).apply {
                    text = message.sender
                    textSize = 16f
                    setTextColor(Color.BLACK)
                    setTypeface(TypeFace.DEFAULT, TypeFace.BOLD)
                    setPadding(dp(8), 0, 0, dp(4))
                }

                messageHeader.addView(statusText)
                messageHeader.addView(senderText)
                card.addView(messageHeader)

                // Content
                val contentText = TextView(this).apply {
                    text = message.content
                    textSize = 15f
                    setTextColor(Color.parseColor("#374151")) // Dark Gray
                    setLineSpacing(0f, 1.2f)
                    setPadding(0, dp(4), 0, dp(12))
                }
                card.addView(contentText)

                // Timestamp
                val timeText = TextView(this).apply {
                    text = DateFormat.getDateTimeInstance().format(message.receivedAt)
                    textSize = 12f
                    setTextColor(Color.GRAY)
                    setTypeface(TypeFace.DEFAULT, TypeFace.ITALIC)
                }
                card.addView(timeText)

                content.addView(card, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, dp(12), 0, 0)
                })
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
        const val REQUEST_APP_PERMISSIONS = 100
    }
}
