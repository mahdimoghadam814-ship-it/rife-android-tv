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
 * Temporal blend offered by [SvPlayerSettings.blendAlgorithm], mirroring the SVP `algo` switch.
 * The rungs are cumulative, so each one keeps everything the one below it did:
 * BIDIRECTIONAL is the plain forward/backward average (`algo 11`), MEDIAN additionally takes the
 * per-pixel median so a single wrong vector cannot pull the result (`algo 13`, SVP's
 * minimum-artifact setting), and COVER additionally weights the two directions by their
 * cover/uncover masks so an uncovered region follows the frame that actually contains it
 * (`algo 21`).
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
 *
 * The shipped defaults are the strongest rung of every one of those, not a compromise between
 * them. SVPlayer at its *lowest* quality setting already out-produces a middle-of-the-road
 * configuration here, so there is nothing to be gained from starting a user out somewhere cheaper
 * than the reference implementation's floor. Anything that turns out to be too expensive belongs
 * behind the performance end of the bar, where it can be paid for deliberately.
 */
@Serializable
data class SvPlayerSettings(
    /** 0f is the performance end of the bar, 1f is the quality end. */
    val performanceQuality: Float = 1.0f,
    /** 0f disables bad-area masking, 1f masks every block that cannot be explained. */
    val artifactMaskLevel: Float = 1.0f,
    val blockSize: SvBlockSizeSetting = SvBlockSizeSetting.BLOCK_32X16,
    /** Search radius in pixels; 0 derives it from local contrast the way SVP's negative range does. */
    val searchDistance: Int = 16,
    /** Samples per pixel: 1 is whole-pixel, 2 is half-pixel. */
    val subpel: Int = 2,
    /** Overlap between neighbouring blocks in quarter-blocks: 0, 1 or 2. */
    val overlap: Int = 2,
    /** Cost of a vector that disagrees with its neighbours; SVP's `penalty.lambda`. */
    val penaltyLambda: Float = 30f,
    val blendAlgorithm: SvBlendAlgorithmSetting = SvBlendAlgorithmSetting.COVER,
    /** Backs off the search and the blend when the motion field says the scene just changed. */
    val sceneAdaptive: Boolean = true,
    /** Luma downscale the search runs at: 1 is full resolution, 2 is half. */
    val meScale: Int = 1,
)
