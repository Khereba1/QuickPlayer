package com.myplayer.app

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val vm: PlayerViewModel by viewModels()

    private var hasPermission by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermission()
        }

    private var pipReceiverRegistered = false

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PIP_PLAY -> vm.togglePlay()
                ACTION_PIP_AUDIO -> {
                    // نحوّل لصوت فقط ونقفل النافذة العائمة، والصوت يكمّل في الخلفية
                    vm.switchToAudioOnly(fromPip = true)
                    finishAndRemoveTask()
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasPermission = mediaPermissionGranted()

        setContent {
            PlayerApp(
                vm = vm,
                hasPermission = hasPermission,
                onRequestPermission = { requestPermissionsOrOpenSettings() },
                onEnterPip = { enterPip() },
                onLanguageChanged = { recreate() },
            )
        }

        // تحديث زر التشغيل/الإيقاف في النافذة العائمة
        lifecycleScope.launch {
            snapshotFlow { vm.isPlaying }.collect {
                if (vm.inPip) {
                    try {
                        setPictureInPictureParams(buildPipParams())
                    } catch (e: Exception) {
                        AppLog.log("PIP", "update params failed", e)
                    }
                }
            }
        }

        if (savedInstanceState == null) {
            val missing = wantedPermissions().filter { !isGranted(it) }
            if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
            handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        vm.onAppForegrounded()
    }

    override fun onResume() {
        super.onResume()
        refreshPermission()
    }

    override fun onStop() {
        super.onStop()
        if (vm.inPip) vm.onPipClosed() else vm.onAppBackgrounded()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val playingVideo = vm.showPlayer && vm.currentItem?.isVideoItem() == true && vm.isPlaying
        if (playingVideo && vm.settings.autoPip) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) registerPipReceiver() else unregisterPipReceiver()
    }

    override fun onDestroy() {
        unregisterPipReceiver()
        super.onDestroy()
    }

    private fun registerPipReceiver() {
        if (pipReceiverRegistered) return
        val filter = IntentFilter()
        filter.addAction(ACTION_PIP_PLAY)
        filter.addAction(ACTION_PIP_AUDIO)
        ContextCompat.registerReceiver(this, pipReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        pipReceiverRegistered = true
    }

    private fun unregisterPipReceiver() {
        if (!pipReceiverRegistered) return
        try {
            unregisterReceiver(pipReceiver)
        } catch (e: Exception) {
            // ignore
        }
        pipReceiverRegistered = false
    }

    private fun pipAction(code: Int, action: String, icon: Int, label: String): RemoteAction {
        val pending = PendingIntent.getBroadcast(
            this,
            code,
            Intent(action).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return RemoteAction(Icon.createWithResource(this, icon), label, label, pending)
    }

    private fun buildPipParams(): PictureInPictureParams {
        var w = vm.videoWidth
        var h = vm.videoHeight
        if (w <= 0 || h <= 0) {
            w = 16
            h = 9
        }
        val ratio = (w.toFloat() / h.toFloat()).coerceIn(0.45f, 2.3f)
        val rational = Rational((ratio * 1000).toInt(), 1000)
        val playLabel = getString(if (vm.isPlaying) R.string.pause else R.string.play)
        val actions = listOf(
            pipAction(1, ACTION_PIP_PLAY, if (vm.isPlaying) R.drawable.ic_pause else R.drawable.ic_play, playLabel),
            pipAction(2, ACTION_PIP_AUDIO, R.drawable.ic_headphones, getString(R.string.audio_only)),
        )
        return PictureInPictureParams.Builder().setAspectRatio(rational).setActions(actions).build()
    }

    private fun enterPip() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            vm.message = UiMessage(R.string.msg_pip_unsupported)
            return
        }
        try {
            enterPictureInPictureMode(buildPipParams())
        } catch (e: Exception) {
            AppLog.log("PIP", "enter PiP failed", e)
            vm.message = UiMessage(R.string.msg_pip_unsupported)
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val mime = intent.type ?: contentResolver.getType(uri) ?: ""
        vm.playExternal(uri, !mime.startsWith("audio"))
    }

    private fun wantedPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun mediaPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            isGranted(Manifest.permission.READ_MEDIA_AUDIO) && isGranted(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun requestPermissionsOrOpenSettings() {
        val missing = wantedPermissions().filter { !isGranted(it) }
        val canAsk = missing.any { shouldShowRequestPermissionRationale(it) } ||
            !getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("perm_asked", false)
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("perm_asked", true).apply()
        if (missing.isNotEmpty() && canAsk) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            startActivity(i)
        }
    }

    private fun refreshPermission() {
        hasPermission = mediaPermissionGranted()
        if (hasPermission && !vm.libraryLoaded) vm.loadLibrary()
    }

    private fun isGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}

const val ACTION_PIP_PLAY = "com.khereba1.quickplayer.PIP_PLAY"
const val ACTION_PIP_AUDIO = "com.khereba1.quickplayer.PIP_AUDIO"

@Composable
fun PlayerApp(
    vm: PlayerViewModel,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onEnterPip: () -> Unit,
    onLanguageChanged: () -> Unit,
) {
    val system = isSystemInDarkTheme()
    val dark = when (vm.settings.themeMode) {
        1 -> false
        2 -> true
        else -> system
    }
    AppTheme(dark = dark, accent = vm.settings.accent, amoled = vm.settings.amoled) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val ctx = LocalContext.current
            val snackbar = remember { SnackbarHostState() }
            val item = vm.currentItem
            val isVideo = item?.isVideoItem() == true

            LaunchedEffect(vm.message?.id) {
                val m = vm.message
                if (m != null) {
                    snackbar.showSnackbar(ctx.getString(m.resId, m.arg ?: ""))
                    vm.message = null
                }
            }

            BackHandler(enabled = vm.showPlayer && !vm.inPip) { vm.closePlayer() }

            Box(modifier = Modifier.fillMaxSize()) {
                if (!vm.inPip) {
                    LibraryScreen(
                        vm = vm,
                        hasPermission = hasPermission,
                        onRequestPermission = onRequestPermission,
                        onLanguageChanged = onLanguageChanged,
                    )
                }

                AnimatedVisibility(
                    visible = vm.showPlayer && !isVideo && item != null && !vm.inPip,
                    enter = slideInVertically(initialOffsetY = { it }),
                    exit = slideOutVertically(targetOffsetY = { it }),
                ) {
                    NowPlayingScreen(vm = vm)
                }

                if ((vm.showPlayer && isVideo) || (vm.inPip && isVideo)) {
                    VideoPlayerScreen(vm = vm, onEnterPip = onEnterPip)
                }

                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 88.dp, start = 16.dp, end = 16.dp)
                        .fillMaxWidth(),
                )
            }
        }
    }
}
