package com.myplayer.app

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ملفات الصوت اللي بتيجي من مدير الملفات بتيجي بإذن مؤقت بيتلغي لما الشاشة الشفافة تتقفل.
 * عشان كده بنفتح الملف مرة واحدة ونحتفظ بالمقبض (File Descriptor) ونشغّل منه مباشرة.
 */
object FdRegistry {
    private val map = ConcurrentHashMap<String, ParcelFileDescriptor>()
    private val counter = AtomicInteger(0)

    fun register(pfd: ParcelFileDescriptor, name: String): Uri {
        val id = counter.incrementAndGet().toString()
        map[id] = pfd
        if (map.size > 16) {
            val oldest = map.keys.minByOrNull { it.toInt() }
            if (oldest != null && oldest != id) {
                try {
                    map.remove(oldest)?.close()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
        return Uri.Builder().scheme("fd").authority("local").appendPath(id).appendPath(name).build()
    }

    fun get(id: String): ParcelFileDescriptor? = map[id]
}

@androidx.annotation.OptIn(UnstableApi::class)
class FdDataSource : BaseDataSource(false) {
    private var uri: Uri? = null
    private var channel: FileChannel? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val id = dataSpec.uri.pathSegments.firstOrNull() ?: throw IOException("Bad fd uri")
        val pfd = FdRegistry.get(id) ?: throw IOException("File handle is closed")
        val ch = FileInputStream(pfd.fileDescriptor).channel
        val size = ch.size()
        if (dataSpec.position > size) throw IOException("Position out of range")
        channel = ch
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, size - position)
        } else {
            size - position
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val ch = channel ?: throw IOException("Not open")
        val toRead = minOf(remaining, length.toLong()).toInt()
        val read = ch.read(ByteBuffer.wrap(buffer, offset, toRead), position)
        if (read < 0) return C.RESULT_END_OF_INPUT
        position += read
        remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        // مقصود إننا منقفلش المقبض نفسه لأنه مشترك
        channel = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

/** بيختار المصدر المناسب: مقبض الملف لو الرابط fd://، وغير كده المصدر العادي. */
@androidx.annotation.OptIn(UnstableApi::class)
class FdAwareDataSource(private val base: DataSource) : DataSource {
    private val fd = FdDataSource()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        base.addTransferListener(transferListener)
        fd.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source: DataSource = if (dataSpec.uri.scheme == "fd") fd else base
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val source = current ?: throw IOException("Not open")
        return source.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> {
        return current?.responseHeaders ?: emptyMap()
    }

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }
}
