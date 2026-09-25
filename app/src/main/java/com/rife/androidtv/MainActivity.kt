package com.rife.androidtv

import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.rife.androidtv.databinding.ActivityMainBinding

@androidx.media3.common.util.UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private var videoFrameProcessor: VideoFrameProcessor? = null
    private var mediaPlaybackManager: MediaPlaybackManager? = null
    private var audioDelayProcessor: AudioDelayAudioProcessor? = null

    private var isRifeModelLoaded = false
    private var videoName = "None"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hidePlayerControls() }
    private val hideSeekFeedbackRunnable = Runnable { binding.tvSeekFeedback.visibility = View.GONE }

    private val mediaItemsList = mutableListOf<MediaFileItem>()
    private lateinit var gridAdapter: VideoGridAdapter

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val uri: Uri? = result.data?.data
            uri?.let {
                videoName = it.lastPathSegment ?: "Local Video"
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

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scanLocalMediaFiles()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupVideoGridBrowser()
        setupVideoFrameProcessor()
        setupPlayer()
        setupUIControls()
        setupResolutionSpinner()
        runDiagnosticsAndInitializeRife()

        checkPermissionsAndLoadMedia()
        startProgressUpdater()
    }

    private fun setupVideoGridBrowser() {
        gridAdapter = VideoGridAdapter(this, mediaItemsList)
        binding.gridVideos.adapter = gridAdapter

        binding.gridVideos.setOnItemClickListener { _, _, position, _ ->
            if (position in mediaItemsList.indices) {
                val item = mediaItemsList[position]
                videoName = item.title
                playVideo(item.uri)
            }
        }
    }

    private fun checkPermissionsAndLoadMedia() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            scanLocalMediaFiles()
        }
    }

    private fun scanLocalMediaFiles() {
        mediaItemsList.clear()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE
        )

        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "Video"
                    val size = cursor.getLong(sizeColumn)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                    val sizeMb = "%.1f MB".format(size / (1024.0 * 1024.0))
                    mediaItemsList.add(MediaFileItem(contentUri, name, sizeMb, null))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        gridAdapter.notifyDataSetChanged()
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
                        Resolution: ${stats.currentResolution} | RIFE: ${if (videoFrameProcessor?.isRifeEnabled == true) "ON" else "OFF"}
                        Processing Time: ${stats.processingTimeMs} ms | Dropped: ${stats.droppedFrames}
                    """.trimIndent()
                }
            },
            onError = { error ->
                runOnUiThread {
                    Toast.makeText(this, "RIFE Processing Error: $error", Toast.LENGTH_SHORT).show()
                }
            }
        )
        videoFrameProcessor?.start()
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

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Toast.makeText(this@MainActivity, "Playback Error: ${error.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun setupUIControls() {
        binding.btnSystemFilePicker.setOnClickListener {
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
            performSeek(-10000)
            showPlayerControls()
        }

        binding.btnForward.setOnClickListener {
            performSeek(10000)
            showPlayerControls()
        }

        binding.btnAudioTracks.setOnClickListener {
            showTrackSelectionDialog(C.TRACK_TYPE_AUDIO, "Audio Tracks")
        }

        binding.btnSubtitleTracks.setOnClickListener {
            showTrackSelectionDialog(C.TRACK_TYPE_TEXT, "Subtitle Tracks")
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
                    player?.seekTo(progress.toLong())
                    binding.tvCurrentTime.text = formatTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.switchRife.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isRifeModelLoaded) {
                binding.switchRife.isChecked = false
                Toast.makeText(this, "Cannot enable RIFE: Model not loaded.", Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }

            videoFrameProcessor?.isRifeEnabled = isChecked
            binding.switchRife.text = if (isChecked) "ON" else "OFF"

            if (isChecked) {
                videoFrameProcessor?.inputSurface?.let { surface ->
                    player?.setVideoSurface(surface)
                }
                binding.playerView.visibility = View.GONE
                binding.displaySurfaceView.visibility = View.VISIBLE
                Toast.makeText(this, "RIFE Frame Interpolation Active", Toast.LENGTH_SHORT).show()
            } else {
                player?.setVideoSurface(null)
                binding.playerView.setPlayer(player)
                binding.displaySurfaceView.visibility = View.GONE
                binding.playerView.visibility = View.VISIBLE
                Toast.makeText(this, "Normal ExoPlayer Playback Active", Toast.LENGTH_SHORT).show()
            }
            showPlayerControls()
        }
    }

    private fun setupResolutionSpinner() {
        val options = arrayOf("Original", "720p", "480p")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerResolution.adapter = adapter

        binding.spinnerResolution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val res = when (position) {
                    1 -> RifeResolution.RES_720P
                    2 -> RifeResolution.RES_480P
                    else -> RifeResolution.ORIGINAL
                }
                videoFrameProcessor?.resolution = res
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

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
        binding.btnPlayPause.requestFocus()

        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, 5000)
    }

    private fun hidePlayerControls() {
        if (player?.isPlaying == true && binding.layoutSettingsOverlay.visibility != View.VISIBLE && binding.layoutDiagnosticDialog.visibility != View.VISIBLE) {
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
                    performSeek(-10000)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    performSeek(10000)
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

        binding.tvDiagnosticDetails.text = sb.toString()
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
        videoFrameProcessor?.stop()
        player?.release()
        player = null
    }
}
