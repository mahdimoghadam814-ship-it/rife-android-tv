package com.rife.androidtv

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.rife.androidtv.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { playVideo(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPlayer()
        setupControls()

        binding.btnOpen.requestFocus()
    }

    private fun setupPlayer() {
        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            binding.playerView.player = exoPlayer
            binding.playerView.keepScreenOn = true
        }
    }

    private fun setupControls() {
        binding.btnOpen.setOnClickListener {
            openVideoPicker()
        }

        binding.btnPlayPause.setOnClickListener {
            player?.let {
                if (it.isPlaying) it.pause() else it.play()
            }
        }

        binding.btnRewind.setOnClickListener {
            player?.seekBack()
        }

        binding.btnForward.setOnClickListener {
            player?.seekForward()
        }
    }

    private fun openVideoPicker() {
        filePicker.launch(arrayOf("video/*"))
    }

    private fun playVideo(uri: Uri) {
        val exoPlayer = player ?: return

        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
        exoPlayer.play()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                player?.let {
                    if (it.isPlaying) it.pause() else it.play()
                }
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player?.play()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.pause()
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                player?.seekBack()
                return true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                player?.seekForward()
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        binding.playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
