package com.rife.androidtv.rife

import androidx.media3.common.util.UnstableApi

/**
 * Default implementation of TemporalFrameStore.
 * Maintains a bounded buffer of recent frames with configurable eviction policy.
 */
@UnstableApi
class TemporalFrameStoreImpl(
    private val config: TemporalFrameStoreConfig = TemporalFrameStoreConfig()
) : TemporalFrameStore {

    // Use a simple array-based ring buffer for efficiency
    private val frames = mutableListOf<FrameMetadata>()
    private var nextFrameId: Long = 0

    override fun addFrame(metadata: FrameMetadata): Boolean {
        // Check if we need to evict
        if (frames.size >= config.maxHistorySize) {
            // Remove oldest non-keyframe if possible, otherwise oldest frame
            val evictIndex = frames.indexOfFirst { !it.isKeyFrame }
            if (evictIndex >= 0) {
                frames.removeAt(evictIndex)
            } else if (frames.isNotEmpty()) {
                frames.removeAt(0)
            }
        }
        
        // Add new frame with assigned ID
        val newMetadata = metadata.copy(frameId = nextFrameId++)
        frames.add(newMetadata)
        
        // Evict old frames based on age
        evictOlderThan(newMetadata.presentationTimeUs - config.maxFrameAgeUs)
        
        return true
    }

    override fun getLatestFramePair(): FramePair? {
        if (frames.size < 2) return null
        
        val current = frames[frames.size - 1]
        val previous = frames[frames.size - 2]
        
        val intervalUs = current.presentationTimeUs - previous.presentationTimeUs
        if (intervalUs <= 0) return null
        
        return FramePair(previous, current, intervalUs)
    }

    override fun getFrame(frameId: Long): FrameMetadata? {
        return frames.find { it.frameId == frameId }
    }

    override fun evictOlderThan(timestampUs: Long): Int {
        val initialSize = frames.size
        frames.removeAll { it.presentationTimeUs < timestampUs && (!it.isKeyFrame || !config.retainKeyFrames) }
        return initialSize - frames.size
    }

    override fun clear() {
        frames.clear()
    }

    override fun size(): Int = frames.size

    override fun hasValidFramePair(): Boolean {
        return getLatestFramePair() != null
    }

    override fun getConfig(): TemporalFrameStoreConfig = config
}