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
        InterpolationAlgorithmSetting.SVPLAYER -> R.string.interpolation_algorithm_svplayer
    }

    return stringResource(stringRes)
}

@Composable
fun InterpolationAlgorithmSetting.description(): String {
    val stringRes = when (this) {
        InterpolationAlgorithmSetting.RIFE -> R.string.interpolation_description_rife
        InterpolationAlgorithmSetting.MEMC -> R.string.interpolation_description_memc
        InterpolationAlgorithmSetting.SVPLAYER -> R.string.interpolation_description_svplayer
    }

    return stringResource(stringRes)
}
