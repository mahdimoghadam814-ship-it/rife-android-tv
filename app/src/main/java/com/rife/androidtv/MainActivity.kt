package com.rife.androidtv

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.rife.androidtv.databinding.ActivityMainBinding
import kotlin.concurrent.thread

@androidx.media3.common.util.UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private var videoFrameProcessor: VideoFrameProcessor? = null
    private var mediaPlaybackManager: MediaPlaybackManager? = null
    private var audioDelayProcessor: AudioDelayAudioProcessor? = null

    @Volatile
    private var isRifeModelLoaded = false

    @Volatile
    private var isFastDvdNetModelLoaded = false

    private var videoName = "None"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideOverlayStats() }
    private val hideSeekFeedbackRunnable = Runnable { binding.tvSeekFeedback.visibility = View.GONE }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val uri: Uri? = result.data?.data
            uri?.let {
                videoName = it.lastPathSegment ?: "Local Video"
                videoFrameProcessor?.clearTemporalBuffers()
                mediaPlaybackManager?.setVideoSource(it)
                binding.layoutHome.visibility = View.GONE
                showOverlayStats()
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
                showOverlayStats()
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
                showOverlayStats()
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

        runDiagnosticsAndInitializeModelsInBackground()
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
                        Audio Delay: ${audioOff}ms | Sub Delay: ${subOff}ms
                        Input FPS: ${"%.1f".format(stats.inputFps)} | Output FPS: ${"%.1f".format(stats.outputFps)}
                        Resolution: ${stats.currentResolution}
                        FastDVDnet: ${if (videoFrameProcessor?.isFastDvdNetEnabled == true) "ON" else "OFF"} | RIFE: ${if (videoFrameProcessor?.isRifeEnabled == true) "ON" else "OFF"}
                        Processing Time: ${stats.processingTimeMs} ms | Dropped: ${stats.droppedFrames}
                    """.trimIndent()
                }
            },
            onError = { error ->
                runOnUiThread {
                    Toast.makeText(this, "Processing Error: $error", Toast.LENGTH_SHORT).show()
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
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    binding.layoutHome.visibility = View.GONE
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                videoFrameProcessor?.clearTemporalBuffers()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Toast.makeText(this@MainActivity, "Playback Error: ${error.message}", Toast.LENGTH_LONG).show()
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

        binding.btnHomeSettings.setOnClickListener {
            openSettingsOverlay()
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
            binding.tvAudioDelay.text = "Audio Delay: ${updated}ms"
            showOverlayStats()
        }

        binding.btnAudioDelayPlus.setOnClickListener {
            val current = mediaPlaybackManager?.audioOffsetMs ?: 0L
            val updated = (current + 500L).coerceAtMost(5000L)
            mediaPlaybackManager?.setAudioOffset(updated)
            binding.tvAudioDelay.text = "Audio Delay: ${updated}ms"
            showOverlayStats()
        }

        binding.btnSubDelayMinus.setOnClickListener {
            val current = mediaPlaybackManager?.subtitleOffsetMs ?: 0L
            val updated = (current - 500L).coerceAtLeast(-5000L)
            mediaPlaybackManager?.setSubtitleOffset(updated)
            binding.tvSubDelay.text = "Subtitle Delay: ${updated}ms"
            showOverlayStats()
        }

        binding.btnSubDelayPlus.setOnClickListener {
            val current = mediaPlaybackManager?.subtitleOffsetMs ?: 0L
            val updated = (current + 500L).coerceAtMost(5000L)
            mediaPlaybackManager?.setSubtitleOffset(updated)
            binding.tvSubDelay.text = "Subtitle Delay: ${updated}ms"
            showOverlayStats()
        }

        binding.btnRunDiagnostic.setOnClickListener {
            binding.layoutDiagnosticDialog.visibility = View.VISIBLE
            binding.btnCloseDiagnostic.requestFocus()
        }

        binding.btnCloseDiagnostic.setOnClickListener {
            binding.layoutDiagnosticDialog.visibility = View.GONE
        }

        binding.btnCloseSettings.setOnClickListener {
            closeSettingsOverlay()
        }

        binding.btnSettingsDiagnostics.setOnClickListener {
            binding.layoutSettingsOverlay.visibility = View.GONE
            binding.layoutDiagnosticDialog.visibility = View.VISIBLE
            binding.btnCloseDiagnostic.requestFocus()
        }

        binding.switchFastDvdNet.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isFastDvdNetModelLoaded) {
                binding.switchFastDvdNet.isChecked = false
                Toast.makeText(this, "FastDVDnet model not loaded", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }

            videoFrameProcessor?.isFastDvdNetEnabled = isChecked
            binding.switchFastDvdNet.text = if (isChecked) "ON" else "OFF"
            updateSurfaceOutputMode()
            showOverlayStats()
        }

        binding.switchRife.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isRifeModelLoaded) {
                binding.switchRife.isChecked = false
                Toast.makeText(this, "Cannot enable RIFE: Model not loaded.", Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }

            videoFrameProcessor?.isRifeEnabled = isChecked
            binding.switchRife.text = if (isChecked) "ON" else "OFF"
            updateSurfaceOutputMode()
            showOverlayStats()
        }
    }

    private fun updateSurfaceOutputMode() {
        val customProcessingActive = (videoFrameProcessor?.isRifeEnabled == true || videoFrameProcessor?.isFastDvdNetEnabled == true)

        if (customProcessingActive) {
            videoFrameProcessor?.inputSurface?.let { surface ->
                player?.setVideoSurface(surface)
            }
            binding.playerView.visibility = View.GONE
            binding.displaySurfaceView.visibility = View.VISIBLE
        } else {
            player?.setVideoSurface(null)
            binding.playerView.setPlayer(player)
            binding.displaySurfaceView.visibility = View.GONE
            binding.playerView.visibility = View.VISIBLE
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

    private fun showOverlayStats() {
        binding.tvOverlayStats.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, 5000)
    }

    private fun hideOverlayStats() {
        if (player?.isPlaying == true && binding.layoutSettingsOverlay.visibility != View.VISIBLE && binding.layoutDiagnosticDialog.visibility != View.VISIBLE) {
            binding.tvOverlayStats.visibility = View.GONE
        }
    }

    private fun openSettingsOverlay() {
        binding.layoutSettingsOverlay.visibility = View.VISIBLE
        binding.switchRife.requestFocus()
    }

    private fun closeSettingsOverlay() {
        binding.layoutSettingsOverlay.visibility = View.GONE
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

        if (binding.layoutHome.visibility == View.VISIBLE) {
            return super.onKeyDown(keyCode, event)
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                showOverlayStats()
                openSettingsOverlay()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                binding.layoutHome.visibility = View.VISIBLE
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun runDiagnosticsAndInitializeModelsInBackground() {
        thread {
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

                sb.append("\n=== FASTDVDNET MODEL INITIALIZATION ===\n")
                isFastDvdNetModelLoaded = true
                sb.append("FastDVDnet Model Loaded: YES\n")

            } catch (e: Throwable) {
                sb.append("Diagnostics Exception: ${e.message}\n")
                e.printStackTrace()
            }

            runOnUiThread {
                binding.tvDiagnosticDetails.text = sb.toString()
            }
        }
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
