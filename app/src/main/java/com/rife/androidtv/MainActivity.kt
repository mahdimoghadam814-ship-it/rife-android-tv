package com.rife.androidtv

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.rife.androidtv.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private var videoFrameProcessor: VideoFrameProcessor? = null

    private var isRifeModelLoaded = false
    private var videoName = "None"

    private val handler = Handler(Looper.getMainLooper())
    private var frameCaptureRunnable: Runnable? = null

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val uri: Uri? = result.data?.data
            uri?.let {
                videoName = it.lastPathSegment ?: "Local Video"
                playVideo(it)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.textureView.surfaceTextureListener = this

        setupVideoFrameProcessor()
        setupPlayer()
        setupUIControls()
        runDiagnosticsAndInitializeRife()
    }

    private fun setupVideoFrameProcessor() {
        videoFrameProcessor = VideoFrameProcessor(
            textureView = binding.textureView,
            onStatisticsUpdated = { stats ->
                runOnUiThread {
                    binding.tvDiagnosticOverlay.text = """
                        Video: $videoName
                        Input FPS: ${"%.1f".format(stats.inputFps)}
                        Output FPS: ${"%.1f".format(stats.outputFps)}
                        Resolution: ${stats.currentResolution}
                        RIFE: ${if (videoFrameProcessor?.isRifeEnabled == true) "ON" else "OFF"}
                        Processing: ${stats.processingTimeMs} ms
                        Dropped: ${stats.droppedFrames}
                    """.trimIndent()
                }
            },
            onError = { error ->
                runOnUiThread {
                    Toast.makeText(this, "RIFE Error: $error", Toast.LENGTH_SHORT).show()
                }
            }
        )
        videoFrameProcessor?.start()
    }

    private fun setupPlayer() {
        player = ExoPlayer.Builder(this).build()
        binding.playerView.player = player

        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                binding.btnPlayPause.text = if (isPlaying) "Pause" else "Play"
                if (isPlaying && videoFrameProcessor?.isRifeEnabled == true) {
                    startFrameCaptureLoop()
                } else {
                    stopFrameCaptureLoop()
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Toast.makeText(this@MainActivity, "Playback Error: ${error.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun startFrameCaptureLoop() {
        stopFrameCaptureLoop()
        frameCaptureRunnable = object : Runnable {
            override fun run() {
                if (player?.isPlaying == true && videoFrameProcessor?.isRifeEnabled == true) {
                    val bitmap = binding.textureView.getBitmap(640, 360)
                    if (bitmap != null) {
                        val timestampUs = (player?.currentPosition ?: 0) * 1000L
                        videoFrameProcessor?.onNewFrameDecoded(bitmap, timestampUs)
                    }
                    handler.postDelayed(this, 33) // ~30 FPS frame interception
                }
            }
        }
        handler.post(frameCaptureRunnable!!)
    }

    private fun stopFrameCaptureLoop() {
        frameCaptureRunnable?.let { handler.removeCallbacks(it) }
        frameCaptureRunnable = null
    }

    private fun setupUIControls() {
        binding.btnOpenVideo.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
            }
            filePickerLauncher.launch(intent)
        }

        binding.btnPlayPause.setOnClickListener {
            player?.let { p ->
                if (p.isPlaying) {
                    p.pause()
                } else {
                    p.play()
                }
            }
        }

        binding.switchRife.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isRifeModelLoaded) {
                binding.switchRife.isChecked = false
                Toast.makeText(this, "Cannot enable RIFE: RIFE model not loaded.", Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }

            videoFrameProcessor?.isRifeEnabled = isChecked
            binding.switchRife.text = if (isChecked) "ON" else "OFF"

            if (isChecked) {
                player?.setVideoTextureView(binding.textureView)
                binding.playerView.visibility = View.GONE
                binding.textureView.visibility = View.VISIBLE
                if (player?.isPlaying == true) {
                    startFrameCaptureLoop()
                }
                Toast.makeText(this, "RIFE Video Frame Interpolation Enabled", Toast.LENGTH_SHORT).show()
            } else {
                stopFrameCaptureLoop()
                player?.clearVideoTextureView(binding.textureView)
                binding.textureView.visibility = View.GONE
                binding.playerView.visibility = View.VISIBLE
                Toast.makeText(this, "Normal ExoPlayer Playback Enabled", Toast.LENGTH_SHORT).show()
            }
        }

        binding.rgResolution.setOnCheckedChangeListener { _, checkedId ->
            val res = when (checkedId) {
                R.id.rb720p -> RifeResolution.RES_720P
                R.id.rb480p -> RifeResolution.RES_480P
                else -> RifeResolution.ORIGINAL
            }
            videoFrameProcessor?.resolution = res
        }

        binding.btnRunRifeTest.setOnClickListener {
            runRifeTest()
        }
    }

    private fun playVideo(uri: Uri) {
        player?.let {
            val mediaItem = MediaItem.fromUri(uri)
            it.setMediaItem(mediaItem)
            it.prepare()
            it.playWhenReady = true
        }
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
                val loadSuccess = NativeEngine.loadRifeModel(assets, "rife-v2.4", isV2 = true, isV4 = false)
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

        binding.tvDiagnosticLogs.text = sb.toString()
    }

    private fun runRifeTest() {
        if (!isRifeModelLoaded) {
            Toast.makeText(this, "RIFE model not loaded! Cannot run GPU test.", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnRunRifeTest.isEnabled = false
        binding.tvDiagnosticLogs.append("\nRunning RIFE GPU test (256x256)...\n")

        Thread {
            val testSuccess = NativeEngine.runRifeTest(256, 256)
            val status = NativeEngine.getRifeStatus()

            runOnUiThread {
                binding.btnRunRifeTest.isEnabled = true
                if (testSuccess) {
                    binding.tvDiagnosticLogs.append("Test Result: PASSED (${status.lastInferenceTimeMs} ms)\n")
                    binding.tvDiagnosticLogs.append("Details: ${status.opDetails}\n")
                    Toast.makeText(this, "RIFE Test Passed in ${status.lastInferenceTimeMs} ms", Toast.LENGTH_SHORT).show()
                } else {
                    binding.tvDiagnosticLogs.append("Test Result: FAILED\n")
                    binding.tvDiagnosticLogs.append("Error: ${status.lastError}\n")
                    Toast.makeText(this, "RIFE Test Failed: ${status.lastError}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        stopFrameCaptureLoop()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        stopFrameCaptureLoop()
        videoFrameProcessor?.stop()
        player?.release()
        player = null
    }
}
