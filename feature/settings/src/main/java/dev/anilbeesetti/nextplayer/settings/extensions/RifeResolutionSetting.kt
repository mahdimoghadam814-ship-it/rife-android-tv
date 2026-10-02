package dev.anilbeesetti.nextplayer.settings.extensions

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.anilbeesetti.nextplayer.core.model.RifeResolutionSetting
import dev.anilbeesetti.nextplayer.core.ui.R

@Composable
fun RifeResolutionSetting.name(): String {
    val stringRes = when (this) {
        RifeResolutionSetting.AUTO -> R.string.rife_resolution_auto
        RifeResolutionSetting.ORIGINAL -> R.string.rife_resolution_original
        RifeResolutionSetting.RES_1080P -> R.string.rife_resolution_1080p
        RifeResolutionSetting.RES_720P -> R.string.rife_resolution_720p
        RifeResolutionSetting.RES_480P -> R.string.rife_resolution_480p
    }

    return stringResource(stringRes)
}
