package com.rife.androidtv

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource

@androidx.media3.common.util.UnstableApi
class MediaPlaybackManager(
    private val context: Context,
    private val player: ExoPlayer,
    private val audioDelayProcessor: AudioDelayAudioProcessor? = null
) {
    var videoUri: Uri? = null
        private set

    var externalAudioUri: Uri? = null
        private set

    var externalAudioName: String? = null
        private set

    var externalSubtitleUri: Uri? = null
        private set

    var externalSubtitleMimeType: String? = null
        private set

    var externalSubtitleName: String? = null
        private set

    var audioOffsetMs: Long = 0L
        private set

    var subtitleOffsetMs: Long = 0L
        private set

    var isExternalAudioSelected: Boolean = false
        private set

    var isExternalSubtitleEnabled: Boolean = false
        private set

    private val dataSourceFactory = DefaultDataSource.Factory(context)

    fun setVideoSource(uri: Uri) {
        this.videoUri = uri
        rebuildAndApplyMediaSource()
    }

    fun setExternalAudioSource(uri: Uri?, label: String? = "External Audio") {
        this.externalAudioUri = uri
        this.externalAudioName = label
        this.isExternalAudioSelected = (uri != null)
        rebuildAndApplyMediaSource()
    }

    fun setExternalAudioSelected(selected: Boolean) {
        if (this.externalAudioUri == null) {
            isExternalAudioSelected = false
        } else {
            isExternalAudioSelected = selected
        }
        updateTrackSelection()
    }

    fun setExternalSubtitleSource(uri: Uri?, mimeType: String? = null, label: String? = "External Subtitle") {
        this.externalSubtitleUri = uri
        this.externalSubtitleMimeType = mimeType ?: inferSubtitleMimeType(uri)
        this.externalSubtitleName = label
        this.isExternalSubtitleEnabled = (uri != null)
        rebuildAndApplyMediaSource()
    }

    fun setExternalSubtitleEnabled(enabled: Boolean) {
        if (externalSubtitleUri == null) {
            isExternalSubtitleEnabled = false
        } else {
            isExternalSubtitleEnabled = enabled
        }
        updateTrackSelection()
    }

    fun setAudioOffset(offsetMs: Long) {
        val clampedOffset = offsetMs.coerceIn(-5000L, 5000L)
        this.audioOffsetMs = clampedOffset
        applyOffsetsToPlayer()
    }

    fun setSubtitleOffset(offsetMs: Long) {
        val clampedOffset = offsetMs.coerceIn(-5000L, 5000L)
        if (this.subtitleOffsetMs != clampedOffset) {
            this.subtitleOffsetMs = clampedOffset
            rebuildAndApplyMediaSource()
        }
    }

    fun rebuildAndApplyMediaSource() {
        val currentVideoUri = videoUri ?: return

        val wasPlaying = player.isPlaying
        val currentPos = player.currentPosition

        val mediaSources = mutableListOf<MediaSource>()

        // Main Video Source
        val videoMediaItem = MediaItem.fromUri(currentVideoUri)
        val videoMediaSource = DefaultMediaSourceFactory(dataSourceFactory)
            .createMediaSource(videoMediaItem)
        mediaSources.add(videoMediaSource)

        // External Audio Source (if provided)
        val extAudioUri = externalAudioUri
        if (extAudioUri != null) {
            val audioMediaItem = MediaItem.Builder()
                .setUri(extAudioUri)
                .setMediaId("EXTERNAL_AUDIO")
                .build()
            val audioMediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(audioMediaItem)
            mediaSources.add(audioMediaSource)
        }

        // External Subtitle Source (if provided)
        val extSubUri = externalSubtitleUri
        if (extSubUri != null) {
            val mime = externalSubtitleMimeType ?: MimeTypes.APPLICATION_SUBRIP
            val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(extSubUri)
                .setMimeType(mime)
                .setLanguage("und")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .setLabel(externalSubtitleName ?: "External Subtitle")
                .setId("EXTERNAL_SUBTITLE")
                .build()

            var subtitleMediaSource: MediaSource = SingleSampleMediaSource.Factory(dataSourceFactory)
                .createMediaSource(subtitleConfig, C.TIME_UNSET)

            if (subtitleOffsetMs < 0) {
                val startUs = (-subtitleOffsetMs) * 1000L
                subtitleMediaSource = ClippingMediaSource(subtitleMediaSource, startUs, C.TIME_UNSET)
            }

            mediaSources.add(subtitleMediaSource)
        }

        val finalSource: MediaSource = if (mediaSources.size == 1) {
            mediaSources[0]
        } else {
            MergingMediaSource(true, *mediaSources.toTypedArray())
        }

        player.setMediaSource(finalSource)
        player.prepare()
        if (currentPos > 0) {
            player.seekTo(currentPos)
        }
        player.playWhenReady = wasPlaying

        updateTrackSelection()
        applyOffsetsToPlayer()
    }

    fun updateTrackSelection() {
        val currentTracks = player.currentTracks
        if (currentTracks.groups.isEmpty()) {
            return
        }

        val builder = player.trackSelectionParameters.buildUpon()

        if (externalAudioUri != null) {
            var externalAudioGroup: TrackGroup? = null
            var embeddedAudioGroup: TrackGroup? = null

            for (group in currentTracks.groups) {
                if (group.type == C.TRACK_TYPE_AUDIO) {
                    val trackGroup = group.mediaTrackGroup
                    if (trackGroup.length > 0) {
                        val format = trackGroup.getFormat(0)
                        if (format.id == "EXTERNAL_AUDIO" || (format.label != null && format.label == externalAudioName)) {
                            externalAudioGroup = trackGroup
                        } else {
                            if (embeddedAudioGroup == null) {
                                embeddedAudioGroup = trackGroup
                            }
                        }
                    }
                }
            }

            if (isExternalAudioSelected && externalAudioGroup != null) {
                builder.setOverrideForType(
                    TrackSelectionOverride(externalAudioGroup, 0)
                )
            } else if (!isExternalAudioSelected && embeddedAudioGroup != null) {
                builder.setOverrideForType(
                    TrackSelectionOverride(embeddedAudioGroup, 0)
                )
            }
        }

        if (externalSubtitleUri != null) {
            var externalSubGroup: TrackGroup? = null

            for (group in currentTracks.groups) {
                if (group.type == C.TRACK_TYPE_TEXT) {
                    val trackGroup = group.mediaTrackGroup
                    if (trackGroup.length > 0) {
                        val format = trackGroup.getFormat(0)
                        if (format.id == "EXTERNAL_SUBTITLE" || (format.label != null && format.label == externalSubtitleName)) {
                            externalSubGroup = trackGroup
                        }
                    }
                }
            }

            if (isExternalSubtitleEnabled && externalSubGroup != null) {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                builder.setOverrideForType(
                    TrackSelectionOverride(externalSubGroup, 0)
                )
            } else if (!isExternalSubtitleEnabled) {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            }
        }

        player.trackSelectionParameters = builder.build()
    }

    private fun applyOffsetsToPlayer() {
        audioDelayProcessor?.delayMs = audioOffsetMs
    }

    companion object {
        fun inferSubtitleMimeType(uri: Uri?): String {
            if (uri == null) return MimeTypes.APPLICATION_SUBRIP
            val path = uri.path?.lowercase() ?: ""
            return when {
                path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                path.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
                else -> MimeTypes.APPLICATION_SUBRIP
            }
        }
    }
}
