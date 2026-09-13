package voice

// ArenaMP U025 — запрос прав на голос.
//
// Три права одной цепочкой: микрофон (иначе нечего передавать),
// уведомления (иначе foreground service невидим и игрок не понимает,
// почему горит индикатор микрофона) и BLUETOOTH_CONNECT для гарнитур.

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

object VoicePermissions {

    fun required(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            permissions += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        return permissions.toTypedArray()
    }

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Вызывать только из Activity на переднем плане: FGS типа microphone
     * на Android 14+ нельзя стартовать из фона, а право нужно спросить до старта.
     */
    fun request(launcher: ActivityResultLauncher<Array<String>>) = launcher.launch(required())

    /** «Больше не спрашивать» — единственный путь остался через настройки. */
    fun permanentlyDenied(activity: AppCompatActivity): Boolean =
        !granted(activity) && !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)

    fun openAppSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/*
Подключение в MainActivity (пример):

    private val voicePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            // Стартуем ЗДЕСЬ, пока Activity на переднем плане.
            VoiceService.start(this, host, gamePort, bridgePort, bridgeToken)
        } else {
            prefs.edit().putBoolean("pref_voice_enabled", false).apply()
            toast(getString(R.string.voice_denied))
        }
    }

    // и в обработчике «Играть», перед startActivity(GameActivity):
    if (prefs.getBoolean("pref_voice_enabled", false)) {
        if (VoicePermissions.granted(this))
            VoiceService.start(this, host, gamePort, bridgePort, bridgeToken)
        else
            VoicePermissions.request(voicePermissionLauncher)
    }
*/
