package dev.anilbeesetti.nextplayer.settings.extensions

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.anilbeesetti.nextplayer.core.model.MemcLevelSetting
import dev.anilbeesetti.nextplayer.core.ui.R

@Composable
fun MemcLevelSetting.name(): String {
    val stringRes = when (this) {
        MemcLevelSetting.TWO_X -> R.string.memc_level_2x
        MemcLevelSetting.THREE_X -> R.string.memc_level_3x
    }

    return stringResource(stringRes)
}
