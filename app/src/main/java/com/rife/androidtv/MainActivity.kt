package com.rife.androidtv

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.rife.androidtv.databinding.ActivityMainBinding
import com.rife.androidtv.ui.components.EngineStatusOverlay
import com.rife.androidtv.ui.components.NextPlayerControls
import com.rife.androidtv.ui.components.NextPlayerSettingsSheet
import com.rife.androidtv.ui.theme.NextPlayerTheme

@androidx.media3.common.util.UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private var videoFrameProcessor: VideoFrameProcessor? = null
    private var mediaPlaybackManager: MediaPlaybackManager? = null
    private var audioDelayProcessor: AudioDelayAudioProcessor? = null

    @Volatile
    private var isRifeModelLoaded = false
    private var videoName = "None"

    private val mainHandler = Handler(Looper.getMainLooper())

    // Compose State Holders
    private val isControlsVisible = mutableStateOf(true)
    private val isSettingsOpen = mutableStateOf(false)
    private val isEngineOverlayVisible = mutableStateOf(false)
    private val isVideoLoaded = mutableStateOf(false)
    private val isPlayingState = mutableStateOf(false)
    private val currentTimeMsState = mutableStateOf(0L)
    private val durationMsState = mutableStateOf(0L)
    private val isRifeEnabledState = mutableStateOf(false)
    private val isFastDvdNetEnabledState = mutableStateOf(false)
    private val rifeResolutionState = mutableStateOf(RifeResolution.ORIGINAL)
    private val audioOffsetState = mutableStateOf(0L)
    private val subtitleOffsetState = mutableStateOf(0L)
    private val currentStatsState = mutableStateOf<Statistics?>(null)

    private val hideControlsRunnable = Runnable {
        if (isPlayingState.value && !isSettingsOpen.value) {
            isControlsVisible.value = false
        }
    }

    private val hideEngineOverlayRunnable = Runnable {
        isEngineOverlayVisible.value = false
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val uri: Uri? = result.data?.data
            uri?.let {
                videoName = it.lastPathSegment ?: "Local Video"
                videoFrameProcessor?.resetForNewStream("new_video_selected")
                mediaPlaybackManager?.setVideoSource(it)
                isVideoLoaded.value = true
                showPlayerControls()
            }
        }
    }

    private val audioPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val uri: Uri? = result.data?.data
            uri?.let {
                val label = it.lastPathSegment ?: "External Audio"
                mediaPlaybackManager?.setExternalAudioSource(it, label)
                Toast.makeText(this, "External Audio Attached: $label", Toast.LENGTH_SHORT).show()
                showPlayerControls()
            }
        }
    }

    private val subPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val uri: Uri? = result.data?.data
            uri?.let {
                val label = it.lastPathSegment ?: "External Subtitle"
                mediaPlaybackManager?.setExternalSubtitleSource(it, null, label)
                Toast.makeText(this, "External Subtitle Attached: $label", Toast.LENGTH_SHORT).show()
                showPlayerControls()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupVideoFrameProcessor()
        setupPlayer()
        runDiagnosticsAndInitializeRife()
        setupComposeUI()

        startProgressUpdater()
    }

    private fun setupVideoFrameProcessor() {
        videoFrameProcessor = VideoFrameProcessor(
            displaySurfaceView = binding.displaySurfaceView,
            onStatisticsUpdated = { stats ->
                runOnUiThread {
                    currentStatsState.value = stats
                }
            },
            onError = { error ->
                runOnUiThread {
                    Toast.makeText(this, "RIFE Processing Error: $error", Toast.LENGTH_SHORT).show()
                }
            },
            onInputSurfaceCreated = { surface ->
                attachProcessingInputSurface(surface)
            },
            onInputSurfaceFailed = {
                fallBackToNormalPlayback("Input surface unavailable, processing disabled")
            }
        )
        videoFrameProcessor?.start()
    }

    private fun applyProcessingSurfaces() {
        val processor = videoFrameProcessor ?: return
        val currentPlayer = player ?: return

        if (processor.isProcessingEnabled) {
            binding.displaySurfaceView.visibility = View.VISIBLE
            binding.playerView.visibility = View.GONE
            processor.rifeInputSurface?.let { surface ->
                currentPlayer.setVideoSurface(surface)
                processor.onInputSurfaceAttached()
            }
        } else {
            binding.displaySurfaceView.visibility = View.GONE
            binding.playerView.visibility = View.VISIBLE
            restorePlayerSurface()
        }
    }

    private fun attachProcessingInputSurface(surface: Surface) {
        val currentPlayer = player ?: return
        val processor = videoFrameProcessor ?: return
        if (!processor.isProcessingEnabled) {
            return
        }
        currentPlayer.setVideoSurface(surface)
        processor.onInputSurfaceAttached()
    }

    private fun restorePlayerSurface() {
        val currentPlayer = player ?: return
        videoFrameProcessor?.onInputSurfaceDetached()
        when (val playerViewSurface = binding.playerView.getVideoSurfaceView()) {
            is SurfaceView -> {
                currentPlayer.clearVideoSurface()
                currentPlayer.setVideoSurfaceView(playerViewSurface)
            }
            is TextureView -> {
                currentPlayer.clearVideoSurface()
                currentPlayer.setVideoTextureView(playerViewSurface)
            }
            else -> {
                Toast.makeText(this, "Could not restore the player surface", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun fallBackToNormalPlayback(reason: String) {
        isRifeEnabledState.value = false
        isFastDvdNetEnabledState.value = false

        videoFrameProcessor?.setRifeEnabled(false)
        videoFrameProcessor?.setFastDvdNetEnabled(false)
        applyProcessingSurfaces()
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
    }

    private fun setupPlayer() {
        val audioProcessor = AudioDelayAudioProcessor()
        this.audioDelayProcessor = audioProcessor

        val renderersFactory = CustomRenderersFactory(this, audioProcessor)

        player = ExoPlayer.Builder(this, renderersFactory).build()
        binding.playerView.player = player

        mediaPlaybackManager = MediaPlaybackManager(
            context = this,
            player = player!!,
            audioDelayProcessor = audioProcessor
        )

        player?.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                mediaPlaybackManager?.updateTrackSelection()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                isPlayingState.value = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    durationMsState.value = player?.duration ?: 0L
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@MainActivity, "Playback Error: ${error.message}", Toast.LENGTH_LONG).show()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT ||
                    reason == Player.DISCONTINUITY_REASON_INTERNAL
                ) {
                    videoFrameProcessor?.resetPipeline("position_discontinuity_$reason")
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                videoFrameProcessor?.resetForNewStream("media_item_transition_$reason")
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoFrameProcessor?.setInputFrameSize(videoSize.width, videoSize.height)
            }
        })
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun setupComposeUI() {
        binding.composeView.setContent {
            NextPlayerTheme {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                Box(modifier = Modifier.fillMaxSize()) {
                    // INITIAL HOME / NO VIDEO STATE
                    if (!isVideoLoaded.value) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "Next Player",
                                        style = MaterialTheme.typography.headlineLarge.copy(
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = MaterialTheme.colorScheme.primary
                                    )

                                    Spacer(modifier = Modifier.height(24.dp))

                                    Button(
                                        onClick = { launchFilePicker() },
                                        modifier = Modifier.width(240.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.FolderOpen, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Open Video File")
                                    }
                                }
                            }
                        }
                    } else {
                        // NEXT PLAYER CONTROLS OVERLAY
                        NextPlayerControls(
                            visible = isControlsVisible.value,
                            isPlaying = isPlayingState.value,
                            title = videoName,
                            currentTimeMs = currentTimeMsState.value,
                            durationMs = durationMsState.value,
                            onPlayPauseToggle = { togglePlayPause() },
                            onSeek = { offsetMs -> performSeek(offsetMs) },
                            onSeekTo = { targetMs ->
                                player?.seekTo(targetMs)
                                currentTimeMsState.value = targetMs
                            },
                            onOpenFile = { launchFilePicker() },
                            onOpenAudioTracks = { showTrackSelectionDialog(C.TRACK_TYPE_AUDIO, "Audio Tracks") },
                            onOpenSubTracks = { showTrackSelectionDialog(C.TRACK_TYPE_TEXT, "Subtitle Tracks") },
                            onOpenSettings = { isSettingsOpen.value = true },
                            onUserInteraction = { showPlayerControls() }
                        )

                        // 5-SECOND TEMPORARY ENGINE STATUS OVERLAY (TOP LEFT)
                        EngineStatusOverlay(
                            visible = isEngineOverlayVisible.value,
                            videoName = videoName,
                            extAudioName = if (mediaPlaybackManager?.isExternalAudioSelected == true) mediaPlaybackManager?.externalAudioName ?: "None" else "None",
                            extSubName = if (mediaPlaybackManager?.isExternalSubtitleEnabled == true) mediaPlaybackManager?.externalSubtitleName ?: "None" else "None",
                            audioOffsetMs = audioOffsetState.value,
                            subOffsetMs = subtitleOffsetState.value,
                            isRifeEnabled = isRifeEnabledState.value,
                            isFastDvdNetEnabled = isFastDvdNetEnabledState.value,
                            stats = currentStatsState.value,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(16.dp)
                        )

                        // NEXT PLAYER SETTINGS SHEET
                        if (isSettingsOpen.value) {
                            NextPlayerSettingsSheet(
                                sheetState = sheetState,
                                onDismissRequest = { isSettingsOpen.value = false },
                                isRifeEnabled = isRifeEnabledState.value,
                                onRifeToggle = { enabled -> toggleRife(enabled) },
                                rifeResolution = rifeResolutionState.value,
                                onResolutionSelect = { res -> setResolution(res) },
                                isFastDvdNetEnabled = isFastDvdNetEnabledState.value,
                                onFastDvdNetToggle = { enabled -> toggleFastDvdNet(enabled) },
                                onOpenAudioTracks = { showTrackSelectionDialog(C.TRACK_TYPE_AUDIO, "Audio Tracks") },
                                onOpenSubTracks = { showTrackSelectionDialog(C.TRACK_TYPE_TEXT, "Subtitle Tracks") },
                                onOpenExternalAudio = { launchAudioPicker() },
                                onOpenExternalSub = { launchSubPicker() },
                                audioOffsetMs = audioOffsetState.value,
                                onAudioOffsetChange = { offset -> setAudioOffset(offset) },
                                subtitleOffsetMs = subtitleOffsetState.value,
                                onSubtitleOffsetChange = { offset -> setSubtitleOffset(offset) }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun launchFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        filePickerLauncher.launch(intent)
    }

    private fun launchAudioPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        audioPickerLauncher.launch(intent)
    }

    private fun launchSubPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        subPickerLauncher.launch(intent)
    }

    private fun togglePlayPause() {
        player?.let { p ->
            if (p.isPlaying) {
                p.pause()
            } else {
                p.play()
            }
        }
        showPlayerControls()
    }

    private fun performSeek(offsetMs: Long) {
        player?.let { p ->
            val newPosition = (p.currentPosition + offsetMs).coerceIn(0, p.duration.coerceAtLeast(0))
            p.seekTo(newPosition)
            currentTimeMsState.value = newPosition
        }
        showPlayerControls()
    }

    private fun toggleRife(enabled: Boolean) {
        if (enabled && !isRifeModelLoaded) {
            Toast.makeText(this, "Cannot enable RIFE: Model not loaded.", Toast.LENGTH_LONG).show()
            isRifeEnabledState.value = false
            return
        }

        isRifeEnabledState.value = enabled
        videoFrameProcessor?.setRifeEnabled(enabled)
        applyProcessingSurfaces()

        triggerEngineStatusOverlay()
        showPlayerControls()
    }

    private fun toggleFastDvdNet(enabled: Boolean) {
        isFastDvdNetEnabledState.value = enabled
        videoFrameProcessor?.setFastDvdNetEnabled(enabled)
        applyProcessingSurfaces()

        triggerEngineStatusOverlay()
        showPlayerControls()
    }

    private fun setResolution(res: RifeResolution) {
        rifeResolutionState.value = res
        videoFrameProcessor?.resolution = res
        triggerEngineStatusOverlay()
        showPlayerControls()
    }

    private fun setAudioOffset(offsetMs: Long) {
        audioOffsetState.value = offsetMs
        mediaPlaybackManager?.setAudioOffset(offsetMs)
        triggerEngineStatusOverlay()
    }

    private fun setSubtitleOffset(offsetMs: Long) {
        subtitleOffsetState.value = offsetMs
        mediaPlaybackManager?.setSubtitleOffset(offsetMs)
        triggerEngineStatusOverlay()
    }

    private fun triggerEngineStatusOverlay() {
        isEngineOverlayVisible.value = true
        mainHandler.removeCallbacks(hideEngineOverlayRunnable)
        mainHandler.postDelayed(hideEngineOverlayRunnable, 5000)
    }

    private fun showPlayerControls() {
        isControlsVisible.value = true
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, 5000)
    }

    private fun showTrackSelectionDialog(trackType: Int, title: String) {
        val p = player ?: return
        val tracks = p.currentTracks

        val trackGroups = mutableListOf<Tracks.Group>()
        val trackNames = mutableListOf<String>()

        trackNames.add("Disabled")

        for (group in tracks.groups) {
            if (group.type == trackType) {
                trackGroups.add(group)
                val mediaTrackGroup = group.mediaTrackGroup
                for (i in 0 until mediaTrackGroup.length) {
                    val format = mediaTrackGroup.getFormat(i)
                    val label = format.label ?: format.language ?: "Track ${trackNames.size}"
                    trackNames.add(label)
                }
            }
        }

        if (trackNames.size <= 1) {
            Toast.makeText(this, "No embedded $title found in media", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setItems(trackNames.toTypedArray()) { dialog, which ->
                if (which == 0) {
                    p.trackSelectionParameters = p.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(trackType, true)
                        .build()
                } else {
                    var selectedIdx = 1
                    for (group in trackGroups) {
                        val mediaTrackGroup = group.mediaTrackGroup
                        for (i in 0 until mediaTrackGroup.length) {
                            if (selectedIdx == which) {
                                p.trackSelectionParameters = p.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(trackType, false)
                                    .setOverrideForType(
                                        TrackSelectionOverride(mediaTrackGroup, i)
                                    )
                                    .build()
                                break
                            }
                            selectedIdx++
                        }
                    }
                }
                dialog.dismiss()
            }
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isSettingsOpen.value) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                isSettingsOpen.value = false
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        if (!isVideoLoaded.value) {
            return super.onKeyDown(keyCode, event)
        }

        if (!isControlsVisible.value) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    performSeek(-10000L)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    performSeek(10000L)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    showPlayerControls()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    isVideoLoaded.value = false
                    return true
                }
            }
        } else {
            showPlayerControls()
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                isControlsVisible.value = false
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun runDiagnosticsAndInitializeRife() {
        Thread {
            try {
                val initSuccess = NativeEngine.initRife(0)
                if (initSuccess) {
                    val baseCacheDir = cacheDir.absolutePath
                    val loadSuccess = NativeEngine.loadRifeModel(assets, baseCacheDir, "rife-v2.4", isV2 = true, isV4 = false)
                    isRifeModelLoaded = loadSuccess
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun startProgressUpdater() {
        mainHandler.post(object : Runnable {
            override fun run() {
                player?.let { p ->
                    if (p.isPlaying) {
                        currentTimeMsState.value = p.currentPosition
                    }
                }
                mainHandler.postDelayed(this, 1000)
            }
        })
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.clearVideoSurface()
        videoFrameProcessor?.stop()
        player?.release()
        player = null
    }
}
