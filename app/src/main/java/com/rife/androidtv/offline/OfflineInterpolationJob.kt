package com.rife.androidtv.offline

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Immutable snapshot of the one offline job the app may run at a time. */
data class OfflineProgress(
    val running: Boolean = false,
    val sourceName: String? = null,
    val fraction: Float = 0f,
    val etaMs: Long = -1L,
    val outputName: String? = null,
    val error: String? = null,
    val done: Boolean = false,
    val cancelled: Boolean = false,
)

/**
 * Owner of the single offline interpolation job. One job at a time is a deliberate limit: the
 * pipeline already saturates the decoder, the CPU ME and the encoder; two would thrash all
 * three and finish neither quickly. The [InterpolationService] observes [progress] to keep the
 * foreground notification honest and to stop itself when the job ends.
 */
object OfflineInterpolationJob {

    private const val TAG = "OfflineInterpJob"

    /** Movies/Interpolated, matching where players look for personal video content. */
    private const val OUTPUT_DIR = "Interpolated"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _progress = MutableStateFlow(OfflineProgress())
    val progress: StateFlow<OfflineProgress> = _progress.asStateFlow()

    @Volatile
    private var cancelRequested = false

    private var activeThread: Thread? = null

    fun start(context: Context, inputUri: Uri, displayName: String) {
        if (_progress.value.running) {
            Log.w(TAG, "job already running; ignoring start for $displayName")
            return
        }
        cancelRequested = false
        _progress.value = OfflineProgress(running = true, sourceName = displayName)
        val appContext = context.applicationContext
        scope.launch {
            withContext(Dispatchers.IO) {
                runJob(appContext, inputUri, displayName)
            }
        }
    }

    fun cancel() {
        if (!_progress.value.running) return
        cancelRequested = true
        Log.i(TAG, "cancel requested for ${_progress.value.sourceName}")
    }

    private fun runJob(context: Context, inputUri: Uri, displayName: String) {
        var outputUri: Uri? = null
        try {
            val outputName = outputFileName(displayName)
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, outputName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/x-matroska")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + OUTPUT_DIR)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                } else {
                    @Suppress("DEPRECATION")
                    put(
                        MediaStore.Video.Media.DATA,
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                            .toString() + "/" + OUTPUT_DIR + "/" + outputName,
                    )
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore insert failed for $outputName")
            outputUri = uri
            val outputStream = resolver.openOutputStream(uri, "w")
                ?: throw IllegalStateException("cannot open output stream for $outputName")

            val runner = OfflineInterpolationRunner(
                context = context,
                inputUri = inputUri,
                output = outputStream,
                sourceDisplayName = displayName,
                onProgress = { fraction, etaMs ->
                    _progress.value = _progress.value.copy(fraction = fraction, etaMs = etaMs)
                },
                cancelled = { cancelRequested },
            )
            activeThread = Thread({ runner.run() }, "OfflineInterpJob").also {
                it.priority = Thread.NORM_PRIORITY - 1
                it.start()
                it.join()
            }
            outputStream.close()

            if (cancelRequested) {
                resolver.delete(uri, null, null)
                _progress.value = OfflineProgress(cancelled = true, sourceName = displayName)
                Log.i(TAG, "job cancelled; deleted partial output $outputName")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            _progress.value = OfflineProgress(
                running = false,
                sourceName = displayName,
                fraction = 1f,
                outputName = outputName,
                done = true,
            )
            Log.i(TAG, "job published $outputName at $uri")
        } catch (t: Throwable) {
            Log.e(TAG, "offline job failed", t)
            outputUri?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
            _progress.value = OfflineProgress(
                running = false,
                sourceName = displayName,
                error = t.message ?: t.javaClass.simpleName,
                done = true,
            )
        } finally {
            activeThread = null
        }
    }

    private fun outputFileName(displayName: String): String {
        val base = displayName.substringBeforeLast('.')
        return if (base.isBlank()) "interpolated.mkv" else "$base (3x).mkv"
    }
}
