package dev.anilbeesetti.nextplayer.feature.player

import android.app.AlertDialog
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.util.Consumer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import dev.anilbeesetti.nextplayer.core.common.extensions.getInitialDirectoryUri
import dev.anilbeesetti.nextplayer.core.common.extensions.getMediaContentUri
import dev.anilbeesetti.nextplayer.core.common.service.registerForSuspendActivityResult
import dev.anilbeesetti.nextplayer.core.data.repository.PlaylistRepository
import dev.anilbeesetti.nextplayer.core.ui.R as coreUiR
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.feature.player.extensions.OpenDocumentAtInitialUri
import dev.anilbeesetti.nextplayer.feature.player.extensions.setExtras
import dev.anilbeesetti.nextplayer.feature.player.extensions.uriToSubtitleConfiguration
import dev.anilbeesetti.nextplayer.feature.player.rife.RifeController
import dev.anilbeesetti.nextplayer.feature.player.service.addAudioTrack
import dev.anilbeesetti.nextplayer.feature.player.service.addSubtitleTrack
import dev.anilbeesetti.nextplayer.feature.player.service.decoderServiceState
import dev.anilbeesetti.nextplayer.feature.player.service.setAudioDecoderMode
import dev.anilbeesetti.nextplayer.feature.player.service.setVideoDecoderMode
import dev.anilbeesetti.nextplayer.feature.player.service.stopPlayerSession
import dev.anilbeesetti.nextplayer.feature.player.service.tryDecoderFallback
import dev.anilbeesetti.nextplayer.feature.player.state.rememberMediaController
import dev.anilbeesetti.nextplayer.feature.player.utils.PlayerApi
import dev.anilbeesetti.nextplayer.feature.player.utils.PlaylistPlaybackContract
import dev.anilbeesetti.nextplayer.feature.player.utils.toMediaQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

internal fun shouldResumeExistingPlayback(
    returningFromBackground: Boolean,
    isRequestedUriCurrent: Boolean,
    hasExplicitPlaylist: Boolean,
): Boolean = returningFromBackground || (isRequestedUriCurrent && !hasExplicitPlaylist)

@SuppressLint("UnsafeOptInUsageError")
class PlayerActivity : ComponentActivity() {

    private val playlistRepository: PlaylistRepository by inject()

    private val rifeController: RifeController by inject()

    private val viewModel: PlayerViewModel by viewModel { parametersOf(playerOutput()) }
    val playerPreferences get() = viewModel.state.value.playerPreferences

    private val onWindowAttributesChangedListener = CopyOnWriteArrayList<Consumer<WindowManager.LayoutParams?>>()

    private var isPlaybackFinished = false
    private var playInBackground: Boolean = false
    private var isIntentNew: Boolean = true

    private var mediaController by mutableStateOf<MediaController?>(null)
    private var udpStreamingActive by mutableStateOf(false)
    private lateinit var playerApi: PlayerApi
    private var playbackRequestJob: Job? = null

    private val playbackStateListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            intent.data = mediaItem?.localConfiguration?.uri
            rifeController.resetForDiscontinuity("media_item_transition_$reason", 0L)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateKeepScreenOnFlag()
            // Deliberately no resetForDiscontinuity here. isPlaying is false whenever playback is
            // not *ready* as well as when the user pauses, so every rebuffer used to tear the UDP
            // stream down: discardPending() threw away queued datagrams, dropUntilKeyFrame blanked
            // the receiver until an IDR arrived, and resetMemcState() flushed the pipeline - which
            // is the "pauses for a while, then normal, then Signal Interruption again" loop. A
            // pause produces no encoder frames and the muxer's PTS stays monotonic on its own, so
            // the stream simply goes quiet and resumes; nothing needs re-anchoring.
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            rifeController.setInputFrameSize(videoSize.width, videoSize.height)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT ||
                reason == Player.DISCONTINUITY_REASON_INTERNAL
            ) {
                rifeController.resetForDiscontinuity(
                    "position_discontinuity_$reason",
                    newPosition.positionMs,
                )
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                isPlaybackFinished = mediaController?.playbackState == Player.STATE_ENDED
                finishAndStopPlayerSession()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
                mediaController?.repeatMode == Player.REPEAT_MODE_OFF
            ) {
                isPlaybackFinished = true
                finishAndStopPlayerSession()
            }
        }
    }

    private val audioFileSuspendLauncher = registerForSuspendActivityResult(OpenDocumentAtInitialUri())

    private val subtitleFileSuspendLauncher = registerForSuspendActivityResult(OpenDocumentAtInitialUri())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isIntentNew = savedInstanceState?.getBoolean("isIntentNew", true) ?: true
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        viewModel.output = playerOutput()
        playerApi = PlayerApi(this)
        rifeController.start()
        setContent {
            val player = rememberMediaController(
                onBeforeRelease = ::onControllerStopped,
                onExtrasChanged = { extras ->
                    viewModel.onAction(PlayerAction.UpdateDecoderServiceState(extras.decoderServiceState()))
                },
            )
            LaunchedEffect(player) {
                if (player == null || !player.isConnected) return@LaunchedEffect
                mediaController = player
                viewModel.onAction(PlayerAction.UpdateDecoderServiceState(player.sessionExtras.decoderServiceState()))
                player.addListener(playbackStateListener)
                updateKeepScreenOnFlag()
                startPlayback()
                // Processing may have been enabled while the session was still connecting; attach
                // the processor input surface as soon as the player can take it.
                val pendingSurface = rifeController.inputSurface.value
                if (pendingSurface != null && rifeController.processingEnabled.value) {
                    player.setVideoSurface(pendingSurface)
                    rifeController.onInputSurfaceAttached()
                }
            }

            val rifeInputSurface by rifeController.inputSurface.collectAsStateWithLifecycle()
            val rifeProcessingEnabled by rifeController.processingEnabled.collectAsStateWithLifecycle()
            val rifeErrorMessage by rifeController.error.collectAsStateWithLifecycle()
            val udpStreaming by rifeController.udpEnabled.collectAsStateWithLifecycle()
            LaunchedEffect(udpStreaming) {
                udpStreamingActive = udpStreaming
                updateKeepScreenOnFlag()
            }

            // While a processing stage is enabled the decoder renders into the processor-owned
            // input surface instead of the PlayerSurface; the processed frames are rendered to the
            // output surface published by the player screen.
            LaunchedEffect(rifeInputSurface, rifeProcessingEnabled) {
                val surface = rifeInputSurface
                val controller = mediaController
                if (surface != null && controller != null && rifeProcessingEnabled) {
                    controller.setVideoSurface(surface)
                    rifeController.onInputSurfaceAttached()
                }
            }

            // Hand the video output back to the real Next Player surface when processing is off.
            LaunchedEffect(rifeProcessingEnabled, rifeInputSurface) {
                if (!rifeProcessingEnabled) {
                    val surface = rifeInputSurface
                    val controller = mediaController
                    if (controller != null) {
                        if (surface != null) controller.clearVideoSurface(surface)
                        rifeController.onInputSurfaceDetached()
                    }
                    rifeController.setOutputSurfaceInfo(null)
                }
            }

            LaunchedEffect(rifeErrorMessage) {
                val message = rifeErrorMessage
                if (message != null) {
                    Toast.makeText(
                        this@PlayerActivity,
                        "RIFE Processing Error: $message",
                        Toast.LENGTH_SHORT,
                    ).show()
                    rifeController.consumeError()
                }
            }

            NextPlayerTheme(darkTheme = true) {
                MediaPlayerScreen(
                    viewModel = viewModel,
                    player = player,
                )
            }
        }
    }

    private fun playerOutput() = PlayerViewModel.Output(
        navigateUp = ::finishAndStopPlayerSession,
        selectSubtitle = ::selectSubtitle,
        selectAudio = ::selectAudio,
        playInBackground = {
            playInBackground = true
            finish()
        },
        setVideoDecoderMode = { mode ->
            lifecycleScope.launch { mediaController?.setVideoDecoderMode(mode) }
        },
        setAudioDecoderMode = { mode ->
            lifecycleScope.launch { mediaController?.setAudioDecoderMode(mode) }
        },
        tryDecoderFallback = {
            lifecycleScope.launch { mediaController?.tryDecoderFallback() }
        },
        showUdpConfigDialog = { isEnabled, host, port, onConfirm ->
            runOnUiThread {
                showUdpConfigDialog(isEnabled, host, port, onConfirm)
            }
        },
    )

    private suspend fun currentMediaDirectory(): Uri? {
        val uri = mediaController?.currentMediaItem?.localConfiguration?.uri ?: return null
        return withContext(Dispatchers.IO) { getInitialDirectoryUri(uri) }
    }

    private fun selectSubtitle() {
        lifecycleScope.launch {
            val uri = subtitleFileSuspendLauncher.launch(
                OpenDocumentAtInitialUri.Input(
                    mimeTypes = arrayOf(
                        MimeTypes.APPLICATION_SUBRIP,
                        MimeTypes.APPLICATION_TTML,
                        MimeTypes.TEXT_VTT,
                        MimeTypes.TEXT_SSA,
                        MimeTypes.BASE_TYPE_APPLICATION + "/octet-stream",
                        MimeTypes.BASE_TYPE_TEXT + "/*",
                    ),
                    initialUri = currentMediaDirectory(),
                ),
            ) ?: return@launch
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            snapshotFlow { mediaController }.filterNotNull().first().addSubtitleTrack(uri)
        }
    }

    private fun showUdpConfigDialog(
        isEnabled: Boolean,
        currentHost: String,
        currentPort: Int,
        onConfirm: (String, Int) -> Unit,
    ) {
        val hostInput = EditText(this).apply {
            setText(currentHost)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            hint = "Host/IP"
        }
        val portInput = EditText(this).apply {
            setText(currentPort.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Port"
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
            addView(hostInput)
            addView(portInput)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(coreUiR.string.udp_streaming))
            .setView(layout)
            .setPositiveButton(getString(coreUiR.string.okay)) { _, _ ->
                val host = hostInput.text.toString()
                val port = portInput.text.toString().toIntOrNull() ?: 5004
                onConfirm(host, port)
            }
            .setNegativeButton(getString(coreUiR.string.cancel), null)
            .show()
    }

    private fun selectAudio() {
        lifecycleScope.launch {
            val uri = audioFileSuspendLauncher.launch(
                OpenDocumentAtInitialUri.Input(
                    mimeTypes = arrayOf("audio/*", "application/ogg"),
                    initialUri = currentMediaDirectory(),
                ),
            ) ?: return@launch
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val controller = snapshotFlow { mediaController }.filterNotNull().first()
                if (!controller.addAudioTrack(uri)) {
                    Toast.makeText(this@PlayerActivity, coreUiR.string.error_opening_audio, Toast.LENGTH_LONG).show()
                }
            } catch (_: SecurityException) {
                Toast.makeText(this@PlayerActivity, coreUiR.string.error_opening_audio, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onControllerStopped() {
        playbackRequestJob?.cancel()
        mediaController?.run {
            viewModel.onAction(PlayerAction.UpdatePlayWhenReady(playWhenReady))
            removeListener(playbackStateListener)
            val shouldPlayInBackground = playInBackground || playerPreferences.autoBackgroundPlay
            if (subtitleFileSuspendLauncher.isAwaitingResult || audioFileSuspendLauncher.isAwaitingResult || !shouldPlayInBackground) {
                pause()
            }
            if (isInPictureInPictureMode) {
                finish()
                if (!shouldPlayInBackground) stopPlayerSession()
            }
        }
        mediaController = null
        updateKeepScreenOnFlag()
    }

    private fun startPlayback() {
        val controller = mediaController ?: return
        if (!isIntentNew && (controller.currentMediaItem == null || controller.playbackState == Player.STATE_ENDED)) {
            // Completion can happen while the activity's playback listener is detached.
            isPlaybackFinished = true
            finishAndStopPlayerSession()
            return
        }
        val uri = intent.data ?: return

        val returningFromBackground = !isIntentNew
        val currentUri = controller.currentMediaItem?.localConfiguration?.uri
        val hasExplicitPlaylist = intent.hasExtra(PlayerApi.API_PLAYLIST) ||
            intent.hasExtra(PlaylistPlaybackContract.EXTRA_PLAYLIST_ID)
        isIntentNew = false

        if (shouldResumeExistingPlayback(
                returningFromBackground = returningFromBackground,
                isRequestedUriCurrent = currentUri.toString() == uri.toString(),
                hasExplicitPlaylist = hasExplicitPlaylist,
            )
        ) {
            controller.prepare()
            controller.playWhenReady = viewModel.state.value.playWhenReady
            return
        }

        playbackRequestJob?.cancel()
        playbackRequestJob = lifecycleScope.launch {
            playVideo(
                uri = uri,
                playlistId = intent.playlistIdOrNull(),
            )
        }
    }

    private suspend fun playVideo(
        uri: Uri,
        playlistId: Long?,
    ) = withContext(Dispatchers.Default) {
        val savedQueue = playlistId
            ?.let { playlistRepository.getPlaylist(it) }
            ?.toMediaQueue(selectedUri = uri.toString())

        if (playlistId != null) {
            val mediaItems = savedQueue?.mediaItems ?: listOf(
                MediaItem.Builder()
                    .setUri(uri)
                    .setMediaId(uri.toString())
                    .build(),
            )
            val startIndex = savedQueue?.startIndex ?: 0
            ensureActive()
            withContext(Dispatchers.Main) {
                mediaController?.run {
                    setMediaItems(mediaItems, startIndex, C.TIME_UNSET)
                    playWhenReady = viewModel.state.value.playWhenReady
                    prepare()
                }
            }
            return@withContext
        }

        val mediaContentUri = getMediaContentUri(uri)
        val playlist = playerApi.getPlaylist().takeIf { it.isNotEmpty() }
            ?: mediaContentUri?.let { mediaUri ->
                viewModel.getPlaylistFromUri(mediaUri)
                    .map { it.uriString }
                    .toMutableList()
                    .apply {
                        if (!contains(mediaUri.toString())) {
                            add(index = 0, element = mediaUri.toString())
                        }
                    }
            } ?: listOf(uri.toString())

        val mediaItemIndexToPlay = playlist.indexOfFirst {
            it == (mediaContentUri ?: uri).toString()
        }.takeIf { it >= 0 } ?: 0

        val mediaItems = playlist.mapIndexed { index, uri ->
            MediaItem.Builder().apply {
                setUri(uri)
                setMediaId(uri)
                if (index == mediaItemIndexToPlay) {
                    setMediaMetadata(
                        MediaMetadata.Builder().apply {
                            setTitle(playerApi.title)
                            setExtras(positionMs = playerApi.position?.toLong())
                        }.build(),
                    )
                    val apiSubs = playerApi.getSubs().map { subtitle ->
                        uriToSubtitleConfiguration(
                            uri = subtitle.uri,
                            subtitleEncoding = playerPreferences.subtitleTextEncoding,
                            isSelected = subtitle.isSelected,
                        )
                    }
                    setSubtitleConfigurations(apiSubs)
                }
            }.build()
        }

        withContext(Dispatchers.Main) {
            mediaController?.run {
                setMediaItems(mediaItems, mediaItemIndexToPlay, playerApi.position?.toLong() ?: C.TIME_UNSET)
                playWhenReady = viewModel.state.value.playWhenReady
                prepare()
            }
        }
    }

    private fun Intent.playlistIdOrNull(): Long? = getLongExtra(
        PlaylistPlaybackContract.EXTRA_PLAYLIST_ID,
        Long.MIN_VALUE,
    ).takeUnless { it == Long.MIN_VALUE }

    override fun finish() {
        if (playerApi.shouldReturnResult) {
            val result = playerApi.getResult(
                isPlaybackFinished = isPlaybackFinished,
                duration = mediaController?.duration ?: C.TIME_UNSET,
                position = mediaController?.currentPosition ?: C.TIME_UNSET,
            )
            setResult(RESULT_OK, result)
        }
        super.finish()
    }

    override fun onDestroy() {
        udpStreamingActive = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rifeController.stop()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.data != null) {
            setIntent(intent)
            isIntentNew = true
            startPlayback()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("isIntentNew", isIntentNew)
        super.onSaveInstanceState(outState)
    }

    private fun updateKeepScreenOnFlag() {
        if (mediaController?.isPlaying == true || udpStreamingActive) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun finishAndStopPlayerSession() {
        finish()
        mediaController?.stopPlayerSession()
    }

    override fun onWindowAttributesChanged(params: WindowManager.LayoutParams?) {
        super.onWindowAttributesChanged(params)
        for (listener in onWindowAttributesChangedListener) {
            listener.accept(params)
        }
    }

    fun addOnWindowAttributesChangedListener(listener: Consumer<WindowManager.LayoutParams?>) {
        onWindowAttributesChangedListener.add(listener)
    }

    fun removeOnWindowAttributesChangedListener(listener: Consumer<WindowManager.LayoutParams?>) {
        onWindowAttributesChangedListener.remove(listener)
    }
}
