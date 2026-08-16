package server

import android.content.Context
import android.content.Intent
import android.os.Build
import android.preference.PreferenceManager

object ServerController {
    const val PREF_AUTO_START = "pref_server_auto_start"
    const val PREF_AUTO_RESTART = "pref_server_auto_restart"
    const val PREF_SERVER_DEFAULTS_INITIALIZED = "pref_server_defaults_initialized"

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
        ServerRuntime.ensureInstalled(ctx)
        val intent = Intent(ctx, ArenaServerService::class.java)
            .setAction(ArenaServerService.ACTION_START)
            .putExtra(ArenaServerService.EXTRA_AUTO_RESTART, autoRestart)
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
    }

    fun stop(ctx: Context) {
        val intent = Intent(ctx, ArenaServerService::class.java).setAction(ArenaServerService.ACTION_STOP)
        ctx.startService(intent)
    }
}
