package com.spiderstudio.songs

import java.io.ByteArrayOutputStream
import java.io.OutputStream

private val LATIN1 = Charsets.ISO_8859_1

private fun u32(v: Int) =
    byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

private fun readInt(b: ByteArray, o: Int): Int =
    ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
        ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

private fun readType(b: ByteArray, o: Int): String = String(b, o, 4, LATIN1)

/** يبني صندوق udta (وسوم iTunes: العنوان/الفنان/الغلاف) جاهز للحقن داخل moov. */
object Mp4Tags {
    private fun box(type: String, payload: ByteArray): ByteArray =
        u32(8 + payload.size) + type.toByteArray(LATIN1) + payload

    // flags: 1 = نص UTF-8، 13 = صورة JPEG
    private fun data(flags: Int, value: ByteArray) = box("data", u32(flags) + u32(0) + value)

    private fun text(type: String, v: String) = box(type, data(1, v.toByteArray(Charsets.UTF_8)))

    fun build(title: String, artist: String?, cover: ByteArray?): ByteArray {
        val items = ByteArrayOutputStream()
        items.write(text("\u00A9nam", title))
        if (!artist.isNullOrBlank()) items.write(text("\u00A9ART", artist))
        if (cover != null) items.write(box("covr", data(13, cover)))

        val hdlr = box(
            "hdlr",
            u32(0) + u32(0) + "mdir".toByteArray(LATIN1) + "appl".toByteArray(LATIN1) +
                u32(0) + u32(0) + byteArrayOf(0)
        )
        val meta = box("meta", u32(0) + hdlr + box("ilst", items.toByteArray()))
        return box("udta", meta)
    }
}

/**
 * مجرى كتابة بيحقن الوسوم داخل moov أثناء التنزيل (بدون تمرير ثاني على الملف).
 * بيشتغل فقط على ملفات MP4 المجزّأة (fragmented، فيها mvex) وmoov قبل البيانات.
 * أي شي غير متوقع -> بيمرّر الملف كما هو بدون تعديل ([injected] يبقى false).
 */
class Mp4Tagger(private val out: OutputStream, private val udta: ByteArray?) : OutputStream() {

    private val acc = ByteArrayOutputStream()
    private var done = udta == null

    var injected = false
        private set

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (done) {
            out.write(b, off, len)
            return
        }
        acc.write(b, off, len)
        step(false)
    }

    /** لازم تنادى بعد آخر كتابة (لو الملف أصغر من المتوقع). */
    fun finish() {
        if (!done) step(true)
    }

    override fun flush() {
        if (done) out.flush()
    }

    override fun close() {
        finish()
        out.close()
    }

    private fun passThrough(data: ByteArray) {
        out.write(data)
        acc.reset()
        done = true
    }

    private fun step(last: Boolean) {
        val data = acc.toByteArray()
        var pos = 0
        while (true) {
            if (pos + 8 > data.size) {
                if (last || data.size > LIMIT) passThrough(data)
                return
            }
            val size = readInt(data, pos)
            val type = readType(data, pos + 4)
            if (size < 8 || type == "moof" || type == "mdat") {
                passThrough(data)
                return
            }
            if (type == "moov") {
                if (pos.toLong() + size > data.size) {
                    if (last || data.size > LIMIT) passThrough(data)
                    return
                }
                inject(data, pos, size)
                return
            }
            if (pos.toLong() + size > Int.MAX_VALUE) {
                passThrough(data)
                return
            }
            pos += size
        }
    }

    private fun inject(data: ByteArray, start: Int, size: Int) {
        val tags = udta ?: run { passThrough(data); return }
        val end = start + size
        var p = start + 8
        val kept = ByteArrayOutputStream()
        var fragmented = false

        while (p < end) {
            if (p + 8 > end) { passThrough(data); return }
            val cs = readInt(data, p)
            if (cs < 8 || p.toLong() + cs > end) { passThrough(data); return }
            val ct = readType(data, p + 4)
            if (ct == "mvex") fragmented = true
            if (ct != "udta") kept.write(data, p, cs)   // نحذف udta القديم إن وجد
            p += cs
        }
        if (!fragmented) { passThrough(data); return }   // offsets مطلقة: ما منقدر نزيح الملف بأمان

        kept.write(tags)
        out.write(data, 0, start)
        out.write(u32(8 + kept.size()))
        out.write("moov".toByteArray(LATIN1))
        out.write(kept.toByteArray())
        out.write(data, end, data.size - end)

        acc.reset()
        done = true
        injected = true
    }

    private companion object {
        const val LIMIT = 1024 * 1024
    }
}
