package voice

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Compatibility shim for the experimental U025 service name.
 *
 * U026 deliberately uses the native ArenaMP VoiceChat owned by the game process;
 * the launcher only stores the enable/PTT settings and asks for RECORD_AUDIO.
 * Keeping this tiny Service prevents old U025 source trees from failing to compile
 * when the cumulative archive is overlaid on them, without opening a second audio
 * capture pipeline next to the engine.
 */
class VoiceService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
