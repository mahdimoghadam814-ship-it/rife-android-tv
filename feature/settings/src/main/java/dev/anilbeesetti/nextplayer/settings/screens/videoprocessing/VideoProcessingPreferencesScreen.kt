package dev.anilbeesetti.nextplayer.settings.screens.videoprocessing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.model.InterpolationAlgorithmSetting
import dev.anilbeesetti.nextplayer.core.model.RifeResolutionSetting
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.ClickablePreferenceItem
import dev.anilbeesetti.nextplayer.core.ui.components.ListSectionTitle
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.components.PreferenceSwitch
import dev.anilbeesetti.nextplayer.core.ui.components.RadioTextButton
import dev.anilbeesetti.nextplayer.core.ui.components.tvFocusDown
import dev.anilbeesetti.nextplayer.core.ui.components.tvListFocus
import dev.anilbeesetti.nextplayer.core.ui.components.rememberTvListFocusRequester
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.settings.composables.OptionsDialog
import dev.anilbeesetti.nextplayer.settings.extensions.name
import androidx.compose.foundation.lazy.items

@Composable
fun VideoProcessingPreferencesScreen(
    viewModel: VideoProcessingPreferencesViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    VideoProcessingPreferencesScreenContent(
        state = state,
        onAction = viewModel::onAction,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VideoProcessingPreferencesScreenContent(
    state: VideoProcessingPreferencesUiState,
    onAction: (VideoProcessingPreferencesUiEvent) -> Unit,
) {
    val listFocusRequester = rememberTvListFocusRequester()
    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(id = R.string.video_processing),
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = { onAction(VideoProcessingPreferencesUiEvent.NavigateUp) },
                        modifier = Modifier.tvFocusDown(listFocusRequester),
                    ) {
                        Icon(
                            imageVector = NextIcons.ArrowBack,
                            contentDescription = stringResource(id = R.string.navigate_up),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(state = rememberScrollState())
                .tvListFocus(listFocusRequester)
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            ListSectionTitle(text = stringResource(id = R.string.video_processing))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                PreferenceSwitch(
                    title = stringResource(id = R.string.rife),
                    description = stringResource(id = R.string.rife_description),
                    icon = NextIcons.Speed,
                    isChecked = state.preferences.rifeEnabled,
                    onClick = { onAction(VideoProcessingPreferencesUiEvent.ToggleRife) },
                    isFirstItem = true,
                )
                ClickablePreferenceItem(
                    title = stringResource(id = R.string.processing_resolution),
                    description = state.preferences.rifeResolution.name(),
                    icon = NextIcons.Player,
                    onClick = {
                        onAction(VideoProcessingPreferencesUiEvent.ShowDialog(VideoProcessingDialog.RifeResolutionDialog))
                    },
                )
                ClickablePreferenceItem(
                    title = stringResource(id = R.string.interpolation_algorithm),
                    description = state.preferences.interpolationAlgorithm.name(),
                    icon = NextIcons.Speed,
                    onClick = {
                        onAction(VideoProcessingPreferencesUiEvent.ShowDialog(VideoProcessingDialog.InterpolationAlgorithmDialog))
                    },
                )
                PreferenceSwitch(
                    title = stringResource(id = R.string.fastdvdnet),
                    description = stringResource(id = R.string.fastdvdnet_description),
                    icon = NextIcons.Player,
                    isChecked = state.preferences.fastDvdNetEnabled,
                    onClick = { onAction(VideoProcessingPreferencesUiEvent.ToggleFastDvdNet) },
                    isLastItem = true,
                )
            }
        }

        when (state.showDialog) {
            VideoProcessingDialog.RifeResolutionDialog -> {
                OptionsDialog(
                    text = stringResource(id = R.string.processing_resolution),
                    onDismissClick = { onAction(VideoProcessingPreferencesUiEvent.ShowDialog(null)) },
                ) {
                    items(RifeResolutionSetting.entries.toTypedArray()) {
                        RadioTextButton(
                            text = it.name(),
                            selected = it == state.preferences.rifeResolution,
                            onClick = {
                                onAction(VideoProcessingPreferencesUiEvent.UpdateRifeResolution(it))
                                onAction(VideoProcessingPreferencesUiEvent.ShowDialog(null))
                            },
                        )
                    }
                }
            }
            VideoProcessingDialog.InterpolationAlgorithmDialog -> {
                OptionsDialog(
                    text = stringResource(id = R.string.interpolation_algorithm),
                    onDismissClick = { onAction(VideoProcessingPreferencesUiEvent.ShowDialog(null)) },
                ) {
                    items(InterpolationAlgorithmSetting.entries.toTypedArray()) {
                        RadioTextButton(
                            text = it.name(),
                            selected = it == state.preferences.interpolationAlgorithm,
                            onClick = {
                                onAction(VideoProcessingPreferencesUiEvent.UpdateInterpolationAlgorithm(it))
                                onAction(VideoProcessingPreferencesUiEvent.ShowDialog(null))
                            },
                        )
                    }
                }
            }
            null -> Unit
        }
    }
}
