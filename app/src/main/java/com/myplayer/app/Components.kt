package com.myplayer.app

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun Ico(
    @DrawableRes id: Int,
    desc: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(painter = painterResource(id), contentDescription = desc, modifier = modifier, tint = tint)
}

/** صورة مصغرة للملف (غلاف الألبوم أو لقطة من الفيديو) وإلا أيقونة افتراضية. */
@Composable
fun MediaThumb(
    entry: MediaEntry?,
    isVideo: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    px: Int = 160,
) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(initialValue = null, entry?.key, px) {
        value = if (entry == null) null else withContext(Dispatchers.IO) { Thumbs.load(ctx, entry, px, px) }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Ico(
                id = if (isVideo) R.drawable.ic_video else R.drawable.ic_music,
                desc = null,
                modifier = Modifier.size(size * 0.45f),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * شريط التقدم: دايماً من الشمال لليمين حتى في العربي، لأن الوقت بيمشي في اتجاه واحد.
 * أثناء السحب بتظهر فقاعة فيها الوقت اللي هتوصله، ولو فيه [preview] بتظهر معاها لقطة مصغرة من الفيديو.
 */
@Composable
fun SeekBar(
    position: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    preview: (suspend (Long) -> Bitmap?)? = null,
    previewAspect: Float = 16f / 9f,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val frac = if (duration > 0L) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(modifier = modifier) {
            Slider(
                value = if (dragging) dragValue else frac,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    onSeek((dragValue * duration).toLong())
                    dragging = false
                },
                enabled = duration > 0L,
                modifier = Modifier.fillMaxWidth(),
            )
            if (dragging && duration > 0L) {
                val target = (dragValue * duration).toLong()
                val bucket = target / 2000L
                val frame by produceState<Bitmap?>(initialValue = null, bucket) {
                    val fn = preview
                    if (fn != null) {
                        delay(120L)
                        value = fn(bucket * 2000L)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xE6000000),
                    contentColor = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .layout { measurable, constraints ->
                            val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                            val inset = 10.dp.roundToPx()
                            val gap = 14.dp.roundToPx()
                            val center = inset + (dragValue * (constraints.maxWidth - 2 * inset)).roundToInt()
                            val x = (center - p.width / 2).coerceIn(0, max(0, constraints.maxWidth - p.width))
                            layout(constraints.maxWidth, 0) { p.place(x, -p.height - gap) }
                        },
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val b = frame
                        if (preview != null && b != null) {
                            Image(
                                bitmap = b.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(150.dp)
                                    .height((150f / previewAspect).dp)
                                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)),
                            )
                        }
                        Text(
                            formatTime(target),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** المشغل المصغّر أسفل الشاشة. */
@Composable
fun MiniPlayer(vm: PlayerViewModel, modifier: Modifier = Modifier) {
    val item = vm.currentItem ?: return
    val entry = vm.entryFor(item.mediaId)
    val isVideo = item.isVideoItem()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .clickable { vm.showPlayer = true }
                    .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaThumb(entry = entry, isVideo = isVideo, size = 46.dp, corner = 14.dp, px = 120)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.mediaMetadata.title?.toString() ?: "",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sub = item.mediaMetadata.artist?.toString() ?: ""
                    if (sub.isNotBlank()) {
                        Text(
                            text = sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = { vm.togglePlay() }) {
                    Ico(
                        id = if (vm.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                        desc = stringResource(if (vm.isPlaying) R.string.pause else R.string.play),
                        modifier = Modifier.size(30.dp),
                    )
                }
                IconButton(onClick = { vm.next() }) {
                    Ico(R.drawable.ic_next, stringResource(R.string.next), Modifier.size(26.dp))
                }
            }
            val frac = if (vm.durationMs > 0L) (vm.positionMs.toFloat() / vm.durationMs.toFloat()).coerceIn(0f, 1f) else 0f
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    trackColor = Color.Transparent,
                )
            }
        }
    }
}

/** دائرة ملونة بتستخدم كزر رئيسي (تشغيل/إيقاف). */
@Composable
fun BigPlayButton(playing: Boolean, size: Dp, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Ico(
                id = if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                desc = stringResource(if (playing) R.string.pause else R.string.play),
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}
