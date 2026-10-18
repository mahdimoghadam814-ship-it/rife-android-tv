package com.rife.androidtv.offline

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar

/**
 * Main-menu entry for the offline batch interpolator: pick a file, watch the progress the
 * foreground service reports through [OfflineInterpolationJob], collect the finished copy from
 * Movies/Interpolated.
 */
@Composable
fun InterpolateFileScreen(onNavigateUp: () -> Unit) {
    val context = LocalContext.current
    var pickedUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pickedName by rememberSaveable { mutableStateOf<String?>(null) }
    val progress by OfflineInterpolationJob.progress.collectAsStateWithLifecycle()
    val busy = progress.running

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pickedUri = uri
            pickedName = uri.lastPathSegment
        }
    }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(R.string.interpolate_file),
                navigationIcon = {
                    OutlinedButton(onClick = onNavigateUp) {
                        Text(stringResource(R.string.navigate_up))
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.interpolate_file_description),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(
                onClick = { picker.launch(arrayOf("video/*")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = pickedName ?: stringResource(R.string.offline_pick_file),
                )
            }
            when {
                progress.error != null -> Text(
                    text = stringResource(R.string.offline_failed, progress.error!!),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                progress.outputName != null -> Text(
                    text = stringResource(R.string.offline_done, progress.outputName!!),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                progress.cancelled -> Text(
                    text = stringResource(R.string.offline_cancelled),
                    style = MaterialTheme.typography.bodyMedium,
                )
                busy -> {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val etaMinutes = (progress.etaMs / 60_000L).coerceAtLeast(0L)
                    Text(
                        text = if (etaMinutes > 0) {
                            stringResource(
                                R.string.offline_eta,
                                (progress.fraction * 100).toInt(),
                                etaMinutes,
                            )
                        } else {
                            stringResource(R.string.offline_running, (progress.fraction * 100).toInt())
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> Text(
                    text = stringResource(R.string.offline_idle),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val sourceUri = pickedUri
            if (sourceUri != null) {
                if (!busy) {
                    Button(
                        onClick = {
                            InterpolationService.start(
                                context, sourceUri, pickedName ?: "video",
                            )
                        },
                        enabled = !progress.running,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.offline_start))
                    }
                } else {
                    OutlinedButton(
                        onClick = { OfflineInterpolationJob.cancel() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.offline_cancel))
                    }
                }
            }
        }
    }
}
