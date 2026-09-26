package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

/**
 * Processing resolutions offered by the Video Processing settings entry.
 */
@Serializable
enum class RifeResolutionSetting {
    ORIGINAL,
    RES_1080P,
    RES_720P,
    RES_480P,
}
