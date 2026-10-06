package com.rife.androidtv.rife

/**
 * Processing resolutions offered in the Video Processing settings entry. The longest edge of the
 * source frame is clamped to the selected size (aspect ratio preserved), so 1080p/720p/480p
 * materially reduce the pixels the RIFE stage has to read back, interpolate and re-render.
 */
enum class RifeResolution {
    /**
     * Let the engine pick: with MEMC on, process 720p at 480p, 1080p at 720p, and 4K at 1080p;
     * smaller sources stay native. With only denoising on, 4K starts native and degrades
     * stepwise while the native frame rate cannot be held.
     */
    AUTO,
    ORIGINAL,
    RES_1080P,
    RES_720P,
    RES_480P
}
