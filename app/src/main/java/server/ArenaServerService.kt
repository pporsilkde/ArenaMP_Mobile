package server

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.preference.PreferenceManager
import android.util.Log
import com.libopenmw.openmw.R

class ArenaServerService : Service() {
    @Volatile private var worker: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var restartRequested = false
    @Volatile private var exitRequested = false
    @Volatile private var autoRestart = true
    @Volatile private var currentState = "starting"
    private var wakeLock: PowerManager.WakeLock? = null

    external fun nativeRun(globalPath: String, userPath: String, runtimePath: String): Int
    external fun nativeStop()

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("c++_shared")
        System.loadLibrary("arenamp_server")
        createChannel()
        startForeground(NOTIFICATION_ID, notification(getString(R.string.server_status_starting)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXIT -> {
                requestExit()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                requestStop()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                autoRestart = intent?.getBooleanExtra(
                    EXTRA_AUTO_RESTART,
                    prefs.getBoolean(ServerController.PREF_AUTO_RESTART, true)
                ) ?: prefs.getBoolean(ServerController.PREF_AUTO_RESTART, true)
                prefs.edit().putBoolean(ServerController.PREF_SERVER_ENABLED, true).apply()
                exitRequested = false

                val active = worker?.isAlive == true
                if (active) {
                    // If Start is pressed while a graceful Stop is still in
                    // progress, queue one clean restart as soon as nativeRun returns.
                    if (stopRequested) {
                        restartRequested = true
                        currentState = "restarting"
                        updateNotification(getString(R.string.server_status_restarting))
                    } else {
                        updateNotification(statusText(currentState))
                    }
                } else {
                    restartRequested = false
                    startWorker()
                }
                return if (autoRestart) START_STICKY else START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun requestStop() {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putBoolean(ServerController.PREF_SERVER_ENABLED, false).apply()
        restartRequested = false
        stopRequested = true
        currentState = "stopping"
        ServerRuntime.writeStatus(this, "stopping")
        updateNotification(getString(R.string.server_status_stopping))
        sendState("stopping")

        val active = worker?.isAlive == true
        if (!active) {
            stopRequested = false
            currentState = "stopped"
            releaseWakeLock()
            ServerRuntime.writeStatus(this, "stopped")
            updateNotification(getString(R.string.server_status_stopped))
            sendState("stopped")
            return
        }

        try { nativeStop() } catch (_: Throwable) {}
        // A second graceful request covers the short startup window before
        // Networking::getPtr() exists.
        Thread {
            try { Thread.sleep(500L); nativeStop() } catch (_: Throwable) {}
        }.start()

        // If native shutdown hangs, the only safe recovery is to terminate the
        // dedicated :arenamp_server process. The launcher itself is unaffected.
        Thread {
            try {
                Thread.sleep(2500L)
                if (!exitRequested && stopRequested && worker?.isAlive == true) {
                    ServerRuntime.writeStatus(this, "stopped", 137)
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            } catch (_: Throwable) {}
        }.start()
    }

    private fun requestExit() {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putBoolean(ServerController.PREF_SERVER_ENABLED, false).apply()
        exitRequested = true
        restartRequested = false
        stopRequested = true
        currentState = "exiting"
        ServerRuntime.writeStatus(this, "exiting")
        updateNotification(getString(R.string.server_status_exiting))
        sendState("exiting")

        if (worker?.isAlive == true) {
            try { nativeStop() } catch (_: Throwable) {}
            Thread {
                try { Thread.sleep(500L); nativeStop() } catch (_: Throwable) {}
            }.start()
            Thread {
                try {
                    Thread.sleep(2500L)
                    if (exitRequested && worker?.isAlive == true)
                        android.os.Process.killProcess(android.os.Process.myPid())
                } catch (_: Throwable) {}
            }.start()
        } else {
            finishExit()
        }
    }

    private fun startWorker() {
        stopRequested = false
        currentState = "starting"
        acquireWakeLock()
        ServerRuntime.writeStatus(this, "starting")
        updateNotification(getString(R.string.server_status_starting))
        sendState("starting")

        val thread = Thread {
            var rapidCrashes = 0
            var lastStart = 0L
            var terminalState = "stopped"
            try {
                ServerRuntime.ensureInstalled(this)
                val writableRoot = ServerRuntime.verifyWritableRuntime(this)
                Log.i(TAG, "Writable ArenaMP server runtime: $writableRoot")
                while (!stopRequested && !exitRequested) {
                    val now = System.currentTimeMillis()
                    if (lastStart != 0L && now - lastStart < 15000L) rapidCrashes++ else rapidCrashes = 0
                    lastStart = now
                    if (rapidCrashes >= 3) {
                        Log.e(TAG, "Server stopped after 3 rapid restarts")
                        terminalState = "error"
                        break
                    }

                    currentState = "running"
                    ServerRuntime.writeStatus(this, "running")
                    updateNotification(getString(R.string.server_status_running))
                    sendState("running")

                    ServerRuntime.syncPersistentScriptConfig(this)
                    val globalRoot = filesDir.parentFile?.absolutePath ?: filesDir.absolutePath
                    val userRoot = ServerRuntime.root(this).absolutePath
                    val code = nativeRun(globalRoot, userRoot, ServerRuntime.root(this).absolutePath)
                    ServerRuntime.writeStatus(this, "stopped", code)
                    sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName)
                        .putExtra(EXTRA_STATE, "stopped").putExtra(EXTRA_EXIT_CODE, code))

                    // Desktop ArenaMP makes a backup whenever automatic
                    // restart is enabled, including an intentional stop.
                    if (autoRestart) {
                        try {
                            ServerRuntime.createBackup(this)
                        } catch (e: Throwable) {
                            Log.w(TAG, "Server backup failed", e)
                        }
                    }

                    if (stopRequested || exitRequested || !autoRestart) break
                    currentState = "restarting"
                    updateNotification(getString(R.string.server_status_restarting))
                    sendState("restarting")
                    Thread.sleep(1500L)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "ArenaMP server service failed", e)
                terminalState = "error"
                ServerRuntime.writeStatus(this, "error", 125)
            } finally {
                releaseWakeLock()
                if (worker === Thread.currentThread()) worker = null

                if (exitRequested) {
                    finishExit()
                    return@Thread
                }

                if (restartRequested) {
                    restartRequested = false
                    stopRequested = false
                    startWorker()
                    return@Thread
                }

                stopRequested = false
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putBoolean(ServerController.PREF_SERVER_ENABLED, false).apply()

                currentState = terminalState
                if (terminalState == "error") {
                    ServerRuntime.writeStatus(this, "error")
                    updateNotification(getString(R.string.server_status_error))
                    sendState("error")
                } else {
                    currentState = "stopped"
                    ServerRuntime.writeStatus(this, "stopped")
                    updateNotification(getString(R.string.server_status_stopped))
                    sendState("stopped")
                }
                // Keep the foreground service and its notification alive while
                // the native server is stopped. This makes the notification's
                // Start action immediately available without reopening launcher UI.
            }
        }
        worker = thread
        thread.name = "ArenaMP-Server"
        thread.start()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ArenaMP:Server").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun finishExit() {
        releaseWakeLock()
        ServerRuntime.writeStatus(this, "stopped")
        sendState("stopped")
        stopForeground(true)
        stopSelf()
    }

    private fun sendState(state: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATE, state))
    }

    private fun statusText(state: String): String = when (state) {
        "running" -> getString(R.string.server_status_running)
        "restarting" -> getString(R.string.server_status_restarting)
        "stopping" -> getString(R.string.server_status_stopping)
        "exiting" -> getString(R.string.server_status_exiting)
        "error" -> getString(R.string.server_status_error)
        "stopped" -> getString(R.string.server_status_stopped)
        else -> getString(R.string.server_status_starting)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.server_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun pendingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 23)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT

    private fun notification(text: String): Notification {
        val openPending = PendingIntent.getActivity(
            this, 0, Intent(this, ServerActivity::class.java), pendingFlags()
        )
        val startPending = PendingIntent.getService(
            this, 1, Intent(this, ArenaServerService::class.java).setAction(ACTION_START), pendingFlags()
        )
        val stopPending = PendingIntent.getService(
            this, 2, Intent(this, ArenaServerService::class.java).setAction(ACTION_STOP), pendingFlags()
        )
        val exitPending = PendingIntent.getService(
            this, 3, Intent(this, ArenaServerService::class.java).setAction(ACTION_EXIT), pendingFlags()
        )

        val endpoint = try {
            ServerRuntime.ensureInstalled(this)
            val cfg = ServerConfig.load(ServerRuntime.userConfig(this))
            getString(R.string.server_endpoint, ServerRuntime.lanAddress(), cfg.port)
        } catch (_: Throwable) {
            ""
        }
        val details = if (endpoint.isBlank()) text else "$text\n$endpoint"

        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID)
        else Notification.Builder(this)

        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.server_notification_title))
            .setContentText(if (endpoint.isBlank()) text else "$text • $endpoint")
            .setStyle(Notification.BigTextStyle().bigText(details))
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_play, getString(R.string.server_start), startPending)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.server_stop), stopPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.server_exit), exitPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        exitRequested = true
        stopRequested = true
        try { nativeStop() } catch (_: Throwable) {}
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.mrzer0x0.arenamp.server.START"
        const val ACTION_STOP = "com.mrzer0x0.arenamp.server.STOP"
        const val ACTION_EXIT = "com.mrzer0x0.arenamp.server.EXIT"
        const val ACTION_STATUS = "com.mrzer0x0.arenamp.server.STATUS"
        const val EXTRA_AUTO_RESTART = "autoRestart"
        const val EXTRA_STATE = "state"
        const val EXTRA_EXIT_CODE = "exitCode"
        private const val CHANNEL_ID = "arenamp_server"
        private const val NOTIFICATION_ID = 4708
        private const val TAG = "ArenaMPServer"
    }
}
