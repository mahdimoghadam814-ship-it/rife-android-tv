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
    private val maxDatagramBytes: Int = DEFAULT_DATAGRAM_BYTES,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) : TsPacketSink, Closeable {

    private class Slot(val buffer: ByteArray) {
        var length = 0
    }

    private val address: InetAddress = InetAddress.getByName(host)
    private val socket = DatagramSocket()

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
        Log.i(TAG, "streaming to $address:$port, $maxDatagramBytes bytes per datagram")
    }

    override fun onTsPacket(packet: ByteArray, length: Int) {
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
    fun flush() {
        if (stagingLength > 0) emit()
    }

    private fun emit() {
        val slot = free.poll()
        if (slot == null) {
            datagramsDropped.incrementAndGet()
            stagingLength = 0
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

    private fun sendLoop() {
        while (running || pending.isNotEmpty()) {
            val slot = try {
                pending.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } ?: continue
            try {
                socket.send(DatagramPacket(slot.buffer, slot.length, address, port))
                datagramsSent.incrementAndGet()
                bytesSent.addAndGet(slot.length.toLong())
                consecutiveFailures = 0
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
        socket.close()
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
         * Two seconds of a 60 Mbps stream. Sized so a slow receiver costs dropped datagrams
         * instead of a stalled encoder, while not so large that a stalled link buffers minutes of
         * latency before anything gives.
         */
        const val DEFAULT_QUEUE_CAPACITY = 512

        /**
         * How long the sender parks between datagrams. Two milliseconds, not twenty: the whole
         * frame budget at 4K60 is 16.6 ms, and a 20 ms poll is a full frame of added latency on a
         * stream whose entire reason for existing is to be live.
         */
        private const val POLL_INTERVAL_MS = 2L
        private const val JOIN_TIMEOUT_MS = 500L
    }
}
