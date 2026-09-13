package voice

// ArenaMP U025 — голосовой сервис Android.
//
// Играет ту же роль, что лаунчер-коммутатор на ПК: владеет микрофоном и
// воспроизведением, держит сессию ArenaVoice и переживает переход
// MainActivity -> GameActivity. Движок общается с ним через тот же
// loopback-UDP мост, что и на ПК, поэтому C++ сторона не знает о платформе.
//
// ВАЖНО (Android 14 / API 34): foreground service типа microphone нельзя
// запустить из фона. startVoice() обязан вызываться, пока MainActivity на
// переднем плане — при включении тумблера голоса или в MainActivity перед
// startActivity(GameActivity), но НЕ из GameActivity.onCreate().

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class VoiceService : Service() {

    companion object {
        private const val TAG = "ArenaVoice"
        const val ACTION_START = "voice.START"
        const val ACTION_STOP = "voice.STOP"
        const val ACTION_TOGGLE_MUTE = "voice.TOGGLE_MUTE"
        const val EXTRA_SERVER_HOST = "serverHost"
        const val EXTRA_SERVER_PORT = "serverPort"      // игровой порт; голос = +1
        const val EXTRA_BRIDGE_PORT = "bridgePort"
        const val EXTRA_BRIDGE_TOKEN = "bridgeToken"

        private const val NOTIFICATION_ID = 7402        // рядом с LauncherUpdater.INSTALL_PERMISSION
        private const val CHANNEL_ID = "arenamp_voice"

        const val SAMPLE_RATE = 48000
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = SAMPLE_RATE / 1000 * FRAME_MS   // 960

        /** Запускать ТОЛЬКО пока Activity на переднем плане (см. шапку файла). */
        fun start(context: Context, host: String, gamePort: Int, bridgePort: Int, bridgeToken: String) {
            val intent = Intent(context, VoiceService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SERVER_HOST, host)
                putExtra(EXTRA_SERVER_PORT, gamePort)
                putExtra(EXTRA_BRIDGE_PORT, bridgePort)
                putExtra(EXTRA_BRIDGE_TOKEN, bridgeToken)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, VoiceService::class.java).apply { action = ACTION_STOP })
        }
    }

    private val running = AtomicBoolean(false)
    private val muted = AtomicBoolean(false)
    private val transmitting = AtomicBoolean(false)     // PTT или VAD

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var voiceSocket: DatagramSocket? = null
    private var bridgeSocket: DatagramSocket? = null

    private var serverHost: String = ""
    private var voicePort: Int = 0
    private var session: Int = 0
    private var ssrc: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopVoice(); stopSelf(); return START_NOT_STICKY }
            ACTION_TOGGLE_MUTE -> { muted.set(!muted.get()); updateNotification(); return START_STICKY }
        }

        serverHost = intent?.getStringExtra(EXTRA_SERVER_HOST).orEmpty()
        voicePort = (intent?.getIntExtra(EXTRA_SERVER_PORT, 25565) ?: 25565) + 1

        startForegroundCompat()
        startVoice(
            intent?.getIntExtra(EXTRA_BRIDGE_PORT, 0) ?: 0,
            intent?.getStringExtra(EXTRA_BRIDGE_TOKEN).orEmpty()
        )
        return START_STICKY
    }

    // ── foreground + уведомление ────────────────────────────────────────

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_LOW: без звука, но уведомление видно — игрок должен
            // понимать, почему горит индикатор микрофона (Android 12L+).
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.voice_title), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34 требует явный тип; манифест должен объявлять
            // foregroundServiceType="microphone" и разрешение
            // FOREGROUND_SERVICE_MICROPHONE.
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val toggle = PendingIntent.getService(
            this, 1,
            Intent(this, VoiceService::class.java).apply { action = ACTION_TOGGLE_MUTE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, VoiceService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle(getString(R.string.voice_title))
            .setContentText(getString(if (muted.get()) R.string.voice_state_muted else R.string.voice_state_active))
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null,
                getString(if (muted.get()) R.string.voice_unmute else R.string.voice_mute), toggle).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.voice_disable), stop).build())
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    // ── аудио ───────────────────────────────────────────────────────────

    private fun startVoice(bridgePort: Int, bridgeToken: String) {
        if (!running.compareAndSet(false, true)) return

        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            // VOICE_COMMUNICATION включает системные AEC/NS/AGC — без них
            // динамик телефона возвращает в канал собственное эхо.
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME_SAMPLES * 2 * 4)
            )
            record?.audioSessionId?.let { sessionId ->
                if (AcousticEchoCanceler.isAvailable())
                    echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                if (NoiseSuppressor.isAvailable())
                    noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }

            // USAGE_GAME, а НЕ VOICE_COMMUNICATION: последний уводит вывод
            // в разговорный динамик и приглушает игровой звук. Режим
            // AudioManager тоже не трогаем — MODE_IN_COMMUNICATION на части
            // прошивок ломает игровое аудио.
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
            val trackBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(trackBuffer, FRAME_SAMPLES * 4 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
                .build()

            selectCommunicationDevice()

            voiceSocket = DatagramSocket()
            bridgeSocket = if (bridgePort > 0) DatagramSocket(bridgePort) else null

            record?.startRecording()
            track?.play()

            thread(name = "arena-voice-capture") { captureLoop() }
            thread(name = "arena-voice-receive") { receiveLoop() }
            thread(name = "arena-voice-bridge") { bridgeLoop(bridgeToken) }
        } catch (error: SecurityException) {
            // RECORD_AUDIO отозвано во время работы.
            Log.w(TAG, "microphone permission missing", error)
            stopVoice()
            stopSelf()
        } catch (error: Throwable) {
            Log.e(TAG, "voice start failed", error)
            stopVoice()
            stopSelf()
        }
    }

    /** Bluetooth-гарнитура: без этого микрофон берётся со встроенного. */
    private fun selectCommunicationDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val manager = getSystemService(AudioManager::class.java) ?: return
        manager.availableCommunicationDevices
            .firstOrNull {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
            }
            ?.let { runCatching { manager.setCommunicationDevice(it) } }
    }

    private fun captureLoop() {
        val pcm = ShortArray(FRAME_SAMPLES)
        // TODO: OpusEncoder через JNI (libopus в NDK-части):
        //   opus_encoder_create(48000, 1, OPUS_APPLICATION_VOIP)
        //   OPUS_SET_BITRATE(24000), OPUS_SET_INBAND_FEC(1), OPUS_SET_DTX(1)
        while (running.get()) {
            val read = record?.read(pcm, 0, FRAME_SAMPLES) ?: break
            if (read <= 0) continue
            if (muted.get() || !transmitting.get()) continue
            // TODO: шумовой гейт → opus_encode → ArenaVoice.makeAudioUp → send
        }
    }

    private fun receiveLoop() {
        val buffer = ByteArray(1200)
        val packet = DatagramPacket(buffer, buffer.size)
        while (running.get()) {
            try {
                voiceSocket?.receive(packet) ?: break
            } catch (_: Throwable) {
                break
            }
            // TODO: разобрать заголовок AVX1 (тот же формат, что в
            // components/openmw-mp/arenavoice.hpp), для AUDIO_DOWN:
            //   opus_decode -> джиттер-буфер 40..120 мс
            //   gain/azimuth -> равномощная панорама (equalPowerPan)
            //   сложить до maxAudibleSpeakers потоков в один stereo-буфер
            //   с мягким ограничением и отдать в AudioTrack.
            // На слабых устройствах вместо этого приходит один MIXDOWN.
        }
    }

    private fun bridgeLoop(bridgeToken: String) {
        val buffer = ByteArray(1200)
        val packet = DatagramPacket(buffer, buffer.size)
        while (running.get()) {
            try {
                bridgeSocket?.receive(packet) ?: return
            } catch (_: Throwable) {
                return
            }
            if (!packet.address.isLoopbackAddress) continue
            // TODO: сверить bridgeToken, забрать тикет из BRIDGE_HELLO,
            // отправить HELLO на serverHost:voicePort, а из BRIDGE_STATE
            // взять PTT (transmitting) и канал речи.
        }
    }

    private fun stopVoice() {
        if (!running.compareAndSet(true, false)) return
        runCatching { record?.stop() }
        runCatching { track?.stop() }
        record?.release(); record = null
        track?.release(); track = null
        echoCanceler?.release(); echoCanceler = null
        noiseSuppressor?.release(); noiseSuppressor = null
        voiceSocket?.close(); voiceSocket = null
        bridgeSocket?.close(); bridgeSocket = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            runCatching { getSystemService(AudioManager::class.java)?.clearCommunicationDevice() }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopVoice()
        super.onDestroy()
    }

    /** Экономия батареи: игра свёрнута — микрофон отпускаем, сессию держим. */
    fun setForegroundState(active: Boolean) {
        transmitting.set(transmitting.get() && active)
        if (!active) muted.set(true)
        updateNotification()
    }

}
