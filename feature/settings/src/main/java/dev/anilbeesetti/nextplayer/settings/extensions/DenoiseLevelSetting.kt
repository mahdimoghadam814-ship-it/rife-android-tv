package dev.anilbeesetti.nextplayer.settings.extensions

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.anilbeesetti.nextplayer.core.model.DenoiseLevelSetting
import dev.anilbeesetti.nextplayer.core.ui.R

@Composable
fun DenoiseLevelSetting.name(): String {
    val stringRes = when (this) {
        DenoiseLevelSetting.LIGHT -> R.string.denoise_level_light
        DenoiseLevelSetting.BALANCED -> R.string.denoise_level_balanced
        DenoiseLevelSetting.STRONG -> R.string.denoise_level_strong
    }

    return stringResource(stringRes)
}
