package com.myplayer.app

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
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
import kotlin.math.PI
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
    preview: (suspend (Long, Boolean) -> Bitmap?)? = null,
    previewAspect: Float = 16f / 9f,
    onDragChange: (Boolean) -> Unit = {},
    onScrub: ((Long) -> Unit)? = null,
    clean: Boolean = false,
    playing: Boolean = false,
    speed: Float = 1f,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val frac = if (duration > 0L) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(modifier = modifier) {
            val changeFn: (Float) -> Unit = {
                if (!dragging) onDragChange(true)
                dragging = true
                dragValue = it
                if (duration > 0L) onScrub?.invoke((it * duration).toLong())
            }
            val finishFn: () -> Unit = {
                onSeek((dragValue * duration).toLong())
                dragging = false
                onDragChange(false)
            }
            if (clean) {
                ThinSlider(
                    positionMs = position,
                    durationMs = duration,
                    speed = speed,
                    drag = if (dragging) dragValue else null,
                    enabled = duration > 0L,
                    playing = playing && !dragging,
                    onChange = changeFn,
                    onFinished = finishFn,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Slider(
                    value = if (dragging) dragValue else frac,
                    onValueChange = changeFn,
                    onValueChangeFinished = finishFn,
                    enabled = duration > 0L,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (dragging && duration > 0L) {
                val target = (dragValue * duration).toLong()
                val bucket = target / 500L
                val frame by produceState<Bitmap?>(initialValue = null, bucket) {
                    val fn = preview
                    if (fn != null) {
                        // أولاً لقطة سريعة (أقرب إطار مفتاحي)، ولو وقفت عن الحركة لحظة بتتبدّل بالإطار الدقيق
                        delay(60L)
                        value = fn(bucket * 500L, false)
                        delay(250L)
                        value = fn(target, true)
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
                            val inset = (if (clean) 4 else 10).dp.roundToPx()
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

/** شريط تقدم للوضع النظيف: الجزء المتشغّل موجة بيضا بتتحرك (زي تليجرام)، والباقي خط رفيع، والمقبض شرطة رأسية. */
@Composable
private fun ThinSlider(
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    drag: Float?,
    enabled: Boolean,
    playing: Boolean,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val amp by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(400),
        label = "waveAmp",
    )
    var phase by remember { mutableFloatStateOf(0f) }
    // الموضع المعروض بيتحرك كل فريم (60/90 مرة في الثانية) بدل ما يستنى تحديث المشغّل المتقطع
    var shownMs by remember { mutableFloatStateOf(positionMs.toFloat()) }
    val syncPos = remember { longArrayOf(positionMs) }
    val syncAt = remember { longArrayOf(System.nanoTime()) }
    androidx.compose.runtime.LaunchedEffect(positionMs) {
        syncPos[0] = positionMs
        syncAt[0] = System.nanoTime()
        if (!playing || kotlin.math.abs(shownMs - positionMs) > 700f) shownMs = positionMs.toFloat()
    }
    androidx.compose.runtime.LaunchedEffect(playing, speed, durationMs) {
        if (!playing) {
            shownMs = syncPos[0].toFloat()
        } else {
            var last = 0L
            while (true) {
                androidx.compose.runtime.withFrameNanos { t ->
                    if (last != 0L) phase = (phase + (t - last) / 1_000_000_000f * 5f) % (2f * PI.toFloat())
                    last = t
                    val est = syncPos[0] + (System.nanoTime() - syncAt[0]) / 1_000_000f * speed
                    val capped = if (durationMs > 0L) minOf(est, durationMs.toFloat()) else est
                    if (capped > shownMs) shownMs = capped
                }
            }
        }
    }
    val v = (drag ?: if (durationMs > 0L) shownMs / durationMs.toFloat() else 0f).coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(40.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = { offset ->
                    if (size.width > 0) {
                        onChange((offset.x / size.width).coerceIn(0f, 1f))
                        onFinished()
                    }
                })
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (size.width > 0) onChange((offset.x / size.width).coerceIn(0f, 1f))
                    },
                    onDragEnd = { onFinished() },
                    onDragCancel = { onFinished() },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        if (size.width > 0) onChange((change.position.x / size.width).coerceIn(0f, 1f))
                    },
                )
            },
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val stroke = 3.dp.toPx()
            val thumbW = 4.dp.toPx()
            val thumbH = 20.dp.toPx()
            val x = (size.width * v).coerceIn(thumbW / 2f, size.width - thumbW / 2f)
            val a = 3.dp.toPx() * amp
            val wl = 22.dp.toPx()
            // الباقي: خط رفيع شفاف
            drawLine(
                color = Color(0x59FFFFFF),
                start = androidx.compose.ui.geometry.Offset(x, cy),
                end = androidx.compose.ui.geometry.Offset(size.width, cy),
                strokeWidth = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            // المتشغّل: موجة
            val path = androidx.compose.ui.graphics.Path()
            var xx = 0f
            path.moveTo(0f, cy + a * kotlin.math.sin(-phase))
            while (xx < x) {
                xx = minOf(xx + 3f, x)
                path.lineTo(xx, cy + a * kotlin.math.sin(2f * PI.toFloat() * xx / wl - phase))
            }
            drawPath(
                path = path,
                color = Color.White,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
            )
            // المقبض: شرطة رأسية
            drawRoundRect(
                color = Color.White,
                topLeft = androidx.compose.ui.geometry.Offset(x - thumbW / 2f, cy - thumbH / 2f),
                size = androidx.compose.ui.geometry.Size(thumbW, thumbH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(thumbW / 2f),
            )
        }
    }
}

/** تلميح بسيط وبحركة، بيظهر مرة واحدة أول ما يتفتح فيديو والوضع النظيف مفعّل. */
@Composable
fun CleanHint(visible: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it / 2 },
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { -it / 2 },
    ) {
        val accent = MaterialTheme.colorScheme.primary
        Surface(
            modifier = Modifier.padding(horizontal = 24.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xE6111111),
            contentColor = Color.White,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "hint")
                val p by t.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                    ),
                    label = "hintp",
                )
                androidx.compose.foundation.Canvas(modifier = Modifier.width(150.dp).height(44.dp)) {
                    val cy = size.height / 2f
                    val r = 11.dp.toPx()
                    val track = size.width - 2 * r - 22.dp.toPx()
                    val x = r + 11.dp.toPx() + track * p
                    drawLine(
                        color = Color(0x33FFFFFF),
                        start = androidx.compose.ui.geometry.Offset(r, cy),
                        end = androidx.compose.ui.geometry.Offset(size.width - r, cy),
                        strokeWidth = 2.dp.toPx(),
                    )
                    drawCircle(color = accent.copy(alpha = 0.35f), radius = r * 1.7f, center = androidx.compose.ui.geometry.Offset(x, cy))
                    drawCircle(color = accent, radius = r, center = androidx.compose.ui.geometry.Offset(x, cy - 7.dp.toPx()))
                    drawCircle(color = Color.White, radius = r, center = androidx.compose.ui.geometry.Offset(x + 12.dp.toPx(), cy + 7.dp.toPx()))
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.clean_hint_title), style = MaterialTheme.typography.titleSmall, color = accent)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.clean_hint_swipe), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.clean_hint_tap), style = MaterialTheme.typography.bodySmall, color = Color(0xB3FFFFFF))
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.clean_hint_ok))
                }
            }
        }
    }
}
