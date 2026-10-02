package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

/**
 * Interpolation multiplier offered by the Video Processing settings entry: how many output frames
 * the interpolation synthesises for every source frame. 2x is one frame between each pair, 3x is
 * two, and so on - the actual output frame rate is that multiple of whatever the source runs at.
 */
@Serializable
enum class MemcLevelSetting(val multiplier: Float) {
    TWO_X(2f),
    THREE_X(3f),
}
