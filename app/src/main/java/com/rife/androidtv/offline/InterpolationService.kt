package com.rife.androidtv.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.anilbeesetti.nextplayer.core.ui.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Foreground wrapper around [OfflineInterpolationJob]. The job itself survives this service
 * (it is a process-level singleton on its own coroutine scope), so a swipe-away notification
 * or a system kill mid-movie does not lose the muxer's state - the service only exists to
 * hold the platform's foreground promise while the batch runs and to show honest progress.
 */
class InterpolationService : Service() {

    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                OfflineInterpolationJob.cancel()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val uri = intent.getParcelableExtra<Uri>(EXTRA_INPUT_URI)
                val name = intent.getStringExtra(EXTRA_DISPLAY_NAME)
                if (uri == null || name == null) {
                    Log.e(TAG, "start intent missing uri/name")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundWithNotification(name)
                observeJob?.cancel()
                observeJob = CoroutineScope(Dispatchers.Main).launch {
                    OfflineInterpolationJob.progress.collect { state ->
                        updateNotification(state)
                        if (!state.running && state.done) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
                OfflineInterpolationJob.start(this, uri, name)
                return START_NOT_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        observeJob?.cancel()
        super.onDestroy()
    }

    private fun startForegroundWithNotification(sourceName: String) {
        ensureChannel()
        val notification = buildNotification(sourceName, 0f, -1L)
        when {
            // mediaProcessing is the API 35+ home for transcoding jobs; API 34 only knows
            // dataSync, and below 34 no type is required at all.
            Build.VERSION.SDK_INT >= 35 -> startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            else -> {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun updateNotification(state: OfflineProgress) {
        val manager = getSystemService(NotificationManager::class.java)
        val name = state.sourceName ?: return
        val notification = when {
            state.error != null -> buildErrorNotification(name, state.error!!)
            state.outputName != null -> buildDoneNotification(name, state.outputName)
            else -> buildNotification(name, state.fraction, state.etaMs)
        }
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.offline_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.offline_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(sourceName: String, fraction: Float, etaMs: Long): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, InterpolationService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.rife.androidtv.R.drawable.ic_notification)
            .setContentTitle(getString(R.string.offline_notification_title, sourceName))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, (fraction * 100).toInt(), fraction <= 0f)
        if (etaMs > 0) {
            val minutes = (etaMs / 60_000L).coerceAtLeast(1L)
            builder.setContentText(getString(R.string.offline_notification_eta, minutes))
        } else {
            builder.setContentText(getString(R.string.offline_notification_running))
        }
        builder.addAction(0, getString(R.string.offline_notification_cancel), cancelIntent)
        return builder.build()
    }

    private fun buildDoneNotification(sourceName: String, outputName: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.rife.androidtv.R.drawable.ic_notification)
            .setContentTitle(getString(R.string.offline_notification_done_title, sourceName))
            .setContentText(getString(R.string.offline_notification_done_text, outputName))
            .setAutoCancel(true)
            .build()

    private fun buildErrorNotification(sourceName: String, error: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.rife.androidtv.R.drawable.ic_notification)
            .setContentTitle(getString(R.string.offline_notification_error_title, sourceName))
            .setContentText(error)
            .setAutoCancel(true)
            .build()

    companion object {
        private const val TAG = "InterpService"
        private const val CHANNEL_ID = "offline_interpolation"
        private const val NOTIFICATION_ID = 0x0FF1

        const val ACTION_START = "com.rife.androidtv.offline.START"
        const val ACTION_CANCEL = "com.rife.androidtv.offline.CANCEL"
        const val EXTRA_INPUT_URI = "input_uri"
        const val EXTRA_DISPLAY_NAME = "display_name"

        fun start(context: Context, inputUri: Uri, displayName: String) {
            val intent = Intent(context, InterpolationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_INPUT_URI, inputUri)
                .putExtra(EXTRA_DISPLAY_NAME, displayName)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, InterpolationService::class.java).setAction(ACTION_CANCEL)
            context.startService(intent)
        }
    }
}
