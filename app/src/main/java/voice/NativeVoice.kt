package voice

/** JNI is implemented by MP/apps/openmw/mwmp/VoiceChat.cpp in libtes3mp. */
object NativeVoice {
    const val ENABLED = 1
    const val MICROPHONE = 2
    const val LOGGED_IN = 4
    const val TRANSMITTING = 8
    const val SPEAKING = 16
    const val READY = 32
    private var supported = true

    private external fun nativeSetForeground(active: Boolean)
    private external fun nativeSetPressed(held: Boolean)
    private external fun nativeState(): Int
    private external fun nativeSpeakers(): ByteArray

    // An old native library displays an unavailable state instead of crashing.
    fun foreground(active: Boolean) {
        if (!supported) return
        try { nativeSetForeground(active) } catch (_: UnsatisfiedLinkError) { supported = false }
    }
    fun press(held: Boolean) {
        if (!supported) return
        try { nativeSetPressed(held) } catch (_: UnsatisfiedLinkError) { supported = false }
    }
    fun state(): Int {
        if (!supported) return -1
        return try { nativeState() } catch (_: UnsatisfiedLinkError) { supported = false; -1 }
    }
    fun speakers(): String {
        if (!supported) return ""
        return try { String(nativeSpeakers(), Charsets.UTF_8) }
        catch (_: UnsatisfiedLinkError) { supported = false; "" }
    }
}
