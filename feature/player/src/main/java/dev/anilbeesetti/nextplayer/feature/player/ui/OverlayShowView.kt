package dev.anilbeesetti.nextplayer.feature.player.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.common.Player
import androidx.media3.extractor.metadata.Chapter
import dev.anilbeesetti.nextplayer.core.model.DenoiseLevelSetting
import dev.anilbeesetti.nextplayer.core.model.InterpolationAlgorithmSetting
import dev.anilbeesetti.nextplayer.core.model.MemcLevelSetting
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.core.model.RifeResolutionSetting
import dev.anilbeesetti.nextplayer.core.model.SvPlayerSettings
import dev.anilbeesetti.nextplayer.core.model.VideoContentScale
import dev.anilbeesetti.nextplayer.feature.player.extensions.noRippleClickable
import dev.anilbeesetti.nextplayer.feature.player.state.SubtitleOptionsEvent
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode

@Composable
fun BoxScope.OverlayShowView(
    player: Player,
    overlayView: OverlayView?,
    videoDecoderMode: DecoderMode?,
    audioDecoderMode: DecoderMode?,
    videoContentScale: VideoContentScale,
    chapters: List<Chapter>,
    currentChapterIndex: Int,
    onChapterSelected: (Chapter) -> Unit,
    onDismiss: () -> Unit = {},
    onVideoDecoderModeSelected: (DecoderMode) -> Unit = {},
    onAudioDecoderModeSelected: (DecoderMode) -> Unit = {},
    onSelectSubtitleClick: () -> Unit = {},
    onSelectAudioClick: () -> Unit = {},
    onSubtitleOptionEvent: (SubtitleOptionsEvent) -> Unit = {},
    onVideoContentScaleChanged: (VideoContentScale) -> Unit = {},
    processingPreferences: PlayerPreferences = PlayerPreferences(),
    onToggleInterpolation: () -> Unit = {},
    onToggleDenoise: () -> Unit = {},
    onResolutionSelected: (RifeResolutionSetting) -> Unit = {},
    onAlgorithmSelected: (InterpolationAlgorithmSetting) -> Unit = {},
    onMemcLevelSelected: (MemcLevelSetting) -> Unit = {},
    onDenoiseLevelSelected: (DenoiseLevelSetting) -> Unit = {},
    onSvSettingsChanged: (SvPlayerSettings) -> Unit = {},
    onRecordTestClip: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .then(
                if (overlayView != null) {
                    Modifier.noRippleClickable(onClick = onDismiss)
                } else {
                    Modifier
                },
            ),
    )

    AudioTrackSelectorView(
        show = overlayView == OverlayView.AUDIO_SELECTOR,
        player = player,
        onSelectAudioClick = onSelectAudioClick,
        onDismiss = onDismiss,
    )

    DecoderSelectorView(
        show = overlayView == OverlayView.DECODER_SELECTOR,
        videoMode = videoDecoderMode,
        audioMode = audioDecoderMode,
        onVideoModeSelected = onVideoDecoderModeSelected,
        onAudioModeSelected = onAudioDecoderModeSelected,
    )

    SubtitleSelectorView(
        show = overlayView == OverlayView.SUBTITLE_SELECTOR,
        player = player,
        onSelectSubtitleClick = onSelectSubtitleClick,
        onEvent = onSubtitleOptionEvent,
        onDismiss = onDismiss,
    )

    PlaybackSpeedSelectorView(
        show = overlayView == OverlayView.PLAYBACK_SPEED,
        player = player,
    )

    VideoContentScaleSelectorView(
        show = overlayView == OverlayView.VIDEO_CONTENT_SCALE,
        videoContentScale = videoContentScale,
        onVideoContentScaleChanged = onVideoContentScaleChanged,
        onDismiss = onDismiss,
    )

    PlaylistView(
        show = overlayView == OverlayView.PLAYLIST,
        player = player,
    )

    ChaptersView(
        show = overlayView == OverlayView.CHAPTERS,
        chapters = chapters,
        currentChapterIndex = currentChapterIndex,
        onChapterSelected = onChapterSelected,
    )

    ProcessingSettingsView(
        show = overlayView == OverlayView.PROCESSING_SETTINGS,
        playerPreferences = processingPreferences,
        onToggleInterpolation = onToggleInterpolation,
        onToggleDenoise = onToggleDenoise,
        onResolutionSelected = onResolutionSelected,
        onAlgorithmSelected = onAlgorithmSelected,
        onMemcLevelSelected = onMemcLevelSelected,
        onDenoiseLevelSelected = onDenoiseLevelSelected,
        onSvSettingsChanged = onSvSettingsChanged,
        onRecordTestClip = onRecordTestClip,
    )
}

val Configuration.isPortrait: Boolean
    get() = orientation == Configuration.ORIENTATION_PORTRAIT

enum class OverlayView {
    DECODER_SELECTOR,
    AUDIO_SELECTOR,
    SUBTITLE_SELECTOR,
    PLAYBACK_SPEED,
    VIDEO_CONTENT_SCALE,
    PLAYLIST,
    CHAPTERS,
    PROCESSING_SETTINGS,
}
