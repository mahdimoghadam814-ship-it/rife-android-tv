package dev.anilbeesetti.nextplayer.feature.player

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.ProgressStateWithTickInterval
import dev.anilbeesetti.nextplayer.core.model.ControlButtonsPosition
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.feature.player.extensions.nameRes
import dev.anilbeesetti.nextplayer.feature.player.state.ChaptersState
import dev.anilbeesetti.nextplayer.feature.player.state.ControlsVisibilityState
import dev.anilbeesetti.nextplayer.feature.player.state.SeekGestureState
import dev.anilbeesetti.nextplayer.feature.player.state.VideoZoomAndContentScaleState
import dev.anilbeesetti.nextplayer.feature.player.state.seekAmountFormatted
import dev.anilbeesetti.nextplayer.feature.player.state.seekToPositionFormated
import dev.anilbeesetti.nextplayer.feature.player.ui.InfoView
import dev.anilbeesetti.nextplayer.feature.player.ui.OverlayView
import dev.anilbeesetti.nextplayer.feature.player.ui.controls.ControlsBottomView
import dev.anilbeesetti.nextplayer.feature.player.ui.controls.ControlsMiddleView
import dev.anilbeesetti.nextplayer.feature.player.ui.controls.ControlsTopView
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode

@OptIn(UnstableApi::class)
@Composable
fun PlayerControls(
    player: Player?,
    title: String,
    playerPreferences: PlayerPreferences,
    videoDecoderMode: DecoderMode?,
    controlsVisibilityState: ControlsVisibilityState,
    seekGestureState: SeekGestureState,
    videoZoomAndContentScaleState: VideoZoomAndContentScaleState,
    progressState: ProgressStateWithTickInterval,
    chaptersState: ChaptersState,
    isPipSupported: Boolean,
    onShowOverlay: (OverlayView) -> Unit,
    onBackClick: () -> Unit,
    onPlayInBackgroundClick: () -> Unit,
    onToggleTimeDisplay: () -> Unit,
    onPictureInPictureClick: () -> Unit,
    modifier: Modifier = Modifier,
    middleControlsModifier: Modifier = Modifier,
    engineStatusOverlay: @Composable () -> Unit = {},
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column {
            AnimatedVisibility(
                visible = controlsVisibilityState.controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                ControlsTopView(
                    title = title,
                    videoDecoderMode = videoDecoderMode,
                    onDecoderClick = { onShowOverlay(OverlayView.DECODER_SELECTOR) },
                    onAudioClick = { onShowOverlay(OverlayView.AUDIO_SELECTOR) },
                    onSubtitleClick = { onShowOverlay(OverlayView.SUBTITLE_SELECTOR) },
                    onPlaylistClick = { onShowOverlay(OverlayView.PLAYLIST) },
                    onBackClick = onBackClick,
                )
            }
            // Engine status sits directly under the title row so the two read as one block, and
            // follows the controls it belongs to instead of floating over the video on its own.
            // It takes the same horizontal insets and gutter as that row: without them it starts
            // at the raw edge of the window, which on a phone in landscape is under the status bar.
            AnimatedVisibility(
                visible = controlsVisibilityState.controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(
                    modifier = Modifier
                        .windowInsetsPadding(
                            WindowInsets.systemBars
                                .union(WindowInsets.displayCutout)
                                .only(WindowInsetsSides.Horizontal),
                        )
                        .padding(horizontal = 16.dp),
                ) {
                    engineStatusOverlay()
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            AnimatedVisibility(
                visible = controlsVisibilityState.controlsVisible && !controlsVisibilityState.controlsLocked,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                ControlsBottomView(
                    player = player,
                    progressState = progressState,
                    chaptersState = chaptersState,
                    onChaptersClick = { onShowOverlay(OverlayView.CHAPTERS) },
                    controlsAlignment = when (playerPreferences.controlButtonsPosition) {
                        ControlButtonsPosition.LEFT -> Alignment.Start
                        ControlButtonsPosition.RIGHT -> Alignment.End
                    },
                    videoContentScale = videoZoomAndContentScaleState.videoContentScale,
                    isPipSupported = isPipSupported,
                    showRemainingTime = playerPreferences.showRemainingTime,
                    onToggleTimeDisplay = onToggleTimeDisplay,
                    onSeek = seekGestureState::onSeek,
                    onSeekEnd = seekGestureState::onSeekEnd,
                    onPlaybackSpeedClick = { onShowOverlay(OverlayView.PLAYBACK_SPEED) },
                    onPlayInBackgroundClick = onPlayInBackgroundClick,
                    onLockControlsClick = {
                        controlsVisibilityState.showControls()
                        controlsVisibilityState.lockControls()
                    },
                    onVideoContentScaleClick = {
                        controlsVisibilityState.showControls()
                        videoZoomAndContentScaleState.switchToNextVideoContentScale()
                    },
                    onVideoContentScaleLongClick = { onShowOverlay(OverlayView.VIDEO_CONTENT_SCALE) },
                    onPictureInPictureClick = onPictureInPictureClick,
                    onProcessingSettingsClick = { onShowOverlay(OverlayView.PROCESSING_SETTINGS) },
                )
            }
        }
        when {
            seekGestureState.seekAmount != null -> InfoView(info = "${seekGestureState.seekAmountFormatted}\n[${seekGestureState.seekToPositionFormated}]")
            videoZoomAndContentScaleState.isZooming -> InfoView(info = "${(videoZoomAndContentScaleState.zoom * 100).toInt()}%")
            videoZoomAndContentScaleState.showContentScaleIndicator -> InfoView(info = stringResource(videoZoomAndContentScaleState.videoContentScale.nameRes()))
            controlsVisibilityState.controlsVisible -> ControlsMiddleView(
                player = player,
                modifier = middleControlsModifier,
            )
        }
    }
}
