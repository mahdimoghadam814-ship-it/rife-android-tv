package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

/**
 * Interpolation backends offered by the Video Processing settings entry.
 *
 * RIFE is the neural network (high quality, GPU-bound); MEMC is block-matching motion
 * estimation and compensation (classic, CPU-only, cheap enough for low-end set-top boxes);
 * SVPLAYER is the SVPflow-shaped search: hierarchical SATD/SAD matching with half-pixel
 * refinement, neighbour penalties, overlapping blocks and bad-area masking, exposed through
 * its own two-bar tuning surface rather than a single strength.
 */
@Serializable
enum class InterpolationAlgorithmSetting {
    SVPLAYER,
    MEMC,
    RIFE,
}
