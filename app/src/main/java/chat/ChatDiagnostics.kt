package chat

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Metadata only: callers must never pass credentials, payloads or exception messages. */
class ChatDiagnostics(private val preferred: File, private val fallback: File) {
    @Volatile var file: File = preferred
        private set
    @Volatile var writeFailed = false
        private set

    fun event(value: String) = synchronized(lock) {
        val clean = value.take(1024).replace('\r', ' ').replace('\n', ' ')
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        var written = false
        for (target in listOf(file, fallback).distinct()) {
            try {
                target.parentFile?.mkdirs()
                if (target.length() >= MAX_BYTES) {
                    val old = File(target.path + ".1")
                    if (old.exists() && !old.delete()) continue
                    if (!target.renameTo(old)) continue
                }
                target.appendText("$stamp $clean\n", Charsets.UTF_8)
                file = target
                written = true
                break
            } catch (_: Exception) { /* Logging must never interrupt a login. */ }
        }
        writeFailed = !written
    }

    fun snapshot(): String = synchronized(lock) {
        try {
            val old = File(file.path + ".1")
            val previous = if (old.isFile) old.readText(Charsets.UTF_8).takeLast(MAX_BYTES) else ""
            val current = if (file.isFile) file.readText(Charsets.UTF_8).takeLast(MAX_BYTES) else ""
            previous + current
        } catch (_: Exception) { "LOG_READ_FAILED\n" }
    }

    companion object {
        private val lock = Any()
        private const val MAX_BYTES = 1024 * 1024
    }
}
