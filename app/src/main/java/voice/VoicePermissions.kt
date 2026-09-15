package voice

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.libopenmw.openmw.R

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

    /** Always explain why the microphone is requested before Android shows its system dialog. */
    fun requestExplained(activity: Activity) {
        if (granted(activity)) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.permission_mic_title)
            .setMessage(R.string.permission_mic_message)
            .setPositiveButton(R.string.permission_continue) { _, _ -> request(activity) }
            .setNegativeButton(R.string.permission_later, null)
            .show()
    }
}
