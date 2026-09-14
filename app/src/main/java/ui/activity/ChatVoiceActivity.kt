package ui.activity

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.preference.PreferenceManager
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.inputmethod.EditorInfo
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import chat.ArenaLinkClient
import chat.ChatDiagnostics
import android.content.Intent
import android.app.Activity
import androidx.appcompat.app.AlertDialog
import chat.LinkChannel
import chat.LinkMessage
import chat.LinkProfile
import com.libopenmw.openmw.R
import constants.Constants
import java.io.File
import file.BuildManifest
import server.ServerConfig
import server.ServerController
import server.ServerRuntime
import voice.VoicePermissions

/** Launcher-side ArenaLink chat and native in-game voice controls. */
class ChatVoiceActivity : AppCompatActivity(), ArenaLinkClient.Listener {
    private lateinit var nameEdit: EditText
    private lateinit var secretEdit: EditText
    private lateinit var codeMode: CheckBox
    private lateinit var connectButton: Button
    private lateinit var status: TextView
    private lateinit var channelSpinner: Spinner
    private lateinit var historyScroll: ScrollView
    private lateinit var history: TextView
    private lateinit var voiceToggleMode: CheckBox
    private lateinit var messageEdit: EditText
    private lateinit var sendButton: Button
    private lateinit var voiceEnabled: ToggleButton
    private lateinit var pttKey: EditText
    private lateinit var voiceStatus: TextView

    private val diagnostics by lazy {
        ChatDiagnostics(File(getExternalFilesDir(null) ?: filesDir, "Chat.log"), File(filesDir, "Chat.log"))
    }
    private val client by lazy { ArenaLinkClient(diagnostics) }
    private var exportedChatLog: String? = null
    private val channels = ArrayList<LinkChannel>()
    private var channelId = 0
    // U035: the rendered history is kept as a model, not as ever growing text.
    // Appending to a TextView forever is what made a long session unusable -
    // the outer ScrollView grew without bound and the compose box walked off
    // the bottom of the screen.
    private val shown = ArrayList<LinkMessage>()
    private var ownName: String = ""
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.chat_voice_title)
        client.listener = this
        setContentView(buildView())
        loadSettings()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun buildView(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(24))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun heading(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(8))
        }

        root.addView(heading(getString(R.string.chat_title)))
        root.addView(TextView(this).apply { text = getString(R.string.chat_login_hint) })

        nameEdit = EditText(this).apply {
            hint = getString(R.string.chat_character_name)
            isSingleLine = true
            maxLines = 1
        }
        secretEdit = EditText(this).apply {
            hint = getString(R.string.chat_password)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        codeMode = CheckBox(this).apply { text = getString(R.string.chat_use_code); visibility = View.GONE }
        connectButton = Button(this).apply { text = getString(R.string.chat_login_button) }
        status = TextView(this).apply { setPadding(0, dp(5), 0, dp(8)) }
        root.addView(nameEdit)
        root.addView(secretEdit)
        root.addView(codeMode)
        root.addView(connectButton)
        root.addView(status)
        root.addView(Button(this).apply {
            text = getString(R.string.chat_open_log)
            setOnClickListener { showChatLog() }
        })

        channelSpinner = Spinner(this)
        root.addView(channelSpinner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

        // The history gets its own fixed-height scroller. Without it the chat
        // lived inside the page scroll, so reading old messages meant scrolling
        // the whole settings page and losing sight of the input box.
        history = TextView(this).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setTextIsSelectable(true)
            textSize = 14f
        }
        historyScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(history, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(historyScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(260)))

        val compose = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        messageEdit = EditText(this).apply {
            hint = getString(R.string.chat_message_hint)
            maxLines = 3
            // Enter sends instead of inserting a newline nobody wanted.
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        sendButton = Button(this).apply { text = getString(R.string.chat_send); isEnabled = false }
        compose.addView(messageEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        compose.addView(sendButton)
        root.addView(compose)

        root.addView(heading(getString(R.string.voice_title)))
        root.addView(TextView(this).apply { text = getString(R.string.voice_native_summary) })
        voiceEnabled = ToggleButton(this).apply {
            textOn = getString(R.string.voice_button_on)
            textOff = getString(R.string.voice_button_off)
            isAllCaps = false
            textSize = 15f
            minHeight = dp(54)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setTextColor(android.graphics.Color.rgb(229, 204, 161))
            backgroundTintList = null
            setBackgroundResource(R.drawable.arena_voice_toggle)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.arena_ic_microphone, 0, 0, 0)
            compoundDrawablePadding = dp(12)
        }
        voiceStatus = TextView(this).apply { setPadding(0, dp(2), 0, dp(5)) }
        pttKey = EditText(this).apply {
            hint = getString(R.string.voice_ptt_key)
            isSingleLine = true
            maxLines = 1
            setSelectAllOnFocus(true)
        }
        voiceToggleMode = CheckBox(this).apply { text = getString(R.string.voice_radio_mode) }
        val permissionButton = Button(this).apply { text = getString(R.string.voice_permissions) }
        root.addView(voiceEnabled, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8); bottomMargin = dp(8) })
        root.addView(TextView(this).apply {
            text = getString(R.string.voice_touch_summary)
            textSize = 13f
            setPadding(0, 0, 0, dp(6))
        })
        root.addView(voiceStatus)
        root.addView(pttKey)
        root.addView(voiceToggleMode)
        root.addView(TextView(this).apply {
            text = getString(R.string.voice_radio_mode_summary)
            textSize = 13f
            setPadding(0, 0, 0, dp(6))
        })
        root.addView(permissionButton)

        codeMode.setOnCheckedChangeListener { _, checked ->
            secretEdit.inputType = if (checked) InputType.TYPE_CLASS_TEXT
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            secretEdit.hint = getString(if (checked) R.string.chat_in_game_code else R.string.chat_password)
            secretEdit.text.clear()
            if (!checked) loadGameLogin()
        }
        connectButton.setOnClickListener { connect() }
        sendButton.setOnClickListener { sendMessage() }
        messageEdit.setOnEditorActionListener { _, actionId, event ->
            // Only react to a real send action; the old catch-all consumed every
            // IME event, including the ones that just move focus.
            val isSend = actionId == EditorInfo.IME_ACTION_SEND
                || actionId == EditorInfo.IME_ACTION_DONE
                || (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                    && event.action == android.view.KeyEvent.ACTION_DOWN)
            if (isSend) sendMessage()
            isSend
        }
        channelSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectChannel(position)
            }
        }
        voiceEnabled.setOnCheckedChangeListener { _, checked ->
            if (checked && !VoicePermissions.granted(this)) {
                prefs.edit().putBoolean(PREF_VOICE_ENABLED, false).apply()
                voiceEnabled.isChecked = false
                VoicePermissions.request(this)
            } else {
                prefs.edit().putBoolean(PREF_VOICE_ENABLED, checked).apply()
                updateVoiceStatus()
            }
        }
        pttKey.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) savePttKey() }
        voiceToggleMode.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_VOICE_TOGGLE, checked).apply()
            updateVoiceStatus()
        }
        permissionButton.setOnClickListener { VoicePermissions.request(this) }

        return scroll
    }

    private fun loadSettings() {
        nameEdit.setText(prefs.getString(PREF_CHAT_NAME, "").orEmpty())
        voiceEnabled.isChecked = prefs.getBoolean(PREF_VOICE_ENABLED, false) && VoicePermissions.granted(this)
        pttKey.setText(prefs.getString(PREF_VOICE_PTT, "V").orEmpty().ifBlank { "V" })
        voiceToggleMode.isChecked = prefs.getBoolean(PREF_VOICE_TOGGLE, false)
        loadGameLogin()
        updateVoiceStatus()
    }

    private fun loadGameLogin() {
        var section = ""
        var hasName = false
        var hasPassword = false
        val loaded = runCatching {
            File(Constants.USER_CONFIG, "settings.cfg").forEachLine(Charsets.UTF_8) { raw ->
                val line = raw.trim().removePrefix("\uFEFF")
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length - 1).trim()
                } else if (section.equals("Login", true) && !line.startsWith("#") && !line.startsWith(";")) {
                    val split = line.indexOf('=')
                    if (split > 0) {
                        val key = line.substring(0, split).trim()
                        val value = line.substring(split + 1).trim()
                        if (key.equals("name", true)) { hasName = value.isNotEmpty(); nameEdit.setText(value) }
                        if (key.equals("password", true)) { hasPassword = value.isNotEmpty(); if (!codeMode.isChecked) secretEdit.setText(value) }
                    }
                }
            }
        }.isSuccess
        diagnostics.event("CONFIG_SOURCE settings_readable=$loaded name_present=$hasName password_present=$hasPassword")
    }

    private fun showChatLog() {
        val snapshot = diagnostics.snapshot()
        val heading = diagnostics.file.absolutePath + "\n" +
            (if (diagnostics.writeFailed) getString(R.string.chat_log_write_failed) + "\n" else "")
        val text = TextView(this).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            this.text = heading + snapshot.takeLast(65536)
        }
        val scroll = ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this).setTitle(R.string.chat_open_log).setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.chat_save_log) { _, _ ->
                exportedChatLog = snapshot
                try {
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, "Chat.log")
                    }, REQUEST_SAVE_CHAT_LOG)
                } catch (_: Exception) {
                    exportedChatLog = null
                    Toast.makeText(this, R.string.chat_log_save_failed, Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SAVE_CHAT_LOG) return
        val snapshot = exportedChatLog
        exportedChatLog = null
        if (resultCode != Activity.RESULT_OK || snapshot == null) return
        val uri = data?.data ?: return
        val saved = runCatching {
            val output = contentResolver.openOutputStream(uri) ?: throw java.io.IOException()
            output.use { it.write(snapshot.toByteArray(Charsets.UTF_8)) }
        }.isSuccess
        Toast.makeText(this, if (saved) R.string.chat_log_saved else R.string.chat_log_save_failed, Toast.LENGTH_LONG).show()
    }

    private fun savePttKey() {
        val value = pttKey.text.toString().trim().ifBlank { "V" }.take(16)
        if (pttKey.text.toString() != value) pttKey.setText(value)
        prefs.edit().putString(PREF_VOICE_PTT, value).apply()
    }

    private fun updateVoiceStatus() {
        voiceStatus.text = when {
            !voiceEnabled.isChecked -> getString(R.string.voice_state_off)
            voiceToggleMode.isChecked ->
                getString(R.string.voice_ready_radio, pttKey.text.toString().ifBlank { "V" })
            else -> getString(R.string.voice_ready_ptt, pttKey.text.toString().ifBlank { "V" })
        }
    }

    private fun endpoint(): Pair<String, Int> {
        if (prefs.getBoolean(ServerController.PREF_SERVER_ENABLED, false)) {
            val port = try { ServerConfig.load(ServerRuntime.userConfig(this)).port.toInt() } catch (_: Throwable) { 25565 }
            return Pair("127.0.0.1", port)
        }
        if (prefs.getBoolean("pref_use_alt_server", false)) {
            val host = prefs.getString("pref_alt_address", "").orEmpty()
            val port = prefs.getString("pref_alt_port", "25565").orEmpty().toIntOrNull() ?: 25565
            return Pair(host, port)
        }
        val manifest = try { BuildManifest.read(this) } catch (_: Throwable) { null }
        val host = manifest?.serverAddress ?: prefs.getString("pref_server_ip", BuildManifest.DEFAULT_SERVER_ADDRESS).orEmpty()
        val portText = manifest?.serverPort ?: prefs.getString("pref_server_port", BuildManifest.DEFAULT_SERVER_PORT).orEmpty()
        return Pair(host, portText.toIntOrNull() ?: 25565)
    }

    private fun connect() {
        val name = nameEdit.text.toString().trim()
        val secret = secretEdit.text.toString()
        if (name.isEmpty() || secret.isEmpty()) {
            diagnostics.event("LOCAL_VALIDATION_FAILED name_present=${name.isNotEmpty()} password_present=${secret.isNotEmpty()}")
            status.text = getString(R.string.chat_enter_credentials)
            return
        }
        val ep = endpoint()
        if (ep.first.isBlank()) {
            diagnostics.event("LOCAL_VALIDATION_FAILED host_missing=true")
            status.text = getString(R.string.chat_no_server)
            return
        }
        prefs.edit().putString(PREF_CHAT_NAME, name).apply()
        connectButton.isEnabled = false
        status.text = getString(R.string.chat_connecting, ep.first, ep.second + 2)
        client.connect(ep.first, ep.second, name, secret, codeMode.isChecked)
    }

    private fun sendMessage() {
        val text = messageEdit.text.toString().trim()
        if (!client.authorized || channelId == 0 || text.isEmpty()) return
        client.sendMessage(channelId, text)
        messageEdit.text.clear()
    }

    private fun appendMessage(message: LinkMessage) {
        shown.add(message)
        // Hard cap the model, not the widget: an all-day session used to keep
        // every line in memory and re-lay out a TextView that grew past the
        // point where scrolling it was smooth.
        while (shown.size > MAX_HISTORY_LINES) shown.removeAt(0)
        renderHistory()
    }

    private fun renderHistory() {
        val text = SpannableStringBuilder()
        for (message in shown) {
            val badge = if (message.fromGame) " · " + getString(R.string.chat_from_game) else ""
            val head = "[" + message.author + badge + "] "
            val start = text.length
            text.append(head)
            // Own lines stand out, game-bridged lines are tinted so it is clear
            // which side of the bridge a message came from.
            val colour = when {
                ownName.isNotEmpty() && message.author.equals(ownName, true) -> 0xFF8FD98F.toInt()
                message.fromGame -> 0xFFE5CCA1.toInt()
                else -> 0xFF9FD7FF.toInt()
            }
            text.setSpan(ForegroundColorSpan(colour), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append(message.text).append("\n")
        }
        history.text = text
        // Jump to the newest line after the layout pass, otherwise the scroll
        // happens against the previous (shorter) content and lands short.
        historyScroll.post { historyScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onLoggedIn(profile: LinkProfile) {
        connectButton.isEnabled = true
        ownName = profile.name
        status.text = getString(R.string.chat_logged_in, profile.name, profile.level)
        secretEdit.text.clear()
        sendButton.isEnabled = false
    }

    override fun onLoginFailed(reason: Int, text: String) {
        connectButton.isEnabled = true
        sendButton.isEnabled = false
        status.text = if (text.isNotBlank()) text else getString(R.string.chat_login_failed)
    }

    override fun onChannels(newChannels: List<LinkChannel>) {
        channels.clear(); channels.addAll(newChannels)
        channelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, channels.map { it.name })
        if (channels.isEmpty()) return

        // setSelection(0) does not always deliver onItemSelected when position 0
        // was already selected before the adapter was replaced, which left the
        // send button permanently disabled after a reconnect. Select explicitly.
        channelSpinner.setSelection(0)
        selectChannel(0)
    }

    private fun selectChannel(position: Int) {
        if (position !in channels.indices || !client.authorized) return
        channelId = channels[position].id
        sendButton.isEnabled = channels[position].writable
        messageEdit.isEnabled = channels[position].writable
        shown.clear()
        renderHistory()
        client.joinChannel(channelId)
        client.requestHistory(channelId, 0L, 50)
    }

    override fun onMessage(message: LinkMessage) {
        if (message.channel == channelId) appendMessage(message)
    }

    override fun onHistory(channel: Int, messages: List<LinkMessage>) {
        if (channel != channelId) return
        // History can land after live messages for the same channel have already
        // been shown. Rebuild from history and re-append anything newer that was
        // received in the meantime, instead of dropping it on the floor.
        val live = ArrayList(shown)
        shown.clear()
        shown.addAll(messages)
        for (message in live) {
            if (messages.none { it.author == message.author && it.text == message.text })
                shown.add(message)
        }
        while (shown.size > MAX_HISTORY_LINES) shown.removeAt(0)
        renderHistory()
    }

    override fun onVoiceTicket(ticket: ByteArray, port: Int, ttl: Int) = Unit
    override fun onNotice(severity: Int, text: String) { status.text = text }
    override fun onDisconnected(reason: String) {
        connectButton.isEnabled = true
        sendButton.isEnabled = false
        status.text = reason
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != VoicePermissions.REQUEST_CODE) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        voiceEnabled.isChecked = granted
        prefs.edit().putBoolean(PREF_VOICE_ENABLED, granted).apply()
        if (!granted) Toast.makeText(this, R.string.voice_denied, Toast.LENGTH_LONG).show()
        updateVoiceStatus()
    }

    override fun onPause() {
        savePttKey()
        super.onPause()
    }

    override fun onDestroy() {
        client.listener = null
        client.disconnect()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_SAVE_CHAT_LOG = 731
        const val PREF_CHAT_NAME = "pref_chat_name"
        const val PREF_VOICE_ENABLED = "pref_voice_enabled"
        const val PREF_VOICE_PTT = "pref_voice_ptt_key"
        const val PREF_VOICE_TOGGLE = "pref_voice_toggle_mode"
        private const val MAX_HISTORY_LINES = 400
    }
}
