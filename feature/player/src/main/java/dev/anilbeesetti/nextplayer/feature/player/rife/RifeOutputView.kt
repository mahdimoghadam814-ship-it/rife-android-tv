package dev.anilbeesetti.nextplayer.feature.player.rife

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.SurfaceInfo
import androidx.media3.common.util.UnstableApi

/**
 * The full-screen surface the interpolated / FastDVDnet output is rendered to while a processing stage is
 * enabled.
 *
 * It is only composed while processing is on; the real Next Player [PlayerSurface] takes over
 * again when processing is switched off. The surface is published to the controller as it is
 * created, resized and destroyed, so the processor never has to observe a View lifecycle itself.
 */
@UnstableApi
@Composable
fun RifeOutputView(
    controller: RifeController,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        controller.setOutputSurfaceInfo(buildSurfaceInfo(holder))
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        controller.setOutputSurfaceInfo(buildSurfaceInfo(holder))
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        controller.setOutputSurfaceInfo(null)
                    }
                })
            }
        },
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    )
}

private fun buildSurfaceInfo(holder: SurfaceHolder): SurfaceInfo? {
    val surface = holder.surface
    if (!surface.isValid) {
        return null
    }
    val width = holder.surfaceFrame.width()
    val height = holder.surfaceFrame.height()
    return SurfaceInfo(surface, width, height)
}
