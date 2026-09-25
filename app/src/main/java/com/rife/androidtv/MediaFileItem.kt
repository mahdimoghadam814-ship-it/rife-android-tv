package com.rife.androidtv

import android.graphics.Bitmap
import android.net.Uri

data class MediaFileItem(
    val uri: Uri,
    val title: String,
    val sizeText: String,
    val thumbnail: Bitmap? = null
)
