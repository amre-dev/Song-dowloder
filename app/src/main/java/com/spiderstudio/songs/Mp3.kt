package com.spiderstudio.songs

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteOrder

/** جسر JNI لمشفّر LAME (الكود بـ app/src/main/cpp). */
object Lame {
    init {
        System.loadLibrary("amrlame")
    }

    @JvmStatic external fun create(sampleRate: Int, channels: Int, kbps: Int): Long
    @JvmStatic external fun encode(handle: Long, pcm: ShortArray, frames: Int, out: ByteArray): Int
    @JvmStatic external fun flush(handle: Long, out: ByteArray): Int
    @JvmStatic external fun close(handle: Long)
}

/** وسوم ID3v2.3: عنوان (TIT2) وفنان (TPE1) وغلاف (APIC)، بترميز UTF-16 لدعم العربي. */
object Id3 {
    private fun frame(id: String, payload: ByteArray): ByteArray {
        val h = ByteArray(10)
        id.toByteArray(Charsets.ISO_8859_1).copyInto(h, 0)
        h[4] = (payload.size ushr 24).toByte()
        h[5] = (payload.size ushr 16).toByte()
        h[6] = (payload.size ushr 8).toByte()
        h[7] = payload.size.toByte()
        return h + payload                      // الفلاغات (بايتين) أصفار
    }

    private fun text(id: String, s: String): ByteArray =
        frame(id, byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0))

    fun build(title: String, artist: String?, cover: ByteArray?): ByteArray {
        val frames = ByteArrayOutputStream()
        frames.write(text("TIT2", title))
        if (!artist.isNullOrBlank()) frames.write(text("TPE1", artist))
        if (cover != null) {
            val payload = byteArrayOf(0) + "image/jpeg".toByteArray(Charsets.ISO_8859_1) +
                byteArrayOf(0, 3, 0) + cover       // ترميز، mime، 0، نوع الصورة=غلاف أمامي، وصف فاضي، 0
            frames.write(frame("APIC", payload))
        }
        val body = frames.toByteArray()
        val n = body.size
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            ((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(),
            ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte(),
        )
        return header + body
    }
}

object Mp3 {
    /**
     * يفك ترميز ملف صوت (AAC) بـ MediaCodec ويشفّره MP3 بـ LAME مباشرة إلى [out].
     * التقدّم [onProgress] من 0 إلى 100 حسب موضع الفك.
     */
    fun encode(
        src: File,
        out: OutputStream,
        id3: ByteArray?,
        kbps: Int,
        onProgress: (Int) -> Unit,
        isCanceled: () -> Boolean,
    ) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var handle = 0L
        try {
            ex.setDataSource(src.path)
            var track = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { track = i; break }
            }
            if (track < 0) throw IOException("ما لقيت مسار صوت بالملف المنزّل")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L

            val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = dec
            dec.configure(fmt, null, null, 0)
            dec.start()

            if (id3 != null) out.write(id3)

            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            var pcm = ShortArray(0)
            var mp3 = ByteArray(0)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastPct = -1

            while (!outputDone) {
                if (isCanceled()) throw Engine.Canceled()

                if (!inputDone) {
                    val ii = dec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = dec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }

                val oi = dec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = dec.outputFormat
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    oi >= 0 -> {
                        val ob = dec.getOutputBuffer(oi)!!
                        if (info.size > 0) {
                            if (channels !in 1..2) throw IOException("عدد قنوات الصوت غير مدعوم ($channels)")
                            if (handle == 0L) {
                                handle = Lame.create(rate, channels, kbps)
                                if (handle == 0L) throw IOException("فشل تهيئة مشفّر MP3")
                            }
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            ob.order(ByteOrder.LITTLE_ENDIAN)
                            val total: Int
                            if (floatPcm) {
                                val fb = ob.asFloatBuffer()
                                total = fb.remaining()
                                if (pcm.size < total) pcm = ShortArray(total)
                                for (k in 0 until total) {
                                    pcm[k] = (fb.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                }
                            } else {
                                val sb = ob.asShortBuffer()
                                total = sb.remaining()
                                if (pcm.size < total) pcm = ShortArray(total)
                                sb.get(pcm, 0, total)
                            }
                            val frames = total / channels
                            if (frames > 0) {
                                val need = frames * 5 / 4 + 7200
                                if (mp3.size < need) mp3 = ByteArray(need)
                                val n = Lame.encode(handle, pcm, frames, mp3)
                                if (n < 0) throw IOException("خطأ بتشفير MP3 ($n)")
                                if (n > 0) out.write(mp3, 0, n)
                            }
                        }
                        if (durationUs > 0) {
                            val pct = (info.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                        dec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }

            if (handle != 0L) {
                if (mp3.size < 7200) mp3 = ByteArray(7200)
                val n = Lame.flush(handle, mp3)
                if (n > 0) out.write(mp3, 0, n)
            }
            onProgress(100)
        } finally {
            if (handle != 0L) Lame.close(handle)
            try { codec?.stop() } catch (e: Exception) { }
            try { codec?.release() } catch (e: Exception) { }
            ex.release()
        }
    }
}
