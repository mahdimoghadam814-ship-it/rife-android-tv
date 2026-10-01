package dev.anilbeesetti.nextplayer.feature.player.rife

import androidx.media3.common.util.UnstableApi

@UnstableApi
interface TemporalFrameStore {
    fun addFrame(metadata: FrameMetadata): Boolean
    fun getLatestFramePair(): FramePair?
    fun getFrame(frameId: Long): FrameMetadata?
    fun evictOlderThan(timestampUs: Long): Int
    fun clear()
    fun size(): Int
    fun hasValidFramePair(): Boolean
    fun getConfig(): TemporalFrameStoreConfig
}