package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

/**
 * Search pattern offered by [SvBlockSizeSetting]. `AUTO` hands the choice to the performance
 * versus quality bar so the two never fight over it.
 */
@Serializable
enum class SvBlockSizeSetting {
    AUTO,
    BLOCK_16X8,
    BLOCK_32X8,
    BLOCK_32X16,
}

/**
 * Temporal blend offered by [SvPlayerSettings.blendAlgorithm], mirroring the SVP `algo` switch:
 * BIDIRECTIONAL is the plain forward/backward average, MEDIAN additionally takes the per-pixel
 * median of the two so a single wrong vector cannot pull the result, and COVER also weights the
 * two directions by their cover/uncover masks so an uncovered region follows the frame that
 * actually contains it.
 */
@Serializable
enum class SvBlendAlgorithmSetting {
    BIDIRECTIONAL,
    MEDIAN,
    COVER,
}

/**
 * Tuning surface for the SVPlayer interpolation algorithm.
 *
 * Two bars carry the whole design: [performanceQuality] is the master dial that moves the search
 * along the cost ladder (levels, search distance, subpel, block size, overlap, working
 * resolution), and [artifactMaskLevel] decides how aggressively blocks whose motion cannot be
 * explained are allowed to fall back to a conservative blend. Everything below them is the
 * expert escape hatch for a user who wants to set one rung of that ladder by hand.
 *
 * The values are deliberately on the same scales as the SVPflow configuration they are modelled
 * on, so the defaults read as recognisable rather than arbitrary: [penaltyLambda] is
 * `penalty.lambda`, [searchDistance] is `main.search.distance` with 0 meaning "derive it from the
 * local contrast", [subpel] is `super.pel`, [overlap] is `block.overlap` in quarter-blocks and
 * [meScale] is the luma downscale the search runs at.
 */
@Serializable
data class SvPlayerSettings(
    /** 0f is the performance end of the bar, 1f is the quality end. */
    val performanceQuality: Float = 0.6f,
    /** 0f disables bad-area masking, 1f masks every block that cannot be explained. */
    val artifactMaskLevel: Float = 0.5f,
    val blockSize: SvBlockSizeSetting = SvBlockSizeSetting.AUTO,
    /** Search radius in pixels; 0 derives it from local contrast the way SVP's negative range does. */
    val searchDistance: Int = 0,
    /** Samples per pixel: 1 is whole-pixel, 2 is half-pixel. */
    val subpel: Int = 2,
    /** Overlap between neighbouring blocks in quarter-blocks: 0, 1 or 2. */
    val overlap: Int = 2,
    /** Cost of a vector that disagrees with its neighbours; SVP's `penalty.lambda`. */
    val penaltyLambda: Float = 10f,
    val blendAlgorithm: SvBlendAlgorithmSetting = SvBlendAlgorithmSetting.MEDIAN,
    /** Backs off the search and the blend when the motion field says the scene just changed. */
    val sceneAdaptive: Boolean = true,
    /** Luma downscale the search runs at: 1 is full resolution, 2 is half. */
    val meScale: Int = 1,
)
