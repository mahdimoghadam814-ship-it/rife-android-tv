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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.PositionInfo
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.rife.androidtv.databinding.ActivityMainBinding

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
    private val hideControlsRunnable = Runnable { hidePlayerControls() }
    private val hideSeekFeedbackRunnable = Runnable { binding.tvSeekFeedback.visibility = View.GONE }

    /**
     * Guards the RIFE / FastDVDnet switch listeners against the extra callback that
     * `Switch.setChecked()` triggers while the listener is normalising a state (for example when the
     * RIFE model is not loaded). Without it the same transition would be applied twice.
     */
    private var isUpdatingProcessingSwitches = false

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val uri: Uri? = result.data?.data
            uri?.let {
                videoName = it.lastPathSegment ?: "Local Video"
                // A new video invalidates every buffered frame of the previous one, independently of
                // the player callbacks, which may not fire for an identical media source.
                videoFrameProcessor?.resetForNewStream("new_video_selected")
                mediaPlaybackManager?.setVideoSource(it)
                binding.layoutFilePicker.visibility = View.GONE
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
        setupUIControls()
        setupResolutionSpinner()
        runDiagnosticsAndInitializeRife()

        startProgressUpdater()
    }

    private fun setupVideoFrameProcessor() {
        videoFrameProcessor = VideoFrameProcessor(
            displaySurfaceView = binding.displaySurfaceView,
            onStatisticsUpdated = { stats ->
                runOnUiThread {
                    val audioOff = mediaPlaybackManager?.audioOffsetMs ?: 0L
                    val subOff = mediaPlaybackManager?.subtitleOffsetMs ?: 0L
                    val extAud = if (mediaPlaybackManager?.isExternalAudioSelected == true) mediaPlaybackManager?.externalAudioName else "None"
                    val extSub = if (mediaPlaybackManager?.isExternalSubtitleEnabled == true) mediaPlaybackManager?.externalSubtitleName else "None"

                    binding.tvOverlayStats.text = """
                        Video: $videoName
                        Ext Audio: $extAud | Ext Sub: $extSub
                        Audio Offset: ${audioOff}ms | Sub Offset: ${subOff}ms
                        Input FPS: ${"%.1f".format(stats.inputFps)} | Output FPS: ${"%.1f".format(stats.outputFps)}
                        Resolution: ${stats.currentResolution} | RIFE: ${if (videoFrameProcessor?.isRifeEnabled == true) "ON" else "OFF"} | FastDVDnet (scaffold): ${if (videoFrameProcessor?.fastDvdNetEngine?.isEnabled == true) "ON" else "OFF"}
                        Processing Time: ${stats.processingTimeMs} ms | Dropped: ${stats.droppedFrames}
                    """.trimIndent()
                }
            },
            onError = { error ->
                runOnUiThread {
                    Toast.makeText(this, "RIFE Processing Error: $error", Toast.LENGTH_SHORT).show()
                }
            },
            onInputSurfaceCreated = { surface ->
                // Called on the main thread by the processor. The processor owns this Surface: it is
                // only attached while a processing stage is intercepting frames, and the processor is
                // told once the player has actually taken it over.
                attachProcessingInputSurface(surface)
            },
            onInputSurfaceFailed = {
                // No input surface means no decoded frames can be intercepted. Rather than leaving the
                // processing output surface in front of the user with a stale picture, both stages
                // are switched off and normal PlayerView playback is restored.
                fallBackToNormalPlayback("Input surface unavailable, processing disabled")
            }
        )
        videoFrameProcessor?.start()
    }

    /**
     * Single place where the video output surface is decided.
     *
     * While RIFE or FastDVDnet is on, the processor owns the input Surface and the decoded frames
     * are rendered into it, with the processed result drawn on [displaySurfaceView]. While both are
     * off, normal PlayerView playback is restored through the explicit Media3 surface setters — no
     * `setVideoSurface(null)` + `PlayerView.setPlayer(player)` dance, which is a no-op when the
     * PlayerView already owns the same player instance, and no second competing output surface.
     */
    private fun applyProcessingSurfaces() {
        val processor = videoFrameProcessor ?: return
        val currentPlayer = player ?: return

        if (processor.isProcessingEnabled) {
            // Show the processing output surface first so the processor has somewhere to render to.
            binding.displaySurfaceView.visibility = View.VISIBLE
            binding.playerView.visibility = View.GONE
            processor.rifeInputSurface?.let { surface ->
                currentPlayer.setVideoSurface(surface)
                processor.onInputSurfaceAttached()
            }
        } else {
            // The processor has already released its output surface and dropped its frames.
            binding.displaySurfaceView.visibility = View.GONE
            binding.playerView.visibility = View.VISIBLE
            restorePlayerSurface()
        }
    }

    /**
     * Attaches the processor-owned input Surface to the player so that MediaCodec decodes straight
     * into the processing pipeline. Called for every surface the processor hands over, including the
     * re-created ones after a toggle.
     */
    private fun attachProcessingInputSurface(surface: Surface) {
        val currentPlayer = player ?: return
        val processor = videoFrameProcessor ?: return
        if (!processor.isProcessingEnabled) {
            // Processing was switched off again before the worker finished creating the surface.
            return
        }
        currentPlayer.setVideoSurface(surface)
        processor.onInputSurfaceAttached()
    }

    /**
     * Gives rendering back to the PlayerView after both processing stages have been switched off.
     *
     * The surface is never left detached: the PlayerView's own SurfaceView/TextureView is handed
     * back to the player through the explicit Media3 setters, which also replaces the processor's
     * surface instead of adding a second one.
     */
    private fun restorePlayerSurface() {
        val currentPlayer = player ?: return
        // The processor must know that the player no longer writes into its surface, otherwise the
        // next enable would assume the surface is still attached and skip re-creating it.
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

    /**
     * Safety net for the "the processor cannot intercept frames" case: both stages are switched off,
     * the switches are brought back in sync and the PlayerView is restored.
     */
    private fun fallBackToNormalPlayback(reason: String) {
        isUpdatingProcessingSwitches = true
        binding.switchRife.isChecked = false
        binding.switchFastDvdNet.isChecked = false
        isUpdatingProcessingSwitches = false
        binding.switchRife.text = "OFF"
        binding.switchFastDvdNet.text = "OFF"

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
                binding.btnPlayPause.text = if (isPlaying) "Pause" else "Play"
                if (isPlaying) {
                    binding.layoutFilePicker.visibility = View.GONE
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val duration = player?.duration ?: 0L
                    binding.seekBar.max = duration.toInt()
                    binding.tvDuration.text = formatTime(duration)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@MainActivity, "Playback Error: ${error.message}", Toast.LENGTH_LONG).show()
            }

            /**
             * K3: a seek must not interpolate a frame from before the seek against the first frame
             * after it, so every frame of pipeline state is dropped on a position discontinuity.
             * All seek entry points (seek bar, -10s/+10s, D-pad) end up here, so this stays the one
             * place that resets on a seek.
             */
            override fun onPositionDiscontinuity(
                oldPosition: PositionInfo,
                newPosition: PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT ||
                    reason == Player.DISCONTINUITY_REASON_INTERNAL
                ) {
                    videoFrameProcessor?.resetPipeline("position_discontinuity_$reason")
                }
            }

            /** K3: a new media item must not be paired with frames of the previous one. */
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                videoFrameProcessor?.resetForNewStream("media_item_transition_$reason")
            }

            /** The decoded size drives the real readback size used before RIFE. */
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoFrameProcessor?.setInputFrameSize(videoSize.width, videoSize.height)
            }
        })
    }

    private fun setupUIControls() {
        binding.btnOpenVideo.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
            }
            filePickerLauncher.launch(intent)
        }

        binding.btnExtAudio.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            }
            audioPickerLauncher.launch(intent)
        }

        binding.btnExtSub.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            subPickerLauncher.launch(intent)
        }

        binding.btnAudioDelayMinus.setOnClickListener {
            val current = mediaPlaybackManager?.audioOffsetMs ?: 0L
            val updated = (current - 500L).coerceAtLeast(-5000L)
            mediaPlaybackManager?.setAudioOffset(updated)
            binding.tvAudioDelay.text = "A: ${updated}ms"
            showPlayerControls()
        }

        binding.btnAudioDelayPlus.setOnClickListener {
            val current = mediaPlaybackManager?.audioOffsetMs ?: 0L
            val updated = (current + 500L).coerceAtMost(5000L)
            mediaPlaybackManager?.setAudioOffset(updated)
            binding.tvAudioDelay.text = "A: ${updated}ms"
            showPlayerControls()
        }

        binding.btnSubDelayMinus.setOnClickListener {
            val current = mediaPlaybackManager?.subtitleOffsetMs ?: 0L
            val updated = (current - 500L).coerceAtLeast(-5000L)
            mediaPlaybackManager?.setSubtitleOffset(updated)
            binding.tvSubDelay.text = "S: ${updated}ms"
            showPlayerControls()
        }

        binding.btnSubDelayPlus.setOnClickListener {
            val current = mediaPlaybackManager?.subtitleOffsetMs ?: 0L
            val updated = (current + 500L).coerceAtMost(5000L)
            mediaPlaybackManager?.setSubtitleOffset(updated)
            binding.tvSubDelay.text = "S: ${updated}ms"
            showPlayerControls()
        }

        binding.btnRunDiagnostic.setOnClickListener {
            binding.layoutDiagnosticDialog.visibility = View.VISIBLE
            binding.btnCloseDiagnostic.requestFocus()
        }

        binding.btnCloseDiagnostic.setOnClickListener {
            binding.layoutDiagnosticDialog.visibility = View.GONE
        }

        binding.btnPlayPause.setOnClickListener {
            player?.let { p ->
                if (p.isPlaying) {
                    p.pause()
                } else {
                    p.play()
                }
            }
            showPlayerControls()
        }

        binding.btnRewind.setOnClickListener {
            performSeek(-SEEK_STEP_MS)
            showPlayerControls()
        }

        binding.btnForward.setOnClickListener {
            performSeek(SEEK_STEP_MS)
            showPlayerControls()
        }

        binding.btnOpenSettings.setOnClickListener {
            openSettingsOverlay()
        }

        binding.btnCloseSettings.setOnClickListener {
            closeSettingsOverlay()
        }

        binding.btnSettingsAudioTrack.setOnClickListener {
            showTrackSelectionDialog(C.TRACK_TYPE_AUDIO, "Audio Tracks")
        }

        binding.btnSettingsSubtitleTrack.setOnClickListener {
            showTrackSelectionDialog(C.TRACK_TYPE_TEXT, "Subtitle Tracks")
        }

        binding.btnSettingsDiagnostics.setOnClickListener {
            binding.layoutSettingsOverlay.visibility = View.GONE
            binding.layoutDiagnosticDialog.visibility = View.VISIBLE
            binding.btnCloseDiagnostic.requestFocus()
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // The processor state is dropped by onPositionDiscontinuity below, so this path
                    // does not reset the pipeline a second time.
                    player?.seekTo(progress.toLong())
                    binding.tvCurrentTime.text = formatTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.switchFastDvdNet.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingProcessingSwitches) {
                return@setOnCheckedChangeListener
            }
            // Reseeds the pipeline: frames captured with a different stage combination must not be
            // paired with frames captured after the toggle.
            videoFrameProcessor?.setFastDvdNetEnabled(isChecked)
            binding.switchFastDvdNet.text = if (isChecked) "ON" else "OFF"
            applyProcessingSurfaces()
            if (isChecked) {
                Toast.makeText(
                    this,
                    "FastDVDnet pre-processing active (scaffold: frames pass through)",
                    Toast.LENGTH_SHORT
                ).show()
            }
            showPlayerControls()
        }

        binding.switchRife.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingProcessingSwitches) {
                return@setOnCheckedChangeListener
            }
            if (isChecked && !isRifeModelLoaded) {
                isUpdatingProcessingSwitches = true
                binding.switchRife.isChecked = false
                isUpdatingProcessingSwitches = false
                Toast.makeText(this, "Cannot enable RIFE: Model not loaded.", Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }

            binding.switchRife.text = if (isChecked) "ON" else "OFF"

            // Resets every piece of pipeline state and (re)creates the Media3 input surface, which
            // comes back through onInputSurfaceCreated -> attachProcessingInputSurface().
            videoFrameProcessor?.setRifeEnabled(isChecked)
            applyProcessingSurfaces()

            val processingOn = videoFrameProcessor?.isProcessingEnabled == true
            Toast.makeText(
                this,
                if (processingOn) "RIFE Frame Interpolation Active" else "Normal ExoPlayer Playback Active",
                Toast.LENGTH_SHORT
            ).show()
            showPlayerControls()
        }
    }

    private fun setupResolutionSpinner() {
        val options = arrayOf("Original", "1080p", "720p", "480p")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerResolution.adapter = adapter

        binding.spinnerResolution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val res = when (position) {
                    1 -> RifeResolution.RES_1080P
                    2 -> RifeResolution.RES_720P
                    3 -> RifeResolution.RES_480P
                    else -> RifeResolution.ORIGINAL
                }
                videoFrameProcessor?.resolution = res
                showPlayerControls()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * Seeks by [offsetMs] and shows the on-screen seek feedback. The pipeline reset is intentionally
     * not done here: `onPositionDiscontinuity` is the single reset point for every seek.
     */
    private fun performSeek(offsetMs: Long) {
        player?.let { p ->
            val newPosition = (p.currentPosition + offsetMs)
                .coerceIn(0, p.duration.coerceAtLeast(0))
            p.seekTo(newPosition)

            val text = if (offsetMs > 0) {
                "+${offsetMs / 1000}s"
            } else {
                "${offsetMs / 1000}s"
            }

            binding.tvSeekFeedback.text = text
            binding.tvSeekFeedback.visibility = View.VISIBLE

            mainHandler.removeCallbacks(hideSeekFeedbackRunnable)
            mainHandler.postDelayed(hideSeekFeedbackRunnable, 1200)
        }
    }

    private fun showPlayerControls() {
        binding.layoutPlayerControls.visibility = View.VISIBLE
        binding.tvOverlayStats.visibility = View.VISIBLE
        // Never steal the focus out of the settings overlay while it is open.
        if (binding.layoutSettingsOverlay.visibility != View.VISIBLE) {
            binding.btnPlayPause.requestFocus()
        }

        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, 5000)
    }

    private fun hidePlayerControls() {
        if (player?.isPlaying == true &&
            binding.layoutSettingsOverlay.visibility != View.VISIBLE &&
            binding.layoutDiagnosticDialog.visibility != View.VISIBLE
        ) {
            binding.layoutPlayerControls.visibility = View.GONE
            binding.tvOverlayStats.visibility = View.GONE
        }
    }

    private fun openSettingsOverlay() {
        binding.layoutSettingsOverlay.visibility = View.VISIBLE
        binding.spinnerResolution.requestFocus()
    }

    private fun closeSettingsOverlay() {
        binding.layoutSettingsOverlay.visibility = View.GONE
        if (binding.layoutPlayerControls.visibility == View.VISIBLE) {
            binding.btnOpenSettings.requestFocus()
        }
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
        if (binding.layoutDiagnosticDialog.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                binding.layoutDiagnosticDialog.visibility = View.GONE
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        if (binding.layoutSettingsOverlay.visibility == View.VISIBLE) {
            // Navigation inside the settings overlay must stay normal, so LEFT/RIGHT are not
            // hijacked for seeking while it is open.
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                closeSettingsOverlay()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        if (binding.layoutFilePicker.visibility == View.VISIBLE) {
            return super.onKeyDown(keyCode, event)
        }

        if (binding.layoutPlayerControls.visibility != View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    performSeek(-SEEK_STEP_MS)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    performSeek(SEEK_STEP_MS)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    showPlayerControls()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    binding.layoutFilePicker.visibility = View.VISIBLE
                    return true
                }
            }
        } else {
            showPlayerControls()
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                hidePlayerControls()
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun runDiagnosticsAndInitializeRife() {
        binding.tvDiagnosticDetails.text = "Running Vulkan / RIFE diagnostics..."
        // Model initialisation loads the RIFE network from assets and can take seconds, so it must
        // not run on the main thread.
        Thread {
            val primaryAbi = if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "Unknown"
            val sb = StringBuilder()

            sb.append("=== SYSTEM & VULKAN DIAGNOSTICS ===\n")
            sb.append("Primary Target ABI: $primaryAbi\n")

            try {
                val vkRes = NativeEngine.runDiagnostics()
                sb.append("Vulkan Available: ${if (vkRes.vulkanSupported) "YES" else "NO"}\n")
                sb.append("GPU Device: ${vkRes.gpuName}\n")
                sb.append("Vulkan API Version: ${vkRes.vulkanApiVersion}\n")
                sb.append("ncnn Version: ${vkRes.ncnnVersion}\n\n")

                sb.append("=== RIFE MODEL INITIALIZATION ===\n")
                val initSuccess = NativeEngine.initRife(0)
                if (initSuccess) {
                    val baseCacheDir = cacheDir.absolutePath
                    val loadSuccess = NativeEngine.loadRifeModel(assets, baseCacheDir, "rife-v2.4", isV2 = true, isV4 = false)
                    isRifeModelLoaded = loadSuccess
                    sb.append("RIFE Model Loaded: ${if (loadSuccess) "YES (rife-v2.4)" else "FAILED"}\n")
                } else {
                    sb.append("RIFE Engine Init: FAILED\n")
                }

                val rifeStatus = NativeEngine.getRifeStatus()
                if (rifeStatus.lastError.isNotEmpty()) {
                    sb.append("Error: ${rifeStatus.lastError}\n")
                }
            } catch (e: Throwable) {
                sb.append("Diagnostics Exception: ${e.message}\n")
                e.printStackTrace()
            }

            runOnUiThread {
                binding.tvDiagnosticDetails.text = sb.toString()
            }
        }.start()
    }

    private fun startProgressUpdater() {
        mainHandler.post(object : Runnable {
            override fun run() {
                player?.let { p ->
                    if (p.isPlaying) {
                        val current = p.currentPosition
                        binding.seekBar.progress = current.toInt()
                        binding.tvCurrentTime.text = formatTime(current)
                    }
                }
                mainHandler.postDelayed(this, 1000)
            }
        })
    }

    private fun formatTime(timeMs: Long): String {
        val totalSec = timeMs / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format("%02d:%02d", min, sec)
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
        // Detach the processor-owned surface before it is released, so the player never renders
        // into a Surface that no longer exists.
        player?.clearVideoSurface()
        videoFrameProcessor?.stop()
        player?.release()
        player = null
    }

    companion object {
        private const val SEEK_STEP_MS = 10000L
    }
}
