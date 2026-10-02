package dev.anilbeesetti.nextplayer.feature.player.rife

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Temporary engine status overlay.
 *
 * This is the only custom on-video information overlay. It is shown for about five seconds
 * whenever the playback controls become visible (user interaction) or a processing setting
 * changes, then fades out. It is never permanent and never consumes video space while hidden.
 *
 * The visual design (top-start, translucent black card, green monospace text) is the one the user
 * approved for the previous standalone player and is intentionally unchanged.
 */
@Composable
fun RifeStatusOverlay(
    visible: Boolean,
    videoTitle: String,
    stats: RifeStats,
    rifeEnabled: Boolean,
    fastDvdNetEnabled: Boolean,
    resolution: RifeResolution,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val resolutionName = when (resolution) {
            RifeResolution.AUTO -> "Auto"
            RifeResolution.ORIGINAL -> "Original"
            RifeResolution.RES_1080P -> "1080p"
            RifeResolution.RES_720P -> "720p"
            RifeResolution.RES_480P -> "480p"
        }
        val text = buildString {
            append("Video: ").append(videoTitle.ifBlank { "None" }).append('\n')
            append("Input FPS: ").append("%.1f".format(stats.inputFps))
                .append(" | Output FPS: ").append("%.1f".format(stats.outputFps)).append('\n')
            append("Resolution: ").append(resolutionName)
                .append(" | RIFE: ").append(if (rifeEnabled) "ON" else "OFF")
                .append(" | FastDVDnet (scaffold): ").append(if (fastDvdNetEnabled) "ON" else "OFF").append('\n')
            append("Processing Time: ").append(stats.processingTimeMs).append(" ms")
                .append(" | Dropped: ").append(stats.droppedFrames)
        }
        Text(
            text = text,
            color = Color(0xFF00E676),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(start = 16.dp, top = 16.dp)
                .background(Color(0xC0000000))
                .padding(8.dp),
        )
    }
}
