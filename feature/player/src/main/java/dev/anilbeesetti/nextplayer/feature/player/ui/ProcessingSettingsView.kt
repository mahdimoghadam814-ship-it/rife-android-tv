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
import dev.anilbeesetti.nextplayer.core.model.SvBlockSizeSetting
import dev.anilbeesetti.nextplayer.core.model.SvBlendAlgorithmSetting
import dev.anilbeesetti.nextplayer.core.model.SvPlayerSettings
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.NextSwitch
import dev.anilbeesetti.nextplayer.core.ui.components.PreferenceSlider

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
    onSvSettingsChanged: (SvPlayerSettings) -> Unit,
    onRecordTestClip: () -> Unit = {},
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
            if (playerPreferences.interpolationAlgorithm == InterpolationAlgorithmSetting.SVPLAYER) {
                SvSettingsSection(
                    settings = playerPreferences.svPlayerSettings,
                    onSettingsChanged = onSvSettingsChanged,
                )
            }
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
            HorizontalDivider()
            // Phase D's only in-app control. The capture exercises the very settings above it, so
            // it belongs on this sheet rather than in the playback controls - and its own state
            // lives here, because whether a diagnostic clip is rolling is not a preference anyone
            // would want restored the next time the player opens.
            var recording by remember { mutableStateOf(false) }
            ToggleRow(
                title = stringResource(R.string.record_test_clip),
                description = stringResource(R.string.record_test_clip_description),
                checked = recording,
                onToggle = {
                    recording = !recording
                    onRecordTestClip()
                },
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

/**
 * The two ends of a bar, so the direction the slider moves in is legible without a paragraph.
 */
@Composable
private fun BarEndLabels(start: String, end: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = start,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = end,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The SVPlayer tuning surface: the two bars that carry the design, then the expert rungs of the
 * same ladder for a user who wants to set one by hand. Everything writes through
 * [onSettingsChanged] as a whole object so the panel never has to know which fields the engine
 * has started reading yet.
 */
@Composable
private fun SvSettingsSection(
    settings: SvPlayerSettings,
    onSettingsChanged: (SvPlayerSettings) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.sv_settings),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PreferenceSlider(
            title = stringResource(R.string.sv_performance_quality),
            description = stringResource(R.string.sv_performance_quality_description),
            value = settings.performanceQuality,
            valueRange = 0f..1f,
            onValueChange = { onSettingsChanged(settings.copy(performanceQuality = it)) },
        )
        BarEndLabels(
            start = stringResource(R.string.sv_performance_end),
            end = stringResource(R.string.sv_quality_end),
        )
        PreferenceSlider(
            title = stringResource(R.string.sv_artifact_mask),
            description = stringResource(R.string.sv_artifact_mask_description),
            value = settings.artifactMaskLevel,
            valueRange = 0f..1f,
            onValueChange = { onSettingsChanged(settings.copy(artifactMaskLevel = it)) },
        )
        BarEndLabels(
            start = stringResource(R.string.sv_artifact_mask_off),
            end = stringResource(R.string.sv_artifact_mask_strong),
        )
        HorizontalDivider()
        Text(
            text = stringResource(R.string.sv_expert),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OptionGroup(
            title = stringResource(R.string.sv_subpel),
            options = listOf(1, 2),
            selected = settings.subpel,
            label = {
                stringResource(if (it == 2) R.string.sv_subpel_2 else R.string.sv_subpel_1)
            },
            onSelected = { onSettingsChanged(settings.copy(subpel = it)) },
        )
        OptionGroup(
            title = stringResource(R.string.sv_overlap),
            options = listOf(0, 1, 2),
            selected = settings.overlap,
            label = {
                stringResource(
                    when (it) {
                        1 -> R.string.sv_overlap_1
                        2 -> R.string.sv_overlap_2
                        else -> R.string.sv_overlap_0
                    }
                )
            },
            onSelected = { onSettingsChanged(settings.copy(overlap = it)) },
        )
        OptionGroup(
            title = stringResource(R.string.sv_block_size),
            options = SvBlockSizeSetting.entries,
            selected = settings.blockSize,
            label = {
                stringResource(
                    when (it) {
                        SvBlockSizeSetting.AUTO -> R.string.sv_block_auto
                        SvBlockSizeSetting.BLOCK_16X8 -> R.string.sv_block_16x8
                        SvBlockSizeSetting.BLOCK_16X16 -> R.string.sv_block_16x16
                        SvBlockSizeSetting.BLOCK_32X8 -> R.string.sv_block_32x8
                        SvBlockSizeSetting.BLOCK_32X16 -> R.string.sv_block_32x16
                    }
                )
            },
            onSelected = { onSettingsChanged(settings.copy(blockSize = it)) },
        )
        OptionGroup(
            title = stringResource(R.string.sv_me_scale),
            options = listOf(1, 2),
            selected = settings.meScale,
            label = {
                stringResource(if (it == 2) R.string.sv_me_scale_2 else R.string.sv_me_scale_1)
            },
            onSelected = { onSettingsChanged(settings.copy(meScale = it)) },
        )
        OptionGroup(
            title = stringResource(R.string.sv_search_distance),
            options = listOf(0, 8, 16, 32, 48),
            selected = settings.searchDistance,
            label = {
                if (it == 0) {
                    stringResource(R.string.sv_search_distance_adaptive)
                } else {
                    "$it px"
                }
            },
            onSelected = { onSettingsChanged(settings.copy(searchDistance = it)) },
        )
        PreferenceSlider(
            title = stringResource(R.string.sv_penalty_lambda),
            description = null,
            value = settings.penaltyLambda,
            valueRange = 0f..30f,
            onValueChange = { onSettingsChanged(settings.copy(penaltyLambda = it)) },
        )
        OptionGroup(
            title = stringResource(R.string.sv_blend_algorithm),
            options = SvBlendAlgorithmSetting.entries,
            selected = settings.blendAlgorithm,
            label = {
                stringResource(
                    when (it) {
                        SvBlendAlgorithmSetting.BIDIRECTIONAL -> R.string.sv_blend_bidirectional
                        SvBlendAlgorithmSetting.MEDIAN -> R.string.sv_blend_median
                        SvBlendAlgorithmSetting.COVER -> R.string.sv_blend_cover
                    }
                )
            },
            onSelected = { onSettingsChanged(settings.copy(blendAlgorithm = it)) },
        )
        ToggleRow(
            title = stringResource(R.string.sv_scene_adaptive),
            description = stringResource(R.string.sv_scene_adaptive_description),
            checked = settings.sceneAdaptive,
            onToggle = { onSettingsChanged(settings.copy(sceneAdaptive = !settings.sceneAdaptive)) },
        )
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
        InterpolationAlgorithmSetting.SVPLAYER -> R.string.interpolation_algorithm_svplayer
    },
)

@Composable
private fun InterpolationAlgorithmSetting.interpolationDescription(): String = stringResource(
    when (this) {
        InterpolationAlgorithmSetting.RIFE -> R.string.interpolation_description_rife
        InterpolationAlgorithmSetting.MEMC -> R.string.interpolation_description_memc
        InterpolationAlgorithmSetting.SVPLAYER -> R.string.interpolation_description_svplayer
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
