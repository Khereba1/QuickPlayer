package com.myplayer.app

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** عنصر واحد من حالات واتساب (فيديو أو صورة). */
data class StatusItem(
    val docId: String,
    val uri: Uri,
    val name: String,
    val mime: String,
    val modified: Long,
) {
    val isVideo: Boolean get() = mime.startsWith("video/")

    fun toEntry(): MediaEntry = MediaEntry(
        key = "status:" + docId,
        uri = uri,
        title = name,
        artist = "",
        album = "",
        folder = "WhatsApp",
        durationMs = 0L,
        isVideo = true,
        dateAdded = modified / 1000L,
    )
}

/** قراءة مجلد الحالات عن طريق إذن المجلد (SAF) من غير أي صلاحية إضافية. */
object StatusSource {
    const val WA = "com.whatsapp"
    const val BIZ = "com.whatsapp.w4b"
    private const val AUTH = "com.android.externalstorage.documents"

    fun installed(ctx: Context, pkg: String): Boolean {
        return try {
            ctx.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** بيفتح اختيار المجلد على المكان الصح مباشرة. */
    fun initialUri(pkg: String): Uri {
        val app = if (pkg == BIZ) "WhatsApp Business" else "WhatsApp"
        return DocumentsContract.buildDocumentUri(AUTH, "primary:Android/media/" + pkg + "/" + app + "/Media/.Statuses")
    }

    private class Doc(val id: String, val name: String, val mime: String, val modified: Long)

    private fun children(ctx: Context, tree: Uri, parentId: String): List<Doc> {
        val out = ArrayList<Doc>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out.add(Doc(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3)))
            }
        }
        return out
    }

    /** لو المستخدم اختار مجلد أعلى من .Statuses بندوّر جواه لحد تلات مستويات. */
    private fun resolveRoot(ctx: Context, tree: Uri, startId: String): String {
        if (startId.endsWith(".Statuses")) return startId
        var level = listOf(startId)
        var depth = 0
        while (depth < 3 && level.isNotEmpty()) {
            val next = ArrayList<String>()
            for (id in level) {
                for (r in children(ctx, tree, id)) {
                    if (r.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (r.name == ".Statuses") return r.id
                        next.add(r.id)
                    }
                }
            }
            level = next
            depth++
        }
        return startId
    }

    fun list(ctx: Context, tree: Uri): List<StatusItem> {
        val root = resolveRoot(ctx, tree, DocumentsContract.getTreeDocumentId(tree))
        val result = ArrayList<StatusItem>()
        for (r in children(ctx, tree, root)) {
            if (r.mime.startsWith("video/") || r.mime.startsWith("image/")) {
                result.add(
                    StatusItem(
                        docId = r.id,
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, r.id),
                        name = r.name,
                        mime = r.mime,
                        modified = r.modified,
                    ),
                )
            }
        }
        result.sortByDescending { it.modified }
        return result
    }
}

private object StatusThumbs {
    private val cache = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val gate = Semaphore(3)

    suspend fun load(ctx: Context, item: StatusItem): Bitmap? {
        val hit = cache.get(item.docId)
        if (hit != null) return hit
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                val bmp = if (item.isVideo) videoFrame(ctx, item.uri) else decodeImage(ctx, item.uri, 420)
                if (bmp != null) cache.put(item.docId, bmp)
                bmp
            }
        }
    }

    private fun videoFrame(ctx: Context, uri: Uri): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.getScaledFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 480)
        } catch (e: Exception) {
            null
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}

/** فك صورة بحجم مناسب للشاشة من غير ما نحمّل الصورة الأصلية كاملة في الذاكرة. */
private fun decodeImage(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        null
    }
}

private suspend fun saveToGallery(ctx: Context, item: StatusItem): Boolean = saveUriToGallery(ctx, item.uri, item.name, item.mime)

suspend fun saveUriToGallery(ctx: Context, srcUri: Uri, name: String, mime: String): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val video = mime.startsWith("video")
            val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) "Movies/" else "Pictures/") + "QuickPlayer Statuses")
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            val dest = ctx.contentResolver.insert(collection, values)
            if (dest == null) {
                false
            } else {
                var ok = false
                try {
                    val input = ctx.contentResolver.openInputStream(srcUri)
                    val output = ctx.contentResolver.openOutputStream(dest)
                    if (input != null && output != null) {
                        input.use { i -> output.use { o -> i.copyTo(o) } }
                        ok = true
                    }
                } catch (e: Exception) {
                    AppLog.log("STATUS", "copy failed", e)
                }
                if (ok) {
                    val done = ContentValues()
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    ctx.contentResolver.update(dest, done, null, null)
                } else {
                    ctx.contentResolver.delete(dest, null, null)
                }
                ok
            }
        } catch (e: Exception) {
            AppLog.log("STATUS", "save failed", e)
            false
        }
    }
}

private fun shareItem(ctx: Context, item: StatusItem) = shareUri(ctx, item.uri, item.mime)

fun shareUri(ctx: Context, uri: Uri, mime: String) {
    try {
        val send = Intent(Intent.ACTION_SEND)
        send.type = mime
        send.putExtra(Intent.EXTRA_STREAM, uri)
        send.clipData = ClipData.newRawUri("", uri)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, null))
    } catch (e: Exception) {
        AppLog.log("STATUS", "share failed", e)
    }
}

@Composable
fun StatusScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("status_prefs", Context.MODE_PRIVATE) }
    val pkgs = remember { listOf(StatusSource.WA, StatusSource.BIZ).filter { StatusSource.installed(ctx, it) } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    val trees = remember {
        mutableStateMapOf<String, String?>().apply {
            for (p in pkgs) put(p, prefs.getString("tree_" + p, null))
        }
    }
    val pkg: String? = if (pkgs.isEmpty()) null else pkgs[tab.coerceIn(0, pkgs.lastIndex)]
    val tree: String? = if (pkg != null) trees[pkg] else null

    var items by remember(pkg, tree, tick) { mutableStateOf<List<StatusItem>?>(null) }
    LaunchedEffect(pkg, tree, tick) {
        if (tree != null) {
            items = withContext(Dispatchers.IO) {
                try {
                    StatusSource.list(ctx, Uri.parse(tree))
                } catch (e: Exception) {
                    AppLog.log("STATUS", "list failed", e)
                    emptyList()
                }
            }
        }
    }

    var pending by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val target = pending
        if (uri != null && target != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: Exception) {
                AppLog.log("STATUS", "persist permission failed", e)
            }
            prefs.edit().putString("tree_" + target, uri.toString()).apply()
            trees[target] = uri.toString()
        }
    }

    val selected = remember { mutableStateListOf<String>() }
    var viewer by remember { mutableStateOf<StatusItem?>(null) }
    BackHandler(enabled = viewer != null && !vm.showPlayer) { viewer = null }
    BackHandler(enabled = viewer == null && selected.isNotEmpty() && !vm.showPlayer) { selected.clear() }

    val all: List<StatusItem> = items ?: emptyList()
    val shown = when (filter) {
        1 -> all.filter { it.isVideo }
        2 -> all.filter { !it.isVideo }
        else -> all
    }
    val videoEntries = all.filter { it.isVideo }.map { it.toEntry() }

    fun saveItems(list: List<StatusItem>) {
        scope.launch {
            var okCount = 0
            for (it in list) {
                if (saveToGallery(ctx, it)) okCount++
            }
            vm.message = if (okCount > 0) UiMessage(R.string.status_saved) else UiMessage(R.string.status_save_failed)
            selected.clear()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // الشريط العلوي
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                if (selected.isEmpty()) {
                    IconButton(onClick = onBack) { Ico(R.drawable.ic_back, stringResource(R.string.back)) }
                    Text(
                        stringResource(R.string.status_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { tick++ }, enabled = tree != null) {
                        Ico(R.drawable.ic_refresh, stringResource(R.string.refresh))
                    }
                } else {
                    IconButton(onClick = { selected.clear() }) { Ico(R.drawable.ic_close, stringResource(R.string.cancel)) }
                    Text(
                        stringResource(R.string.status_selected, selected.size),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { saveItems(all.filter { selected.contains(it.docId) }) }) {
                        Ico(R.drawable.ic_download, stringResource(R.string.status_save))
                    }
                }
            }

            if (pkgs.size > 1) {
                TabRow(selectedTabIndex = tab.coerceIn(0, pkgs.lastIndex)) {
                    for ((i, p) in pkgs.withIndex()) {
                        Tab(
                            selected = tab == i,
                            onClick = {
                                tab = i
                                selected.clear()
                            },
                            text = {
                                Text(stringResource(if (p == StatusSource.BIZ) R.string.status_tab_business else R.string.status_tab_whatsapp))
                            },
                        )
                    }
                }
            }

            if (pkg == null) {
                CenterMessage(stringResource(R.string.status_no_app))
            } else if (tree == null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Ico(R.drawable.ic_status, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.status_allow), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.status_allow_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = {
                        pending = pkg
                        picker.launch(StatusSource.initialUri(pkg))
                    }) { Text(stringResource(R.string.status_choose)) }
                }
            } else if (items == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = filter == 0, onClick = { filter = 0 }, label = { Text(stringResource(R.string.status_all), maxLines = 1) })
                    FilterChip(selected = filter == 1, onClick = { filter = 1 }, label = { Text(stringResource(R.string.status_videos), maxLines = 1) })
                    FilterChip(selected = filter == 2, onClick = { filter = 2 }, label = { Text(stringResource(R.string.status_photos), maxLines = 1) })
                }
                if (shown.isEmpty()) {
                    CenterMessage(stringResource(R.string.status_empty))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(shown, key = { it.docId }) { item ->
                            StatusCell(
                                item = item,
                                selected = selected.contains(item.docId),
                                onClick = {
                                    if (selected.isNotEmpty()) {
                                        if (selected.contains(item.docId)) selected.remove(item.docId) else selected.add(item.docId)
                                    } else if (item.isVideo) {
                                        val index = videoEntries.indexOfFirst { it.key == "status:" + item.docId }
                                        if (index >= 0) vm.play(videoEntries, index)
                                    } else {
                                        viewer = item
                                    }
                                },
                                onLongClick = {
                                    if (!selected.contains(item.docId)) selected.add(item.docId)
                                },
                            )
                        }
                    }
                }
            }
        }

        val view = viewer
        if (view != null) {
            PhotoViewer(
                item = view,
                onClose = { viewer = null },
                onSave = { saveItems(listOf(view)) },
                onShare = { shareItem(ctx, view) },
            )
        }
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StatusCell(
    item: StatusItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, item.docId) { value = StatusThumbs.load(ctx, item) }
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongClick)
    Box(
        modifier = Modifier
            .aspectRatio(0.75f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(item.docId) {
                detectTapGestures(onTap = { click() }, onLongPress = { long() })
            },
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(0x99000000)),
                contentAlignment = Alignment.Center,
            ) {
                Ico(R.drawable.ic_play, null, Modifier.size(16.dp), tint = Color.White)
            }
        }
        if (selected) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0x66000000)))
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Ico(R.drawable.ic_check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun PhotoViewer(
    item: StatusItem,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, item.docId) {
        value = withContext(Dispatchers.IO) { decodeImage(ctx, item.uri, 1600) }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offX by remember { mutableFloatStateOf(0f) }
    var offY by remember { mutableFloatStateOf(0f) }
    var bars by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offX, translationY = offY)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale > 1f) {
                                offX += pan.x
                                offY += pan.y
                            } else {
                                offX = 0f
                                offY = 0f
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { bars = !bars },
                            onDoubleTap = {
                                scale = 1f
                                offX = 0f
                                offY = 0f
                            },
                        )
                    },
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        if (bars) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color(0xCC000000), Color.Transparent),
                        ),
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Ico(R.drawable.ic_back, stringResource(R.string.back), tint = Color.White) }
                Text(
                    text = item.name,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = onShare) { Ico(R.drawable.ic_share, stringResource(R.string.status_share), tint = Color.White) }
                IconButton(onClick = onSave) { Ico(R.drawable.ic_download, stringResource(R.string.status_save), tint = Color.White) }
            }
        }
    }
}
