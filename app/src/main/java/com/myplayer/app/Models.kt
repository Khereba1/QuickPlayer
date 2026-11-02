package com.myplayer.app

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.util.Locale

data class MediaEntry(
    val key: String,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val folder: String,
    val durationMs: Long,
    val isVideo: Boolean,
    val dateAdded: Long,
) {
    fun toMediaItem(): MediaItem {
        val extras = Bundle()
        extras.putBoolean(EXTRA_VIDEO, isVideo)
        val shownArtist: String? = if (artist.isNotBlank()) artist else if (folder.isNotBlank()) folder else null
        val shownAlbum: String? = if (album.isNotBlank()) album else null
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(shownArtist)
            .setAlbumTitle(shownAlbum)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(key)
            .setUri(uri)
            .setMediaMetadata(meta)
            .build()
    }

    companion object {
        const val EXTRA_VIDEO = "is_video"
    }
}

fun MediaItem.isVideoItem(): Boolean {
    return mediaMetadata.extras?.getBoolean(MediaEntry.EXTRA_VIDEO) == true
}

data class UiMessage(
    val resId: Int,
    val arg: String? = null,
    val id: Long = System.nanoTime(),
)

fun isSystemRecordingFolder(name: String): Boolean {
    val n = name.lowercase(Locale.ROOT)
    return n.contains("call record") ||
        n.contains("callrecord") ||
        n.contains("voice note") ||
        n.contains("whatsapp audio") ||
        n.contains("whatsapp voice")
}

fun formatTime(ms: Long): String {
    val total = maxOf(ms, 0L) / 1000L
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

fun speedLabel(speed: Float): String {
    return String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.') + "x"
}

fun freqLabel(hz: Int): String {
    return if (hz >= 1000) {
        String.format(Locale.US, "%.1f kHz", hz / 1000f).replace(".0 ", " ")
    } else {
        "$hz Hz"
    }
}
