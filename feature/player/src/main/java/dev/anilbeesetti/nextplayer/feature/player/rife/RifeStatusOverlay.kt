package dev.anilbeesetti.nextplayer.feature.player.rife

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Temporary engine status overlay.
 *
 * Shown for a few seconds whenever the playback controls become visible (user interaction) or a
 * processing setting changes, then faded out. It carries every engine detail except the video
 * title, which the top bar already shows one row above it, and is hosted by [PlayerControls]
 * directly beneath that bar so the two read as one block.
 *
 * Styled from the player's Material 3 tokens so it matches the rest of the controls rather than
 * the old terminal-style green monospace card.
 */
@Composable
fun RifeStatusOverlay(
    visible: Boolean,
    stats: RifeStats,
    rifeEnabled: Boolean,
    fastDvdNetEnabled: Boolean,
    resolution: RifeResolution,
    algorithm: InterpolationAlgorithm,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val resolutionName = when (resolution) {
            RifeResolution.ORIGINAL -> "Original"
            RifeResolution.RES_1080P -> "1080p"
            RifeResolution.RES_720P -> "720p"
            RifeResolution.RES_480P -> "480p"
        }
        val algorithmName = when (algorithm) {
            InterpolationAlgorithm.SVPLAYER -> "SVPlayer"
        }
        val text = buildString {
            append("Input FPS: ").append("%.1f".format(stats.inputFps))
                .append(" | Output FPS: ").append("%.1f".format(stats.outputFps)).append('\n')
            append("Resolution: ").append(resolutionName)
                .append(" | Interpolation: ")
                .append(if (rifeEnabled) "ON ($algorithmName)" else "OFF")
                .append(" | Denoise: ").append(if (fastDvdNetEnabled) "ON" else "OFF").append('\n')
            append("Processing Time: ").append(stats.processingTimeMs).append(" ms")
                .append(" | Dropped: ").append(stats.droppedFrames)
        }
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
    }
}
