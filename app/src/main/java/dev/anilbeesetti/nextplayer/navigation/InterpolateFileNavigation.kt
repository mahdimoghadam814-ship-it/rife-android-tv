package dev.anilbeesetti.nextplayer.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.rife.androidtv.offline.InterpolateFileScreen
import kotlinx.serialization.Serializable

@Serializable
object InterpolateFileRoute : NavKey

fun NavBackStack<NavKey>.navigateToInterpolateFile() {
    add(InterpolateFileRoute)
}

fun EntryProviderScope<NavKey>.interpolateFileEntry(onNavigateUp: () -> Unit) {
    entry<InterpolateFileRoute> {
        InterpolateFileScreen(onNavigateUp = onNavigateUp)
    }
}
