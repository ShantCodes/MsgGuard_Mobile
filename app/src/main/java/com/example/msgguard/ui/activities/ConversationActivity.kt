package com.example.msgguard.ui.activities

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.msgguard.data.local.ContactDirectory
import com.example.msgguard.data.local.ContactEntry
import com.example.msgguard.data.local.SmsGateway
import com.example.msgguard.data.local.SmsRecord
import com.example.msgguard.data.local.SystemSmsStore
import com.example.msgguard.notifications.SmsNotifier
import com.example.msgguard.sms.ActiveThread
import com.example.msgguard.sms.DefaultSmsRole
import com.example.msgguard.sms.InboxEvents
import com.example.msgguard.sms.SmsAddresses
import com.example.msgguard.sms.SmsDatabase
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Thread detail and compose screen, styled to match the MsgGuard inbox. */
class ConversationActivity : Activity() {
    private var recipient: String = ""
    private lateinit var titleView: TextView
    private lateinit var messages: LinearLayout
    private lateinit var transcript: ScrollView
    private lateinit var recipientInput: EditText
    private lateinit var contactResults: LinearLayout
    private lateinit var composer: EditText
    private var stickToBottom = true
    private var sendInFlight = false
    private var watching = false
    private var renderedRecipient: String? = null
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var conversationGeneration = 0
    private var contactSearchGeneration = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refreshMessages() }
    private val dark by lazy { getSharedPreferences("ui_preferences", MODE_PRIVATE).getBoolean("dark_mode", false) }
    private val colors by lazy { ConversationColors(dark) }
    private val smsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) = scheduleRefresh()
    }
    private val inboxReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = scheduleRefresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recipient = savedInstanceState?.getString(STATE_ADDRESS) ?: SmsAddresses.fromIntent(intent)
        buildScreen()
        if (savedInstanceState != null) {
            if (recipient.isBlank()) recipientInput.setText(savedInstanceState.getString(STATE_RECIPIENT).orEmpty())
            composer.setText(savedInstanceState.getString(STATE_DRAFT).orEmpty())
        } else {
            val body = SmsAddresses.bodyFromIntent(intent)
            if (body.isNotBlank()) composer.setText(body)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recipient = SmsAddresses.fromIntent(intent)
        val body = SmsAddresses.bodyFromIntent(intent)
        if (body.isNotBlank() && ::composer.isInitialized && composer.text.isNullOrBlank()) composer.setText(body)
        showCurrentThread()
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
        showCurrentThread()
    }

    override fun onPause() {
        val open = ActiveThread.address
        if (open != null && (recipient.isBlank() || SmsAddresses.same(this, open, recipient))) {
            ActiveThread.address = null
        }
        super.onPause()
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
        uiScope.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ADDRESS, recipient)
        if (::recipientInput.isInitialized) outState.putString(STATE_RECIPIENT, recipientInput.text.toString())
        if (::composer.isInitialized) outState.putString(STATE_DRAFT, composer.text.toString())
    }

    private fun showCurrentThread() {
        ActiveThread.address = recipient.takeIf { it.isNotBlank() }
        if (recipient.isNotBlank()) SmsNotifier(this).cancel(recipient)
        if (::messages.isInitialized) refreshMessages()
    }

    private fun scheduleRefresh() {
        if (isFinishing) return
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, 120)
    }

    private fun buildScreen() {
        configureSystemBars()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
            setBackgroundColor(colors.background)
        }
        root.addView(createHeader())
        recipientInput = EditText(this).apply {
            hint = "Search contacts or enter a number"
            setText(recipient)
            textSize = 16f
            setTextColor(colors.text)
            setHintTextColor(colors.muted)
            inputType = InputType.TYPE_CLASS_TEXT
            background = rounded(colors.surfaceVariant, 16)
            setPadding(dp(16), 0, dp(16), 0)
            isSingleLine = true
            visibility = if (recipient.isBlank()) View.VISIBLE else View.GONE
        }
        root.addView(recipientInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(16) })
        contactResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(contactResults, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        recipientInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(value: Editable?) = searchContacts(value?.toString().orEmpty())
        })
        transcript = ScrollView(this).apply { isFillViewport = true }
        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, dp(10))
        }
        transcript.addView(messages, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        transcript.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val child = transcript.getChildAt(0) ?: return@setOnScrollChangeListener
            stickToBottom = scrollY + transcript.height >= child.height - dp(80)
        }
        root.addView(transcript, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(createComposer(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    private fun createHeader() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        addView(ImageButton(this@ConversationActivity).apply {
            contentDescription = "Back"
            setImageResource(android.R.drawable.ic_media_previous)
            imageTintList = android.content.res.ColorStateList.valueOf(colors.text)
            background = rounded(colors.surfaceVariant, 20)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        titleView = TextView(this@ConversationActivity).apply {
            text = headerTitle()
            textSize = 20f
            setTextColor(colors.text)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(14), 0, 0, 0)
        }
        addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun createComposer() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        background = rounded(colors.surface, 24, colors.outline)
        setPadding(dp(14), dp(5), dp(6), dp(5))
        composer = EditText(this@ConversationActivity).apply {
            hint = "Text message"
            textSize = 16f
            setTextColor(colors.text)
            setHintTextColor(colors.muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = null
            maxLines = 4
        }
        addView(composer, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageButton(this@ConversationActivity).apply {
            contentDescription = "Send message"
            setImageResource(android.R.drawable.ic_menu_send)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = rounded(colors.brand, 20)
            setOnClickListener { sendMessage() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
    }

    private fun refreshMessages() {
        if (!::messages.isInitialized) return
        val followTail = stickToBottom
        val requestedRecipient = recipient
        val generation = ++conversationGeneration
        recipientInput.visibility = if (recipient.isBlank()) View.VISIBLE else View.GONE
        titleView.text = headerTitle()
        if (requestedRecipient.isBlank()) {
            renderedRecipient = null
            messages.removeAllViews()
            messages.addView(emptyLabel("Add a phone number to start a conversation."))
            return
        }
        val initialLoad = renderedRecipient != requestedRecipient
        if (initialLoad) {
            messages.removeAllViews()
            messages.addView(emptyLabel("Loading messages…"))
        }
        uiScope.launch(Dispatchers.IO) {
            val store = SystemSmsStore(this@ConversationActivity)
            runCatching { store.markConversationRead(requestedRecipient) }
            val thread = runCatching { store.loadConversation(requestedRecipient) }.getOrElse { emptyList() }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed || generation != conversationGeneration || recipient != requestedRecipient) return@withContext
                messages.removeAllViews()
                renderedRecipient = requestedRecipient
                val visible = if (thread.size > MAX_VISIBLE_MESSAGES) thread.takeLast(MAX_VISIBLE_MESSAGES) else thread
                if (thread.size > visible.size) messages.addView(emptyLabel("Showing the latest $MAX_VISIBLE_MESSAGES messages."))
                if (visible.isEmpty()) messages.addView(emptyLabel("Start the conversation with a message below."))
                visible.forEach { message ->
                    messages.addView(messageBubble(message), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        gravity = if (message.isOutgoing()) Gravity.END else Gravity.START
                        topMargin = dp(7)
                    })
                }
                if (followTail) transcript.post { transcript.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun searchContacts(query: String) {
        val generation = ++contactSearchGeneration
        if (recipient.isNotBlank() || query.trim().isBlank()) {
            if (::contactResults.isInitialized) contactResults.removeAllViews()
            return
        }
        uiScope.launch(Dispatchers.IO) {
            val results = ContactDirectory(this@ConversationActivity).search(query)
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed || generation != contactSearchGeneration || recipient.isNotBlank()) return@withContext
                showContactResults(results)
            }
        }
    }

    private fun showContactResults(results: List<ContactEntry>) {
        contactResults.removeAllViews()
        results.forEach { entry ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(colors.surface, 16, colors.outline)
                setOnClickListener {
                    recipient = entry.number
                    recipientInput.setText(entry.number)
                    recipientInput.visibility = View.GONE
                    contactResults.removeAllViews()
                    titleView.text = entry.name
                    stickToBottom = true
                    refreshMessages()
                }
            }
            row.addView(TextView(this).apply {
                text = entry.name.first().uppercaseChar().toString()
                gravity = Gravity.CENTER
                textSize = 15f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(colors.brand)
                background = rounded(colors.surfaceVariant, 16)
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(this@ConversationActivity).apply { text = entry.name; textSize = 16f; setTextColor(colors.text); setTypeface(Typeface.DEFAULT, Typeface.BOLD) })
                addView(TextView(this@ConversationActivity).apply { text = entry.number; textSize = 13f; setTextColor(colors.muted); setPadding(0, dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            contactResults.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
    }

    private fun messageBubble(message: SmsRecord) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(8))
        val outgoing = message.isOutgoing()
        val bubbleMax = (resources.displayMetrics.widthPixels * 0.74f).toInt()
        background = rounded(if (outgoing) colors.brand else colors.surfaceVariant, 18)
        addView(TextView(this@ConversationActivity).apply {
            text = message.body
            textSize = 16f
            maxWidth = bubbleMax
            setTextColor(if (outgoing) Color.WHITE else colors.text)
        })
        addView(TextView(this@ConversationActivity).apply {
            text = caption(message)
            textSize = 11f
            maxWidth = bubbleMax
            setTextColor(if (message.type == Telephony.Sms.MESSAGE_TYPE_FAILED) Color.parseColor("#FFD18C") else if (outgoing) Color.parseColor("#DDEBFF") else colors.muted)
            gravity = Gravity.END
            setPadding(0, dp(4), 0, 0)
        })
        setOnLongClickListener {
            showMessageActions(message)
            true
        }
    }

    private fun showMessageActions(message: SmsRecord) {
        MaterialAlertDialogBuilder(this)
            .setItems(arrayOf("Copy", "Delete")) { _, which ->
                if (which == 0) {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SMS", message.body))
                    Toast.makeText(this, "Message copied", Toast.LENGTH_SHORT).show()
                } else {
                    confirmDelete(message)
                }
            }
            .show()
    }

    private fun confirmDelete(message: SmsRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete message")
            .setMessage("Delete this message?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val removed = runCatching { SystemSmsStore(this).deleteMessage(message.id) }.getOrDefault(false)
                if (!removed) {
                    Toast.makeText(this, "MsgGuard needs to be the default SMS app to delete messages.", Toast.LENGTH_LONG).show()
                } else {
                    refreshMessages()
                }
            }
            .show()
    }

    private fun caption(message: SmsRecord): String {
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(message.date)
        return when {
            message.type == Telephony.Sms.MESSAGE_TYPE_FAILED -> "Not sent"
            message.isOutgoing() && message.status == Telephony.Sms.STATUS_COMPLETE -> "Delivered · $time"
            else -> time
        }
    }

    private fun headerTitle(): String = when {
        recipient.isBlank() -> "New message"
        else -> ContactDirectory(this).nameFor(recipient)
    }

    private fun emptyLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(colors.muted)
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(56), dp(20), dp(20))
    }

    private fun sendMessage() {
        if (sendInFlight) return
        val destination = SmsAddresses.forSending(recipientInput.text.toString())
        val text = composer.text.toString().trim()
        if (destination.isBlank() || text.isBlank()) {
            Toast.makeText(this, "Add a recipient and a message.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!DefaultSmsRole.isDefault(this)) {
            Toast.makeText(this, "Set MsgGuard as the default SMS app before sending.", Toast.LENGTH_LONG).show()
            val started = DefaultSmsRole.request(this, REQUEST_DEFAULT_SMS_ROLE)
            if (!started) Toast.makeText(this, "This device can't switch the default SMS app.", Toast.LENGTH_LONG).show()
            return
        }
        sendInFlight = true
        SmsGateway(applicationContext).send(destination, text)
            .onSuccess {
                recipient = destination
                recipientInput.setText(destination)
                composer.text.clear()
                stickToBottom = true
                ActiveThread.address = destination
                refreshMessages()
            }
            .onFailure { error ->
                Toast.makeText(this, error.message ?: "Couldn't send the message.", Toast.LENGTH_LONG).show()
            }
        sendInFlight = false
    }

    private fun configureSystemBars() {
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background
        val light = !dark
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && light) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class ConversationColors(val dark: Boolean) {
        val background = Color.parseColor(if (dark) "#10131A" else "#F8F9FF")
        val surface = Color.parseColor(if (dark) "#1C2029" else "#FFFFFF")
        val surfaceVariant = Color.parseColor(if (dark) "#2A303B" else "#EDF1F9")
        val text = Color.parseColor(if (dark) "#E5EAF5" else "#1A1B20")
        val muted = Color.parseColor(if (dark) "#BFC5D2" else "#5B606C")
        val outline = Color.parseColor(if (dark) "#323946" else "#E2E6EE")
        val brand = Color.parseColor(if (dark) "#A8C7FA" else "#0B57D0")
    }

    private companion object {
        const val REQUEST_DEFAULT_SMS_ROLE = 101
        const val MAX_VISIBLE_MESSAGES = 400
        const val STATE_ADDRESS = "address_state"
        const val STATE_RECIPIENT = "recipient_state"
        const val STATE_DRAFT = "draft_state"
    }
}
