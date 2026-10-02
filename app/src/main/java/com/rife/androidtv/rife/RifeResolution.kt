package com.rife.androidtv.rife

/**
 * Processing resolutions offered in the Video Processing settings entry. The longest edge of the
 * source frame is clamped to the selected size (aspect ratio preserved), so 1080p/720p/480p
 * materially reduce the pixels the RIFE stage has to read back, interpolate and re-render.
 */
enum class RifeResolution {
    /**
     * Let the engine pick: the source resolution below 4K, 1080p processing for a 4K source with
     * MEMC on, and native 4K for a 4K source with only the denoiser on (degrading stepwise while
     * the native frame rate cannot be held).
     */
    AUTO,
    ORIGINAL,
    RES_1080P,
    RES_720P,
    RES_480P
}
