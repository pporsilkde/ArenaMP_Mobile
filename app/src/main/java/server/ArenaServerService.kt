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
    private var worker: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var autoRestart = true
    private var wakeLock: PowerManager.WakeLock? = null

    external fun nativeRun(globalPath: String, userPath: String, runtimePath: String): Int
    external fun nativeStop()

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("c++_shared")
        System.loadLibrary("arenamp_server")
        createChannel()
        startForeground(NOTIFICATION_ID, notification(getString(R.string.server_status_starting)))
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ArenaMP:Server").apply { setReferenceCounted(false); acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRequested = true
                try { nativeStop() } catch (_: Throwable) {}
                // If Stop is pressed while native startup is still creating the
                // Networking singleton, retry once shortly afterwards. This
                // mirrors the desktop launcher's graceful-stop-first policy.
                Thread {
                    try { Thread.sleep(500L); nativeStop() } catch (_: Throwable) {}
                }.start()
                // Match the desktop launcher: allow a short graceful shutdown,
                // then force-stop only the dedicated :arenamp_server process.
                Thread {
                    try {
                        Thread.sleep(2000L)
                        if (stopRequested && worker?.isAlive == true) {
                            ServerRuntime.writeStatus(this, "stopped", 137)
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }
                    } catch (_: Throwable) {}
                }.start()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                autoRestart = intent?.getBooleanExtra(EXTRA_AUTO_RESTART,
                    PreferenceManager.getDefaultSharedPreferences(this)
                        .getBoolean(ServerController.PREF_AUTO_RESTART, true))
                    ?: PreferenceManager.getDefaultSharedPreferences(this)
                        .getBoolean(ServerController.PREF_AUTO_RESTART, true)
                if (worker?.isAlive != true) startWorker()
            }
        }
        return if (autoRestart) START_STICKY else START_NOT_STICKY
    }

    private fun startWorker() {
        stopRequested = false
        worker = Thread {
            var rapidCrashes = 0
            var lastStart = 0L
            try {
                ServerRuntime.ensureInstalled(this)
                while (!stopRequested) {
                    val now = System.currentTimeMillis()
                    if (lastStart != 0L && now - lastStart < 15000L) rapidCrashes++ else rapidCrashes = 0
                    lastStart = now
                    if (rapidCrashes >= 3) {
                        Log.e(TAG, "Server stopped after 3 rapid restarts")
                        break
                    }

                    ServerRuntime.writeStatus(this, "running")
                    updateNotification(getString(R.string.server_status_running))
                    sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATE, "running"))

                    val globalRoot = filesDir.parentFile?.absolutePath ?: filesDir.absolutePath
                    val userRoot = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
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

                    if (stopRequested || !autoRestart) break
                    updateNotification(getString(R.string.server_status_restarting))
                    Thread.sleep(1500L)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "ArenaMP server service failed", e)
                ServerRuntime.writeStatus(this, "error", 125)
            } finally {
                ServerRuntime.writeStatus(this, "stopped")
                stopForeground(true)
                stopSelf()
            }
        }.apply { name = "ArenaMP-Server"; start() }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.server_notification_channel), NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(text: String): Notification {
        val openIntent = Intent(this, ServerActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, openIntent,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT)
        val stopIntent = Intent(this, ArenaServerService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 1, stopIntent,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.server_notification_title))
            .setContentText(text)
            .setContentIntent(pending)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.server_stop), stopPending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        stopRequested = true
        try { nativeStop() } catch (_: Throwable) {}
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.mrzer0x0.arenamp.server.START"
        const val ACTION_STOP = "com.mrzer0x0.arenamp.server.STOP"
        const val ACTION_STATUS = "com.mrzer0x0.arenamp.server.STATUS"
        const val EXTRA_AUTO_RESTART = "autoRestart"
        const val EXTRA_STATE = "state"
        const val EXTRA_EXIT_CODE = "exitCode"
        private const val CHANNEL_ID = "arenamp_server"
        private const val NOTIFICATION_ID = 4708
        private const val TAG = "ArenaMPServer"
    }
}
