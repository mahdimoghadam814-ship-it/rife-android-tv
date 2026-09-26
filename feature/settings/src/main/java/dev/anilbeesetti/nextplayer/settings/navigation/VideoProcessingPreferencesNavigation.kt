package dev.anilbeesetti.nextplayer.settings.navigation

import androidx.compose.runtime.SideEffect
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.settings.screens.videoprocessing.VideoProcessingPreferencesScreen
import dev.anilbeesetti.nextplayer.settings.screens.videoprocessing.VideoProcessingPreferencesViewModel
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Serializable
object VideoProcessingPreferencesRoute : NavKey

fun NavBackStack<NavKey>.navigateToVideoProcessingPreferences() {
    add(VideoProcessingPreferencesRoute)
}

fun EntryProviderScope<NavKey>.videoProcessingPreferencesEntry(onNavigateUp: () -> Unit) {
    entry<VideoProcessingPreferencesRoute> {
        val output = VideoProcessingPreferencesViewModel.Output(
            navigateUp = onNavigateUp,
        )
        val viewModel = koinViewModel<VideoProcessingPreferencesViewModel>(
            parameters = { parametersOf(output) },
        )
        SideEffect { viewModel.output = output }
        VideoProcessingPreferencesScreen(viewModel = viewModel)
    }
}
