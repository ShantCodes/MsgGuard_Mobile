package com.example.msgguard.ui.activities

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.msgguard.data.local.SmsHistoryStore
import com.example.msgguard.data.local.StoredSms
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.text.DateFormat
import java.util.Calendar

/** A private inbox for SMS messages that have been checked on this device. */
class MainActivityActivity : Activity() {
    private var selectedFilter = MessageFilter.ALL
    private lateinit var palette: Palette

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRequiredPermissions()
    }

    override fun onResume() {
        super.onResume()
        renderInbox()
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions += Manifest.permission.POST_NOTIFICATIONS
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(permissions.toTypedArray(), REQUEST_APP_PERMISSIONS)
        }
    }

    private fun renderInbox() {
        palette = Palette(isDarkMode())
        configureSystemBars()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(30))
            background = rounded(palette.background, 0)
        }
        content.addView(createTopBar())
        content.addView(createTitleRow(), marginParams(top = 28))
        content.addView(createFilterBar(), marginParams(top = 18))

        val allMessages = SmsHistoryStore(this).load()
        val filtered = allMessages.filter { message ->
            when (selectedFilter) {
                MessageFilter.ALL -> true
                MessageFilter.ADVERTISEMENTS -> message.isAdvertisement == true
                MessageFilter.KEEP -> message.isAdvertisement == false
            }
        }
        content.addView(createSectionLabel(filtered.size), marginParams(top = 26, bottom = 4))
        if (filtered.isEmpty()) content.addView(createEmptyState(allMessages.isNotEmpty()), marginParams(top = 12))
        else filtered.forEachIndexed { index, message -> content.addView(createMessageCard(message), marginParams(top = if (index == 0) 8 else 12)) }

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(palette.background)
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    private fun createTopBar(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        addView(TextView(this@MainActivityActivity).apply {
            text = "M"; gravity = Gravity.CENTER; textSize = 19f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(Color.WHITE); background = rounded(palette.brand, 18)
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        addView(label("MsgGuard", 20f, palette.text, Typeface.BOLD).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageButton(this@MainActivityActivity).apply {
            contentDescription = "Open settings"; setImageResource(android.R.drawable.ic_menu_manage)
            imageTintList = ColorStateList.valueOf(palette.textSecondary); background = rounded(palette.surfaceVariant, 18)
            setPadding(dp(10), dp(10), dp(10), dp(10)); setOnClickListener { showSettings() }
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
    }

    private fun createTitleRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label("Messages", 32f, palette.text, Typeface.BOLD))
        addView(label("Your personal, spam-aware inbox", 15f, palette.textSecondary).apply { setPadding(0, dp(5), 0, 0) })
    }

    private fun createFilterBar(): View = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(this@MainActivityActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            MessageFilter.entries.forEachIndexed { index, filter ->
                addView(createFilterChip(filter), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { if (index > 0) marginStart = dp(8) })
            }
        })
    }

    private fun createFilterChip(filter: MessageFilter): TextView {
        val active = filter == selectedFilter
        return label(filter.title, 14f, if (active) Color.WHITE else palette.textSecondary, Typeface.BOLD).apply {
            gravity = Gravity.CENTER; minWidth = dp(74); setPadding(dp(16), 0, dp(16), 0)
            background = rounded(if (active) palette.brand else palette.surfaceVariant, 20)
            contentDescription = "Show ${filter.title.lowercase()} messages"
            setOnClickListener { selectedFilter = filter; renderInbox() }
        }
    }

    private fun createSectionLabel(count: Int) = label(
        when { selectedFilter != MessageFilter.ALL -> "${selectedFilter.title} · $count"; count == 1 -> "1 message"; else -> "$count messages" },
        13f, palette.textSecondary, Typeface.BOLD
    ).apply { letterSpacing = .05f }

    private fun createMessageCard(message: StoredSms): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP; setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded(palette.surface, 22, palette.outline); elevation = dp(1).toFloat()
        addView(TextView(this@MainActivityActivity).apply {
            text = initial(message.sender); gravity = Gravity.CENTER; textSize = 16f; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(statusColor(message)); background = rounded(statusBackground(message), 18)
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val details = LinearLayout(this@MainActivityActivity).apply { orientation = LinearLayout.VERTICAL }
        details.addView(LinearLayout(this@MainActivityActivity).apply {
            gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL
            addView(label(message.sender, 16f, palette.text, Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(formatTime(message.receivedAt), 12f, palette.textSecondary))
        })
        details.addView(label(message.content, 14f, palette.textSecondary).apply { maxLines = 2; setLineSpacing(dp(2).toFloat(), 1f); setPadding(0, dp(4), 0, dp(10)) })
        details.addView(createStatusPill(message))
        addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
    }

    private fun createStatusPill(message: StoredSms): TextView {
        val (text, color) = when (message.isAdvertisement) {
            true -> "Advertisement filtered" to palette.warning
            false -> "Safe message" to palette.success
            null -> "Needs review" to palette.warning
        }
        return label(text, 12f, color, Typeface.BOLD).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(9), dp(5), dp(9), dp(5))
            background = rounded(if (message.isAdvertisement == false) palette.successContainer else palette.warningContainer, 12)
        }
    }

    private fun createEmptyState(hasAnyMessages: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(34), dp(24), dp(34))
        background = rounded(palette.surface, 24, palette.outline)
        addView(TextView(this@MainActivityActivity).apply {
            text = if (hasAnyMessages) "⌕" else "✦"; textSize = 32f; gravity = Gravity.CENTER; setTextColor(palette.brand); background = rounded(palette.brandContainer, 22)
        }, LinearLayout.LayoutParams(dp(54), dp(54)))
        addView(label(if (hasAnyMessages) "Nothing in this view" else "Your inbox is ready", 18f, palette.text, Typeface.BOLD).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(6)) })
        addView(label(if (hasAnyMessages) "Try another filter to see your classified messages." else "New messages will appear here after MsgGuard checks them.", 14f, palette.textSecondary).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER })
    }

    private fun showSettings() {
        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
        settings.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL
            addView(LinearLayout(this@MainActivityActivity).apply {
                orientation = LinearLayout.VERTICAL; addView(label("Dark theme", 17f, palette.text, Typeface.BOLD))
                addView(label("Use a darker, easier-on-the-eyes inbox", 13f, palette.textSecondary).apply { setPadding(0, dp(3), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(MaterialSwitch(this@MainActivityActivity).apply {
                isChecked = isDarkMode(); contentDescription = "Turn dark theme on or off"
                setOnCheckedChangeListener { _, checked -> getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().putBoolean(DARK_MODE_KEY, checked).apply(); renderInbox() }
            })
        })
        settings.addView(label("MsgGuard analyzes messages on this device and keeps your history private.", 13f, palette.textSecondary).apply { setPadding(0, dp(24), 0, dp(4)) })
        MaterialAlertDialogBuilder(this).setTitle("Settings").setView(settings).setPositiveButton("Done", null).show()
    }

    private fun configureSystemBars() {
        window.statusBarColor = palette.background; window.navigationBarColor = palette.background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION") window.decorView.systemUiVisibility = if (palette.isLight) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && palette.isLight) {
            @Suppress("DEPRECATION") window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun isDarkMode() = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).getBoolean(DARK_MODE_KEY, false)
    private fun statusColor(message: StoredSms) = if (message.isAdvertisement == false) palette.success else palette.warning
    private fun statusBackground(message: StoredSms) = if (message.isAdvertisement == false) palette.successContainer else palette.warningContainer
    private fun initial(sender: String) = sender.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    private fun formatTime(time: Long): String {
        val today = Calendar.getInstance(); val date = Calendar.getInstance().apply { timeInMillis = time }
        return if (today.get(Calendar.YEAR) == date.get(Calendar.YEAR) && today.get(Calendar.DAY_OF_YEAR) == date.get(Calendar.DAY_OF_YEAR)) DateFormat.getTimeInstance(DateFormat.SHORT).format(time) else DateFormat.getDateInstance(DateFormat.MEDIUM).format(time)
    }
    private fun label(text: String, size: Float, color: Int, style: Int = Typeface.NORMAL) = TextView(this).apply { this.text = text; textSize = size; setTextColor(color); setTypeface(Typeface.DEFAULT, style) }
    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); strokeColor?.let { setStroke(dp(1), it) } }
    private fun marginParams(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(top), 0, dp(bottom)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private enum class MessageFilter(val title: String) { ALL("All"), KEEP("Safe"), ADVERTISEMENTS("Filtered") }
    private data class Palette(val dark: Boolean) {
        val isLight = !dark
        val background = Color.parseColor(if (dark) "#10131A" else "#F8F9FF")
        val surface = Color.parseColor(if (dark) "#1C2029" else "#FFFFFF")
        val surfaceVariant = Color.parseColor(if (dark) "#2A303B" else "#EDF1F9")
        val text = Color.parseColor(if (dark) "#E5EAF5" else "#1A1B20")
        val textSecondary = Color.parseColor(if (dark) "#BFC5D2" else "#5B606C")
        val outline = Color.parseColor(if (dark) "#323946" else "#E2E6EE")
        val brand = Color.parseColor(if (dark) "#A8C7FA" else "#0B57D0")
        val brandContainer = Color.parseColor(if (dark) "#273A58" else "#D3E3FD")
        val success = Color.parseColor(if (dark) "#8BE0B2" else "#137333")
        val successContainer = Color.parseColor(if (dark) "#183B2B" else "#D7F5E2")
        val warning = Color.parseColor(if (dark) "#FFD18C" else "#A84300")
        val warningContainer = Color.parseColor(if (dark) "#4A3117" else "#FFF0DB")
    }
    private companion object { const val REQUEST_APP_PERMISSIONS = 100; const val PREFERENCES_NAME = "ui_preferences"; const val DARK_MODE_KEY = "dark_mode" }
}
