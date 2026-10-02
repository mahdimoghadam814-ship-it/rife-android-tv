package dev.anilbeesetti.nextplayer.settings.extensions

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.anilbeesetti.nextplayer.core.model.InterpolationAlgorithmSetting
import dev.anilbeesetti.nextplayer.core.ui.R

@Composable
fun InterpolationAlgorithmSetting.name(): String {
    val stringRes = when (this) {
        InterpolationAlgorithmSetting.RIFE -> R.string.interpolation_algorithm_rife
        InterpolationAlgorithmSetting.MEMC -> R.string.interpolation_algorithm_memc
    }

    return stringResource(stringRes)
}
