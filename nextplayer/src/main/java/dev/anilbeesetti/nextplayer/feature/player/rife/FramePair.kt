package dev.anilbeesetti.nextplayer.feature.player.rife

import androidx.media3.common.util.UnstableApi

@UnstableApi
data class FramePair(
    val previous: FrameMetadata,
    val current: FrameMetadata,
    val frameIntervalUs: Long
) {
    val isValid: Boolean
        get() = previous.frameId < current.frameId && frameIntervalUs > 0
}