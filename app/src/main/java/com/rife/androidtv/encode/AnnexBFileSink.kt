package com.rife.androidtv.encode

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Phase D's sink: every access unit the encoder produces, written to one file as an Annex-B
 * HEVC elementary stream, so the bitstream can be put in front of `ffprobe` before any muxing or
 * networking exists.
 *
 * MediaCodec hands the codec configuration (VPS/SPS/PPS) out as a buffer flagged
 * [MediaCodec.BUFFER_FLAG_CODEC_CONFIG], already in Annex-B with start codes, and then repeats it
 * in-band ahead of key frames. Only the first copy is written: repeating it per key frame is
 * legal but makes every later diagnostic that counts NAL units count the wrong thing.
 *
 * The whole point of this stage is to answer "is the stream standards-compatible and valid"
 * *before* blaming a player, so this class deliberately does nothing clever - no re-lengthing, no
 * timing rewrite, no container. Whatever the codec produced is what `ffprobe` reads.
 */
class AnnexBFileSink(private val file: File) : EncodedStreamSink {

    companion object {
        private const val TAG = "AnnexBFileSink"
    }

    private var out: FileOutputStream? = null
    private var configWritten = false
    private var format: MediaFormat? = null

    var unitsWritten = 0L
        private set
    var bytesWritten = 0L
        private set

    /** The output format the codec reported, captured for the ffprobe command line and logs. */
    val outputFormat: MediaFormat?
        get() = format

    init {
        file.parentFile?.mkdirs()
        out = FileOutputStream(file)
        Log.i(TAG, "writing ${file.absolutePath}")
    }

    override fun onOutputFormat(newFormat: MediaFormat) {
        format = newFormat
        Log.i(TAG, "output format: $newFormat")
    }

    override fun onAccessUnit(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        val stream = out ?: run {
            Log.w(TAG, "access unit after close() dropped")
            return
        }
        if (info.size <= 0) return
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            if (configWritten) return
            configWritten = true
            Log.i(TAG, "codec config: ${info.size} bytes")
        }
        val bytes = ByteArray(data.remaining())
        data.get(bytes)
        try {
            stream.write(bytes)
        } catch (t: Throwable) {
            Log.e(TAG, "write failed after $bytesWritten bytes", t)
            return
        }
        unitsWritten++
        bytesWritten += bytes.size
    }

    /** Closes the file and returns what was written, or null if the sink was never opened. */
    fun close(): Pair<Long, Long>? {
        val stream = out ?: return null
        out = null
        try {
            stream.flush()
            stream.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close failed: $t")
        }
        Log.i(
            TAG,
            "closed ${file.absolutePath}: units=$unitsWritten bytes=$bytesWritten " +
                "config=$configWritten"
        )
        return unitsWritten to bytesWritten
    }
}
