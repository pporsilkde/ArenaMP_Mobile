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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

object Alk {
    const val MAGIC = 0x414C4B31.toInt()      // 'ALK1'
    const val PROTOCOL = 2
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
    const val AUTH_TES3MP_PROOF = 3

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
    fun u64(): Long = ((u32().toLong() and 0xFFFFFFFFL) shl 32) or (u32().toLong() and 0xFFFFFFFFL)
    fun raw(n: Int): ByteArray {
        if (pos + n > end) { failed = true; return ByteArray(0) }
        val b = data.copyOfRange(pos, pos + n); pos += n; return b
    }
    fun text(): String = String(raw(u8()), Charsets.UTF_8)
    fun text16(): String = String(raw(u16()), Charsets.UTF_8)
}

class ArenaLinkClient(private val diagnostics: ChatDiagnostics? = null) {

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
    private class Session(val id: Long, val name: String, var secret: String, val useCode: Boolean) {
        val started = System.nanoTime()
        @Volatile var stage = "connecting"
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val socket = Socket()
        val writer = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "arena-link-write").apply { isDaemon = true }
        }
        @Volatile var out: DataOutputStream? = null
        var challenged = false
    }
    private var nextAttempt = 0L
    @Volatile private var current: Session? = null
    @Volatile var listener: Listener? = null
    @Volatile var authorized = false
        private set

    private fun log(session: Session?, event: String) {
        val elapsed = if (session == null) 0L else (System.nanoTime() - session.started) / 1000000L
        diagnostics?.event("attempt=${session?.id ?: 0} elapsed_ms=$elapsed stage=${session?.stage ?: "idle"} $event")
    }

    /** A session owns its socket and tasks; an old reader cannot close a new login. */
    fun connect(host: String, gamePort: Int, name: String, secret: String, useCode: Boolean) {
        disconnect()
        if (host.isBlank() || gamePort !in 1..65533 || name.toByteArray(Charsets.UTF_8).size > 120 ||
            secret.toByteArray(Charsets.UTF_8).size > 128) {
            log(null, "LOCAL_VALIDATION_FAILED")
            main.post { listener?.onDisconnected("Invalid endpoint or credentials exceed protocol limits") }
            return
        }
        val session = Session(++nextAttempt, name, secret, useCode)
        current = session
        log(session, "CONNECT build=U031 protocol=${Alk.PROTOCOL} host=$host tcp_port=${gamePort + 2} name_present=${name.isNotEmpty()} password_present=${secret.isNotEmpty()}")
        thread(name = "arena-link-read", isDaemon = true) {
            try {
                val socket = session.socket
                socket.tcpNoDelay = true
                socket.soTimeout = 10000
                session.stage = "resolving_host"
                log(session, "DNS_START")
                val address = InetSocketAddress(host, gamePort + 2)
                log(session, "DNS_RESULT resolved=${!address.isUnresolved} address=${address.address?.hostAddress ?: "unresolved"}")
                session.stage = "connecting"
                socket.connect(address, 8000)
                log(session, "TCP_CONNECTED peer=${socket.inetAddress?.hostAddress} peer_port=${socket.port}")
                session.stage = "waiting_challenge"
                if (current !== session) return@thread
                session.out = DataOutputStream(socket.getOutputStream())
                send(Buf().apply { u16(Alk.PROTOCOL); u8(1); text("ArenaMP U031", 32); text(session.name, 120) }.frame(Alk.HELLO), session)
                session.writer.schedule({
                    if (current === session && !authorized) { log(session, "AUTH_TIMEOUT"); fail(session, "Chat sign-in timed out") }
                }, 10, TimeUnit.SECONDS)
                session.writer.scheduleAtFixedRate({
                    if (current === session && authorized) ping()
                }, 30, 30, TimeUnit.SECONDS)
                readLoop(session)
            } catch (e: Exception) {
                log(session, "SOCKET_EXCEPTION class=${e.javaClass.simpleName}")
                fail(session, e.message ?: "Chat service unavailable (TCP port ${gamePort + 2})")
            } finally {
                session.secret = ""
                runCatching { session.socket.close() }
                session.writer.shutdownNow()
            }
        }
    }

    fun disconnect() {
        val old = current
        log(old, "LOCAL_DISCONNECT")
        old?.finished?.set(true)
        current = null
        authorized = false
        old?.secret = ""
        runCatching { old?.socket?.close() }
        old?.writer?.shutdownNow()
    }

    fun joinChannel(channel: Int) = send(Buf().apply { u16(channel) }.frame(Alk.JOIN_CHANNEL))

    fun requestHistory(channel: Int, beforeId: Long, limit: Int) =
        send(Buf().apply { u16(channel); u64(beforeId); u16(limit) }.frame(Alk.HISTORY_REQ))

    fun sendMessage(channel: Int, text: String) =
        send(Buf().apply { u16(channel); text16(text, 4000) }.frame(Alk.SEND))

    fun requestVoiceTicket(scope: Int) =
        send(Buf().apply { u8(scope) }.frame(Alk.VOICE_TICKET_REQ))

    fun ping() = send(Buf().apply { u32((System.currentTimeMillis() / 1000).toInt()) }.frame(Alk.PING))

    private fun send(frame: ByteArray, session: Session? = current) {
        val active = session ?: return
        if (current !== active) return
        try {
            active.writer.execute {
                if (current === active) {
                    try {
                        active.out?.let {
                            it.write(frame); it.flush()
                            val type = if (frame.size >= Alk.HEADER) frame[4].toInt() and 255 else -1
                            log(active, "TX type=$type bytes=${frame.size}")
                        }
                    } catch (e: Exception) {
                        log(active, "WRITE_EXCEPTION class=${e.javaClass.simpleName}")
                        fail(active, e.message ?: "Connection lost")
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // A deliberate disconnect has already shut down this session.
        }
    }

    private fun fail(session: Session, reason: String) {
        if (!session.finished.compareAndSet(false, true)) return
        // reason is shown in UI only; never store server-provided text in the log.
        log(session, "CONNECTION_END socket_closed=${session.socket.isClosed}")
        runCatching { session.socket.close() }
        session.writer.shutdownNow()
        main.post {
            if (current === session) {
                current = null
                authorized = false
                listener?.onDisconnected(reason)
            }
        }
    }

    private fun post(session: Session, event: (Listener) -> Unit) {
        main.post { if (current === session) listener?.let(event) }
    }

    private fun readLoop(session: Session) {
        val s = session.socket
        val input = s.getInputStream()
        var buffer = ByteArray(0)
        val chunk = ByteArray(8192)

        while (!s.isClosed && current === session) {
            val read = input.read(chunk)
            if (read <= 0) { log(session, "TCP_EOF"); break }
            buffer += chunk.copyOf(read)

            // Разбор потока: пока в буфере есть целый кадр — обрабатываем.
            while (buffer.size >= Alk.HEADER) {
                val cur = Cur(buffer)
                if (cur.u32() != Alk.MAGIC) { log(session, "MALFORMED_MAGIC"); fail(session, "Некорректные данные от сервера"); return }
                val type = cur.u8()
                cur.u8()
                val length = cur.u16()
                if (length > Alk.MAX_PAYLOAD) { log(session, "FRAME_TOO_LARGE bytes=$length"); fail(session, "Слишком большой кадр"); return }
                if (buffer.size < Alk.HEADER + length) break
                val payload = buffer.copyOfRange(Alk.HEADER, Alk.HEADER + length)
                buffer = buffer.copyOfRange(Alk.HEADER + length, buffer.size)
                handle(session, type, payload)
            }
        }
        fail(session, "Соединение закрыто")
    }

    private fun handle(session: Session, type: Int, payload: ByteArray) {
        if (current !== session) return
        log(session, "RX type=$type bytes=${payload.size}")
        val c = Cur(payload)
        when (type) {
            Alk.CHALLENGE -> {
                val nonce = c.raw(Alk.NONCE)
                val serverMode = c.u8()
                val salt = c.text()
                log(session, "CHALLENGE mode=$serverMode salt_bytes=${salt.toByteArray(Charsets.UTF_8).size} valid=${!c.failed}")
                val mode = if (session.useCode) Alk.AUTH_CODE else serverMode
                if (c.failed || session.challenged || mode !in listOf(Alk.AUTH_PROOF, Alk.AUTH_CODE, Alk.AUTH_TES3MP_PROOF)) {
                    log(session, "AUTH_METHOD_UNSUPPORTED_OR_PROTOCOL_MISMATCH")
                    fail(session, "Chat server needs a supported secure sign-in method")
                    return
                }
                session.challenged = true
                val secret: ByteArray = if (mode == Alk.AUTH_TES3MP_PROOF) tes3mpProof(session.secret, nonce, salt)
                                        else if (mode == Alk.AUTH_PROOF) proof(session.secret, nonce)
                                        else session.secret.toByteArray(Charsets.UTF_8)
                val b = Buf().apply {
                    text(session.name, 120); u8(mode)
                    u8(secret.size); raw(secret)
                }
                session.stage = "waiting_auth_result"
                log(session, "AUTH_SEND mode=$mode")
                send(b.frame(Alk.AUTH), session)
                session.secret = ""            // пароль в памяти не держим
            }
            Alk.AUTH_OK -> {
                val profile = LinkProfile(
                    userId = c.u32(), name = c.text(), level = c.u16(),
                    color = c.u32(), className = c.text(), voicePort = c.u16()
                )
                c.u8()
                if (c.failed || !session.challenged) { log(session, "AUTH_OK_MALFORMED"); fail(session, "Malformed sign-in response"); return }
                session.stage = "authorized"
                log(session, "AUTH_OK")
                session.socket.soTimeout = 90000
                // State and its UI notification are serialized with disconnect/reconnect.
                post(session) { authorized = true; it.onLoggedIn(profile) }
            }
            Alk.AUTH_FAIL -> {
                val reason = c.u8(); val text = c.text16()
                session.finished.set(true)
                log(session, "AUTH_FAIL reason=$reason valid=${!c.failed}")
                post(session) {
                    disconnect()
                    it.onLoginFailed(reason, text)
                }
                session.socket.close()
            }
            Alk.CHANNELS -> {
                val count = c.u8()
                val list = ArrayList<LinkChannel>(count)
                repeat(count) {
                    val id = c.u16(); val name = c.text(); val flags = c.u8()
                    list += LinkChannel(id, name, flags and Alk.CHANNEL_WRITABLE != 0,
                        flags and Alk.CHANNEL_MIRRORS_GAME != 0)
                }
                post(session) { it.onChannels(list) }
            }
            Alk.MESSAGE -> readMessage(c)?.let { m -> post(session) { it.onMessage(m) } }
            Alk.MESSAGES -> {
                val channel = c.u16(); val count = c.u8()
                val list = ArrayList<LinkMessage>(count)
                repeat(count) { readMessage(c)?.let { list += it } }
                post(session) { it.onHistory(channel, list) }
            }
            Alk.VOICE_TICKET -> {
                val ticket = c.raw(Alk.TICKET); val port = c.u16(); val ttl = c.u16()
                post(session) { it.onVoiceTicket(ticket, port, ttl) }
            }
            Alk.NOTICE -> {
                val severity = c.u8(); val text = c.text16()
                post(session) { it.onNotice(severity, text) }
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

    private fun tes3mpProof(password: String, nonce: ByteArray, salt: String): ByteArray {
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        fun hexHash(value: String) = hex(hash(value.toByteArray(Charsets.UTF_8)))
        val first = hexHash(password)
        val gameHash = hexHash(first + hexHash(hexHash(first)))
        val key = hash((gameHash + salt).toByteArray(Charsets.UTF_8))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(nonce)
    }

    companion object {
        /** 0x00RRGGBB -> "#RRGGBB" для раскраски ника как в игре. */
        fun colorToHex(color: Int): String = String.format("#%06X", color and 0xFFFFFF)
    }
}
