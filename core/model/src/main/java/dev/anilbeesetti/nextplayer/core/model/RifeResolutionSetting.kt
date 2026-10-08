package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

/**
 * Processing resolutions offered by the Video Processing settings entry.
 *
 * `AUTO` was the adaptive ladder that could hand 4K to 1080p or 1080p to 720p; it was removed
 * along with the RIFE/MEMC architecture it existed for, so a source is now always processed at
 * the resolution it declares (see the resolution policy in the pipeline). Older builds stored
 * `AUTO` here; [RifeResolutionSettingSerializer] maps that to [ORIGINAL] rather than failing
 * the preference store.
 */
@Serializable(with = RifeResolutionSettingSerializer::class)
enum class RifeResolutionSetting {
    ORIGINAL,
    RES_1080P,
    RES_720P,
    RES_480P,
}

/** Reads `AUTO` written by older builds as [RifeResolutionSetting.ORIGINAL]. Writes its own name. */
object RifeResolutionSettingSerializer :
    RemovedEnumEntrySerializer<RifeResolutionSetting>(
        serialName = "RifeResolutionSetting",
        entries = RifeResolutionSetting.entries.toTypedArray(),
        fallback = RifeResolutionSetting.ORIGINAL,
    )
