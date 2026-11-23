package com.spiderstudio.songs

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.io.OutputStream

/** محرك التنزيل: استخراج الرابط -> تنزيل على دفعات -> حفظ بـ MediaStore. */
object Engine {

    private const val CHUNK = 4L * 1024 * 1024
    private val URL_RE = Regex("https?://[^\\s,]+")
    private var inited = false

    class Result(val title: String, val bytes: Long, val uri: Uri, val kbps: Int)

    fun findUrl(text: String?): String? = text?.let { URL_RE.find(it)?.value }

    @Synchronized
    private fun init() {
        if (!inited) {
            NewPipe.init(AppDownloader.instance)
            inited = true
        }
    }

    fun downloadAudio(
        context: Context,
        url: String,
        onStatus: (String) -> Unit,
        onProgress: (Int) -> Unit,
    ): Result {
        init()

        onStatus("🔎 جاري فحص الرابط...")
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)

        val stream = pickAudio(info.audioStreams)
            ?: throw IllegalStateException("ما لقيت ستريم صوت m4a لهذا الرابط (${info.audioStreams.size} ستريم صوت متاح)")
        onStatus("🎵 ${info.name}\n⬇ m4a ~${stream.averageBitrate} kbps")

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, safeName(info.name) + ".m4a")
            put(MediaStore.Audio.Media.TITLE, info.name)
            put(MediaStore.Audio.Media.ARTIST, (info.uploaderName ?: "").removeSuffix(" - Topic"))
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/SongsDownloader")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("فشل إنشاء الملف بالتخزين")

        try {
            val bytes = resolver.openOutputStream(uri)?.use { out ->
                fetch(stream.content, out, onProgress)
            } ?: throw IOException("فشل فتح الملف للكتابة")

            val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return Result(info.name, bytes, uri, stream.averageBitrate)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)   // لا نترك ملف ناقص
            throw e
        }
    }

    private fun pickAudio(list: List<AudioStream>): AudioStream? =
        list.filter {
            it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                it.format == MediaFormat.M4A &&
                it.isUrl && it.content.isNotEmpty()
        }.maxByOrNull { it.averageBitrate }

    /** تنزيل على دفعات 4MB بترويسة Range (يوتيوب بيخنق/بيرفض التنزيل بطلب واحد أحياناً). */
    private fun fetch(url: String, out: OutputStream, onProgress: (Int) -> Unit): Long {
        val client = AppDownloader.instance.client
        val buf = ByteArray(32 * 1024)
        var pos = 0L
        var total = -1L
        var done = false
        var lastPct = -1

        while (!done) {
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", AppDownloader.USER_AGENT)
                .header("Range", "bytes=$pos-${pos + CHUNK - 1}")
                .build()

            var chunkBytes = 0L
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("فشل التنزيل: HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("رد فارغ من السيرفر")

                if (resp.code == 206) {
                    total = resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
                } else {
                    total = body.contentLength()   // السيرفر تجاهل Range وبعت الملف كامل
                    done = true
                }

                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        pos += n
                        chunkBytes += n
                        if (total > 0) {
                            val pct = (pos * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
            if (chunkBytes == 0L) throw IOException("السيرفر رجّع دفعة فارغة")
            if ((total > 0 && pos >= total) || chunkBytes < CHUNK) done = true
        }
        return pos
    }

    private fun safeName(name: String?): String {
        val s = (name ?: "").replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1f]"), "_").trim(' ', '.')
        return s.take(80).ifEmpty { "song" }
    }
}
