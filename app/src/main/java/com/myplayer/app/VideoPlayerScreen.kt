package com.myplayer.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private data class Indicator(val iconRes: Int?, val text: String, val id: Long = System.nanoTime())

/** مشغل فيديو نضيف زي VLC: الفيديو بس، والتحكم بيظهر عند اللمس. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(vm: PlayerViewModel, onEnterPip: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
    val item = vm.currentItem
    val pip = vm.inPip

    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var indicator by remember { mutableStateOf<Indicator?>(null) }
    var showTracks by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    val anyDialog = showTracks || showSpeed || showSleep

    // تراكم الضغطات المتتالية (يمين/شمال): بنعرض المجموع الفعلي اللي اتحركه الفيديو
    var seriesBase by remember { mutableLongStateOf(-1L) }
    val skip: (Long) -> Unit = { delta ->
        if (seriesBase < 0L) seriesBase = vm.positionMs
        vm.seekBy(delta)
        val moved = vm.positionMs - seriesBase
        val sign = if (moved >= 0L) "+" else "-"
        val secs = abs(moved) / 1000L
        val text = if (secs < 60L) sign + secs + "s" else sign + formatTime(abs(moved))
        indicator = Indicator(if (moved >= 0L) R.drawable.ic_forward else R.drawable.ic_rewind, text)
    }

    // معاينة الإطار أثناء سحب شريط التقدم
    val previewH = 100
    val aspect = if (vm.videoWidth > 0 && vm.videoHeight > 0) {
        (vm.videoWidth.toFloat() / vm.videoHeight.toFloat()).coerceIn(0.6f, 2.4f)
    } else {
        16f / 9f
    }

    val audioManager = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var brightness by remember {
        val current = activity?.window?.attributes?.screenBrightness ?: -1f
        val value = if (current >= 0f) {
            current
        } else {
            try {
                Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
            } catch (e: Exception) {
                0.5f
            }
        }
        mutableFloatStateOf(value)
    }

    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.addSubtitle(uri)
    }

    // وضع ملء الشاشة + منع انطفاء الشاشة + استعادة كل حاجة عند الخروج
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller: WindowInsetsControllerCompat? = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        if (window != null) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (window != null) {
                val attrs = window.attributes
                attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = attrs
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            FramePreview.release()
        }
    }

    // الفيديو العريض يفتح أفقي تلقائياً
    LaunchedEffect(item?.mediaId, vm.videoWidth, vm.videoHeight) {
        if (vm.videoWidth > 0 && vm.videoHeight > 0 && !vm.inPip) {
            activity?.requestedOrientation = if (vm.videoWidth > vm.videoHeight) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    LaunchedEffect(controlsVisible, vm.isPlaying, tick, locked, anyDialog) {
        if (controlsVisible && vm.isPlaying && !anyDialog) {
            delay(3500L)
            controlsVisible = false
        }
    }

    LaunchedEffect(indicator?.id) {
        if (indicator != null) {
            delay(900L)
            indicator = null
            seriesBase = -1L
        }
    }

    LaunchedEffect(item?.mediaId) { vm.setVideoEnabled(true) }

    BackHandler(enabled = locked && !pip) { locked = false }

    val resize = when (vm.settings.resizeMode) {
        1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    setKeepContentOnPlayerReset(true)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
                if (view.player !== vm.controller) view.player = vm.controller
                if (view.resizeMode != resize) view.resizeMode = resize
            },
            onRelease = { view -> view.player = null },
        )

        // طبقة اللمس
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(locked, pip) {
                    if (pip) return@pointerInput
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                            tick++
                        },
                        onDoubleTap = { offset ->
                            if (!locked) {
                                val w = size.width
                                if (offset.x < w * 0.33f) {
                                    skip(-10_000L)
                                } else if (offset.x > w * 0.67f) {
                                    skip(10_000L)
                                } else {
                                    vm.togglePlay()
                                }
                            }
                        },
                    )
                }
                .pointerInput(locked, pip) {
                    if (locked || pip) return@pointerInput
                    var mode = 0
                    var startPos = 0L
                    var startVol = 0
                    var startBr = 0f
                    var startX = 0f
                    var accX = 0f
                    var accY = 0f
                    var seekTarget = 0L
                    detectDragGestures(
                        onDragStart = { offset ->
                            mode = 0
                            accX = 0f
                            accY = 0f
                            startX = offset.x
                            startPos = vm.positionMs
                            seekTarget = startPos
                            startBr = brightness
                            startVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        },
                        onDragEnd = {
                            if (mode == 1) vm.seekTo(seekTarget)
                            mode = 0
                        },
                        onDragCancel = { mode = 0 },
                        onDrag = { change, drag ->
                            change.consume()
                            accX += drag.x
                            accY += drag.y
                            if (mode == 0 && (abs(accX) > 28f || abs(accY) > 28f)) {
                                mode = if (abs(accX) > abs(accY)) {
                                    1
                                } else if (startX < size.width / 2f) {
                                    2
                                } else {
                                    3
                                }
                            }
                            if (mode == 1) {
                                val dur = vm.durationMs
                                val delta = (accX / size.width * 120_000f).toLong()
                                seekTarget = (startPos + delta).coerceIn(0L, if (dur > 0L) dur else Long.MAX_VALUE)
                                val sign = if (delta >= 0) "+" else "-"
                                indicator = Indicator(
                                    null,
                                    sign + formatTime(abs(delta)) + "  (" + formatTime(seekTarget) + ")",
                                )
                            } else if (mode == 2) {
                                val value = (startBr - accY / size.height * 1.3f).coerceIn(0.02f, 1f)
                                brightness = value
                                val window = activity?.window
                                if (window != null) {
                                    val attrs = window.attributes
                                    attrs.screenBrightness = value
                                    window.attributes = attrs
                                }
                                indicator = Indicator(R.drawable.ic_brightness, (value * 100).roundToInt().toString() + "%")
                            } else if (mode == 3) {
                                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                val v = (startVol - accY / size.height * max * 1.4f).roundToInt().coerceIn(0, max)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                                indicator = Indicator(R.drawable.ic_volume, (v * 100f / max).roundToInt().toString() + "%")
                            }
                        },
                    )
                },
        )

        if (!pip) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                // مؤشر الإيماءات في المنتصف
                val ind = indicator
                if (ind != null) {
                    Surface(
                        modifier = Modifier.align(Alignment.Center),
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xB3000000),
                        contentColor = Color.White,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (ind.iconRes != null) Ico(ind.iconRes, null, Modifier.size(26.dp))
                            Text(ind.text, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                        }
                    }
                }

                // وضع القفل: زر فتح القفل بس
                AnimatedVisibility(
                    visible = locked && controlsVisible,
                    modifier = Modifier.align(Alignment.CenterStart),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Surface(
                        modifier = Modifier.padding(24.dp),
                        shape = RoundedCornerShape(50),
                        color = Color(0xB3000000),
                        contentColor = Color.White,
                    ) {
                        IconButton(onClick = { locked = false }) {
                            Ico(R.drawable.ic_lock, stringResource(R.string.unlock))
                        }
                    }
                }

                AnimatedVisibility(
                    visible = controlsVisible && !locked,
                    modifier = Modifier.fillMaxSize(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // الشريط العلوي
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { vm.closePlayer() }) {
                                Ico(R.drawable.ic_back, stringResource(R.string.back))
                            }
                            Text(
                                text = item?.mediaMetadata?.title?.toString() ?: "",
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                            )
                            IconButton(onClick = { showTracks = true }) {
                                Ico(R.drawable.ic_subtitles, stringResource(R.string.tracks_title))
                            }
                            IconButton(onClick = { vm.switchToAudioOnly(false) }) {
                                Ico(R.drawable.ic_headphones, stringResource(R.string.audio_only))
                            }
                            IconButton(onClick = onEnterPip) {
                                Ico(R.drawable.ic_pip, stringResource(R.string.pip))
                            }
                        }

                        // الشريط السفلي
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(formatTime(vm.positionMs), style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                                SeekBar(
                                    vm.positionMs,
                                    vm.durationMs,
                                    onSeek = {
                                        vm.seekTo(it)
                                        tick++
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp),
                                    preview = { t ->
                                        val cur = vm.currentItem
                                        if (cur == null) {
                                            null
                                        } else {
                                            withContext(Dispatchers.IO) {
                                                FramePreview.frame(ctx, cur, t, (previewH * aspect).roundToInt(), previewH)
                                            }
                                        }
                                    },
                                    previewAspect = aspect,
                                )
                                Text(formatTime(vm.durationMs), style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                            }
                            // الصف الأول: القفل والسرعة على الشمال، والأدوات على اليمين (بيتسع في الطولي والعرضي)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = {
                                    locked = true
                                    controlsVisible = false
                                }) { Ico(R.drawable.ic_lock_open, stringResource(R.string.lock)) }
                                TextButton(onClick = { showSpeed = true }) {
                                    Text(speedLabel(vm.speed), color = Color.White)
                                }
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = {
                                    vm.settings.updateResizeMode((vm.settings.resizeMode + 1) % 3)
                                    val label = when (vm.settings.resizeMode) {
                                        1 -> R.string.aspect_fill
                                        2 -> R.string.aspect_zoom
                                        else -> R.string.aspect_fit
                                    }
                                    indicator = Indicator(R.drawable.ic_aspect, ctx.getString(label))
                                }) { Ico(R.drawable.ic_aspect, stringResource(R.string.aspect)) }
                                IconButton(onClick = {
                                    val a = activity
                                    if (a != null) {
                                        val landscape = a.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                                        a.requestedOrientation = if (landscape) {
                                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                        } else {
                                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                        }
                                    }
                                }) { Ico(R.drawable.ic_rotate, stringResource(R.string.rotate)) }
                                IconButton(onClick = { showSleep = true }) {
                                    Ico(R.drawable.ic_timer, stringResource(R.string.sleep_title))
                                }
                            }
                            // الصف التاني: أزرار التشغيل في النص
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = {
                                    skip(-10_000L)
                                    tick++
                                }) { Ico(R.drawable.ic_rewind, stringResource(R.string.seek_back)) }
                                IconButton(onClick = { vm.previous() }) {
                                    Ico(R.drawable.ic_prev, stringResource(R.string.previous))
                                }
                                BigPlayButton(playing = vm.isPlaying, size = 60.dp, onClick = {
                                    vm.togglePlay()
                                    tick++
                                })
                                IconButton(onClick = { vm.next() }) {
                                    Ico(R.drawable.ic_next, stringResource(R.string.next))
                                }
                                IconButton(onClick = {
                                    skip(10_000L)
                                    tick++
                                }) { Ico(R.drawable.ic_forward, stringResource(R.string.seek_forward)) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTracks) TracksDialog(vm, onAddSubtitle = { subtitlePicker.launch(arrayOf("*/*")) }) { showTracks = false }
    if (showSpeed) SpeedDialog(vm.speed, { vm.changeSpeed(it) }) { showSpeed = false }
    if (showSleep) SleepDialog(vm) { showSleep = false }
}
