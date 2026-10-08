package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

/**
 * Interpolation backends offered by the Video Processing settings entry.
 *
 * SVPLAYER is the SVPflow-shaped search: hierarchical SATD/SAD matching with half-pixel
 * refinement, neighbour penalties, overlapping blocks and bad-area masking, exposed through
 * its own two-bar tuning surface rather than a single strength.
 *
 * It is now the only backend: the RIFE neural network and the plain MEMC baseline were removed,
 * so the entry is kept (rather than deleted) because the preference it stores is still the thing
 * that turns interpolation on, and the settings screen still offers it as a choice. Older builds
 * stored `RIFE` or `MEMC` here; see [InterpolationAlgorithmSettingSerializer].
 */
@Serializable(with = InterpolationAlgorithmSettingSerializer::class)
enum class InterpolationAlgorithmSetting {
    SVPLAYER,
}

/**
 * Reads `RIFE`/`MEMC` written by older builds as [InterpolationAlgorithmSetting.SVPLAYER]
 * instead of failing the preference store. Writes only `SVPLAYER`.
 */
object InterpolationAlgorithmSettingSerializer :
    RemovedEnumEntrySerializer<InterpolationAlgorithmSetting>(
        serialName = "InterpolationAlgorithmSetting",
        entries = InterpolationAlgorithmSetting.entries.toTypedArray(),
        fallback = InterpolationAlgorithmSetting.SVPLAYER,
    )
