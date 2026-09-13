package ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Offline RakNet ping: no login, password or occupied player slot. */
class ServerStatusMonitor(private val changed: (Phase, Status) -> Unit) {
    enum class Phase { NO_ADDRESS, CHECKING, ONLINE, NO_RESPONSE }
    data class Status(val details: Boolean = false, val players: Int = 0, val capacity: Int = 0, val uptime: Long = 0)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    private val busy = AtomicBoolean(false)
    @Volatile private var active = false
    @Volatile private var socket: DatagramSocket? = null
    private var host = ""
    private var port = 0
    private val poll = object : Runnable {
        override fun run() { if (active) { query(); handler.postDelayed(this, 15000) } }
    }
    private var timeout: Runnable? = null

    fun setEndpoint(address: String, serverPort: Int) {
        val cleaned = address.trim().removeSurrounding("[", "]")
        if (cleaned == host && serverPort == port) return
        cancel()
        host = cleaned; port = serverPort
        changed(if (host.isEmpty() || port !in 1..65535) Phase.NO_ADDRESS else Phase.CHECKING, Status())
        handler.removeCallbacks(poll)
        if (active) handler.postDelayed(poll, 700)
    }
    fun start() { active = true; refresh() }
    fun stop() { active = false; handler.removeCallbacks(poll); cancel() }
    fun close() { stop(); worker.shutdownNow() }
    fun refresh() { if (active) { cancel(); handler.removeCallbacks(poll); handler.post(poll) } }
    private fun cancel() {
        generation.incrementAndGet()
        timeout?.let { handler.removeCallbacks(it) }; timeout = null
        socket?.close()
    }
    private fun query() {
        if (host.isEmpty() || port !in 1..65535) { changed(Phase.NO_ADDRESS, Status()); return }
        if (!busy.compareAndSet(false, true)) return
        val address = host; val targetPort = port; val request = generation.incrementAndGet()
        changed(Phase.CHECKING, Status())
        val limit = Runnable {
            if (active && generation.get() == request) {
                generation.incrementAndGet(); socket?.close(); changed(Phase.NO_RESPONSE, Status())
            }
        }
        timeout = limit; handler.postDelayed(limit, 3500)
        worker.execute {
            var answer: Status? = null
            try {
                val addresses = InetAddress.getAllByName(address).filter { !it.isMulticastAddress && !it.isAnyLocalAddress }
                if (active && generation.get() == request) DatagramSocket().use { udp ->
                    socket = udp
                    if (!active || generation.get() != request) return@use
                    val token = ByteArray(8).also { SecureRandom().nextBytes(it) }
                    val targets = addresses.take(4)
                    for (target in targets) for (width in listOf(8, 4)) {
                        val bytes = byteArrayOf(1) + token.copyOf(width) + MAGIC + ByteArray(8)
                        try { udp.send(DatagramPacket(bytes, bytes.size, target, targetPort)) } catch (_: Exception) { }
                    }
                    val deadline = SystemClock.elapsedRealtime() + 2800
                    while (active && generation.get() == request && SystemClock.elapsedRealtime() < deadline) {
                        udp.soTimeout = (deadline - SystemClock.elapsedRealtime()).toInt().coerceAtLeast(1)
                        val response = DatagramPacket(ByteArray(513), 513)
                        udp.receive(response)
                        if (response.port != targetPort || response.address !in targets || response.length > 512) continue
                        answer = decode(response.data.copyOf(response.length), token)
                        if (answer != null) break
                    }
                }
            } catch (_: Exception) { }
            finally { socket = null; busy.set(false) }
            val captured = answer
            handler.post {
                if (active && generation.get() == request) {
                    handler.removeCallbacks(limit); timeout = null
                    changed(if (captured == null) Phase.NO_RESPONSE else Phase.ONLINE, captured ?: Status())
                }
            }
        }
    }
    companion object {
        private val MAGIC = byteArrayOf(0, -1, -1, 0, -2, -2, -2, -2, -3, -3, -3, -3, 18, 52, 86, 120)
        internal fun decode(packet: ByteArray, token: ByteArray): Status? {
            if (packet.isEmpty() || packet.size > 512 || packet[0] != 28.toByte() || token.size != 8) return null
            for (width in listOf(8, 4)) {
                val header = 1 + width + 8 + 16
                if (packet.size < header || !packet.copyOfRange(1, 1 + width).contentEquals(token.copyOf(width))
                    || !packet.copyOfRange(1 + width + 8, header).contentEquals(MAGIC)) continue
                val fields = String(packet, header, packet.size - header, Charsets.US_ASCII).split('|')
                if (fields.size != 4 || fields[0] != "AMPSTATUS1") return Status()
                if (fields.drop(1).any { it.isEmpty() || it.length > 20 || it.any { ch -> ch !in '0'..'9' } }) return Status()
                val players = fields[1].toIntOrNull() ?: return Status()
                val capacity = fields[2].toIntOrNull() ?: return Status()
                val uptime = fields[3].toLongOrNull() ?: return Status()
                if (capacity !in 0..65535 || players !in 0..capacity || uptime !in 0..3155760000L) return Status()
                return Status(true, players, capacity, uptime)
            }
            return null
        }
    }
}
