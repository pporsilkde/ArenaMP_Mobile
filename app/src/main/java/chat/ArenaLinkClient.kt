package chat

// ArenaMP U025 — клиент ArenaLink для Android.
//
// Тот же протокол, что в components/openmw-mp/arenalink.hpp: постоянный TCP
// к игровому серверу на порт «игровой + 2», обычный сокет, без RakNet и без
// какого-либо веб-API. Пароль по сети не идёт: сервер шлёт nonce, клиент
// отвечает HMAC-SHA256(sha256(пароль), nonce).
//
// Один фоновый поток на чтение; колбэки уходят в главный поток через Handler.

import android.os.Handler
import android.os.Looper
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

object Alk {
    const val MAGIC = 0x414C4B31.toInt()      // 'ALK1'
    const val PROTOCOL = 1
    const val HEADER = 8
    const val MAX_PAYLOAD = 16384
    const val NONCE = 16
    const val TICKET = 16

    const val HELLO = 0x01
    const val CHALLENGE = 0x02
    const val AUTH = 0x03
    const val AUTH_OK = 0x04
    const val AUTH_FAIL = 0x05
    const val CHANNELS = 0x10
    const val JOIN_CHANNEL = 0x11
    const val HISTORY_REQ = 0x12
    const val MESSAGES = 0x13
    const val SEND = 0x14
    const val MESSAGE = 0x15
    const val PRESENCE = 0x20
    const val PRESENCE_DELTA = 0x21
    const val VOICE_TICKET_REQ = 0x30
    const val VOICE_TICKET = 0x31
    const val PING = 0x40
    const val PONG = 0x41
    const val NOTICE = 0x50

    const val AUTH_PROOF = 0
    const val AUTH_PLAIN = 1
    const val AUTH_CODE = 2

    const val CHANNEL_WRITABLE = 1
    const val CHANNEL_MIRRORS_GAME = 4
    const val MESSAGE_SYSTEM = 1
    const val MESSAGE_FROM_GAME = 2
}

data class LinkProfile(
    val userId: Int, val name: String, val level: Int,
    val color: Int, val className: String, val voicePort: Int
)

data class LinkChannel(val id: Int, val name: String, val writable: Boolean, val mirrorsGame: Boolean)

data class LinkMessage(
    val id: Long, val channel: Int, val userId: Int, val author: String,
    val level: Int, val color: Int, val text: String, val timestamp: Long,
    val system: Boolean, val fromGame: Boolean
)

/** Сборка кадра: big-endian, как в C++ стороне. */
private class Buf {
    val out = java.io.ByteArrayOutputStream()
    fun u8(v: Int) { out.write(v and 0xFF) }
    fun u16(v: Int) { u8(v shr 8); u8(v) }
    fun u32(v: Int) { u16(v shr 16); u16(v) }
    fun u64(v: Long) { u32((v shr 32).toInt()); u32(v.toInt()) }
    fun raw(b: ByteArray) { out.write(b) }
    fun text(s: String, limit: Int) {
        val bytes = s.toByteArray(Charsets.UTF_8).let { if (it.size > limit) it.copyOf(limit) else it }
        u8(bytes.size); raw(bytes)
    }
    fun text16(s: String, limit: Int) {
        val bytes = s.toByteArray(Charsets.UTF_8).let { if (it.size > limit) it.copyOf(limit) else it }
        u16(bytes.size); raw(bytes)
    }
    fun frame(type: Int): ByteArray {
        val payload = out.toByteArray()
        val head = Buf()
        head.u32(Alk.MAGIC); head.u8(type); head.u8(0); head.u16(payload.size)
        head.raw(payload)
        return head.out.toByteArray()
    }
}

private class Cur(val data: ByteArray, var pos: Int = 0, val end: Int = data.size) {
    var failed = false
    fun u8(): Int { if (pos + 1 > end) { failed = true; return 0 }; return data[pos++].toInt() and 0xFF }
    fun u16(): Int = (u8() shl 8) or u8()
    fun u32(): Int = (u16() shl 16) or u16()
    fun u64(): Long = (u32().toLong() and 0xFFFFFFFFL shl 32) or (u32().toLong() and 0xFFFFFFFFL)
    fun raw(n: Int): ByteArray {
        if (pos + n > end) { failed = true; return ByteArray(0) }
        val b = data.copyOfRange(pos, pos + n); pos += n; return b
    }
    fun text(): String = String(raw(u8()), Charsets.UTF_8)
    fun text16(): String = String(raw(u16()), Charsets.UTF_8)
}

class ArenaLinkClient {

    interface Listener {
        fun onLoggedIn(profile: LinkProfile)
        fun onLoginFailed(reason: Int, text: String)
        fun onChannels(channels: List<LinkChannel>)
        fun onMessage(message: LinkMessage)
        fun onHistory(channel: Int, messages: List<LinkMessage>)
        fun onVoiceTicket(ticket: ByteArray, port: Int, ttl: Int)
        fun onNotice(severity: Int, text: String)
        fun onDisconnected(reason: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private var reader: Thread? = null

    @Volatile var listener: Listener? = null
    @Volatile private var pendingName = ""
    @Volatile private var pendingSecret = ""
    @Volatile private var pendingMode = Alk.AUTH_PROOF
    @Volatile var authorized = false
        private set

    /** gamePort — игровой порт сервера; чат слушает на gamePort + 2. */
    fun connect(host: String, gamePort: Int, name: String, secret: String, useCode: Boolean) {
        disconnect()
        pendingName = name
        pendingSecret = secret
        pendingMode = if (useCode) Alk.AUTH_CODE else Alk.AUTH_PROOF

        reader = thread(name = "arena-link") {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, gamePort + 2), 8000)
                socket = s
                out = DataOutputStream(s.getOutputStream())

                val hello = Buf().apply { u16(Alk.PROTOCOL); u8(1); text("ArenaMP U025", 32) }
                send(hello.frame(Alk.HELLO))

                readLoop(s)
            } catch (e: Throwable) {
                fail(e.message ?: "Сервер недоступен")
            }
        }
    }

    fun disconnect() {
        authorized = false
        runCatching { socket?.close() }
        socket = null
        out = null
        pendingSecret = ""
    }

    fun joinChannel(channel: Int) = send(Buf().apply { u16(channel) }.frame(Alk.JOIN_CHANNEL))

    fun requestHistory(channel: Int, beforeId: Long, limit: Int) =
        send(Buf().apply { u16(channel); u64(beforeId); u16(limit) }.frame(Alk.HISTORY_REQ))

    fun sendMessage(channel: Int, text: String) =
        send(Buf().apply { u16(channel); text16(text, 4000) }.frame(Alk.SEND))

    fun requestVoiceTicket(scope: Int) =
        send(Buf().apply { u8(scope) }.frame(Alk.VOICE_TICKET_REQ))

    fun ping() = send(Buf().apply { u32((System.currentTimeMillis() / 1000).toInt()) }.frame(Alk.PING))

    private fun send(frame: ByteArray) {
        val stream = out ?: return
        try {
            synchronized(stream) { stream.write(frame); stream.flush() }
        } catch (e: Throwable) {
            fail(e.message ?: "Разрыв соединения")
        }
    }

    private fun fail(reason: String) {
        authorized = false
        main.post { listener?.onDisconnected(reason) }
    }

    private fun readLoop(s: Socket) {
        val input = s.getInputStream()
        var buffer = ByteArray(0)
        val chunk = ByteArray(8192)

        while (!s.isClosed) {
            val read = input.read(chunk)
            if (read <= 0) break
            buffer += chunk.copyOf(read)

            // Разбор потока: пока в буфере есть целый кадр — обрабатываем.
            while (buffer.size >= Alk.HEADER) {
                val cur = Cur(buffer)
                if (cur.u32() != Alk.MAGIC) { fail("Некорректные данные от сервера"); return }
                val type = cur.u8()
                cur.u8()
                val length = cur.u16()
                if (length > Alk.MAX_PAYLOAD) { fail("Слишком большой кадр"); return }
                if (buffer.size < Alk.HEADER + length) break
                val payload = buffer.copyOfRange(Alk.HEADER, Alk.HEADER + length)
                buffer = buffer.copyOfRange(Alk.HEADER + length, buffer.size)
                handle(type, payload)
            }
        }
        fail("Соединение закрыто")
    }

    private fun handle(type: Int, payload: ByteArray) {
        val c = Cur(payload)
        when (type) {
            Alk.CHALLENGE -> {
                val nonce = c.raw(Alk.NONCE)
                val serverMode = c.u8()
                val mode = if (pendingMode == Alk.AUTH_CODE) Alk.AUTH_CODE else serverMode
                val secret: ByteArray = if (mode == Alk.AUTH_PROOF) proof(pendingSecret, nonce)
                                        else pendingSecret.toByteArray(Charsets.UTF_8)
                val b = Buf().apply {
                    text(pendingName, 32); u8(mode)
                    u8(secret.size); raw(secret)
                }
                send(b.frame(Alk.AUTH))
                pendingSecret = ""            // пароль в памяти не держим
            }
            Alk.AUTH_OK -> {
                val profile = LinkProfile(
                    userId = c.u32(), name = c.text(), level = c.u16(),
                    color = c.u32(), className = c.text(), voicePort = c.u16()
                )
                c.u8()
                authorized = true
                main.post { listener?.onLoggedIn(profile) }
            }
            Alk.AUTH_FAIL -> {
                val reason = c.u8(); val text = c.text16()
                main.post { listener?.onLoginFailed(reason, text) }
            }
            Alk.CHANNELS -> {
                val count = c.u8()
                val list = ArrayList<LinkChannel>(count)
                repeat(count) {
                    val id = c.u16(); val name = c.text(); val flags = c.u8()
                    list += LinkChannel(id, name, flags and Alk.CHANNEL_WRITABLE != 0,
                        flags and Alk.CHANNEL_MIRRORS_GAME != 0)
                }
                main.post { listener?.onChannels(list) }
            }
            Alk.MESSAGE -> readMessage(c)?.let { m -> main.post { listener?.onMessage(m) } }
            Alk.MESSAGES -> {
                val channel = c.u16(); val count = c.u8()
                val list = ArrayList<LinkMessage>(count)
                repeat(count) { readMessage(c)?.let { list += it } }
                main.post { listener?.onHistory(channel, list) }
            }
            Alk.VOICE_TICKET -> {
                val ticket = c.raw(Alk.TICKET); val port = c.u16(); val ttl = c.u16()
                main.post { listener?.onVoiceTicket(ticket, port, ttl) }
            }
            Alk.NOTICE -> {
                val severity = c.u8(); val text = c.text16()
                main.post { listener?.onNotice(severity, text) }
            }
        }
    }

    private fun readMessage(c: Cur): LinkMessage? {
        val id = c.u64(); val ts = c.u32().toLong() and 0xFFFFFFFFL
        val channel = c.u16(); val userId = c.u32(); val author = c.text()
        val level = c.u16(); val color = c.u32(); val flags = c.u8(); val text = c.text16()
        if (c.failed) return null
        return LinkMessage(id, channel, userId, author, level, color, text, ts,
            flags and Alk.MESSAGE_SYSTEM != 0, flags and Alk.MESSAGE_FROM_GAME != 0)
    }

    /** HMAC-SHA256(sha256(пароль), nonce) — пароль остаётся на устройстве. */
    private fun proof(password: String, nonce: ByteArray): ByteArray {
        val key = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(nonce)
    }

    companion object {
        /** 0x00RRGGBB -> "#RRGGBB" для раскраски ника как в игре. */
        fun colorToHex(color: Int): String = String.format("#%06X", color and 0xFFFFFF)
    }
}
