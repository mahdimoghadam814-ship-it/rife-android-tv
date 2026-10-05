package com.rife.androidtv.stream

import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream

/**
 * Phase D: writes finished transport packets to local disk so the bitstream can be validated
 * with ffprobe before any networking exists.
 *
 * The muxer already emits whole 188-byte packets, so this is a straight copy: the file is a
 * plain `.ts` that ffprobe, ffplay or any player can open directly, with no post-processing and
 * no second place for the stream to be reinterpreted. That matters here - Phase D's whole job is
 * to prove the bytes the encoder and muxer produced are correct, and anything that rewrote them
 * on the way to disk would make a passing test prove less than it appears to.
 *
 * The encoder's drain thread calls this, so it never blocks: it is a single unbuffered write of
 * an already-serialised packet to a local file, which cannot apply back pressure the way a
 * socket would.
 */
class FilePacketSink(private val file: File) : TsPacketSink, Closeable {

    companion object {
        private const val TAG = "FilePacketSink"
    }

    private val out = run {
        file.parentFile?.mkdirs()
        FileOutputStream(file)
    }

    var packetsWritten = 0L
        private set

    var bytesWritten = 0L
        private set

    init {
        Log.i(TAG, "writing ${file.absolutePath}")
    }

    override fun onTsPacket(packet: ByteArray, length: Int) {
        out.write(packet, 0, length)
        packetsWritten++
        bytesWritten += length.toLong()
    }

    override fun close() {
        runCatching { out.flush() }
        runCatching { out.close() }
        Log.i(TAG, "closed ${file.absolutePath}: packets=$packetsWritten bytes=$bytesWritten")
    }
}
