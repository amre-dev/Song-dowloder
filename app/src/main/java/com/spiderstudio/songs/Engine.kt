package com.spiderstudio.songs

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/** محرك التنزيل: تفكيك الروابط -> استخراج -> تنزيل على دفعات -> حفظ بـ MediaStore. */
object Engine {

    private const val CHUNK = 4L * 1024 * 1024
    private val URL_RE = Regex("https?://[^\\s,]+")
    private val ID_RE = Regex("(?:v=|youtu\\.be/|/shorts/)([A-Za-z0-9_-]{11})")
    private var inited = false

    /** يُرمى عند إلغاء المهمة (مو خطأ). */
    class Canceled : Exception("ملغي")

    class Item(val url: String, val title: String?)
    class Result(val title: String, val bytes: Long, val uri: Uri, val kbps: Int, val note: String)
    private class Saved(val bytes: Long, val uri: Uri, val injected: Boolean, val playable: Boolean, val cover: Boolean)

    fun findUrl(text: String?): String? = text?.let { URL_RE.find(it)?.value }

    fun videoId(url: String): String = ID_RE.find(url)?.groupValues?.get(1) ?: url

    /** مفتاح سجل المنزّل: الصوت بالمعرّف فقط (توافق مع القديم)، والفيديو بالمعرّف + الدقة. */
    fun archiveKey(url: String, fmt: String): String =
        if (fmt == "m4a") videoId(url) else videoId(url) + ":" + fmt

    fun isPlaylist(url: String): Boolean = url.contains("/playlist") && url.contains("list=")

    @Synchronized
    private fun init() {
        if (!inited) {
            NewPipe.init(AppDownloader.instance)
            inited = true
        }
    }

    /** رابط فيديو -> عنصر واحد. رابط قائمة تشغيل -> كل عناصرها (حد أقصى [limit]). يعمل خارج الـ main thread. */
    fun resolve(url: String, limit: Int = 300): List<Item> {
        if (!isPlaylist(url)) return listOf(Item(url, null))
        init()
        val yt = ServiceList.YouTube
        val info = PlaylistInfo.getInfo(yt, url)
        val out = ArrayList<Item>()
        for (s in info.relatedItems) out.add(Item(s.url, s.name))
        var page: Page? = info.nextPage
        while (page != null && out.size < limit) {
            val more = PlaylistInfo.getMoreItems(yt, url, page)
            for (s in more.items) out.add(Item(s.url, s.name))
            page = more.nextPage
        }
        return if (out.size > limit) out.subList(0, limit) else out
    }

    fun download(
        context: Context,
        url: String,
        fmt: String,
        quality: String,
        onTitle: (String) -> Unit,
        onMeta: (artist: String, durationSec: Long, coverPath: String?) -> Unit,
        onProgress: (Int) -> Unit,
        isCanceled: () -> Boolean,
    ): Result {
        init()
        if (isCanceled()) throw Canceled()

        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        onTitle(info.name)
        if (isCanceled()) throw Canceled()

        val stream = pickAudio(info.audioStreams, quality)
            ?: throw IllegalStateException("ما لقيت ستريم صوت m4a لهذا الرابط (${info.audioStreams.size} ستريم صوت متاح)")

        val artist = (info.uploaderName ?: "").removeSuffix(" - Topic")
        // الغلاف بنجيبه دايماً (للعرض بالواجهة)، وبنضمّنه بالملف فقط إذا خيار الوسوم مفعّل
        val cover = try { fetchCover(videoId(url)) } catch (e: Exception) { null }
        val thumb = cover?.let { saveThumb(context, videoId(url), it) }
        onMeta(artist, info.duration, thumb)
        if (isCanceled()) throw Canceled()
        if (fmt.startsWith("mp4")) return downloadVideo(context, info, fmt, pickAudio(info.audioStreams, "high") ?: stream, onProgress, isCanceled)
        val tags: ByteArray? = if (Store.tags(context)) Mp4Tags.build(info.name, artist, cover) else null

        var saved = save(context, info.name, artist, stream, tags, onProgress, isCanceled)
        var note = ""
        if (tags != null) {
            note = when {
                saved.injected && saved.playable ->
                    if (saved.cover) "🖼 غلاف + وسوم" else "🏷 وسوم (بدون غلاف)"
                saved.injected -> {
                    // الملف الموسوم ما انفتح بشكل سليم: نحذفه وننزّله نظيف بدون وسوم
                    context.contentResolver.delete(saved.uri, null, null)
                    onProgress(0)
                    saved = save(context, info.name, artist, stream, null, onProgress, isCanceled)
                    "⚠ بدون وسوم (فشل التحقق من الملف الموسوم)"
                }
                else -> "⚠ بدون وسوم (بنية الملف غير مدعومة)"
            }
        }
        return Result(info.name, saved.bytes, saved.uri, stream.averageBitrate, note)
    }

    // ======================= فيديو mp4 =======================

    class VideoOpt(val height: Int, val fps: Int, val size: Long, val progressive: Boolean) {
        val label: String get() = "${height}p" + if (fps > 30) " • ${fps}fps" else ""
    }

    class Analysis(
        val url: String,
        val title: String,
        val artist: String,
        val durationSec: Long,
        val cover: ByteArray?,
        val audioSize: Long,
        val videos: List<VideoOpt>,
    )

    /** ستريمات فيديو mp4 بترميز H.264 فقط (الوحيد اللي بيقدر MediaMuxer يدمجه بموثوقية). */
    private fun videoCandidates(info: StreamInfo): List<VideoStream> =
        (info.videoOnlyStreams + info.videoStreams).filter {
            it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && it.content.isNotEmpty() &&
                it.format == MediaFormat.MPEG_4 && (it.codec ?: "").startsWith("avc1") && it.height >= 240
        }

    /** لكل دقة: نفضّل الجاهز بصوت (بدون دمج)، ثم 30fps أو أقل، ثم الأعلى bitrate. */
    private fun bestPerHeight(c: List<VideoStream>): List<VideoStream> =
        c.groupBy { it.height }
            .map { (_, l) ->
                l.maxWithOrNull(
                    compareBy<VideoStream>(
                        { if (!it.isVideoOnly) 1 else 0 },
                        { if (it.fps <= 30) 1 else 0 },
                        { it.bitrate },
                    )
                )!!
            }
            .sortedByDescending { it.height }

    private fun probeSize(url: String): Long = try {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", AppDownloader.USER_AGENT)
            .header("Range", "bytes=0-0")
            .build()
        AppDownloader.instance.client.newCall(req).execute().use { r ->
            r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
        }
    } catch (e: Exception) {
        -1L
    }

    /** يحلّل رابط فيديو: العنوان والغلاف والصيغ المتاحة مع أحجامها (للورقة السفلية). خارج الـ main thread. */
    fun analyze(url: String): Analysis {
        init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        val artist = (info.uploaderName ?: "").removeSuffix(" - Topic")
        val cover = try { fetchCover(videoId(url)) } catch (e: Exception) { null }
        val audio = pickAudio(info.audioStreams, "high")
        val audioSize = audio?.let { probeSize(it.content) } ?: -1L
        val videos = bestPerHeight(videoCandidates(info)).take(6).map { v ->
            val s = probeSize(v.content)
            val total = if (s > 0 && v.isVideoOnly && audioSize > 0) s + audioSize else s
            VideoOpt(v.height, v.fps, total, !v.isVideoOnly)
        }
        return Analysis(url, info.name, artist, info.duration, cover, audioSize, videos)
    }

    private fun downloadVideo(
        context: Context,
        info: StreamInfo,
        fmt: String,
        audio: AudioStream,
        onProgress: (Int) -> Unit,
        isCanceled: () -> Boolean,
    ): Result {
        val want = fmt.substringAfter(':', "720").toIntOrNull() ?: 720
        val cands = bestPerHeight(videoCandidates(info))
        val v = cands.firstOrNull { it.height == want }
            ?: cands.filter { it.height <= want }.maxByOrNull { it.height }
            ?: cands.minByOrNull { it.height }
            ?: throw IllegalStateException("ما لقيت ستريم فيديو mp4 (H.264) لهذا الفيديو")

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, safeName(info.name) + ".mp4")
            put(MediaStore.Video.Media.TITLE, info.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SongsDownloader")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("فشل إنشاء ملف الفيديو بالتخزين")

        val tmpDir = java.io.File(context.cacheDir, "mux").apply { mkdirs() }
        val stamp = System.nanoTime()
        val vFile = java.io.File(tmpDir, "$stamp.v.mp4")
        val aFile = java.io.File(tmpDir, "$stamp.a.m4a")
        try {
            val size: Long
            if (!v.isVideoOnly) {
                // ستريم جاهز فيه صوت وصورة: تنزيل مباشر بدون دمج
                size = resolver.openOutputStream(uri)?.use { fetch(v.content, it, onProgress, isCanceled) }
                    ?: throw IOException("فشل فتح الملف للكتابة")
            } else {
                vFile.outputStream().use { fetch(v.content, it, { p -> onProgress(p * 85 / 100) }, isCanceled) }
                aFile.outputStream().use { fetch(audio.content, it, { p -> onProgress(85 + p * 10 / 100) }, isCanceled) }
                onProgress(96)
                size = resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    mux(vFile, aFile, pfd.fileDescriptor, isCanceled)
                    pfd.statSize
                } ?: throw IOException("فشل فتح ملف الفيديو للدمج")
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            onProgress(100)
            return Result(info.name, size, uri, 0, "🎬 ${v.height}p")
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        } finally {
            vFile.delete()
            aFile.delete()
        }
    }

    private fun trackOf(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    /** يدمج ملف فيديو (H.264) وملف صوت (AAC) بـ mp4 واحد، بالتناوب حسب الزمن حتى يكون الملف سلس. */
    private fun mux(video: java.io.File, audio: java.io.File, out: java.io.FileDescriptor, isCanceled: () -> Boolean) {
        val vEx = MediaExtractor()
        val aEx = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            vEx.setDataSource(video.path)
            aEx.setDataSource(audio.path)
            val vi = trackOf(vEx, "video/")
            val ai = trackOf(aEx, "audio/")
            if (vi < 0 || ai < 0) throw IOException("الملفات المنزّلة ما فيها مسار فيديو أو صوت صالح")
            vEx.selectTrack(vi)
            aEx.selectTrack(ai)
            val vf = vEx.getTrackFormat(vi)
            if (vf.getString(android.media.MediaFormat.KEY_MIME) != "video/avc") {
                throw IOException("ترميز الفيديو غير مدعوم للدمج")
            }

            val m = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val vt = m.addTrack(vf)
            val at = m.addTrack(aEx.getTrackFormat(ai))
            m.start()
            started = true

            val buf = java.nio.ByteBuffer.allocate(4 * 1024 * 1024)
            val bi = MediaCodec.BufferInfo()
            while (true) {
                if (isCanceled()) throw Canceled()
                val vTime = vEx.sampleTime
                val aTime = aEx.sampleTime
                if (vTime < 0 && aTime < 0) break
                val useVideo = vTime >= 0 && (aTime < 0 || vTime <= aTime)
                val ex = if (useVideo) vEx else aEx
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) {
                    ex.advance()
                    continue
                }
                val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                bi.set(0, n, ex.sampleTime, flags)
                m.writeSampleData(if (useVideo) vt else at, buf, bi)
                ex.advance()
            }
            m.stop()
            started = false
        } finally {
            try { if (started) muxer?.stop() } catch (e: Exception) { }
            try { muxer?.release() } catch (e: Exception) { }
            vEx.release()
            aEx.release()
        }
    }

    private fun save(
        context: Context,
        title: String,
        artist: String,
        stream: AudioStream,
        tags: ByteArray?,
        onProgress: (Int) -> Unit,
        isCanceled: () -> Boolean,
    ): Saved {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, safeName(title) + ".m4a")
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.ARTIST, artist)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/SongsDownloader")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("فشل إنشاء الملف بالتخزين")

        try {
            var injected = false
            val bytes = resolver.openOutputStream(uri)?.use { raw ->
                val tagger = Mp4Tagger(raw, tags)
                val n = fetch(stream.content, tagger, onProgress, isCanceled)
                tagger.finish()
                injected = tagger.injected
                n
            } ?: throw IOException("فشل فتح الملف للكتابة")

            val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)

            var playable = true
            var cover = false
            if (injected) {
                val r = inspect(context, uri)
                playable = r.first
                cover = r.second
            }
            return Saved(bytes, uri, injected, playable, cover)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)   // لا نترك ملف ناقص
            throw e
        }
    }

    /** يتأكد إنو أندرويد بيقرأ الملف (مدة > 0) ويرجّع (سليم؟، فيه غلاف؟). */
    private fun inspect(context: Context, uri: Uri): Pair<Boolean, Boolean> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            Pair(dur > 0L, r.embeddedPicture != null)
        } catch (e: Exception) {
            Pair(false, false)
        } finally {
            r.release()
        }
    }

    /** غلاف مربّع من صور يوتيوب الرسمية (من الأكبر للأصغر). */
    private fun fetchCover(id: String): ByteArray? {
        if (id.length != 11) return null
        for (name in listOf("maxresdefault", "hq720", "sddefault", "hqdefault")) {
            try {
                val req = okhttp3.Request.Builder()
                    .url("https://i.ytimg.com/vi/$id/$name.jpg")
                    .header("User-Agent", AppDownloader.USER_AGENT)
                    .build()
                val bytes = AppDownloader.instance.client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.bytes() else null
                }
                if (bytes != null) squareJpeg(bytes)?.let { return it }
            } catch (e: Exception) {
                // نجرّب الحجم الأصغر
            }
        }
        return null
    }

    /** نسخة مصغّرة (240px) من الغلاف بالتخزين الداخلي لعرضها بالقائمة. */
    private fun saveThumb(context: Context, id: String, jpeg: ByteArray): String? = try {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: throw IOException("bad cover")
        val small = Bitmap.createScaledBitmap(bmp, 240, 240, true)
        val dir = java.io.File(context.filesDir, "covers").apply { mkdirs() }
        val f = java.io.File(dir, "$id.jpg")
        f.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        f.absolutePath
    } catch (e: Exception) {
        null
    }

    private fun squareJpeg(bytes: ByteArray): ByteArray? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val w = bmp.width
        val h = bmp.height
        if (w < 300 || h < 200) return null            // صورة بديلة صغيرة
        val ratio = w.toFloat() / h
        // صور 4:3 فيها أشرطة سوداء فوق وتحت: نقص من محتوى 16:9 الحقيقي
        val side = if (Math.abs(ratio - 4f / 3f) < 0.05f) h * 3 / 4 else minOf(w, h)
        var sq = Bitmap.createBitmap(bmp, (w - side) / 2, (h - side) / 2, side, side)
        if (side > 600) sq = Bitmap.createScaledBitmap(sq, 600, 600, true)
        val out = ByteArrayOutputStream()
        sq.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    private fun pickAudio(list: List<AudioStream>, quality: String): AudioStream? {
        val ok = list.filter {
            it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                it.format == MediaFormat.M4A &&
                it.isUrl && it.content.isNotEmpty()
        }
        return if (quality == "low") ok.minByOrNull { it.averageBitrate } else ok.maxByOrNull { it.averageBitrate }
    }

    /** تنزيل على دفعات 4MB بترويسة Range (يوتيوب بيخنق/بيرفض التنزيل بطلب واحد أحياناً). */
    private fun fetch(url: String, out: OutputStream, onProgress: (Int) -> Unit, isCanceled: () -> Boolean): Long {
        val client = AppDownloader.instance.client
        val buf = ByteArray(32 * 1024)
        var pos = 0L
        var total = -1L
        var done = false
        var lastPct = -1

        while (!done) {
            if (isCanceled()) throw Canceled()
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", AppDownloader.USER_AGENT)
                .header("Range", "bytes=$pos-${pos + CHUNK - 1}")
                .build()

            var chunkBytes = 0L
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("رد فارغ من السيرفر")

                if (resp.code == 206) {
                    total = resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
                } else {
                    total = body.contentLength()   // السيرفر تجاهل Range وبعت الملف كامل
                    done = true
                }

                body.byteStream().use { input ->
                    while (true) {
                        if (isCanceled()) throw Canceled()
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

    /** رسالة خطأ مفهومة بالعربي. */
    fun explain(e: Throwable): String = when (e) {
        is ReCaptchaException -> "يوتيوب طلب تحقق، حاول لاحقاً"
        is ContentNotAvailableException -> "المحتوى غير متاح (خاص/محذوف/مقيّد بالعمر أو المنطقة)"
        is ExtractionException -> "فشل الاستخراج، غالباً يوتيوب تغيّر وتحتاج المكتبة تحديث (${e.message})"
        is java.net.UnknownHostException, is java.net.SocketTimeoutException -> "مشكلة بالإنترنت"
        is IOException -> "فشل التنزيل: ${e.message}"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }
}
