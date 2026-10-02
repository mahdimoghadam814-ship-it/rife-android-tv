package dev.anilbeesetti.nextplayer.settings.screens.videoprocessing

import androidx.compose.runtime.Stable
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.model.InterpolationAlgorithmSetting
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.core.model.RifeResolutionSetting
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class VideoProcessingPreferencesViewModel(
    private val preferencesRepository: PreferencesRepository,
    @InjectedParam internal var output: Output,
) : MviViewModel<VideoProcessingPreferencesUiState, VideoProcessingPreferencesUiEvent>() {

    data class Output(
        val navigateUp: () -> Unit,
    )

    private val stateInternal = MutableStateFlow(
        VideoProcessingPreferencesUiState(
            preferences = preferencesRepository.playerPreferences.value,
        ),
    )
    override val state: StateFlow<VideoProcessingPreferencesUiState> = stateInternal.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.playerPreferences.collect { preferences ->
                stateInternal.update { it.copy(preferences = preferences) }
            }
        }
    }

    override fun onAction(action: VideoProcessingPreferencesUiEvent) {
        when (action) {
            is VideoProcessingPreferencesUiEvent.NavigateUp -> output.navigateUp()
            is VideoProcessingPreferencesUiEvent.ShowDialog -> showDialog(action.value)
            is VideoProcessingPreferencesUiEvent.ToggleRife -> toggleRife()
            is VideoProcessingPreferencesUiEvent.ToggleFastDvdNet -> toggleFastDvdNet()
            is VideoProcessingPreferencesUiEvent.UpdateRifeResolution -> updateRifeResolution(action.value)
            is VideoProcessingPreferencesUiEvent.UpdateInterpolationAlgorithm -> updateInterpolationAlgorithm(action.value)
        }
    }

    private fun showDialog(value: VideoProcessingDialog?) {
        stateInternal.update {
            it.copy(showDialog = value)
        }
    }

    private fun toggleRife() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(rifeEnabled = !it.rifeEnabled)
            }
        }
    }

    private fun toggleFastDvdNet() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(fastDvdNetEnabled = !it.fastDvdNetEnabled)
            }
        }
    }

    private fun updateRifeResolution(value: RifeResolutionSetting) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(rifeResolution = value)
            }
        }
    }

    private fun updateInterpolationAlgorithm(value: InterpolationAlgorithmSetting) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(interpolationAlgorithm = value)
            }
        }
    }
}

@Stable
data class VideoProcessingPreferencesUiState(
    val showDialog: VideoProcessingDialog? = null,
    val preferences: PlayerPreferences = PlayerPreferences(),
)

sealed interface VideoProcessingDialog {
    data object RifeResolutionDialog : VideoProcessingDialog
    data object InterpolationAlgorithmDialog : VideoProcessingDialog
}

sealed interface VideoProcessingPreferencesUiEvent {
    data object NavigateUp : VideoProcessingPreferencesUiEvent
    data class ShowDialog(val value: VideoProcessingDialog?) : VideoProcessingPreferencesUiEvent
    data object ToggleRife : VideoProcessingPreferencesUiEvent
    data object ToggleFastDvdNet : VideoProcessingPreferencesUiEvent
    data class UpdateRifeResolution(val value: RifeResolutionSetting) : VideoProcessingPreferencesUiEvent
    data class UpdateInterpolationAlgorithm(val value: InterpolationAlgorithmSetting) : VideoProcessingPreferencesUiEvent
}
