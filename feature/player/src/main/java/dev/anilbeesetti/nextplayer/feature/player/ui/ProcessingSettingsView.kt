package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.model.DenoiseLevelSetting
import dev.anilbeesetti.nextplayer.core.model.InterpolationAlgorithmSetting
import dev.anilbeesetti.nextplayer.core.model.MemcLevelSetting
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.core.model.RifeResolutionSetting
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.NextSwitch

/**
 * The six video-processing controls, offered from the player itself so a setting can be changed
 * without leaving playback.
 *
 * Uses the same slide-in [OverlayView] panel as the decoder/speed/playlist selectors so it reads
 * as part of the player rather than as a settings screen dropped on top of it, and keeps the
 * choices inline as chip rows instead of stacking dialogs: there are only two of them and they
 * fit, which is also what keeps the panel from taking over the screen.
 */
@Composable
fun BoxScope.ProcessingSettingsView(
    show: Boolean,
    playerPreferences: PlayerPreferences,
    onToggleInterpolation: () -> Unit,
    onToggleDenoise: () -> Unit,
    onResolutionSelected: (RifeResolutionSetting) -> Unit,
    onAlgorithmSelected: (InterpolationAlgorithmSetting) -> Unit,
    onMemcLevelSelected: (MemcLevelSetting) -> Unit,
    onDenoiseLevelSelected: (DenoiseLevelSetting) -> Unit,
    modifier: Modifier = Modifier,
) {
    OverlayView(
        modifier = modifier,
        show = show,
        title = stringResource(R.string.video_processing),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToggleRow(
                title = stringResource(R.string.rife),
                // The backend is chosen below, so the wording follows the selection instead of
                // naming one algorithm for both.
                description = playerPreferences.interpolationAlgorithm.interpolationDescription(),
                checked = playerPreferences.rifeEnabled,
                onToggle = onToggleInterpolation,
            )
            OptionGroup(
                title = stringResource(R.string.processing_resolution),
                options = RifeResolutionSetting.entries,
                selected = playerPreferences.rifeResolution,
                label = { it.resolutionLabel() },
                onSelected = onResolutionSelected,
            )
            OptionGroup(
                title = stringResource(R.string.interpolation_algorithm),
                options = InterpolationAlgorithmSetting.entries,
                selected = playerPreferences.interpolationAlgorithm,
                label = { it.algorithmLabel() },
                onSelected = onAlgorithmSelected,
            )
            OptionGroup(
                title = stringResource(R.string.memc_level),
                options = MemcLevelSetting.entries,
                selected = playerPreferences.memcLevel,
                label = { it.memcLevelLabel() },
                onSelected = onMemcLevelSelected,
            )
            HorizontalDivider()
            ToggleRow(
                title = stringResource(R.string.fastdvdnet),
                description = stringResource(R.string.fastdvdnet_description),
                checked = playerPreferences.fastDvdNetEnabled,
                onToggle = onToggleDenoise,
            )
            OptionGroup(
                title = stringResource(R.string.denoise_level),
                options = DenoiseLevelSetting.entries,
                selected = playerPreferences.denoiseLevel,
                label = { it.denoiseLevelLabel() },
                onSelected = onDenoiseLevelSelected,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .toggleable(
                    value = checked,
                    onValueChange = { onToggle() },
                )
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NextSwitch(
                checked = checked,
                onCheckedChange = null,
            )
        }
    }
}

@Composable
private fun <T> OptionGroup(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .border(
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                            ),
                            shape = CircleShape,
                        )
                        .background(
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            } else {
                                Color.Transparent
                            },
                            shape = CircleShape,
                        )
                        .clickable { onSelected(option) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            LocalContentColor.current
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RifeResolutionSetting.resolutionLabel(): String = stringResource(
    when (this) {
        RifeResolutionSetting.AUTO -> R.string.rife_resolution_auto
        RifeResolutionSetting.ORIGINAL -> R.string.rife_resolution_original
        RifeResolutionSetting.RES_1080P -> R.string.rife_resolution_1080p
        RifeResolutionSetting.RES_720P -> R.string.rife_resolution_720p
        RifeResolutionSetting.RES_480P -> R.string.rife_resolution_480p
    },
)

@Composable
private fun InterpolationAlgorithmSetting.algorithmLabel(): String = stringResource(
    when (this) {
        InterpolationAlgorithmSetting.RIFE -> R.string.interpolation_algorithm_rife
        InterpolationAlgorithmSetting.MEMC -> R.string.interpolation_algorithm_memc
    },
)

@Composable
private fun InterpolationAlgorithmSetting.interpolationDescription(): String = stringResource(
    when (this) {
        InterpolationAlgorithmSetting.RIFE -> R.string.interpolation_description_rife
        InterpolationAlgorithmSetting.MEMC -> R.string.interpolation_description_memc
    },
)

@Composable
private fun MemcLevelSetting.memcLevelLabel(): String = stringResource(
    when (this) {
        MemcLevelSetting.TWO_X -> R.string.memc_level_2x
        MemcLevelSetting.THREE_X -> R.string.memc_level_3x
    },
)

@Composable
private fun DenoiseLevelSetting.denoiseLevelLabel(): String = stringResource(
    when (this) {
        DenoiseLevelSetting.LIGHT -> R.string.denoise_level_light
        DenoiseLevelSetting.BALANCED -> R.string.denoise_level_balanced
        DenoiseLevelSetting.STRONG -> R.string.denoise_level_strong
    },
)
