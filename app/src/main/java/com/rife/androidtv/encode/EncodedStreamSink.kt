package com.rife.androidtv.encode

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * Receives everything a [HdrHevcEncoder] produces.
 *
 * In its own file because two things implement it and a third - the transport stream - has to be
 * compilable and testable without dragging the encoder's EGL and MediaCodec surface machinery
 * along with it.
 *
 * Both callbacks are invoked on the encoder's drain thread and must not block: the codec stops
 * producing as soon as the caller stops consuming, and a stalled drain thread stalls the whole
 * pipeline behind the encoder.
 */
interface EncodedStreamSink {
    /** Called once, when the codec reports its real output [MediaFormat]. */
    fun onOutputFormat(format: MediaFormat)

    /**
     * One encoded access unit, [MediaCodec.BufferInfo.size] bytes starting at
     * `data.arrayOffset() + data.position()`. Reused by the codec after the call returns, so a
     * sink that keeps the bytes must copy them.
     */
    fun onAccessUnit(data: ByteBuffer, info: MediaCodec.BufferInfo)
}
