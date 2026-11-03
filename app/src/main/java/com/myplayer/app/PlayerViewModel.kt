package com.myplayer.app

import android.app.Application
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class TrackOption(
    val group: Tracks.Group,
    val trackIndex: Int,
    val label: String,
    val selected: Boolean,
)

/** بيبني عنصر تشغيل لملف جاي من برنامج تاني (مدير الملفات). بنحتفظ بمقبض الملف عشان الإذن المؤقت. */
fun externalMediaItem(context: Context, uri: Uri, isVideo: Boolean): MediaItem {
    val name = queryDisplayName(context, uri)
    var playUri = uri
    try {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
        if (pfd != null) playUri = FdRegistry.register(pfd, name.replace('/', '_'))
    } catch (e: Exception) {
        AppLog.log("EXT", "could not open fd for $uri, using uri directly", e)
    }
    val title = name.substringBeforeLast('.', name)
    val extras = android.os.Bundle()
    extras.putBoolean(MediaEntry.EXTRA_VIDEO, isVideo)
    val meta = androidx.media3.common.MediaMetadata.Builder()
        .setTitle(title)
        .setExtras(extras)
        .build()
    return MediaItem.Builder()
        .setMediaId("ext:" + uri.toString())
        .setUri(playUri)
        .setMediaMetadata(meta)
        .build()
}

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Application = app
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val settings = AppSettings(prefs)

    // ---------- المكتبة ----------
    var audio by mutableStateOf<List<MediaEntry>>(emptyList())
        private set
    var videos by mutableStateOf<List<MediaEntry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var libraryLoaded by mutableStateOf(false)
        private set

    private var allAudio: List<MediaEntry> = emptyList()
    private var allVideos: List<MediaEntry> = emptyList()
    private var byKey: Map<String, MediaEntry> = emptyMap()

    // ---------- قوائم المستخدم ----------
    var playlists by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set
    var favorites by mutableStateOf<Set<String>>(emptySet())
        private set
    var recent by mutableStateOf<List<String>>(emptyList())
        private set

    // ---------- حالة الواجهة ----------
    var showPlayer by mutableStateOf(false)
    var inPip by mutableStateOf(false)
    var message by mutableStateOf<UiMessage?>(null)

    // ---------- حالة المشغل ----------
    var controller by mutableStateOf<MediaController?>(null)
        private set
    var currentItem by mutableStateOf<MediaItem?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var shuffleOn by mutableStateOf(false)
        private set
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var sleepLeft by mutableLongStateOf(0L)
        private set
    var sleepEndOfTrack by mutableStateOf(false)
        private set
    var queue by mutableStateOf<List<MediaItem>>(emptyList())
        private set
    var queueIndex by mutableIntStateOf(0)
        private set
    var tracks by mutableStateOf(Tracks.EMPTY)
        private set
    var videoWidth by mutableIntStateOf(0)
        private set
    var videoHeight by mutableIntStateOf(0)
        private set

    private var future: ListenableFuture<MediaController>? = null
    private val pending = ArrayList<(MediaController) -> Unit>()
    private var sleepJob: Job? = null
    private var errorStreak = 0
    private var lastSaveAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val reloadRunnable = Runnable { loadLibrary() }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(reloadRunnable)
            handler.postDelayed(reloadRunnable, 1500L)
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val queueChanged = events.containsAny(
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
            )
            syncState(player, queueChanged)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (sleepEndOfTrack &&
                (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
            ) {
                controller?.pause()
                sleepEndOfTrack = false
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) errorStreak = 0
            if (playbackState == Player.STATE_ENDED) sleepEndOfTrack = false
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) controller?.let { saveProgress(it, true) }
        }

        override fun onPlayerError(error: PlaybackException) {
            val title = currentItem?.mediaMetadata?.title?.toString()
            AppLog.log("PLAY", "Playback error " + error.errorCodeName + " for " + title, error)
            message = UiMessage(R.string.msg_cannot_play, title)
            errorStreak++
            val c = controller ?: return
            if (errorStreak <= 3 && c.hasNextMediaItem()) {
                c.seekToNextMediaItem()
                c.prepare()
                c.play()
            }
        }
    }

    init {
        loadUserData()
        val cr = ctx.contentResolver
        try {
            cr.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
            cr.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        } catch (e: Exception) {
            AppLog.log("LIB", "observer failed", e)
        }
        connect()
        viewModelScope.launch {
            while (true) {
                delay(400L)
                val c = controller ?: continue
                if (c.isPlaying || showPlayer) {
                    positionMs = c.currentPosition
                    val d = c.duration
                    if (d != C.TIME_UNSET) durationMs = d
                    if (c.isPlaying) saveProgress(c, false)
                }
            }
        }
    }

    private fun connect() {
        try {
            val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
            val f = MediaController.Builder(ctx, token).buildAsync()
            future = f
            f.addListener({
                try {
                    val c = f.get()
                    c.addListener(listener)
                    controller = c
                    syncState(c, true)
                    val todo = ArrayList(pending)
                    pending.clear()
                    for (job in todo) job(c)
                } catch (e: Exception) {
                    AppLog.log("PLAY", "Could not connect to playback service", e)
                }
            }, ContextCompat.getMainExecutor(ctx))
        } catch (e: Exception) {
            AppLog.log("PLAY", "connect failed", e)
        }
    }

    private fun whenReady(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else pending.add(block)
    }

    private fun syncState(p: Player, queueChanged: Boolean) {
        currentItem = p.currentMediaItem
        isPlaying = p.playWhenReady &&
            p.playbackState != Player.STATE_ENDED &&
            p.playbackState != Player.STATE_IDLE
        shuffleOn = p.shuffleModeEnabled
        repeatMode = p.repeatMode
        speed = p.playbackParameters.speed
        val d = p.duration
        durationMs = if (d == C.TIME_UNSET) 0L else d
        positionMs = p.currentPosition
        tracks = p.currentTracks
        videoWidth = p.videoSize.width
        videoHeight = p.videoSize.height
        queueIndex = p.currentMediaItemIndex
        if (queueChanged || queue.size != p.mediaItemCount) {
            queue = List(p.mediaItemCount) { p.getMediaItemAt(it) }
        }
    }

    // ---------- تحميل المكتبة ----------
    fun loadLibrary() {
        if (loading) return
        loading = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Pair(queryAudio(), queryVideo())
                } catch (e: Exception) {
                    AppLog.log("LIB", "library query failed", e)
                    null
                }
            }
            if (result != null) {
                allAudio = result.first
                allVideos = result.second
                val map = HashMap<String, MediaEntry>()
                for (e in allAudio) map[e.key] = e
                for (e in allVideos) map[e.key] = e
                byKey = map
                refilter()
                AppLog.log("LIB", "loaded " + allAudio.size + " audio, " + allVideos.size + " video")
            }
            loading = false
            libraryLoaded = true
        }
    }

    private fun cleanText(s: String?): String {
        if (s == null) return ""
        return if (s == "<unknown>") "" else s
    }

    private fun queryAudio(): List<MediaEntry> {
        val out = ArrayList<MediaEntry>()
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.BUCKET_DISPLAY_NAME,
        )
        val sel = MediaStore.Audio.Media.IS_RINGTONE + "=0 AND " +
            MediaStore.Audio.Media.IS_NOTIFICATION + "=0 AND " +
            MediaStore.Audio.Media.IS_ALARM + "=0 AND " +
            MediaStore.Audio.Media.DURATION + ">=1000"
        ctx.contentResolver.query(base, proj, sel, null, MediaStore.Audio.Media.DATE_ADDED + " DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val display = cleanText(c.getString(2))
                val title = cleanText(c.getString(1)).ifBlank { display.substringBeforeLast('.', display) }
                out.add(
                    MediaEntry(
                        key = "a:$id",
                        uri = ContentUris.withAppendedId(base, id),
                        title = title.ifBlank { "Audio $id" },
                        artist = cleanText(c.getString(3)),
                        album = cleanText(c.getString(4)),
                        folder = cleanText(c.getString(7)),
                        durationMs = c.getLong(5),
                        isVideo = false,
                        dateAdded = c.getLong(6),
                    ),
                )
            }
        }
        return out
    }

    private fun queryVideo(): List<MediaEntry> {
        val out = ArrayList<MediaEntry>()
        val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )
        ctx.contentResolver.query(base, proj, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val display = cleanText(c.getString(2))
                val title = cleanText(c.getString(1)).ifBlank { display.substringBeforeLast('.', display) }
                out.add(
                    MediaEntry(
                        key = "v:$id",
                        uri = ContentUris.withAppendedId(base, id),
                        title = title.ifBlank { "Video $id" },
                        artist = "",
                        album = "",
                        folder = cleanText(c.getString(5)),
                        durationMs = c.getLong(3),
                        isVideo = true,
                        dateAdded = c.getLong(4),
                    ),
                )
            }
        }
        return out
    }

    /** بيطبّق إعدادات الإخفاء على القوائم. */
    fun refilter() {
        val hidden = settings.hiddenFolders
        val hideRec = settings.hideRecordings
        audio = allAudio.filter { e ->
            !hidden.contains(e.folder) && !(hideRec && isSystemRecordingFolder(e.folder))
        }
        videos = allVideos.filter { e -> !hidden.contains(e.folder) }
    }

    fun setHideRecordings(v: Boolean) {
        settings.updateHideRecordings(v)
        refilter()
    }

    fun toggleHiddenFolder(name: String) {
        settings.toggleFolderHidden(name)
        refilter()
    }

    fun allFolders(): List<String> {
        return (allAudio + allVideos).map { it.folder }.filter { it.isNotBlank() }.distinct().sorted()
    }

    fun entryFor(key: String): MediaEntry? = byKey[key]

    fun entriesFor(keys: List<String>): List<MediaEntry> = keys.mapNotNull { byKey[it] }

    // ---------- بيانات المستخدم ----------
    private fun loadUserData() {
        try {
            val raw = prefs.getString("playlists", null)
            if (raw != null) {
                val obj = JSONObject(raw)
                val map = LinkedHashMap<String, List<String>>()
                val names = obj.keys()
                while (names.hasNext()) {
                    val name = names.next()
                    val arr = obj.getJSONArray(name)
                    map[name] = List(arr.length()) { arr.getString(it) }
                }
                playlists = map
            }
        } catch (e: Exception) {
            AppLog.log("DATA", "playlists load failed", e)
        }
        favorites = HashSet(prefs.getStringSet("favorites", emptySet()) ?: emptySet())
        val r = prefs.getString("recent", "") ?: ""
        recent = if (r.isEmpty()) emptyList() else r.split(",")
    }

    private fun savePlaylists() {
        val obj = JSONObject()
        for ((name, keys) in playlists) {
            val arr = JSONArray()
            for (k in keys) arr.put(k)
            obj.put(name, arr)
        }
        prefs.edit().putString("playlists", obj.toString()).apply()
    }

    fun createPlaylist(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || playlists.containsKey(n)) return false
        val map = LinkedHashMap(playlists)
        map[n] = emptyList()
        playlists = map
        savePlaylists()
        return true
    }

    fun deletePlaylist(name: String) {
        val map = LinkedHashMap(playlists)
        map.remove(name)
        playlists = map
        savePlaylists()
    }

    fun addToPlaylist(name: String, key: String) {
        val current = playlists[name] ?: return
        if (current.contains(key)) return
        val map = LinkedHashMap(playlists)
        map[name] = current + key
        playlists = map
        savePlaylists()
    }

    fun removeFromPlaylist(name: String, key: String) {
        val current = playlists[name] ?: return
        val map = LinkedHashMap(playlists)
        map[name] = current.filter { it != key }
        playlists = map
        savePlaylists()
    }

    fun toggleFavorite(key: String) {
        val updated: Set<String> = if (favorites.contains(key)) favorites - key else favorites + key
        favorites = updated
        prefs.edit().putStringSet("favorites", HashSet(updated)).apply()
    }

    private fun markRecent(key: String) {
        if (key.startsWith("ext:")) return
        val list = ArrayList<String>()
        list.add(key)
        for (k in recent) if (k != key) list.add(k)
        recent = list.take(30)
        prefs.edit().putString("recent", recent.joinToString(",")).apply()
    }

    // ---------- مواضع الاستكمال ----------
    private fun qualifiesForResume(isVideo: Boolean, durationMs: Long): Boolean {
        return isVideo || durationMs >= 600_000L
    }

    private fun resumePosition(entry: MediaEntry): Long {
        if (!settings.resume || !qualifiesForResume(entry.isVideo, entry.durationMs)) return 0L
        val saved = prefs.getLong("pos_" + entry.key, 0L)
        if (saved < 3000L) return 0L
        if (entry.durationMs > 0L && saved > entry.durationMs - 10_000L) return 0L
        return saved
    }

    /** نسبة اللي اتشاف/اتسمع من الملف (لشريط التقدم في القائمة). */
    fun savedFraction(entry: MediaEntry): Float {
        if (entry.durationMs <= 0L || !qualifiesForResume(entry.isVideo, entry.durationMs)) return 0f
        val saved = prefs.getLong("pos_" + entry.key, 0L)
        if (saved < 3000L) return 0f
        val f = saved.toFloat() / entry.durationMs.toFloat()
        return if (f > 0.97f) 0f else f
    }

    private fun saveProgress(c: MediaController, force: Boolean) {
        val item = c.currentMediaItem ?: return
        val key = item.mediaId
        if (key.startsWith("ext:")) return
        val d = c.duration
        if (d == C.TIME_UNSET) return
        if (!qualifiesForResume(item.isVideoItem(), d)) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSaveAt < 5000L) return
        lastSaveAt = now
        val pos = c.currentPosition
        val editor = prefs.edit()
        if (pos < 3000L || pos > d - 10_000L) editor.remove("pos_$key") else editor.putLong("pos_$key", pos)
        editor.apply()
    }

    // ---------- التشغيل ----------
    fun play(list: List<MediaEntry>, startIndex: Int, shuffle: Boolean = false) {
        if (list.isEmpty()) return
        val idx = if (shuffle) (0 until list.size).random() else startIndex.coerceIn(0, list.lastIndex)
        val from = (idx - 100).coerceAtLeast(0)
        val to = minOf(list.size, from + 300)
        val window = list.subList(from, to)
        val start = idx - from
        val entry = list[idx]
        val resumeAt = resumePosition(entry)
        whenReady { c ->
            errorStreak = 0
            c.setMediaItems(window.map { it.toMediaItem() }, start, resumeAt)
            if (shuffle) c.shuffleModeEnabled = true
            c.prepare()
            c.play()
            markRecent(entry.key)
            if (entry.isVideo) showPlayer = true
        }
    }

    fun playNext(entry: MediaEntry) {
        val c = controller
        if (c == null || c.mediaItemCount == 0) {
            play(listOf(entry), 0)
            return
        }
        c.addMediaItem(c.currentMediaItemIndex + 1, entry.toMediaItem())
    }

    fun addToQueue(entry: MediaEntry) {
        val c = controller
        if (c == null || c.mediaItemCount == 0) {
            play(listOf(entry), 0)
            return
        }
        c.addMediaItem(entry.toMediaItem())
    }

    /** ملف جاي من برنامج تاني (فيديو من مدير الملفات). */
    fun playExternal(uri: Uri, isVideo: Boolean) {
        val item = externalMediaItem(ctx, uri, isVideo)
        whenReady { c ->
            errorStreak = 0
            c.setMediaItem(item)
            c.prepare()
            c.play()
            if (isVideo) showPlayer = true
        }
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 3000L || !c.hasPreviousMediaItem()) {
            c.seekTo(0L)
            positionMs = 0L
        } else {
            c.seekToPreviousMediaItem()
        }
    }

    fun seekTo(ms: Long) {
        val c = controller ?: return
        val target = ms.coerceAtLeast(0L)
        c.seekTo(target)
        positionMs = target
    }

    // ---------- السحب الحي (الفيديو بيتحدث وأنت بتسحب) ----------
    private var scrubWasPlaying = false
    private var lastScrubAt = 0L
    private var scrubTarget = 0L
    private var scrubExact = false

    // لما إصبعك يقف لحظة: قفزة دقيقة للإطار الصح بالظبط
    private val exactSeek = Runnable {
        val c = controller
        if (c != null) {
            setFastSeek(false)
            scrubExact = true
            c.seekTo(scrubTarget)
        }
    }

    private fun setFastSeek(fast: Boolean) {
        val c = controller ?: return
        try {
            val args = android.os.Bundle()
            args.putBoolean("fast", fast)
            c.sendCustomCommand(SessionCommand(PlaybackService.SEEK_PARAMS_ACTION, android.os.Bundle.EMPTY), args)
        } catch (e: Exception) {
            AppLog.log("PLAY", "seek params command failed", e)
        }
    }

    fun beginScrub() {
        val c = controller ?: return
        scrubWasPlaying = c.playWhenReady
        if (scrubWasPlaying) c.pause()
        setFastSeek(true)
        scrubExact = false
        lastScrubAt = 0L
    }

    fun scrubTo(ms: Long) {
        val c = controller ?: return
        val target = ms.coerceAtLeast(0L)
        positionMs = target
        scrubTarget = target
        if (scrubExact) {
            setFastSeek(true)
            scrubExact = false
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastScrubAt >= 100L) {
            lastScrubAt = now
            c.seekTo(target)
        }
        handler.removeCallbacks(exactSeek)
        handler.postDelayed(exactSeek, 220L)
    }

    fun endScrub(ms: Long) {
        val c = controller ?: return
        handler.removeCallbacks(exactSeek)
        scrubExact = false
        setFastSeek(false)
        val target = ms.coerceAtLeast(0L)
        c.seekTo(target)
        positionMs = target
        if (scrubWasPlaying) c.play()
        scrubWasPlaying = false
    }

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        var target = c.currentPosition + deltaMs
        val d = c.duration
        if (d != C.TIME_UNSET) target = target.coerceAtMost(d)
        seekTo(target)
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun changeSpeed(value: Float) {
        controller?.setPlaybackSpeed(value)
    }

    // ---------- مؤقت النوم ----------
    fun setSleep(minutes: Int) {
        cancelSleep()
        sleepLeft = minutes * 60_000L
        sleepJob = viewModelScope.launch {
            while (sleepLeft > 0L) {
                delay(1000L)
                sleepLeft -= 1000L
            }
            sleepLeft = 0L
            controller?.pause()
        }
    }

    fun setSleepEndOfTrack() {
        cancelSleep()
        sleepEndOfTrack = true
    }

    fun cancelSleep() {
        sleepJob?.cancel()
        sleepJob = null
        sleepLeft = 0L
        sleepEndOfTrack = false
    }

    // ---------- قائمة الانتظار ----------
    fun jumpTo(index: Int) {
        val c = controller ?: return
        if (index < 0 || index >= c.mediaItemCount) return
        c.seekTo(index, 0L)
        c.play()
    }

    fun removeFromQueue(index: Int) {
        val c = controller ?: return
        if (index < 0 || index >= c.mediaItemCount) return
        c.removeMediaItem(index)
    }

    // ---------- المسارات والترجمة ----------
    private fun trackLabel(f: Format, position: Int): String {
        val parts = ArrayList<String>()
        val label = f.label
        val lang = f.language
        if (!label.isNullOrBlank()) {
            parts.add(label)
        } else if (!lang.isNullOrBlank() && lang != "und") {
            val name = Locale.forLanguageTag(lang).displayName
            parts.add(if (name.isBlank()) lang else name)
        } else {
            parts.add("#" + (position + 1))
        }
        val mime = f.sampleMimeType
        if (mime != null && MimeTypes.isAudio(mime)) {
            val codec = mime.substringAfter('/').removePrefix("x-").uppercase(Locale.ROOT)
            if (codec.isNotBlank()) parts.add(codec)
            if (f.channelCount > 0) parts.add(f.channelCount.toString() + "ch")
        }
        return parts.joinToString(" · ")
    }

    fun trackOptions(type: Int): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        var n = 0
        for (g in tracks.groups) {
            if (g.type != type || !g.isSupported) continue
            for (i in 0 until g.length) {
                out.add(TrackOption(g, i, trackLabel(g.getTrackFormat(i), n), g.isTrackSelected(i)))
                n++
            }
        }
        return out
    }

    fun selectTrack(option: TrackOption) {
        val c = controller ?: return
        val type = option.group.type
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(type)
            .setTrackTypeDisabled(type, false)
            .addOverride(TrackSelectionOverride(option.group.mediaTrackGroup, option.trackIndex))
            .build()
    }

    fun disableText() {
        val c = controller ?: return
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    fun addSubtitle(uri: Uri) {
        val c = controller ?: return
        val item = c.currentMediaItem ?: return
        val index = c.currentMediaItemIndex
        val name = queryDisplayName(ctx, uri).lowercase(Locale.ROOT)
        val mime = when {
            name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            name.endsWith(".ttml") || name.endsWith(".xml") || name.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        try {
            val sub = MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(mime)
                .setLanguage("und")
                .setLabel(name.substringBeforeLast('.', name))
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            val updated = item.buildUpon().setSubtitleConfigurations(listOf(sub)).build()
            val pos = c.currentPosition
            val wasPlaying = c.playWhenReady
            c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
            c.replaceMediaItem(index, updated)
            c.seekTo(index, pos)
            c.playWhenReady = wasPlaying
            message = UiMessage(R.string.msg_subtitle_added)
        } catch (e: Exception) {
            AppLog.log("SUB", "subtitle failed", e)
            message = UiMessage(R.string.msg_subtitle_failed)
        }
    }

    // ---------- دورة حياة التطبيق ----------
    fun onAppBackgrounded() {
        val c = controller ?: return
        if (c.currentMediaItem?.isVideoItem() == true) {
            if (!settings.videoBackground) c.pause()
            // بنوقف فك الفيديو أثناء الخلفية لتوفير البطارية، والصوت يكمّل عادي
            c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
        }
    }

    fun onAppForegrounded() {
        val c = controller ?: return
        if (!audioOnlyMode && c.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)) {
            c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .build()
        }
    }

    private var audioOnlyFromPip = false
    private var audioOnlyMode = false

    /** بيحوّل الفيديو لصوت فقط: بيقفل الصورة ويكمّل الصوت في الخلفية. */
    fun switchToAudioOnly(fromPip: Boolean) {
        audioOnlyFromPip = fromPip
        audioOnlyMode = true
        setVideoEnabled(false)
        showPlayer = false
    }

    fun setVideoEnabled(enabled: Boolean) {
        val c = controller ?: return
        if (enabled) audioOnlyMode = false
        val params = c.trackSelectionParameters
        if (params.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) == enabled) {
            c.trackSelectionParameters = params.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
                .build()
        }
    }

    fun onPipClosed() {
        inPip = false
        if (audioOnlyFromPip) {
            audioOnlyFromPip = false
            return
        }
        val c = controller ?: return
        if (c.currentMediaItem?.isVideoItem() == true) c.pause()
        showPlayer = false
    }

    fun closePlayer() {
        showPlayer = false
        val c = controller ?: return
        if (c.currentMediaItem?.isVideoItem() == true && !settings.videoBackground) c.pause()
    }

    override fun onCleared() {
        handler.removeCallbacks(reloadRunnable)
        try {
            ctx.contentResolver.unregisterContentObserver(observer)
        } catch (e: Exception) {
            // ignore
        }
        sleepJob?.cancel()
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onCleared()
    }
}
