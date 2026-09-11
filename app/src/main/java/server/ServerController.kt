package server

import android.content.Context
import android.content.Intent
import android.os.Build
import android.preference.PreferenceManager
import android.util.Log
import file.UpdateLog

object ServerController {
    const val PREF_AUTO_START = "pref_server_auto_start"
    const val PREF_SERVER_ENABLED = "pref_server_enabled"
    const val PREF_AUTO_RESTART = "pref_server_auto_restart"
    const val PREF_SERVER_DEFAULTS_INITIALIZED = "pref_server_defaults_initialized"
    private const val TAG = "ArenaMPServer"

    fun initializeDesktopCompatibleDefaults(ctx: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        if (prefs.getBoolean(PREF_SERVER_DEFAULTS_INITIALIZED, false)) return
        val manifest = file.BuildManifest.read(ctx)
        val localDefault = manifest == null || !manifest.serverAddressSpecified
        prefs.edit()
            .putBoolean(PREF_AUTO_START, localDefault)
            .putBoolean(PREF_AUTO_RESTART, localDefault)
            .putBoolean(PREF_SERVER_DEFAULTS_INITIALIZED, true)
            .apply()
    }

    fun start(ctx: Context, autoRestart: Boolean = PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF_AUTO_RESTART, true)) {
        try {
            // APK replacement may have killed :arenamp_server while leaving its
            // external status file and previous asset fingerprint behind.
            ServerRuntime.prepareAfterPackageUpdate(ctx)
            ServerRuntime.reconcileProcessState(ctx, "controller_start")
            ServerRuntime.ensureInstalled(ctx)
            // Re-assert RU/EN immediately before every start as well, so a manually
            // edited/stale config cannot launch the server with an undefined locale.
            ServerScriptConfig.applyLauncherLanguage(ctx)
            val intent = Intent(ctx, ArenaServerService::class.java)
                .setAction(ArenaServerService.ACTION_START)
                .putExtra(ArenaServerService.EXTRA_AUTO_RESTART, autoRestart)
            UpdateLog.write(ctx, "server_start_request", "autoRestart=$autoRestart runtime=${ServerRuntime.root(ctx)}")
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "Could not start ArenaMP server", e)
            try { ServerRuntime.writeStatus(ctx, "error", 126) } catch (_: Throwable) {}
            UpdateLog.write(ctx, "server_start_error", e.message ?: e.javaClass.simpleName, e)
            throw e
        }
    }

    fun stop(ctx: Context) {
        val intent = Intent(ctx, ArenaServerService::class.java).setAction(ArenaServerService.ACTION_STOP)
        try {
            ctx.startService(intent)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not dispatch ArenaMP server stop", e)
            UpdateLog.write(ctx, "server_stop_error", e.message ?: e.javaClass.simpleName, e)
            // If the dedicated process is already gone, make the portable state
            // truthful instead of leaving a permanent "running" flag.
            ServerRuntime.reconcileProcessState(ctx, "controller_stop")
        }
    }
}
