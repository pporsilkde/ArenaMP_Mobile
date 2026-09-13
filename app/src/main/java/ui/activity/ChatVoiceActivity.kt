package ui.activity

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.preference.PreferenceManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import chat.ArenaLinkClient
import chat.LinkChannel
import chat.LinkMessage
import chat.LinkProfile
import com.libopenmw.openmw.R
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
    private lateinit var history: TextView
    private lateinit var messageEdit: EditText
    private lateinit var sendButton: Button
    private lateinit var voiceEnabled: CheckBox
    private lateinit var pttKey: EditText
    private lateinit var voiceStatus: TextView

    private val client = ArenaLinkClient()
    private val channels = ArrayList<LinkChannel>()
    private var channelId = 0
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
        codeMode = CheckBox(this).apply { text = getString(R.string.chat_use_code) }
        connectButton = Button(this).apply { text = getString(R.string.chat_login_button) }
        status = TextView(this).apply { setPadding(0, dp(5), 0, dp(8)) }
        root.addView(nameEdit)
        root.addView(secretEdit)
        root.addView(codeMode)
        root.addView(connectButton)
        root.addView(status)

        channelSpinner = Spinner(this)
        root.addView(channelSpinner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        history = TextView(this).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            minHeight = dp(180)
            setTextIsSelectable(true)
        }
        root.addView(history, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val compose = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        messageEdit = EditText(this).apply { hint = getString(R.string.chat_message_hint); maxLines = 3 }
        sendButton = Button(this).apply { text = getString(R.string.chat_send); isEnabled = false }
        compose.addView(messageEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        compose.addView(sendButton)
        root.addView(compose)

        root.addView(heading(getString(R.string.voice_title)))
        root.addView(TextView(this).apply { text = getString(R.string.voice_native_summary) })
        voiceEnabled = CheckBox(this).apply { text = getString(R.string.voice_enable) }
        voiceStatus = TextView(this).apply { setPadding(0, dp(2), 0, dp(5)) }
        pttKey = EditText(this).apply {
            hint = getString(R.string.voice_ptt_key)
            isSingleLine = true
            maxLines = 1
            setSelectAllOnFocus(true)
        }
        val permissionButton = Button(this).apply { text = getString(R.string.voice_permissions) }
        root.addView(voiceEnabled)
        root.addView(voiceStatus)
        root.addView(pttKey)
        root.addView(permissionButton)

        codeMode.setOnCheckedChangeListener { _, checked ->
            secretEdit.inputType = if (checked) InputType.TYPE_CLASS_TEXT
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            secretEdit.hint = getString(if (checked) R.string.chat_in_game_code else R.string.chat_password)
        }
        connectButton.setOnClickListener { connect() }
        sendButton.setOnClickListener { sendMessage() }
        messageEdit.setOnEditorActionListener { _, _, _ -> sendMessage(); true }
        channelSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in channels.indices && client.authorized) {
                    channelId = channels[position].id
                    client.joinChannel(channelId)
                    client.requestHistory(channelId, 0L, 50)
                }
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
        permissionButton.setOnClickListener { VoicePermissions.request(this) }

        return scroll
    }

    private fun loadSettings() {
        nameEdit.setText(prefs.getString(PREF_CHAT_NAME, "").orEmpty())
        voiceEnabled.isChecked = prefs.getBoolean(PREF_VOICE_ENABLED, false) && VoicePermissions.granted(this)
        pttKey.setText(prefs.getString(PREF_VOICE_PTT, "V").orEmpty().ifBlank { "V" })
        updateVoiceStatus()
    }

    private fun savePttKey() {
        val value = pttKey.text.toString().trim().ifBlank { "V" }.take(16)
        if (pttKey.text.toString() != value) pttKey.setText(value)
        prefs.edit().putString(PREF_VOICE_PTT, value).apply()
    }

    private fun updateVoiceStatus() {
        voiceStatus.text = if (voiceEnabled.isChecked)
            getString(R.string.voice_ready_ptt, pttKey.text.toString().ifBlank { "V" })
        else getString(R.string.voice_state_off)
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
            status.text = getString(R.string.chat_enter_credentials)
            return
        }
        val ep = endpoint()
        if (ep.first.isBlank()) {
            status.text = getString(R.string.chat_no_server)
            return
        }
        prefs.edit().putString(PREF_CHAT_NAME, name).apply()
        connectButton.isEnabled = false
        status.text = getString(R.string.chat_connecting, ep.first, ep.second)
        client.connect(ep.first, ep.second, name, secret, codeMode.isChecked)
        secretEdit.text.clear()
    }

    private fun sendMessage() {
        val text = messageEdit.text.toString().trim()
        if (!client.authorized || channelId == 0 || text.isEmpty()) return
        client.sendMessage(channelId, text)
        messageEdit.text.clear()
    }

    private fun appendMessage(message: LinkMessage) {
        val badge = if (message.fromGame) " · ${getString(R.string.chat_from_game)}" else ""
        history.append("[${message.author}$badge] ${message.text}\n")
    }

    override fun onLoggedIn(profile: LinkProfile) {
        connectButton.isEnabled = true
        status.text = getString(R.string.chat_logged_in, profile.name, profile.level)
        sendButton.isEnabled = true
    }

    override fun onLoginFailed(reason: Int, text: String) {
        connectButton.isEnabled = true
        status.text = if (text.isNotBlank()) text else getString(R.string.chat_login_failed)
    }

    override fun onChannels(newChannels: List<LinkChannel>) {
        channels.clear(); channels.addAll(newChannels)
        channelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, channels.map { it.name })
        if (channels.isNotEmpty()) channelSpinner.setSelection(0)
    }

    override fun onMessage(message: LinkMessage) = appendMessage(message)

    override fun onHistory(channel: Int, messages: List<LinkMessage>) {
        if (channel != channelId) return
        history.text = ""
        messages.forEach { appendMessage(it) }
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
        const val PREF_CHAT_NAME = "pref_chat_name"
        const val PREF_VOICE_ENABLED = "pref_voice_enabled"
        const val PREF_VOICE_PTT = "pref_voice_ptt_key"
    }
}
