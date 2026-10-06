package dev.anilbeesetti.nextplayer.feature.player

import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import dev.anilbeesetti.nextplayer.core.model.InterpolationAlgorithmSetting
import dev.anilbeesetti.nextplayer.core.model.RifeResolutionSetting
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.feature.player.rife.InterpolationAlgorithm
import dev.anilbeesetti.nextplayer.feature.player.rife.RifeController
import dev.anilbeesetti.nextplayer.feature.player.rife.RifeOutputView
import dev.anilbeesetti.nextplayer.feature.player.rife.RifeResolution
import dev.anilbeesetti.nextplayer.feature.player.rife.RifeStatusOverlay
import dev.anilbeesetti.nextplayer.feature.player.state.ControlsVisibilityState
import dev.anilbeesetti.nextplayer.feature.player.state.PlayerOrientationEffect
import dev.anilbeesetti.nextplayer.feature.player.state.rememberBrightnessState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberControlsVisibilityState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberErrorState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberPictureInPictureState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberSeekGestureState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberTapGestureState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberVideoZoomAndContentScaleState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberVolumeAndBrightnessGestureState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberVolumeState
import dev.anilbeesetti.nextplayer.feature.player.ui.PlayerErrorDialogs
import dev.anilbeesetti.nextplayer.feature.player.ui.PlayerGestures
import dev.anilbeesetti.nextplayer.feature.player.ui.PlayerVerticalGestureIndicators
import dev.anilbeesetti.nextplayer.feature.player.ui.SubtitleConfiguration
import dev.anilbeesetti.nextplayer.feature.player.ui.preview.rememberPreviewPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.seconds

val LocalControlsVisibilityState = compositionLocalOf<ControlsVisibilityState?> { null }
val LocalUseMaterialYouControls = compositionLocalOf { false }

@OptIn(UnstableApi::class)
@Composable
fun MediaPlayerScreen(
    viewModel: PlayerViewModel,
    player: Player?,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    MediaPlayerContent(
        player = player,
        state = state,
        onAction = viewModel::onAction,
    )
}

@OptIn(UnstableApi::class)
@Composable
internal fun MediaPlayerContent(
    player: Player?,
    state: PlayerUiState,
    onAction: (PlayerAction) -> Unit,
) {
    val playerPreferences = state.playerPreferences
    val isPreview = LocalInspectionMode.current
    // Layout previews have neither a PlayerActivity nor a live video/audio session.
    val volumeState = if (!isPreview) {
        rememberVolumeState(
            player = player,
            showVolumePanelIfHeadsetIsOn = playerPreferences.showSystemVolumePanel,
        )
    } else {
        null
    }
    if (player == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val context = LocalContext.current
    val controlsVisibilityState = rememberControlsVisibilityState(
        player = player,
        hideAfter = playerPreferences.controllerAutoHideTimeout.seconds,
    )
    val tapGestureState = rememberTapGestureState(
        player = player,
        doubleTapGesture = playerPreferences.doubleTapGesture,
        seekIncrementMillis = playerPreferences.seekIncrement.seconds.inWholeMilliseconds,
        useLongPressGesture = playerPreferences.useLongPressControls,
        longPressSpeed = playerPreferences.longPressControlsSpeed,
    )
    val seekGestureState = rememberSeekGestureState(
        player = player,
        sensitivity = playerPreferences.seekSensitivity,
        enableSeekGesture = playerPreferences.useSeekControls,
    )
    val pictureInPictureState = if (!isPreview) {
        rememberPictureInPictureState(
            player = player,
            autoEnter = playerPreferences.autoPip,
        )
    } else {
        null
    }
    val videoZoomAndContentScaleState = rememberVideoZoomAndContentScaleState(
        player = player,
        initialContentScale = playerPreferences.playerVideoZoom,
        enableZoomGesture = playerPreferences.useZoomControls,
        enablePanGesture = playerPreferences.enablePanGesture,
        onEvent = { onAction(PlayerAction.OnVideoZoomEvent(it)) },
    )
    val brightnessState = if (!isPreview) rememberBrightnessState() else null
    val volumeAndBrightnessGestureState = if (volumeState != null && brightnessState != null) {
        rememberVolumeAndBrightnessGestureState(
            volumeState = volumeState,
            brightnessState = brightnessState,
            enableVolumeGesture = playerPreferences.enableVolumeSwipeGesture,
            enableBrightnessGesture = playerPreferences.enableBrightnessSwipeGesture,
            volumeGestureSensitivity = playerPreferences.volumeGestureSensitivity,
            brightnessGestureSensitivity = playerPreferences.brightnessGestureSensitivity,
        )
    } else {
        null
    }
    if (!isPreview) {
        PlayerOrientationEffect(
            player = player,
            screenOrientation = playerPreferences.playerScreenOrientation,
        )
    }
    val errorState = rememberErrorState(player = player)

    LaunchedEffect(pictureInPictureState?.isInPictureInPictureMode) {
        if (pictureInPictureState?.isInPictureInPictureMode == true) controlsVisibilityState.hideControls()
    }
    LaunchedEffect(tapGestureState.isLongPressGestureInAction) {
        if (tapGestureState.isLongPressGestureInAction) controlsVisibilityState.hideControls()
    }
    if (brightnessState != null) {
        LifecycleEventEffect(Lifecycle.Event.ON_START) {
            if (playerPreferences.rememberPlayerBrightness) {
                brightnessState.setBrightness(playerPreferences.playerBrightness)
            }
        }
        LaunchedEffect(brightnessState.currentBrightness) {
            if (playerPreferences.rememberPlayerBrightness) {
                onAction(PlayerAction.UpdatePlayerBrightness(brightnessState.currentBrightness))
            }
        }
    }

    // The temporary engine status overlay: shown for ~5s whenever the controls become visible or
    // a processing setting changes. Never permanent, never consumes video space while hidden.
    var rifeStatusTrigger by remember { mutableIntStateOf(0) }
    var rifeStatusVisible by remember { mutableStateOf(false) }

    // The RIFE / FastDVDnet processing stage is applied whenever the corresponding preferences
    // change, including when the player screen is (re)opened with processing already enabled.
    val rifeController: RifeController = koinInject()
    val rifeProcessingEnabled by rifeController.processingEnabled.collectAsStateWithLifecycle()
    val rifeStats by rifeController.stats.collectAsStateWithLifecycle()

    // The bypass path lets MediaCodec write the colour volume onto the buffers itself. Once a
    // stage is on, frames come back through our own RGBA8888 window, which the display stack
    // reads as SDR - so PQ/HLG code values are shown through an sRGB curve and the picture comes
    // out flat and milky. Read the source's transfer characteristic and tag the output with the
    // matching dataspace so the panel decodes it as HDR again.
    var outputDataSpace by remember { mutableIntStateOf(0) }
    // Source dimensions, reported by the same listener that publishes the colour metadata. The
    // encoder has to open at the size the processing stage draws, and this is the only place the
    // player hands that size to the UI.
    var clipSourceSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    DisposableEffect(player) {
        fun publish(detected: Int?, source: String) {
            // `null` means the player handed over a track list with no video in it yet - the gap
            // between two items. Treating that as "the source is SDR" is what used to clear the
            // tag a moment before playback resumed, leaving the replay untagged.
            if (detected == null || detected == outputDataSpace) return
            Log.i(TAG, "Source output dataspace ($source): $detected")
            outputDataSpace = detected
        }
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                publish(tracks.outputDataSpace(), "tracks")
            }

            // Tracks are only reported when they change, so a replay that reuses the same track
            // list never fires onTracksChanged. The video format arriving is a second, independent
            // signal that the colour metadata is now known.
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                publish(player.currentTracks.outputDataSpace(), "videoSize")
                clipSourceSize = videoSize.width to videoSize.height
            }
        }
        player.addListener(listener)
        publish(player.currentTracks.outputDataSpace(), "initial")
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(outputDataSpace) {
        rifeController.setOutputDataSpace(outputDataSpace)
    }
    LaunchedEffect(
        playerPreferences.rifeEnabled,
        playerPreferences.fastDvdNetEnabled,
        playerPreferences.rifeResolution,
        playerPreferences.interpolationAlgorithm,
        playerPreferences.memcLevel,
        playerPreferences.denoiseLevel,
        playerPreferences.svPlayerSettings,
    ) {
        // Order matters: the algorithm has to be known before setRifeEnabled decides whether
        // the RIFE model is worth loading at all.
        rifeController.setInterpolationAlgorithm(
            playerPreferences.interpolationAlgorithm.toInterpolationAlgorithm(),
        )
        rifeController.setRifeEnabled(playerPreferences.rifeEnabled)
        rifeController.setFastDvdNetEnabled(playerPreferences.fastDvdNetEnabled)
        rifeController.setResolution(playerPreferences.rifeResolution.toRifeResolution())
        rifeController.setMemcLevel(playerPreferences.memcLevel.multiplier)
        rifeController.setDenoiseLevel(playerPreferences.denoiseLevel.strength)
        rifeController.setSvPlayerSettings(playerPreferences.svPlayerSettings)
        // The settings entry wants the engine status to be visible immediately after a change,
        // even when the controls are already visible.
        rifeStatusTrigger++
    }
    LaunchedEffect(
        playerPreferences.udpStreamingEnabled,
        playerPreferences.udpStreamingHost,
        playerPreferences.udpStreamingPort,
    ) {
        if (playerPreferences.udpStreamingEnabled) {
            rifeController.startUdpStream(
                playerPreferences.udpStreamingHost,
                playerPreferences.udpStreamingPort,
            )
        } else {
            rifeController.stopUdpStream()
        }
    }

    val controlsVisible = controlsVisibilityState.controlsVisible
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) rifeStatusTrigger++
    }
    LaunchedEffect(rifeStatusTrigger) {
        if (rifeStatusTrigger > 0) {
            rifeStatusVisible = true
            // One second shorter than before: the status is read at a glance, and it should not
            // outlive the controls that brought it up by much.
            delay(4000)
            rifeStatusVisible = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (pictureInPictureState != null) {
            // Real Next Player surface while nothing is processing. The processing path feeds the
            // same frame instead of replacing it, so content scale, pinch zoom, subtitles and the
            // shutter keep working with MEMC or denoise on.
            PlayerContentFrame(
                player = player,
                pictureInPictureState = pictureInPictureState,
                videoZoomAndContentScaleState = videoZoomAndContentScaleState,
                subtitleConfiguration = SubtitleConfiguration(
                    useSystemCaptionStyle = playerPreferences.useSystemCaptionStyle,
                    showBackground = playerPreferences.subtitleBackground,
                    font = playerPreferences.subtitleFont,
                    textSize = playerPreferences.subtitleTextSize,
                    textBold = playerPreferences.subtitleTextBold,
                    applyEmbeddedStyles = playerPreferences.applyEmbeddedStyles,
                ),
                processingContent = if (rifeProcessingEnabled) {
                    // The decoder renders into the processor input surface and the processed
                    // frames are rendered to this surface instead.
                    { RifeOutputView(rifeController) }
                } else {
                    null
                },
            )
            if (volumeAndBrightnessGestureState != null) {
                PlayerGestures(
                    controlsVisibilityState = controlsVisibilityState,
                    tapGestureState = tapGestureState,
                    pictureInPictureState = pictureInPictureState,
                    seekGestureState = seekGestureState,
                    videoZoomAndContentScaleState = videoZoomAndContentScaleState,
                    volumeAndBrightnessGestureState = volumeAndBrightnessGestureState,
                )
            }
        }
        // Phase D/E trigger: one press records, the next stops. The clip goes through the very
        // same encoder -> muxer -> sink chain the UDP path uses, so a file that ffprobe accepts
        // is direct evidence the live stream is well formed. The stop is wall-clock rather than
        // frame-count driven, which keeps any timing decision out of the codec path.
        val clipScope = rememberCoroutineScope()
        var clipRolling by remember { mutableStateOf(false) }
        val onRecordTestClip: () -> Unit = {
            if (clipRolling) {
                clipRolling = false
                rifeController.stopTestClip()
            } else {
                val source = clipSourceSize
                val width = source?.first ?: 0
                val height = source?.second ?: 0
                // The encoder has to be opened at the rate frames actually leave the pipeline, which
                // is the source rate times the interpolation ratio - not a constant. A 24 fps source
                // at 3x is 72 fps, and opening the codec at 60 makes the muxer generate a 60 fps
                // timeline for a 72 fps stream.
                val fps = rifeStats.outputFrameRate.takeIf { it > 0f }?.toInt()?.coerceIn(1, 240) ?: 60
                val path = File(context.filesDir, "phaseD_${System.currentTimeMillis()}.ts")
                if (width > 0 && height > 0 &&
                    rifeController.startTestClip(path.absolutePath, width, height, fps)
                ) {
                    clipRolling = true
                    clipScope.launch {
                        delay(5_000L)
                        if (clipRolling) {
                            clipRolling = false
                            rifeController.stopTestClip()
                        }
                    }
                } else {
                    Toast.makeText(context, R.string.record_test_clip, Toast.LENGTH_SHORT).show()
                }
            }
        }
        MediaPlayerControls(
            player = player,
            state = state,
            onAction = onAction,
            controlsVisibilityState = controlsVisibilityState,
            tapGestureState = tapGestureState,
            seekGestureState = seekGestureState,
            videoZoomAndContentScaleState = videoZoomAndContentScaleState,
            isPipSupported = pictureInPictureState?.isPipSupported == true,
            onPictureInPictureClick = {
                pictureInPictureState?.let {
                    if (!it.hasPipPermission) {
                        Toast.makeText(context, R.string.enable_pip_from_settings, Toast.LENGTH_SHORT).show()
                        it.openPictureInPictureSettings()
                    } else {
                        it.enterPictureInPictureMode()
                    }
                }
            },
            engineStatusOverlay = {
                RifeStatusOverlay(
                    visible = rifeStatusVisible,
                    stats = rifeStats,
                    rifeEnabled = playerPreferences.rifeEnabled,
                    fastDvdNetEnabled = playerPreferences.fastDvdNetEnabled,
                    resolution = playerPreferences.rifeResolution.toRifeResolution(),
                    algorithm = playerPreferences.interpolationAlgorithm.toInterpolationAlgorithm(),
                )
            },
        )
        if (volumeAndBrightnessGestureState != null && volumeState != null && brightnessState != null) {
            PlayerVerticalGestureIndicators(
                activeGesture = volumeAndBrightnessGestureState.activeGesture,
                volumePercentage = volumeState.volumePercentage,
                maxVolumePercentage = volumeState.maxVolumePercentage,
                brightnessPercentage = brightnessState.brightnessPercentage,
            )
        }
    }

    PlayerErrorDialogs(
        decoderRecoveryState = state.decoderServiceState.recoveryState,
        playbackError = errorState.playbackError,
        hasNextMediaItem = player.hasNextMediaItem(),
        onTryDecoderFallback = { onAction(PlayerAction.TryDecoderFallback) },
        onPlayNextVideo = {
            errorState.dismiss()
            player.seekToNext()
            player.play()
        },
        onExit = {
            errorState.dismiss()
            onAction(PlayerAction.NavigateUp)
        },
    )
}

@OptIn(UnstableApi::class)
@Preview(name = "Landscape", widthDp = 960, heightDp = 540)
@Preview(name = "Portrait", widthDp = 540, heightDp = 960)
@Composable
private fun MediaPlayerContentPreview() {
    NextPlayerTheme(darkTheme = true) {
        MediaPlayerContent(
            player = rememberPreviewPlayer(),
            state = PlayerUiState(),
            onAction = {},
        )
    }
}

private fun RifeResolutionSetting.toRifeResolution(): RifeResolution = when (this) {
    RifeResolutionSetting.AUTO -> RifeResolution.AUTO
    RifeResolutionSetting.ORIGINAL -> RifeResolution.ORIGINAL
    RifeResolutionSetting.RES_1080P -> RifeResolution.RES_1080P
    RifeResolutionSetting.RES_720P -> RifeResolution.RES_720P
    RifeResolutionSetting.RES_480P -> RifeResolution.RES_480P
}

private const val TAG = "MediaPlayerScreen"

/**
 * Dataspace the processed output must carry for this track list: BT.2020 PQ for an HDR10 source,
 * BT.2020 HLG for an HLG one, and `0` (UNKNOWN) for SDR so the platform default is left alone.
 *
 * The two numbers are `android.hardware.DataSpace.DATASPACE_BT2020_HLG/PQ` written out rather
 * than referenced, so nothing depends on that class being present at runtime below API 34.
 *
 * The result is logged because a container that never publishes a `ColorInfo` leaves this at 0
 * just like an SDR one, and the only way to tell those two apart from a log is to see that the
 * colour metadata was there at all.
 */
private fun Tracks.outputDataSpace(): Int? {
    if (groups.isEmpty()) return null
    var sawColorInfo = false
    for (group in groups) {
        for (index in 0 until group.length) {
            val format = group.getTrackFormat(index)
            // Dolby Vision keeps its transfer inside the codec config rather than in the colour
            // aspects Media3 publishes, so isTransferHdr() reports false for it while the panel
            // still has to be told PQ. Tagging nothing here leaves the processed path as plain
            // sRGB at the compositor - bypass hides this because the platform tags the decoder's
            // own surface - and that is what rendered HDR dim.
            if (format.sampleMimeType == "video/dolby-vision") {
                Log.i(TAG, "Dolby Vision track -> dataspace=163971072")
                return 163971072 // DataSpace.DATASPACE_BT2020_PQ
            }
            val colorInfo = format.colorInfo ?: continue
            sawColorInfo = true
            val dataSpace = when (colorInfo.colorTransfer) {
                C.COLOR_TRANSFER_HLG -> 168165376 // DataSpace.DATASPACE_BT2020_HLG
                C.COLOR_TRANSFER_ST2084 -> 163971072 // DataSpace.DATASPACE_BT2020_PQ
                else -> 0
            }
            if (dataSpace == 0) continue
            Log.i(TAG, "HDR transfer=${colorInfo.colorTransfer} -> dataspace=$dataSpace")
            return dataSpace
        }
    }
    if (sawColorInfo) {
        Log.i(TAG, "Colour metadata present but no HDR transfer; leaving dataspace unknown")
    }
    return 0 // DataSpace.DATASPACE_UNKNOWN
}

private fun InterpolationAlgorithmSetting.toInterpolationAlgorithm(): InterpolationAlgorithm =
    when (this) {
        InterpolationAlgorithmSetting.RIFE -> InterpolationAlgorithm.RIFE
        InterpolationAlgorithmSetting.MEMC -> InterpolationAlgorithm.MEMC
        InterpolationAlgorithmSetting.SVPLAYER -> InterpolationAlgorithm.SVPLAYER
    }
