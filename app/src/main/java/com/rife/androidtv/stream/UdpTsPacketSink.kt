package com.rife.androidtv.stream

import android.util.Log
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase F: puts finished transport packets on the wire as raw TS-over-UDP.
 *
 * A datagram carries a whole number of 188-byte transport packets - [DEFAULT_DATAGRAM_BYTES] is
 * seven of them, the 1316-byte payload every IPTV receiver and `udp://` player already expects -
 * so a receiver can resynchronise on any datagram boundary without hunting for a start code.
 *
 * The encoder's drain thread is the constraint that shapes this class: a sink on the encoded
 * stream must not block, and a blocking `send` is exactly what a full socket buffer produces. So
 * the muxer hands completed datagrams to a bounded queue and a sender thread does the socket
 * work. When the receiver cannot keep up the queue fills, and the newest datagram is dropped
 * rather than the oldest: a live stream that skips ahead stays in order and recovers at the next
 * PAT, while one that jumps backwards makes a player discard everything since the last key frame.
 * Dropped datagrams are counted, not silently absorbed.
 */
class UdpTsPacketSink(
    host: String,
    private val port: Int,
    private val bitrateBps: Int = 0,
    private val processingSize: String = "unknown",
    private val remoteOutputFps: Float = 0f,
    private val encoderCodec: String = "unknown",
    private val encoderProfile: String = "unknown",
    private val droppedVideoFrames: () -> Long = { 0L },
    private val maxDatagramBytes: Int = DEFAULT_DATAGRAM_BYTES,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) : TsPacketSink, Closeable {

    private class Slot(val buffer: ByteArray) {
        var length = 0
    }

    private val address: InetAddress = InetAddress.getByName(host)
    private val socket = DatagramSocket().apply {
        setReuseAddress(true)
        // Increase send buffer to handle bursts
        setSendBufferSize(256 * 1024)
    }

    /** Written by the drain thread, read by the drain thread. */
    private val staging = ByteArray(maxDatagramBytes)
    private var stagingLength = 0

    /** Slot inventory: every slot lives in exactly one of [free] or [pending]. */
    private val free = ArrayBlockingQueue<Slot>(queueCapacity)
    private val pending = ArrayBlockingQueue<Slot>(queueCapacity)

    @Volatile
    private var running = true

    private val senderThread: Thread

    private val datagramsSent = AtomicLong()
    private val bytesSent = AtomicLong()
    private val datagramsDropped = AtomicLong()
    private val windowStartMs = System.currentTimeMillis()
    @Volatile private var lastWindowLogMs = windowStartMs
    @Volatile private var lastWindowBytesSent = 0L

    /**
     * Wall clock spent inside `DatagramSocket.send` over the current report window, plus the
     * worst single send. Measurement only - the transport, the queue discipline and the drop
     * policy are untouched - but it is the difference between "the network is slow" and "the
     * socket blocked the drain thread", which look identical from the outside.
     */
    private var windowSendNs = 0L
    private var windowSendCount = 0L
    private var windowSendMaxNs = 0L

    /** Consecutive send failures, so a dead receiver is visible without logging every packet. */
    @Volatile
    private var consecutiveFailures = 0

    init {
        require(maxDatagramBytes > 0 && maxDatagramBytes % MpegTsMuxer.TS_PACKET_SIZE == 0) {
            "maxDatagramBytes ($maxDatagramBytes) must be a positive multiple of " +
                "${MpegTsMuxer.TS_PACKET_SIZE}"
        }
        require(queueCapacity > 0) { "queueCapacity must be positive" }
        repeat(queueCapacity) { free.add(Slot(ByteArray(maxDatagramBytes))) }

        senderThread = Thread({ sendLoop() }, "ts-udp-sender").also { it.start() }
        Log.i(TAG, "[UDP] host=$address port=$port processing=$processingSize remoteFps=$remoteOutputFps encoder=$encoderCodec/$encoderProfile bitrate=$bitrateBps audio=passthrough(E-AC-3|AAC-ADTS) subtitles=DVB-when-supported queue=${pending.size} droppedVideo=${droppedVideoFrames()} bytesPerDatagram=$maxDatagramBytes; raw UDP has no remote pause/seek control")
    }

    @Synchronized override fun onTsPacket(packet: ByteArray, length: Int) {
        if (!running) return
        if (length <= 0) return
        if (length % MpegTsMuxer.TS_PACKET_SIZE != 0) {
            Log.w(TAG, "dropping $length bytes: not a whole number of transport packets")
            return
        }

        var offset = 0
        while (offset < length) {
            val room = maxDatagramBytes - stagingLength
            val take = minOf(room, length - offset)
            System.arraycopy(packet, offset, staging, stagingLength, take)
            stagingLength += take
            offset += take
            if (stagingLength == maxDatagramBytes) emit()
        }
    }

    /** Sends whatever is still in the staging buffer as a final, short datagram. */
    @Synchronized fun flush() {
        if (stagingLength > 0) emit()
    }

    private fun emit() {
        val slot = free.poll()
        if (slot == null) {
            discardOldestPending()
            val reclaimed = free.poll()
            if (reclaimed != null) {
                System.arraycopy(staging, 0, reclaimed.buffer, 0, stagingLength)
                reclaimed.length = stagingLength
                stagingLength = 0
                if (!pending.offer(reclaimed)) {
                    reclaimed.length = 0
                    free.offer(reclaimed)
                    datagramsDropped.incrementAndGet()
                }
                return
            }
            stagingLength = 0
            datagramsDropped.incrementAndGet()
            return
        }
        System.arraycopy(staging, 0, slot.buffer, 0, stagingLength)
        slot.length = stagingLength
        stagingLength = 0
        if (!pending.offer(slot)) {
            free.offer(slot)
            datagramsDropped.incrementAndGet()
        }
    }

    /** Clear packets produced before a seek/source discontinuity so they cannot trail the new frame. */
    @Synchronized fun discardPending() {
        stagingLength = 0
        while (true) {
            val slot = pending.poll() ?: break
            slot.length = 0
            free.offer(slot)
        }
    }

    private fun discardOldestPending() {
        val stale = pending.poll() ?: return
        stale.length = 0
        free.offer(stale)
        datagramsDropped.incrementAndGet()
    }

    private fun sendLoop() {
        while (running || pending.isNotEmpty()) {
            val slot = try {
                pending.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } ?: continue
            try {
                val tSendNs = System.nanoTime()
                socket.send(DatagramPacket(slot.buffer, slot.length, address, port))
                val costNs = System.nanoTime() - tSendNs
                windowSendNs += costNs
                windowSendCount++
                if (costNs > windowSendMaxNs) windowSendMaxNs = costNs
                datagramsSent.incrementAndGet()
                bytesSent.addAndGet(slot.length.toLong())
                consecutiveFailures = 0
                val now = System.currentTimeMillis()
                if (now - lastWindowLogMs >= 5_000L) {
                    val elapsed = (now - lastWindowLogMs).coerceAtLeast(1L)
                    val totalBytes = bytesSent.get()
                    val rate = (totalBytes - lastWindowBytesSent).coerceAtLeast(0L) * 8.0 / (elapsed * 1000.0)
                    lastWindowBytesSent = totalBytes
                    val sendAvgUs = if (windowSendCount > 0) windowSendNs / windowSendCount / 1000 else 0L
                    val sendMaxUs = windowSendMaxNs / 1000
                    val sendCalls = windowSendCount
                    windowSendNs = 0
                    windowSendCount = 0
                    windowSendMaxNs = 0
                    Log.i(TAG, "[UDP] host=$address port=$port processing=$processingSize remoteFps=$remoteOutputFps encoder=$encoderCodec/$encoderProfile bitrate=$bitrateBps audio=passthrough(E-AC-3|AAC-ADTS) subtitles=DVB-when-supported packetsSent=${datagramsSent.get()} sendRateMbps=${String.format(java.util.Locale.US, "%.2f", rate)} sendAvgUs=$sendAvgUs sendMaxUs=$sendMaxUs sendCalls=$sendCalls queue=${pending.size} droppedVideo=${droppedVideoFrames()} droppedPackets=${datagramsDropped.get()} windowMs=$elapsed")
                    lastWindowLogMs = now
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (io: java.io.IOException) {
                // A transient send failure - an ICMP unreachable, a buffer full during a Wi-Fi
                // roam - must not kill the sender. The drain thread keeps producing into a queue
                // that is never drained, fills it, and drops every datagram from there on, which
                // looks exactly like a dead stream. Count and carry on.
                consecutiveFailures++
                if (consecutiveFailures == 1 || consecutiveFailures % 100 == 0) {
                    Log.w(TAG, "send failed ($consecutiveFailures in a row): ${io.message}")
                }
            } catch (other: Exception) {
                if (running) Log.w(TAG, "send failed: ${other.message}")
                break
            } finally {
                slot.length = 0
                free.add(slot)
            }
        }
    }

    override fun close() {
        if (!running) return
        flush()
        running = false
        try {
            senderThread.join(JOIN_TIMEOUT_MS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // Ensure socket is closed and port is released immediately
        try {
            socket.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing socket", e)
        }
        Log.i(
            TAG,
            "closed after ${datagramsSent.get()} datagrams (${bytesSent.get()} bytes), " +
                "${datagramsDropped.get()} dropped"
        )
    }

    /** Datagrams, bytes and drops since construction - for the transport log in Phase J. */
    fun stats(): Triple<Long, Long, Long> =
        Triple(datagramsSent.get(), bytesSent.get(), datagramsDropped.get())

    companion object {
        private const val TAG = "UdpTsPacketSink"

        /** Seven transport packets: the de-facto payload size for TS carried in UDP. */
        const val DEFAULT_DATAGRAM_BYTES = 1316

        /**
         * Datagrams of buffer. The old value of 32 was sized by a comment claiming "two seconds of
         * a 60 Mbps stream"; 32 x 1316 bytes is 42 kB, which is 5.6 ms at 60 Mbps - a single Wi-Fi
         * hiccup or one bursty encode cycle overflowed it and dropped packets, which is what a
         * receiver reports as Signal Interruption. 1024 is 1.35 MB, ~180 ms at 60 Mbps: long
         * enough to ride out a stall, short enough that it can never become a latency reservoir.
         */
        const val DEFAULT_QUEUE_CAPACITY = 1024

        /**
         * How long the sender parks between datagrams. Two milliseconds, not twenty: the whole
         * frame budget at 4K60 is 16.6 ms, and a 20 ms poll is a full frame of added latency on a
         * stream whose entire reason for existing is to be live.
         */
        private const val POLL_INTERVAL_MS = 2L
        private const val JOIN_TIMEOUT_MS = 2000L
    }
}
