package ui

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import com.libopenmw.openmw.R
import ui.activity.MainActivity

object BuildShortcut {
    private const val ID = "arenamp-build-launcher"

    fun request(context: Context, buildName: String) {
        // Android 8+ owns the confirmation UI; do not send legacy install broadcasts.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return
            val name = buildName.trim().ifBlank { "ArenaMP" }
            val shortcut = ShortcutInfo.Builder(context, ID)
                .setShortLabel(name)
                .setLongLabel(name)
                .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                })
                .build()
            // One launcher owns one selected build. Rename its existing shortcut.
            if (manager.pinnedShortcuts.any { it.id == ID }) {
                manager.updateShortcuts(listOf(shortcut))
            } else if (manager.isRequestPinShortcutSupported) {
                manager.requestPinShortcut(shortcut, null)
            }
        } catch (e: Exception) {
            Log.w("ArenaMP", "Could not pin build launcher shortcut", e)
        }
    }
}
