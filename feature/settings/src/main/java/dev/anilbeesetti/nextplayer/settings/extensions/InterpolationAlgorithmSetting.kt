package dev.anilbeesetti.nextplayer.settings.extensions

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.anilbeesetti.nextplayer.core.model.InterpolationAlgorithmSetting
import dev.anilbeesetti.nextplayer.core.ui.R

/**
 * The label of the selected interpolation backend. SVPlayer is the only backend left, so this is
 * a constant rather than a lookup - the extension is kept because both settings surfaces read the
 * preference through it.
 */
@Composable
fun InterpolationAlgorithmSetting.name(): String = stringResource(
    R.string.interpolation_algorithm_svplayer,
)

/** The one-line description shown under [name]. */
@Composable
fun InterpolationAlgorithmSetting.description(): String = stringResource(
    R.string.interpolation_description_svplayer,
)
