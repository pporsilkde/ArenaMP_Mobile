package voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Permission helper kept compatible with ArenaMP Mobile's compileSdk 29/minSdk line. */
object VoicePermissions {
    const val REQUEST_CODE = 4102

    fun granted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun request(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 23 && !granted(activity))
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
    }
}
