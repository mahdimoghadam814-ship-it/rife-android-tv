package dev.anilbeesetti.nextplayer.feature.player.rife

import androidx.media3.common.util.UnstableApi

@UnstableApi
data class TemporalFrameStoreConfig(
    val maxHistorySize: Int = 3,
    val maxFrameAgeUs: Long = 500_000, // 500ms
    val retainKeyFrames: Boolean = true
)