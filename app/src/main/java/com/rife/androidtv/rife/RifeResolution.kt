package com.rife.androidtv.rife

/**
 * Processing resolutions offered in the Video Processing settings entry. The longest edge of the
 * source frame is clamped to the selected size (aspect ratio preserved), so 1080p/720p/480p
 * materially reduce the pixels the interpolation stage has to read back, interpolate and
 * re-render. There is deliberately no AUTO value: the ladder that used to pick one for you
 * belonged to the removed RIFE/MEMC architecture, and a source is now processed at exactly the
 * size the user chose.
 */
enum class RifeResolution {
    ORIGINAL,
    RES_1080P,
    RES_720P,
    RES_480P
}
