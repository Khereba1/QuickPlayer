package com.myplayer.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/**
 * بيستقبل ملفات الصوت من مدير الملفات.
 * مفيش واجهة بتظهر: الشاشة شفافة وبتتقفل فوراً، والصوت بيشتغل في الخلفية من خلال الخدمة (إشعار + شاشة القفل).
 */
class ExternalAudioActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }

        startPlayback(uri)

        // أول مرة بس: نطلب إذن الإشعارات عشان أزرار التحكم تظهر في الإشعار وعلى شاشة القفل
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val needsNotifPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("notif_asked", false)

        if (needsNotifPermission) {
            prefs.edit().putBoolean("notif_asked", true).apply()
            try {
                waitingForPermission = true
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
            } catch (e: Exception) {
                AppLog.log("EXT", "notification permission request failed", e)
                finish()
            }
        } else {
            // مهلة أمان لو الاتصال بالخدمة اتأخر
            Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) finish() }, 3000L)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        waitingForPermission = false
        finish()
    }

    private fun startPlayback(uri: android.net.Uri) {
        val appContext = applicationContext
        val item = externalMediaItem(appContext, uri, false)
        try {
            val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
            val future = MediaController.Builder(appContext, token).buildAsync()
            future.addListener({
                try {
                    val c = future.get()
                    c.setMediaItem(item)
                    c.prepare()
                    c.play()
                    AppLog.log("EXT", "background audio started: " + item.mediaMetadata.title)
                } catch (e: Exception) {
                    AppLog.log("EXT", "could not start background audio", e)
                }
                // نسيب الأوامر توصل للخدمة قبل ما نقفل الاتصال
                Handler(Looper.getMainLooper()).postDelayed({ MediaController.releaseFuture(future) }, 4000L)
                if (!isFinishing && !waitingForPermission) finish()
            }, ContextCompat.getMainExecutor(appContext))
        } catch (e: Exception) {
            AppLog.log("EXT", "connect failed", e)
            finish()
        }
    }

    private var waitingForPermission = false
}
