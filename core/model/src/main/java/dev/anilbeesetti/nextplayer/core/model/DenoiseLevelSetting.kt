package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

/**
 * Strength of the motion-aligned denoiser, handed to the shader as the multiplier on its
 * motion-compensated history blend. It scales how strongly a pixel may be replaced by the frame
 * the motion field points at - it does not soften the picture: a sample is only ever replaced by
 * its own motion-compensated counterpart, and the per-pixel similarity gate still rejects the
 * moment the two frames disagree by more than their own noise does.
 */
@Serializable
enum class DenoiseLevelSetting(val strength: Float) {
    LIGHT(0.55f),
    BALANCED(1f),
    STRONG(1.5f),
}
