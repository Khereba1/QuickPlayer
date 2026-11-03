package com.myplayer.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

const val PREFS = "player_prefs"

/** نقطة بداية التطبيق: بنشغّل فيها سجل الأخطاء. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
    }
}

/** سجل أخطاء بسيط بيتحفظ في ملف ويظهر من الإعدادات. بيسجل كمان أي كراش. */
object AppLog {
    private var file: File? = null
    private val lock = Any()
    private const val MAX_BYTES = 200_000

    fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "app.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                log("CRASH", "Uncaught exception in thread " + thread.name, error)
            } catch (e: Throwable) {
                // ignore
            }
            previous?.uncaughtException(thread, error)
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("svc_alive", false)) {
            log("SYSTEM", "the previous process was killed by the system while playback service was alive (no clean shutdown)")
            prefs.edit().putBoolean("svc_alive", false).commit()
        }
        log("APP", "Started. Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL)
    }

    fun log(tag: String, message: String, error: Throwable? = null) {
        Log.i("QuickPlayer/" + tag, message)
        val f = file ?: return
        synchronized(lock) {
            try {
                if (f.exists() && f.length() > MAX_BYTES) {
                    val text = f.readText()
                    f.writeText(text.takeLast(MAX_BYTES / 2))
                }
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val sb = StringBuilder()
                sb.append(time).append(" [").append(tag).append("] ").append(message).append('\n')
                if (error != null) sb.append(Log.getStackTraceString(error)).append('\n')
                f.appendText(sb.toString())
            } catch (e: Throwable) {
                // ignore
            }
        }
    }

    fun read(): String {
        val f = file ?: return ""
        return try {
            if (f.exists()) f.readText() else ""
        } catch (e: Throwable) {
            ""
        }
    }

    fun clear() {
        try {
            file?.writeText("")
        } catch (e: Throwable) {
            // ignore
        }
    }
}

/** تغيير لغة التطبيق من جوه التطبيق (النظام / عربي / إنجليزي). */
object LocaleHelper {
    fun wrap(base: Context): Context {
        val lang = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lang", "system") ?: "system"
        if (lang == "system") return base
        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}

/** إعدادات التطبيق، كل قيمة بتتحفظ تلقائياً وبتحدّث الواجهة. */
class AppSettings(private val prefs: SharedPreferences) {
    var language by mutableStateOf(prefs.getString("lang", "system") ?: "system")
        private set
    var themeMode by mutableIntStateOf(prefs.getInt("theme", 0))
        private set
    var accent by mutableIntStateOf(prefs.getInt("accent", 1))
        private set
    var amoled by mutableStateOf(prefs.getBoolean("amoled", false))
        private set
    var resume by mutableStateOf(prefs.getBoolean("resume", true))
        private set
    var videoBackground by mutableStateOf(prefs.getBoolean("video_bg", true))
        private set
    var autoPip by mutableStateOf(prefs.getBoolean("auto_pip", true))
        private set
    var hideRecordings by mutableStateOf(prefs.getBoolean("hide_rec", true))
        private set
    var hiddenFolders by mutableStateOf<Set<String>>(HashSet(prefs.getStringSet("hidden", emptySet()) ?: emptySet()))
        private set
    var resizeMode by mutableIntStateOf(prefs.getInt("resize", 0))
        private set

    fun updateLanguage(v: String) {
        language = v
        prefs.edit().putString("lang", v).apply()
    }

    fun updateThemeMode(v: Int) {
        themeMode = v
        prefs.edit().putInt("theme", v).apply()
    }

    fun updateAccent(v: Int) {
        accent = v
        prefs.edit().putInt("accent", v).apply()
    }

    fun updateAmoled(v: Boolean) {
        amoled = v
        prefs.edit().putBoolean("amoled", v).apply()
    }

    fun updateResume(v: Boolean) {
        resume = v
        prefs.edit().putBoolean("resume", v).apply()
    }

    fun updateVideoBackground(v: Boolean) {
        videoBackground = v
        prefs.edit().putBoolean("video_bg", v).apply()
    }

    fun updateAutoPip(v: Boolean) {
        autoPip = v
        prefs.edit().putBoolean("auto_pip", v).apply()
    }

    fun updateHideRecordings(v: Boolean) {
        hideRecordings = v
        prefs.edit().putBoolean("hide_rec", v).apply()
    }

    fun toggleFolderHidden(name: String) {
        val updated: Set<String> = if (hiddenFolders.contains(name)) hiddenFolders - name else hiddenFolders + name
        hiddenFolders = updated
        prefs.edit().putStringSet("hidden", HashSet(updated)).apply()
    }

    fun updateResizeMode(v: Int) {
        resizeMode = v
        prefs.edit().putInt("resize", v).apply()
    }
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun queryDisplayName(context: Context, uri: Uri): String {
    var name: String? = null
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0)
        }
    } catch (e: Exception) {
        AppLog.log("URI", "display name failed for $uri", e)
    }
    return name ?: uri.lastPathSegment ?: "file"
}

/** تحميل صور مصغرة (غلاف الألبوم / لقطة من الفيديو) مع كاش في الذاكرة. */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val failed: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())

    fun load(context: Context, entry: MediaEntry, w: Int, h: Int): Bitmap? {
        val k = entry.key + "@" + w + "x" + h
        val hit = cache.get(k)
        if (hit != null) return hit
        if (failed.contains(k)) return null
        return try {
            val b = context.contentResolver.loadThumbnail(entry.uri, Size(w, h), null)
            cache.put(k, b)
            b
        } catch (e: Exception) {
            failed.add(k)
            null
        }
    }

    fun decodeBytes(data: ByteArray?, maxSize: Int): Bitmap? {
        if (data == null || data.isEmpty()) return null
        return try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxSize * 2 || bounds.outHeight / sample > maxSize * 2) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        } catch (e: Throwable) {
            null
        }
    }
}
