package com.rife.androidtv.offline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Parses the muxer's output back with a minimal EBML reader, so the assertions run against the
 * bytes a player would actually see rather than against internal state. Helpers are file-level
 * because a nested class cannot call the test class's members.
 */
class MatroskaMuxerTest {

    @Test
    fun `header carries ebml header and unknown-size segment`() {
        val file = mux { muxer ->
            muxer.addTrack(videoTrack())
            muxer.addVideoSample(byteArrayOf(0x01, 0x02), 0L, true)
            muxer.finish()
        }
        val top = readElements(file, 0)
        assertTrue(top.size >= 2)
        assertEquals(0x1A45DFA3, top[0].id)
        assertEquals(0x18538067, top[1].id)
        val info = top[1].children().first { it.id == 0x1549A966 }
        val scale = info.children().first { it.id == 0x2AD7B1 }
        assertEquals(MatroskaMuxer.TIMESTAMP_SCALE, readUint(scale.payload))
    }

    @Test
    fun `video and audio tracks appear with their codec ids`() {
        val file = mux { muxer ->
            muxer.addTrack(videoTrack())
            muxer.addTrack(audioTrack())
            muxer.addVideoSample(byteArrayOf(0x10), 0L, true)
            muxer.addAudioSample(2, byteArrayOf(0x20), 0L)
            muxer.finish()
        }
        val tracks = readElements(file, 0)[1].children()
            .first { it.id == 0x1654AE6B }
            .children()
            .filter { it.id == 0xAE }
        assertEquals(2, tracks.size)
        val numbers = tracks.map { entry ->
            readUint(entry.children().first { it.id == 0xD7 }.payload)
        }.toSet()
        assertEquals(setOf(1L, 2L), numbers)
        val video = tracks.first { entry -> entry.children().any { it.id == 0xE0 } }
        assertEquals(
            "V_MPEGH/ISO/HEVC",
            String(video.children().first { it.id == 0x86 }.payload, Charsets.US_ASCII),
        )
        val audio = tracks.first { entry -> entry.children().any { it.id == 0xE1 } }
        assertEquals(
            "A_OPUS",
            String(audio.children().first { it.id == 0x86 }.payload, Charsets.US_ASCII),
        )
    }

    @Test
    fun `video payload and pts survive the cluster round trip`() {
        val payload = ByteArray(64) { it.toByte() }
        val file = mux { muxer ->
            muxer.addTrack(videoTrack())
            muxer.addVideoSample(payload, 40_000L, true)
            muxer.finish()
        }
        val segment = readElements(file, 0)[1]
        val cluster = segment.children().first { it.id == 0x1F43B675 }
        val timecode = readUint(cluster.children().first { it.id == 0xE7 }.payload)
        assertEquals(40_000L * 1_000L / MatroskaMuxer.TIMESTAMP_SCALE, timecode)
        val body = cluster.children().first { it.id == 0xA3 }.payload
        // Track vint (0x81), int16 relative timecode, flags, then the payload.
        assertEquals(0x81, body[0].toInt() and 0xFF)
        assertEquals(0, body[1].toInt())
        assertEquals(0, body[2].toInt())
        assertEquals(0x80, body[3].toInt() and 0xFF)
        assertArrayEquals(payload, body.copyOfRange(4, body.size))
    }

    @Test
    fun `subtitle events become block groups with durations as ticks`() {
        val event = byteArrayOf('H'.code.toByte(), 'i'.code.toByte())
        val file = mux { muxer ->
            muxer.addTrack(videoTrack())
            muxer.addTrack(
                MatroskaMuxer.Track(
                    number = 3,
                    type = MatroskaMuxer.TRACK_TYPE_SUBTITLE,
                    codecId = "S_TEXT/UTF8",
                ),
            )
            muxer.addSubtitleEvents(3, listOf(MatroskaMuxer.SubtitleEvent(1_000_000L, event)))
            muxer.addVideoSample(byteArrayOf(0x01), 0L, true)
            muxer.finish()
        }
        val groups = readElements(file, 0)[1].children()
            .filter { it.id == 0x1F43B675 }
            .flatMap { it.children() }
            .filter { it.id == 0xA0 }
        assertEquals(1, groups.size)
        val group = groups[0].children()
        val block = group.first { it.id == 0xA1 }.payload
        assertEquals(0x83, block[0].toInt() and 0xFF)
        assertArrayEquals(event, block.copyOfRange(4, block.size))
        // Lone event: default display duration of 500 ms expressed in ticks.
        val duration = readUint(group.first { it.id == 0x9B }.payload)
        assertEquals(500_000L * 1_000L / MatroskaMuxer.TIMESTAMP_SCALE, duration)
    }

    @Test
    fun `cues hold one point per video keyframe at exact cluster offsets`() {
        val file = mux { muxer ->
            muxer.addTrack(videoTrack())
            // 300 ms spacing forces a cluster per sample: the span cap is 250 ms.
            muxer.addVideoSample(byteArrayOf(0x01), 0L, true)
            muxer.addVideoSample(byteArrayOf(0x02), 300_000L, true)
            muxer.addVideoSample(byteArrayOf(0x03), 600_000L, true)
            muxer.finish()
        }
        val top = readElements(file, 0)
        val segment = top[1]
        val points = segment.children()
            .first { it.id == 0x1C53BB6B }
            .children()
            .filter { it.id == 0xBB }
        assertEquals(3, points.size)
        val positions = points.map { point ->
            val inner = point.children()
            val time = readUint(inner.first { it.id == 0xB3 }.payload)
            val pos = inner.first { it.id == 0xB7 }.children()
                .first { it.id == 0xF1 }
            time to readUint(pos.payload)
        }
        // Cue times are ticks: 0 ms, 300 ms, 600 ms at 10 us per tick.
        assertEquals(
            listOf(0L, 30_000L, 60_000L),
            positions.map { it.first },
        )
        // Every recorded position must be the start of a real Cluster element.
        val clusters = segment.children().filter { it.id == 0x1F43B675 }
        assertEquals(3, clusters.size)
        val clusterStarts = clusters.map { it.startOffset }.toSet()
        for ((_, position) in positions) {
            val absolute = (segment.payloadOffset + position).toInt()
            assertTrue(
                "cue position $position is not a cluster start",
                absolute in clusterStarts,
            )
        }
    }

    @Test
    fun `cluster timestamps only move forward`() {
        val times = mutableListOf<Long>()
        val file = mux { muxer ->
            muxer.addTrack(videoTrack())
            for (i in 0 until 40) {
                muxer.addVideoSample(byteArrayOf(i.toByte()), i * 40_000L, i % 3 == 0)
            }
            muxer.finish()
        }
        readElements(file, 0)[1].children()
            .filter { it.id == 0x1F43B675 }
            .forEach { cluster ->
                times += readUint(cluster.children().first { it.id == 0xE7 }.payload)
            }
        assertTrue(times.size > 1)
        assertEquals(times, times.sorted())
        assertEquals(times.distinct(), times)
    }

    @Test
    fun `finish is idempotent and close after finish writes nothing new`() {
        val sink = ByteArrayOutputStream()
        val muxer = MatroskaMuxer(sink, durationUs = 1_000_000L)
        muxer.addTrack(videoTrack())
        muxer.addVideoSample(byteArrayOf(0x01), 0L, true)
        muxer.finish()
        val afterFinish = sink.size()
        muxer.finish()
        assertEquals(afterFinish, sink.size())
        muxer.close()
        assertEquals(afterFinish, sink.size())
    }

    @Test
    fun `samples without a video track are rejected`() {
        val muxer = MatroskaMuxer(ByteArrayOutputStream(), durationUs = 0L)
        muxer.addTrack(audioTrack())
        val thrown = runCatching { muxer.addVideoSample(byteArrayOf(0x01), 0L, true) }
        assertTrue(thrown.isFailure)
    }

    private fun mux(block: (MatroskaMuxer) -> Unit): ByteArray {
        val sink = ByteArrayOutputStream()
        val muxer = MatroskaMuxer(sink, durationUs = 2_000_000L, title = "Test")
        block(muxer)
        return sink.toByteArray()
    }

    private fun videoTrack() = MatroskaMuxer.Track(
        number = 1,
        type = MatroskaMuxer.TRACK_TYPE_VIDEO,
        codecId = "V_MPEGH/ISO/HEVC",
        codecPrivate = byteArrayOf(0x01, 0x02),
        width = 1920,
        height = 1080,
        frameRate = 72f,
    )

    private fun audioTrack() = MatroskaMuxer.Track(
        number = 2,
        type = MatroskaMuxer.TRACK_TYPE_AUDIO,
        codecId = "A_OPUS",
        language = "eng",
        sampleRate = 48_000,
        channels = 6,
    )
}

/** One parsed EBML element with absolute file offsets for position assertions. */
private class MkvElement(
    val id: Int,
    val payload: ByteArray,
    val startOffset: Int,
    val payloadOffset: Int,
) {
    fun children(): List<MkvElement> = readElements(payload, payloadOffset)
}

/** Reads consecutive EBML elements from [bytes]; [base] is the file offset of [bytes][0]. */
private fun readElements(bytes: ByteArray, base: Int): List<MkvElement> {
    val out = ArrayList<MkvElement>()
    var pos = 0
    while (pos < bytes.size) {
        val first = bytes[pos].toInt() and 0xFF
        if (first == 0) break
        var idLength = 1
        var probe = first
        while (probe and 0x80 == 0) {
            probe = probe shl 1
            idLength++
            if (idLength > 4) return out
        }
        var id = 0
        for (i in 0 until idLength) {
            id = (id shl 8) or (bytes[pos + i].toInt() and 0xFF)
        }
        val startOffset = base + pos
        pos += idLength
        if (pos >= bytes.size) break
        val sizeFirst = bytes[pos].toInt() and 0xFF
        var sizeLength = 1
        var p = sizeFirst
        while (p and 0x80 == 0) {
            p = p shl 1
            sizeLength++
            if (sizeLength > 8) return out
        }
        var size = (sizeFirst and ((0x80 shr (sizeLength - 1)).inv())).toLong()
        if (pos + sizeLength > bytes.size) break
        for (i in 1 until sizeLength) {
            size = (size shl 8) or (bytes[pos + i].toInt() and 0xFF).toLong()
        }
        pos += sizeLength
        // Unknown-size Segment: everything after the header belongs to it.
        if (id == 0x18538067 && sizeLength == 8 && size == 0x00FFFFFFFFFFFFFFL) {
            out += MkvElement(id, bytes.copyOfRange(pos, bytes.size), startOffset, base + pos)
            break
        }
        if (size < 0 || pos + size > bytes.size) break
        out += MkvElement(id, bytes.copyOfRange(pos, pos + size.toInt()), startOffset, base + pos)
        pos += size.toInt()
    }
    return out
}

private fun readUint(payload: ByteArray): Long {
    var value = 0L
    for (b in payload) {
        value = (value shl 8) or (b.toLong() and 0xFF)
    }
    return value
}
