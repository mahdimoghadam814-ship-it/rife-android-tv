package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

/**
 * Interpolation multiplier offered by the Video Processing settings entry: how many output frames
 * the interpolation synthesises for every source frame. 2x is one frame between each pair, 3x is
 * two, and so on - the actual output frame rate is that multiple of whatever the source runs at.
 *
 * The pipeline may snap the effective multiplier toward the rate that lands evenly on the
 * display refresh (24 fps x 2 = 48 and x 3 = 72 both miss 60 Hz; 2.5x is 60 exactly), clamped
 * into the same 2x-4x band. The setting picks the neighbourhood; the panel's arithmetic picks
 * the exact rate. The status overlay shows the effective value.
 */
@Serializable
enum class MemcLevelSetting(val multiplier: Float) {
    TWO_X(2f),
    THREE_X(3f),
}
