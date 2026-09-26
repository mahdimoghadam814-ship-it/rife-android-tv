package com.rife.androidtv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rife.androidtv.Statistics

@Composable
fun EngineStatusOverlay(
    visible: Boolean,
    videoName: String,
    extAudioName: String,
    extSubName: String,
    audioOffsetMs: Long,
    subOffsetMs: Long,
    isRifeEnabled: Boolean,
    isFastDvdNetEnabled: Boolean,
    stats: Statistics?,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
            tonalElevation = 6.dp,
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "Engine Status",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                )

                Text(
                    text = "Video: $videoName",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (extAudioName != "None" || extSubName != "None") {
                    Text(
                        text = "Ext Audio: $extAudioName | Ext Sub: $extSubName",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (audioOffsetMs != 0L || subOffsetMs != 0L) {
                    Text(
                        text = "Audio Delay: ${audioOffsetMs}ms | Sub Delay: ${subOffsetMs}ms",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val rifeStatusStr = if (isRifeEnabled) "ON" else "OFF"
                val fastDvdStatusStr = if (isFastDvdNetEnabled) "ON" else "OFF"

                Text(
                    text = "RIFE: $rifeStatusStr | FastDVDnet: $fastDvdStatusStr",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (stats != null) {
                    Text(
                        text = "Input: ${"%.1f".format(stats.inputFps)} FPS | Output: ${"%.1f".format(stats.outputFps)} FPS",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Res: ${stats.currentResolution} | Time: ${stats.processingTimeMs}ms | Drop: ${stats.droppedFrames}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
