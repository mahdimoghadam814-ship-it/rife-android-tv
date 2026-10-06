package dev.anilbeesetti.nextplayer.feature.player

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.data.repository.MediaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.domain.GetSortedPlaylistUseCase
import dev.anilbeesetti.nextplayer.core.model.DenoiseLevelSetting
import dev.anilbeesetti.nextplayer.core.model.InterpolationAlgorithmSetting
import dev.anilbeesetti.nextplayer.core.model.MemcLevelSetting
import dev.anilbeesetti.nextplayer.core.model.LoopMode
import dev.anilbeesetti.nextplayer.core.model.RifeResolutionSetting
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.core.model.SvPlayerSettings
import dev.anilbeesetti.nextplayer.core.model.Video
import dev.anilbeesetti.nextplayer.core.model.VideoContentScale
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import dev.anilbeesetti.nextplayer.feature.player.model.DecoderServiceState
import dev.anilbeesetti.nextplayer.feature.player.state.SubtitleOptionsEvent
import dev.anilbeesetti.nextplayer.feature.player.state.VideoZoomEvent
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class PlayerViewModel(
    private val mediaRepository: MediaRepository,
    private val preferencesRepository: PreferencesRepository,
    private val getSortedPlaylistUseCase: GetSortedPlaylistUseCase,
    @InjectedParam internal var output: Output,
) : MviViewModel<PlayerUiState, PlayerAction>() {

    data class Output(
        val navigateUp: () -> Unit,
        val selectSubtitle: () -> Unit,
        val selectAudio: () -> Unit,
        val playInBackground: () -> Unit,
        val setVideoDecoderMode: (DecoderMode) -> Unit,
        val setAudioDecoderMode: (DecoderMode) -> Unit,
        val tryDecoderFallback: () -> Unit,
        val showUdpConfigDialog: (Boolean, String, Int, (String, Int) -> Unit) -> Unit,
    )

    private val stateInternal = MutableStateFlow(
        PlayerUiState(
            playerPreferences = preferencesRepository.playerPreferences.value,
        ),
    )
    override val state: StateFlow<PlayerUiState> = stateInternal.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.playerPreferences.collect { prefs ->
                stateInternal.update { it.copy(playerPreferences = prefs) }
            }
        }
    }

    override fun onAction(action: PlayerAction) {
        when (action) {
            is PlayerAction.NavigateUp -> output.navigateUp()
            is PlayerAction.SelectSubtitle -> output.selectSubtitle()
            is PlayerAction.SelectAudio -> output.selectAudio()
            is PlayerAction.PlayInBackground -> output.playInBackground()
            is PlayerAction.SetVideoDecoderMode -> output.setVideoDecoderMode(action.mode)
            is PlayerAction.SetAudioDecoderMode -> output.setAudioDecoderMode(action.mode)
            is PlayerAction.TryDecoderFallback -> output.tryDecoderFallback()
            is PlayerAction.UpdateDecoderServiceState -> stateInternal.update { it.copy(decoderServiceState = action.state) }
            is PlayerAction.UpdatePlayWhenReady -> stateInternal.update { it.copy(playWhenReady = action.playWhenReady) }
            is PlayerAction.UpdatePlayerBrightness -> updatePlayerBrightness(action.value)
            is PlayerAction.SetLoopMode -> setLoopMode(action.loopMode)
            is PlayerAction.ToggleInterpolation -> toggleInterpolation()
            is PlayerAction.ToggleDenoise -> toggleDenoise()
            is PlayerAction.SetProcessingResolution -> updateProcessingResolution(action.resolution)
            is PlayerAction.SetInterpolationAlgorithm -> updateInterpolationAlgorithm(action.algorithm)
            is PlayerAction.SetMemcLevel -> updateMemcLevel(action.level)
            is PlayerAction.SetDenoiseLevel -> updateDenoiseLevel(action.level)
            is PlayerAction.SetSvPlayerSettings -> updateSvPlayerSettings(action.settings)
            is PlayerAction.ToggleTimeDisplay -> toggleTimeDisplay()
            is PlayerAction.ToggleUdpStreaming -> toggleUdpStreaming()
            is PlayerAction.OnVideoZoomEvent -> onVideoZoomEvent(action.event)
            is PlayerAction.OnSubtitleOptionEvent -> onSubtitleOptionEvent(action.event)
        }
    }

    suspend fun getPlaylistFromUri(uri: Uri): List<Video> {
        return getSortedPlaylistUseCase.invoke(uri)
    }

    private fun updateVideoZoom(uri: String, zoom: Float) {
        viewModelScope.launch {
            mediaRepository.updateMediumZoom(uri, zoom)
        }
    }

    private fun updatePlayerBrightness(value: Float) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(playerBrightness = value) }
        }
    }

    private fun updateVideoContentScale(contentScale: VideoContentScale) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(playerVideoZoom = contentScale) }
        }
    }

    private fun setLoopMode(loopMode: LoopMode) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(loopMode = loopMode) }
        }
    }

    private fun toggleInterpolation() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(rifeEnabled = !it.rifeEnabled) }
        }
    }

    private fun toggleDenoise() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(fastDvdNetEnabled = !it.fastDvdNetEnabled) }
        }
    }

    private fun updateProcessingResolution(resolution: RifeResolutionSetting) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(rifeResolution = resolution) }
        }
    }

    private fun updateInterpolationAlgorithm(algorithm: InterpolationAlgorithmSetting) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(interpolationAlgorithm = algorithm) }
        }
    }

    private fun updateMemcLevel(level: MemcLevelSetting) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(memcLevel = level) }
        }
    }

    private fun updateDenoiseLevel(level: DenoiseLevelSetting) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(denoiseLevel = level) }
        }
    }

    private fun updateSvPlayerSettings(settings: SvPlayerSettings) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(svPlayerSettings = settings) }
        }
    }

    private fun toggleTimeDisplay() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(showRemainingTime = !it.showRemainingTime) }
        }
    }

    private fun toggleUdpStreaming() {
        val currentPrefs = preferencesRepository.playerPreferences.value
        if (!currentPrefs.udpStreamingEnabled) {
            // Show config dialog before enabling
            output.showUdpConfigDialog(
                false,
                currentPrefs.udpStreamingHost,
                currentPrefs.udpStreamingPort,
                { host, port ->
                    viewModelScope.launch {
                        preferencesRepository.updatePlayerPreferences {
                            it.copy(
                                udpStreamingEnabled = true,
                                udpStreamingHost = host,
                                udpStreamingPort = port,
                            )
                        }
                    }
                }
            )
        } else {
            // Just disable
            viewModelScope.launch {
                preferencesRepository.updatePlayerPreferences { it.copy(udpStreamingEnabled = false) }
            }
        }
    }

    private fun onVideoZoomEvent(event: VideoZoomEvent) {
        when (event) {
            is VideoZoomEvent.ContentScaleChanged -> {
                updateVideoContentScale(event.contentScale)
            }
            is VideoZoomEvent.ZoomChanged -> {
                updateVideoZoom(event.mediaItem.mediaId, event.zoom)
            }
        }
    }

    private fun onSubtitleOptionEvent(event: SubtitleOptionsEvent) {
        when (event) {
            is SubtitleOptionsEvent.DelayChanged -> {
                updateSubtitleDelay(event.mediaItem.mediaId, event.delay)
            }
            is SubtitleOptionsEvent.SpeedChanged -> {
                updateSubtitleSpeed(event.mediaItem.mediaId, event.speed)
            }
        }
    }

    private fun updateSubtitleDelay(uri: String, delay: Long) {
        viewModelScope.launch {
            mediaRepository.updateSubtitleDelay(uri, delay)
        }
    }

    private fun updateSubtitleSpeed(uri: String, speed: Float) {
        viewModelScope.launch {
            mediaRepository.updateSubtitleSpeed(uri, speed)
        }
    }
}

@Stable
data class PlayerUiState(
    val playerPreferences: PlayerPreferences = PlayerPreferences(),
    val playWhenReady: Boolean = true,
    val decoderServiceState: DecoderServiceState = DecoderServiceState(),
)

sealed interface PlayerAction {
    data object NavigateUp : PlayerAction
    data object SelectSubtitle : PlayerAction
    data object SelectAudio : PlayerAction
    data object PlayInBackground : PlayerAction
    data class SetVideoDecoderMode(val mode: DecoderMode) : PlayerAction
    data class SetAudioDecoderMode(val mode: DecoderMode) : PlayerAction
    data object TryDecoderFallback : PlayerAction
    data class UpdateDecoderServiceState(val state: DecoderServiceState) : PlayerAction
    data class UpdatePlayWhenReady(val playWhenReady: Boolean) : PlayerAction
    data class UpdatePlayerBrightness(val value: Float) : PlayerAction
    data class SetLoopMode(val loopMode: LoopMode) : PlayerAction
    data object ToggleInterpolation : PlayerAction
    data object ToggleDenoise : PlayerAction
    data class SetProcessingResolution(val resolution: RifeResolutionSetting) : PlayerAction
    data class SetInterpolationAlgorithm(val algorithm: InterpolationAlgorithmSetting) : PlayerAction
    data class SetMemcLevel(val level: MemcLevelSetting) : PlayerAction
    data class SetDenoiseLevel(val level: DenoiseLevelSetting) : PlayerAction
    data class SetSvPlayerSettings(val settings: SvPlayerSettings) : PlayerAction
    data object ToggleTimeDisplay : PlayerAction
    data object ToggleUdpStreaming : PlayerAction
    data class OnVideoZoomEvent(val event: VideoZoomEvent) : PlayerAction
    data class OnSubtitleOptionEvent(val event: SubtitleOptionsEvent) : PlayerAction
}
