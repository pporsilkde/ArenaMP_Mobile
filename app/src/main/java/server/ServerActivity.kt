package server

import android.app.AlertDialog
import android.content.*
import android.os.Bundle
import android.os.Handler
import android.preference.PreferenceManager
import android.text.InputType
import android.view.ViewGroup
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.libopenmw.openmw.R
import java.io.RandomAccessFile

class ServerActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var endpoint: TextView
    private lateinit var storagePath: TextView
    private lateinit var logPath: TextView
    private lateinit var logView: TextView
    private lateinit var localAddress: EditText
    private lateinit var port: EditText
    private lateinit var maxPlayers: EditText
    private lateinit var hostname: EditText
    private lateinit var password: EditText
    private lateinit var autoStart: CheckBox
    private lateinit var autoRestart: CheckBox
    private lateinit var serverMode: Spinner
    private lateinit var enforceRequired: CheckBox
    private lateinit var clearCells: Button
    private lateinit var fullReset: Button
    private var selectedMode = -1
    private val handler = Handler()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refresh() }
    }
    private val poll = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server)
        setSupportActionBar(findViewById(R.id.server_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.server_title)

        // MainActivity requests legacy storage permission before this screen is
        // reachable. ensureInstalled then deploys the writable portable runtime
        // to /storage/emulated/0/ArenaMP (with an app-external fallback).
        ServerRuntime.ensureInstalled(this)
        ServerController.initializeDesktopCompatibleDefaults(this)

        status = findViewById(R.id.server_status)
        endpoint = findViewById(R.id.server_endpoint)
        storagePath = findViewById(R.id.server_storage_path)
        logPath = findViewById(R.id.server_log_path)
        logView = findViewById(R.id.server_log)
        localAddress = findViewById(R.id.server_local_address)
        port = findViewById(R.id.server_port)
        maxPlayers = findViewById(R.id.server_max_players)
        hostname = findViewById(R.id.server_hostname)
        password = findViewById(R.id.server_password)
        autoStart = findViewById(R.id.server_auto_start)
        autoRestart = findViewById(R.id.server_auto_restart)
        serverMode = findViewById(R.id.server_mode)
        enforceRequired = findViewById(R.id.server_enforce_required)
        clearCells = findViewById(R.id.server_clear_cells)
        fullReset = findViewById(R.id.server_full_reset)

        storagePath.text = getString(R.string.server_storage_path, ServerRuntime.root(this).absolutePath)
        logPath.text = getString(R.string.server_log_path, ServerRuntime.logFile(this).absolutePath)

        val cfg = ServerConfig.load(ServerRuntime.userConfig(this))
        localAddress.setText(cfg.localAddress)
        port.setText(cfg.port)
        maxPlayers.setText(cfg.maximumPlayers)
        hostname.setText(cfg.hostname)
        password.setText(cfg.password)

        setupServerMode()
        enforceRequired.isChecked = ServerScriptConfig.enforceRequiredDataFiles(this)
        enforceRequired.setOnCheckedChangeListener { _, checked ->
            try {
                ServerScriptConfig.setEnforceRequiredDataFiles(this, checked)
            } catch (e: Throwable) {
                Toast.makeText(this,
                    getString(R.string.server_script_config_failed, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG).show()
            }
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        autoStart.isChecked = prefs.getBoolean(ServerController.PREF_AUTO_START, true)
        autoRestart.isChecked = prefs.getBoolean(ServerController.PREF_AUTO_RESTART, true)
        autoStart.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(ServerController.PREF_AUTO_START, checked).apply()
        }
        autoRestart.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(ServerController.PREF_AUTO_RESTART, checked).apply()
            if (ServerRuntime.readStatus(this) == "running")
                ServerController.start(this, checked)
        }

        findViewById<Button>(R.id.server_save).setOnClickListener { saveConfig() }
        findViewById<Button>(R.id.server_edit_script_config).setOnClickListener { showScriptConfigEditor() }
        findViewById<Button>(R.id.server_update_hashes).setOnClickListener {
            try {
                // Match the PC launcher: a generated requiredDataFiles manifest
                // is immediately useful only when enforcement is enabled.
                if (!enforceRequired.isChecked) enforceRequired.isChecked = true
                val result = ServerDataFiles.update(this)
                val message = getString(R.string.server_hashes_updated,
                    result.required, result.groundcover, result.file.absolutePath)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            } catch (e: Throwable) {
                Toast.makeText(this, getString(R.string.server_hashes_failed, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG).show()
            }
        }
        clearCells.setOnClickListener { confirmClearCells() }
        fullReset.setOnClickListener { confirmFullReset() }
        findViewById<Button>(R.id.server_start).setOnClickListener {
            saveConfig(false)
            ServerRuntime.syncPersistentScriptConfig(this)
            ServerController.start(this, autoRestart.isChecked)
            refresh()
        }
        findViewById<Button>(R.id.server_stop).setOnClickListener {
            ServerController.stop(this)
            refresh()
        }
        findViewById<Button>(R.id.server_log_clear).setOnClickListener {
            try { ServerRuntime.logFile(this).writeText("") } catch (_: Throwable) {}
            refresh()
        }
        refresh()
    }

    private fun setupServerMode() {
        val labels = arrayOf(getString(R.string.server_mode_coop), getString(R.string.server_mode_mmo))
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        serverMode.adapter = adapter
        selectedMode = if (ServerScriptConfig.detect(this) == ServerScriptConfig.Mode.COOP) 0 else 1
        serverMode.setSelection(selectedMode, false)
        serverMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position == selectedMode) return
                selectedMode = position
                try {
                    val mode = if (position == 0) ServerScriptConfig.Mode.COOP else ServerScriptConfig.Mode.MMO
                    ServerScriptConfig.apply(this@ServerActivity, mode)
                    Toast.makeText(this@ServerActivity, R.string.server_mode_saved, Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    Toast.makeText(this@ServerActivity,
                        getString(R.string.server_script_config_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveConfig(showToast: Boolean = true) {
        val cfg = ServerConfigData(
            localAddress.text.toString().trim().ifBlank { "0.0.0.0" },
            port.text.toString().trim().ifBlank { "25565" },
            maxPlayers.text.toString().trim().ifBlank { "100" },
            hostname.text.toString().trim().ifBlank { "ArenaMP server" },
            password.text.toString(),
            "1"
        )
        ServerConfig.save(ServerRuntime.userConfig(this), cfg)
        if (showToast) Toast.makeText(this, R.string.server_saved, Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun showScriptConfigEditor() {
        ServerRuntime.ensureInstalled(this)
        val file = ServerRuntime.persistentScriptConfig(this)
        val editor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(true)
            setText(if (file.isFile) file.readText(Charsets.UTF_8) else "")
            setSelection(0)
            minLines = 18
            maxLines = 28
            val pad = (12f * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val container = FrameLayout(this).apply {
            val margin = (12f * resources.displayMetrics.density).toInt()
            setPadding(margin, 0, margin, 0)
            addView(editor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.server_script_config_title)
            .setMessage(getString(R.string.server_script_config_path, file.absolutePath))
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.server_script_config_save) { _, _ ->
                try {
                    file.parentFile?.mkdirs()
                    file.writeText(editor.text.toString(), Charsets.UTF_8)
                    ServerRuntime.syncPersistentScriptConfig(this)
                    val detected = ServerScriptConfig.detect(this)
                    selectedMode = if (detected == ServerScriptConfig.Mode.COOP) 0 else 1
                    serverMode.setSelection(selectedMode, false)
                    enforceRequired.isChecked = ServerScriptConfig.enforceRequiredDataFiles(this)
                    Toast.makeText(this, R.string.server_script_config_saved, Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    Toast.makeText(this,
                        getString(R.string.server_script_config_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun confirmClearCells() {
        if (ServerRuntime.readStatus(this) == "running") {
            Toast.makeText(this, R.string.server_stop_before_cleanup, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.server_clear_cells_confirm_title)
            .setMessage(R.string.server_clear_cells_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ok = ServerRuntime.clearPersistentCells(this)
                Toast.makeText(this,
                    if (ok) R.string.server_clear_cells_done else R.string.server_cleanup_failed,
                    Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun confirmFullReset() {
        if (ServerRuntime.readStatus(this) == "running") {
            Toast.makeText(this, R.string.server_stop_before_cleanup, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.server_full_reset_confirm_title)
            .setMessage(R.string.server_full_reset_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ok = ServerRuntime.resetPersistentServerData(this)
                Toast.makeText(this,
                    if (ok) R.string.server_full_reset_done else R.string.server_cleanup_failed,
                    Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun refresh() {
        val cfg = ServerConfig.load(ServerRuntime.userConfig(this))
        val state = ServerRuntime.readStatus(this)
        val running = state == "running"
        status.text = when (state) {
            "running" -> getString(R.string.server_status_running)
            "error" -> getString(R.string.server_status_error)
            else -> getString(R.string.server_status_stopped)
        }
        endpoint.text = getString(R.string.server_endpoint, ServerRuntime.lanAddress(), cfg.port)
        storagePath.text = getString(R.string.server_storage_path, ServerRuntime.root(this).absolutePath)
        logPath.text = getString(R.string.server_log_path, ServerRuntime.logFile(this).absolutePath)
        clearCells.isEnabled = !running
        fullReset.isEnabled = !running

        val file = ServerRuntime.logFile(this)
        if (file.isFile) {
            try {
                RandomAccessFile(file, "r").use { input ->
                    val length = input.length()
                    val start = (length - 64L * 1024L).coerceAtLeast(0L)
                    input.seek(start)
                    val bytes = ByteArray((length - start).toInt())
                    input.readFully(bytes)
                    logView.text = String(bytes, Charsets.UTF_8)
                }
            } catch (_: Throwable) {}
        } else {
            logView.text = ""
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(receiver, IntentFilter(ArenaServerService.ACTION_STATUS))
        handler.post(poll)
    }

    override fun onStop() {
        handler.removeCallbacks(poll)
        try { unregisterReceiver(receiver) } catch (_: Throwable) {}
        super.onStop()
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            onBackPressed()
            true
        } else super.onOptionsItemSelected(item)
    }

}
