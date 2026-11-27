package com.example.msgguard.ui.activities

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.msgguard.data.local.ContactDirectory
import com.example.msgguard.data.local.SmsHistoryStore
import com.example.msgguard.data.local.StoredSms
import com.example.msgguard.data.local.SystemSmsStore
import com.example.msgguard.sms.DefaultSmsRole
import com.example.msgguard.sms.InboxEvents
import com.example.msgguard.sms.SmsAddresses
import com.example.msgguard.sms.SmsDatabase
import com.example.msgguard.worker.BackfillClassifyWorker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.text.DateFormat
import java.util.Calendar

/** A private inbox for SMS messages that have been checked on this device. */
class MainActivityActivity : Activity() {
    private var selectedFilter = MessageFilter.ALL
    private lateinit var palette: Palette
    private lateinit var scrollView: ScrollView
    private var scrollY = 0
    private var resetScroll = false
    private var watching = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { renderInbox() }
    private val smsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) = scheduleRefresh()
    }
    private val inboxReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = scheduleRefresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString(STATE_FILTER)?.let { name ->
            selectedFilter = MessageFilter.entries.firstOrNull { it.name == name } ?: MessageFilter.ALL
        }
        scrollY = savedInstanceState?.getInt(STATE_SCROLL) ?: 0
        if (savedInstanceState == null) handleExternalIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (!watching) {
            SmsDatabase.register(this, smsObserver)
            InboxEvents.register(this, inboxReceiver)
            watching = true
        }
    }

    override fun onResume() {
        super.onResume()
        renderInbox()
        if (!isDefaultSmsApp()) {
            maybeRequestDefaultRole()
            return
        }
        maybeRequestExtraPermissions()
        maybeBackfill()
    }

    override fun onStop() {
        if (watching) {
            SmsDatabase.unregister(this, smsObserver)
            runCatching { unregisterReceiver(inboxReceiver) }
            watching = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(refreshRunnable)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FILTER, selectedFilter.name)
        outState.putInt(STATE_SCROLL, if (::scrollView.isInitialized) scrollView.scrollY else scrollY)
    }

    private fun scheduleRefresh() {
        if (isFinishing) return
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, 150)
    }

    private fun handleExternalIntent(intent: Intent?) {
        val scheme = intent?.data?.scheme?.lowercase()
        val smsScheme = scheme == "sms" || scheme == "smsto" || scheme == "mms" || scheme == "mmsto"
        if (intent?.action == Intent.ACTION_SENDTO || smsScheme) {
            openConversation(SmsAddresses.fromIntent(intent), SmsAddresses.bodyFromIntent(intent))
        }
    }

    private fun maybeRequestDefaultRole() {
        val preferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (preferences.getBoolean(ASKED_DEFAULT_ROLE, false)) return
        if (DefaultSmsRole.request(this, REQUEST_DEFAULT_SMS_ROLE)) {
            preferences.edit().putBoolean(ASKED_DEFAULT_ROLE, true).apply()
        }
    }

    private fun maybeRequestExtraPermissions() {
        val preferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (preferences.getBoolean(ASKED_EXTRA_PERMISSIONS, false)) return
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.READ_CONTACTS
        }
        listOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_MMS,
            Manifest.permission.RECEIVE_WAP_PUSH
        ).forEach { permission ->
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) needed += permission
        }
        if (needed.isEmpty()) return
        preferences.edit().putBoolean(ASKED_EXTRA_PERMISSIONS, true).apply()
        requestPermissions(needed.toTypedArray(), REQUEST_APP_PERMISSIONS)
    }

    private fun maybeBackfill() {
        if (BackfillClassifyWorker.isDone(this)) return
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
        WorkManager.getInstance(this).enqueueUniqueWork(
            BackfillClassifyWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BackfillClassifyWorker>().build()
        )
    }

    private fun renderInbox() {
        val y = if (resetScroll) 0 else if (::scrollView.isInitialized) scrollView.scrollY else scrollY
        resetScroll = false
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

        val classifiedMessages = SmsHistoryStore(this).load()
        val allMessages = loadInboxMessages(classifiedMessages)
        val filtered = allMessages.filter { message ->
            when (selectedFilter) {
                MessageFilter.ALL -> true
                MessageFilter.ADVERTISEMENTS -> message.isAdvertisement == true
                MessageFilter.KEEP -> message.isAdvertisement == false
            }
        }
        content.addView(createSectionLabel(filtered.size), marginParams(top = 26, bottom = 4))
        if (filtered.isEmpty()) content.addView(createEmptyState(allMessages.isNotEmpty()), marginParams(top = 12))
        else filtered.forEachIndexed { index, message ->
            content.addView(createMessageCard(message), marginParams(top = if (index == 0) 8 else 12))
        }

        scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(palette.background)
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        scrollY = y
        setContentView(scrollView)
        if (y > 0) scrollView.post { scrollView.scrollTo(0, y) }
    }

    private fun createTopBar(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        addView(TextView(this@MainActivityActivity).apply {
            text = "M"
            gravity = Gravity.CENTER
            textSize = 19f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(palette.brand, 18)
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        addView(label("MsgGuard", 20f, palette.text, Typeface.BOLD).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageButton(this@MainActivityActivity).apply {
            contentDescription = "New message"
            setImageResource(android.R.drawable.ic_input_add)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = rounded(palette.brand, 18)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { openConversation("") }
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(8) })
        addView(ImageButton(this@MainActivityActivity).apply {
            contentDescription = "Open settings"
            setImageResource(android.R.drawable.ic_menu_manage)
            imageTintList = ColorStateList.valueOf(palette.textSecondary)
            background = rounded(palette.surfaceVariant, 18)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { showSettings() }
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
            gravity = Gravity.CENTER
            minWidth = dp(74)
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(if (active) palette.brand else palette.surfaceVariant, 20)
            contentDescription = "Show ${filter.title.lowercase()} messages"
            setOnClickListener {
                selectedFilter = filter
                resetScroll = true
                renderInbox()
            }
        }
    }

    private fun createSectionLabel(count: Int) = label(
        when {
            selectedFilter != MessageFilter.ALL -> "${selectedFilter.title} · $count"
            count == 1 -> "1 message"
            else -> "$count messages"
        },
        13f, palette.textSecondary, Typeface.BOLD
    ).apply { letterSpacing = .05f }

    private fun createMessageCard(message: InboxItem): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded(palette.surface, 22, palette.outline)
        elevation = dp(1).toFloat()
        setOnClickListener { openConversation(message.address) }
        setOnLongClickListener {
            confirmDeleteThread(message)
            true
        }
        addView(TextView(this@MainActivityActivity).apply {
            text = initial(message.title)
            gravity = Gravity.CENTER
            textSize = 16f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(statusColor(message.isAdvertisement))
            background = rounded(statusBackground(message.isAdvertisement), 18)
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val details = LinearLayout(this@MainActivityActivity).apply { orientation = LinearLayout.VERTICAL }
        details.addView(LinearLayout(this@MainActivityActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(label(message.title, 16f, palette.text, Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(formatTime(message.receivedAt), 12f, if (message.unread > 0) palette.brand else palette.textSecondary, if (message.unread > 0) Typeface.BOLD else Typeface.NORMAL))
        })
        details.addView(label(message.preview, 14f, if (message.unread > 0) palette.text else palette.textSecondary, if (message.unread > 0) Typeface.BOLD else Typeface.NORMAL).apply {
            maxLines = 2
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(4), 0, dp(10))
        })
        details.addView(createStatusPill(message.isAdvertisement))
        addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
    }

    private fun confirmDeleteThread(message: InboxItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete conversation")
            .setMessage("Delete all messages with ${message.title}?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val removed = runCatching { SystemSmsStore(this).deleteThread(message.address) }.getOrDefault(false)
                if (!removed) {
                    Toast.makeText(this, "MsgGuard needs to be the default SMS app to delete messages.", Toast.LENGTH_LONG).show()
                } else {
                    renderInbox()
                }
            }
            .show()
    }

    private fun createStatusPill(isAdvertisement: Boolean?): TextView {
        val (text, color) = when (isAdvertisement) {
            true -> "Advertisement filtered" to palette.warning
            false -> "Safe message" to palette.success
            null -> "SMS conversation" to palette.brand
        }
        return label(text, 12f, color, Typeface.BOLD).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = rounded(statusBackground(isAdvertisement), 12)
        }
    }

    private fun createEmptyState(hasAnyMessages: Boolean): View {
        val needsRole = !isDefaultSmsApp()
        val denied = checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED
        val headline = when {
            hasAnyMessages -> "Nothing in this view"
            denied && !needsRole -> "SMS access is off"
            else -> "Your inbox is ready"
        }
        val detail = when {
            needsRole && !hasAnyMessages -> "Tap here and set MsgGuard as the default SMS app to send and receive."
            denied && !hasAnyMessages -> "Tap here to allow SMS access."
            hasAnyMessages -> "Try another filter to see your classified messages."
            else -> "New messages will appear here after MsgGuard checks them."
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(34), dp(24), dp(34))
            background = rounded(palette.surface, 24, palette.outline)
            addView(TextView(this@MainActivityActivity).apply {
                text = if (hasAnyMessages) "⌕" else "✦"
                textSize = 32f
                gravity = Gravity.CENTER
                setTextColor(palette.brand)
                background = rounded(palette.brandContainer, 22)
            }, LinearLayout.LayoutParams(dp(54), dp(54)))
            addView(label(headline, 18f, palette.text, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(6))
            })
            addView(label(detail, 14f, palette.textSecondary).apply {
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            })
            if ((needsRole || denied) && !hasAnyMessages) {
                setOnClickListener {
                    if (needsRole) requestDefaultSmsRole()
                    else {
                        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit().putBoolean(ASKED_EXTRA_PERMISSIONS, false).apply()
                        maybeRequestExtraPermissions()
                    }
                }
            }
        }
    }

    private fun showSettings() {
        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
        settings.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(LinearLayout(this@MainActivityActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Dark theme", 17f, palette.text, Typeface.BOLD))
                addView(label("Use a darker, easier-on-the-eyes inbox", 13f, palette.textSecondary).apply { setPadding(0, dp(3), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(MaterialSwitch(this@MainActivityActivity).apply {
                isChecked = isDarkMode()
                contentDescription = "Turn dark theme on or off"
                setOnCheckedChangeListener { _, checked ->
                    getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().putBoolean(DARK_MODE_KEY, checked).apply()
                    renderInbox()
                }
            })
        })
        settings.addView(label(
            if (isDefaultSmsApp()) "MsgGuard is your default SMS app" else "Make MsgGuard your default SMS app",
            15f, palette.brand, Typeface.BOLD
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(26), dp(2), dp(12))
            setOnClickListener { requestDefaultSmsRole() }
        })
        settings.addView(label("MsgGuard analyzes messages on this device and keeps your history private.", 13f, palette.textSecondary).apply { setPadding(0, dp(24), 0, dp(4)) })
        MaterialAlertDialogBuilder(this).setTitle("Settings").setView(settings).setPositiveButton("Done", null).show()
    }

    private fun configureSystemBars() {
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (palette.isLight) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && palette.isLight) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun isDarkMode() = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).getBoolean(DARK_MODE_KEY, false)
    private fun isDefaultSmsApp() = DefaultSmsRole.isDefault(this)

    private fun requestDefaultSmsRole() {
        if (isDefaultSmsApp()) return
        val started = DefaultSmsRole.request(this, REQUEST_DEFAULT_SMS_ROLE)
        if (!started) Toast.makeText(this, "This device can't switch the default SMS app.", Toast.LENGTH_LONG).show()
    }

    private fun openConversation(address: String, body: String = "") = startActivity(
        Intent(this, ConversationActivity::class.java)
            .putExtra(SmsAddresses.EXTRA_ADDRESS, address)
            .putExtra(SmsAddresses.EXTRA_BODY, body)
    )

    private fun loadInboxMessages(classified: List<StoredSms>): List<InboxItem> {
        val directory = ContactDirectory(this)
        return runCatching {
            SystemSmsStore(this).loadThreads().map { thread ->
                val incoming = thread.lastIncoming
                val result = incoming?.let { message ->
                    classified.firstOrNull {
                        it.content.trim() == message.body.trim() && SmsAddresses.same(this, it.sender, message.address)
                    }
                }
                InboxItem(
                    address = thread.address,
                    title = directory.nameFor(thread.address),
                    preview = thread.preview,
                    receivedAt = thread.date,
                    isAdvertisement = result?.isAdvertisement,
                    unread = thread.unreadCount
                )
            }
        }.getOrElse {
            classified.map { message ->
                InboxItem(
                    address = message.sender,
                    title = directory.nameFor(message.sender),
                    preview = message.content,
                    receivedAt = message.receivedAt,
                    isAdvertisement = message.isAdvertisement,
                    unread = 0
                )
            }
        }
    }

    private fun statusColor(isAdvertisement: Boolean?) = when (isAdvertisement) {
        false -> palette.success
        true -> palette.warning
        null -> palette.brand
    }

    private fun statusBackground(isAdvertisement: Boolean?) = when (isAdvertisement) {
        false -> palette.successContainer
        true -> palette.warningContainer
        null -> palette.brandContainer
    }

    private fun initial(sender: String) = sender.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    private fun formatTime(time: Long): String {
        val today = Calendar.getInstance()
        val date = Calendar.getInstance().apply { timeInMillis = time }
        return if (today.get(Calendar.YEAR) == date.get(Calendar.YEAR) && today.get(Calendar.DAY_OF_YEAR) == date.get(Calendar.DAY_OF_YEAR)) {
            DateFormat.getTimeInstance(DateFormat.SHORT).format(time)
        } else {
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(time)
        }
    }

    private fun label(text: String, size: Float, color: Int, style: Int = Typeface.NORMAL) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        setTypeface(Typeface.DEFAULT, style)
    }

    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        strokeColor?.let { setStroke(dp(1), it) }
    }

    private fun marginParams(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, dp(top), 0, dp(bottom)) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class InboxItem(
        val address: String,
        val title: String,
        val preview: String,
        val receivedAt: Long,
        val isAdvertisement: Boolean?,
        val unread: Int
    )

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

    private companion object {
        const val REQUEST_APP_PERMISSIONS = 100
        const val REQUEST_DEFAULT_SMS_ROLE = 101
        const val PREFERENCES_NAME = "ui_preferences"
        const val DARK_MODE_KEY = "dark_mode"
        const val ASKED_DEFAULT_ROLE = "asked_default_sms_role"
        const val ASKED_EXTRA_PERMISSIONS = "asked_extra_permissions"
        const val STATE_FILTER = "filter"
        const val STATE_SCROLL = "scroll"
    }
}
