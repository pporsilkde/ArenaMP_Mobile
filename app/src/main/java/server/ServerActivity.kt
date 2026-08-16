package server

import android.content.*
import android.os.Bundle
import android.os.Handler
import android.preference.PreferenceManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.libopenmw.openmw.R
import java.io.RandomAccessFile

class ServerActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var endpoint: TextView
    private lateinit var logView: TextView
    private lateinit var localAddress: EditText
    private lateinit var port: EditText
    private lateinit var maxPlayers: EditText
    private lateinit var hostname: EditText
    private lateinit var password: EditText
    private lateinit var autoStart: CheckBox
    private lateinit var autoRestart: CheckBox
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
        title = getString(R.string.server_title)
        ServerRuntime.ensureInstalled(this)
        ServerController.initializeDesktopCompatibleDefaults(this)

        status = findViewById(R.id.server_status)
        endpoint = findViewById(R.id.server_endpoint)
        logView = findViewById(R.id.server_log)
        localAddress = findViewById(R.id.server_local_address)
        port = findViewById(R.id.server_port)
        maxPlayers = findViewById(R.id.server_max_players)
        hostname = findViewById(R.id.server_hostname)
        password = findViewById(R.id.server_password)
        autoStart = findViewById(R.id.server_auto_start)
        autoRestart = findViewById(R.id.server_auto_restart)

        val cfg = ServerConfig.load(ServerRuntime.userConfig(this))
        localAddress.setText(cfg.localAddress)
        port.setText(cfg.port)
        maxPlayers.setText(cfg.maximumPlayers)
        hostname.setText(cfg.hostname)
        password.setText(cfg.password)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        autoStart.isChecked = prefs.getBoolean(ServerController.PREF_AUTO_START, true)
        autoRestart.isChecked = prefs.getBoolean(ServerController.PREF_AUTO_RESTART, true)
        autoStart.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(ServerController.PREF_AUTO_START, checked).apply() }
        autoRestart.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(ServerController.PREF_AUTO_RESTART, checked).apply()
            if (ServerRuntime.readStatus(this) == "running")
                ServerController.start(this, checked)
        }

        findViewById<Button>(R.id.server_save).setOnClickListener { saveConfig() }
        findViewById<Button>(R.id.server_update_hashes).setOnClickListener {
            try {
                val result = ServerDataFiles.update(this)
                val message = getString(R.string.server_hashes_updated,
                    result.required, result.groundcover, result.file.absolutePath)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            } catch (e: Throwable) {
                Toast.makeText(this, getString(R.string.server_hashes_failed, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.server_start).setOnClickListener {
            saveConfig()
            ServerController.start(this, autoRestart.isChecked)
            refresh()
        }
        findViewById<Button>(R.id.server_stop).setOnClickListener { ServerController.stop(this); refresh() }
        findViewById<Button>(R.id.server_log_clear).setOnClickListener {
            try { ServerRuntime.logFile(this).writeText("") } catch (_: Throwable) {}
            refresh()
        }
        refresh()
    }

    private fun saveConfig() {
        val cfg = ServerConfigData(
            localAddress.text.toString().trim().ifBlank { "0.0.0.0" },
            port.text.toString().trim().ifBlank { "25565" },
            maxPlayers.text.toString().trim().ifBlank { "100" },
            hostname.text.toString().trim().ifBlank { "ArenaMP server" },
            password.text.toString(),
            "1"
        )
        ServerConfig.save(ServerRuntime.userConfig(this), cfg)
        Toast.makeText(this, R.string.server_saved, Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun refresh() {
        val cfg = ServerConfig.load(ServerRuntime.userConfig(this))
        val state = ServerRuntime.readStatus(this)
        status.text = when (state) {
            "running" -> getString(R.string.server_status_running)
            "error" -> getString(R.string.server_status_error)
            else -> getString(R.string.server_status_stopped)
        }
        endpoint.text = getString(R.string.server_endpoint, ServerRuntime.lanAddress(), cfg.port)
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
}
