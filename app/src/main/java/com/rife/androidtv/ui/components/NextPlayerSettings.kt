package com.rife.androidtv.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

enum class SettingsSubScreen {
    MAIN,
    VIDEO_PROCESSING,
    AUDIO_OFFSET,
    SUBTITLE_OFFSET
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextPlayerSettingsSheet(
    sheetState: SheetState,
    onDismissRequest: () -> Unit,
    // Video Processing callbacks & state
    isRifeEnabled: Boolean,
    onRifeToggle: (Boolean) -> Unit,
    rifeResolution: com.rife.androidtv.RifeResolution,
    onResolutionSelect: (com.rife.androidtv.RifeResolution) -> Unit,
    isFastDvdNetEnabled: Boolean,
    onFastDvdNetToggle: (Boolean) -> Unit,
    // Audio / Subtitle callbacks
    onOpenAudioTracks: () -> Unit,
    onOpenSubTracks: () -> Unit,
    onOpenExternalAudio: () -> Unit,
    onOpenExternalSub: () -> Unit,
    audioOffsetMs: Long,
    onAudioOffsetChange: (Long) -> Unit,
    subtitleOffsetMs: Long,
    onSubtitleOffsetChange: (Long) -> Unit
) {
    var subScreen by remember { mutableStateOf(SettingsSubScreen.MAIN) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            when (subScreen) {
                SettingsSubScreen.MAIN -> {
                    Text(
                        text = "Player Settings",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )

                    SettingsMenuItem(
                        icon = Icons.Default.Audiotrack,
                        title = "Audio Tracks",
                        subtitle = "Select audio track or open external audio",
                        onClick = {
                            onDismissRequest()
                            onOpenAudioTracks()
                        }
                    )

                    SettingsMenuItem(
                        icon = Icons.Default.Subtitles,
                        title = "Subtitle Tracks",
                        subtitle = "Select subtitle track or open external subtitle",
                        onClick = {
                            onDismissRequest()
                            onOpenSubTracks()
                        }
                    )

                    SettingsMenuItem(
                        icon = Icons.Default.Timer,
                        title = "Audio & Subtitle Delays",
                        subtitle = "Audio: ${audioOffsetMs}ms | Subtitle: ${subtitleOffsetMs}ms",
                        onClick = { subScreen = SettingsSubScreen.AUDIO_OFFSET }
                    )

                    // EXACTLY ONE custom settings entry allowed for Video Processing!
                    SettingsMenuItem(
                        icon = Icons.Default.Memory,
                        title = "Video Processing",
                        subtitle = "RIFE Frame Interpolation & FastDVDnet",
                        onClick = { subScreen = SettingsSubScreen.VIDEO_PROCESSING }
                    )
                }

                SettingsSubScreen.VIDEO_PROCESSING -> {
                    SettingsHeader(
                        title = "Video Processing",
                        onBack = { subScreen = SettingsSubScreen.MAIN }
                    )

                    // RIFE Toggle
                    SettingsSwitchItem(
                        title = "RIFE Frame Interpolation",
                        subtitle = if (isRifeEnabled) "Enabled (2x FPS)" else "Disabled",
                        checked = isRifeEnabled,
                        onCheckedChange = onRifeToggle
                    )

                    // RIFE Resolution Selection
                    Text(
                        text = "Processing Resolution",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )

                    val resolutionOptions = listOf(
                        com.rife.androidtv.RifeResolution.ORIGINAL to "Original",
                        com.rife.androidtv.RifeResolution.RES_1080P to "1080p",
                        com.rife.androidtv.RifeResolution.RES_720P to "720p",
                        com.rife.androidtv.RifeResolution.RES_480P to "480p"
                    )

                    resolutionOptions.forEach { (res, label) ->
                        SettingsRadioItem(
                            title = label,
                            selected = rifeResolution == res,
                            onClick = { onResolutionSelect(res) }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // FastDVDnet Toggle
                    SettingsSwitchItem(
                        title = "FastDVDnet Denoising",
                        subtitle = if (isFastDvdNetEnabled) "Enabled (Scaffold)" else "Disabled",
                        checked = isFastDvdNetEnabled,
                        onCheckedChange = onFastDvdNetToggle
                    )
                }

                SettingsSubScreen.AUDIO_OFFSET, SettingsSubScreen.SUBTITLE_OFFSET -> {
                    SettingsHeader(
                        title = "Audio & Subtitle Delays",
                        onBack = { subScreen = SettingsSubScreen.MAIN }
                    )

                    OffsetControlItem(
                        title = "Audio Delay",
                        valueMs = audioOffsetMs,
                        onDecrease = { onAudioOffsetChange((audioOffsetMs - 500L).coerceAtLeast(-5000L)) },
                        onIncrease = { onAudioOffsetChange((audioOffsetMs + 500L).coerceAtMost(5000L)) }
                    )

                    OffsetControlItem(
                        title = "Subtitle Delay",
                        valueMs = subtitleOffsetMs,
                        onDecrease = { onSubtitleOffsetChange((subtitleOffsetMs - 500L).coerceAtLeast(-5000L)) },
                        onIncrease = { onSubtitleOffsetChange((subtitleOffsetMs + 500L).coerceAtMost(5000L)) }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        TextButton(onClick = {
                            onDismissRequest()
                            onOpenExternalAudio()
                        }) {
                            Text("Attach Ext Audio")
                        }
                        TextButton(onClick = {
                            onDismissRequest()
                            onOpenExternalSub()
                        }) {
                            Text("Attach Ext Subtitle")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHeader(
    title: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back"
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun SettingsMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsSwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .focusable()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingsRadioItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 32.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun OffsetControlItem(
    title: String,
    valueMs: Long,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${valueMs} ms",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDecrease) {
                Icon(imageVector = Icons.Default.Remove, contentDescription = "Decrease")
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onIncrease) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Increase")
            }
        }
    }
}
