package com.spiderstudio.songs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * قائمة التنزيل. حالة المهام Compose-state (الواجهة بتتحدّث لحالها)، وبتنحفظ بملف
 * بالتخزين الداخلي فبتضل بعد إغلاق التطبيق لحد ما المستخدم يمسحها.
 * كل تعديل على الحالة يصير على الـ main thread فقط.
 * كل مهمة لها "جيل" (gen): الإلغاء/الإعادة بيزيدوه، وأي عامل قديم بيتوقف وبتتجاهل رسائله.
 */
object Queue {

    enum class S { WAITING, RUNNING, DONE, FAILED, CANCELED, SKIPPED }

    class Task(val id: Int, val url: String, title: String, val quality: String, val batch: Int, val fmt: String = "m4a") {
        var title by mutableStateOf(title)
        var state by mutableStateOf(S.WAITING)
        var progress by mutableStateOf(0)
        var error by mutableStateOf<String?>(null)
        var note by mutableStateOf("")
        var size by mutableStateOf(0L)
        var uri by mutableStateOf<String?>(null)
        var artist by mutableStateOf("")
        var duration by mutableStateOf(0L)
        var cover by mutableStateOf<String?>(null)
        @Volatile var gen = 0
    }

    private val main = Handler(Looper.getMainLooper())
    val tasks = mutableStateListOf<Task>()
    /** يضبطه DownloadService لتحديث الإشعار: (نسبة التقدم، النص). */
    var notifier: ((Int, String) -> Unit)? = null

    private var appCtx: Context? = null
    private var loaded = false
    private var nextId = 1
    private var nextBatch = 1
    private var pool: ExecutorService? = null

    // عدّادات لإشعار الاكتمال (بيطلع مرة وحدة لما تفضى القائمة)
    private var doneSince = 0
    private var failedSince = 0
    private var lastDone: Task? = null
    private var poolSize = 0

    private fun active(): Int = tasks.count { it.state == S.WAITING || it.state == S.RUNNING }

    private fun pool(app: Context): ExecutorService {
        val want = Store.parallel(app)
        if (pool == null || (active() == 0 && poolSize != want)) {
            pool?.shutdown()
            pool = Executors.newFixedThreadPool(want)
            poolSize = want
        }
        return pool!!
    }

    // ---------- الحفظ والاسترجاع ----------

    private fun file(app: Context) = File(app.filesDir, "queue.json")

    /** يُنادى مرة عند فتح التطبيق: بيرجّع القائمة المحفوظة. المهام اللي كانت جارية بتصير "فاشلة" مع إمكانية الإعادة. */
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val app = ctx.applicationContext
        appCtx = app
        try {
            val f = file(app)
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(i)
                    val t = Task(
                        o.getInt("id"), o.getString("url"), o.optString("title"),
                        o.optString("quality", "high"), o.optInt("batch", 0), o.optString("fmt", "m4a")
                    )
                    val saved = S.valueOf(o.optString("state", "DONE"))
                    if (saved == S.WAITING || saved == S.RUNNING) {
                        t.state = S.FAILED
                        t.error = "توقّف التنزيل لأن التطبيق انغلق"
                    } else {
                        t.state = saved
                        t.error = o.optString("error").ifEmpty { null }
                        if (saved == S.DONE) t.progress = 100
                    }
                    t.note = o.optString("note")
                    t.size = o.optLong("size")
                    t.uri = o.optString("uri").ifEmpty { null }
                    t.artist = o.optString("artist")
                    t.duration = o.optLong("duration")
                    t.cover = o.optString("cover").ifEmpty { null }
                    tasks.add(t)
                    if (t.id >= nextId) nextId = t.id + 1
                    if (t.batch >= nextBatch) nextBatch = t.batch + 1
                } catch (e: Exception) {
                    // نتجاهل عنصر تالف ونكمل
                }
            }
        } catch (e: Exception) {
            // ملف تالف: نبدأ بقائمة فاضية
        }
    }

    private fun persist() {
        val app = appCtx ?: return
        try {
            val arr = JSONArray()
            for (t in tasks) {
                arr.put(JSONObject().apply {
                    put("id", t.id)
                    put("url", t.url)
                    put("title", t.title)
                    put("quality", t.quality)
                    put("batch", t.batch)
                    put("fmt", t.fmt)
                    put("state", t.state.name)
                    put("error", t.error ?: "")
                    put("note", t.note)
                    put("size", t.size)
                    put("uri", t.uri ?: "")
                    put("artist", t.artist)
                    put("duration", t.duration)
                    put("cover", t.cover ?: "")
                })
            }
            val f = file(app)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) f.writeText(arr.toString())
        } catch (e: Exception) {
            // الحفظ best-effort
        }
    }

    // ---------- العمليات ----------

    fun add(ctx: Context, items: List<Engine.Item>, fmt: String = "m4a") {
        val app = ctx.applicationContext
        appCtx = app
        val p = pool(app)
        val quality = Store.quality(app)
        val batch = nextBatch++
        val fresh = ArrayList<Task>()
        for (it in items) {
            val t = Task(nextId++, it.url, it.title ?: it.url, quality, batch, fmt)
            fresh.add(t)       // التحقق من المنزّل سابقاً بيصير قبل الإضافة (مع تأكيد المستخدم)
            tasks.add(t)
        }
        if (fresh.isNotEmpty()) {
            startService(app)
            for (t in fresh) submit(p, app, t)
        }
        changed()
    }

    fun cancel(t: Task) {
        if (t.state == S.WAITING || t.state == S.RUNNING) {
            t.gen++
            t.state = S.CANCELED
            changed()
        }
    }

    fun retry(ctx: Context, t: Task) {
        if (t.state == S.FAILED || t.state == S.CANCELED) {
            val app = ctx.applicationContext
            appCtx = app
            val p = pool(app)
            t.gen++
            t.state = S.WAITING
            t.progress = 0
            t.error = null
            t.note = ""
            startService(app)
            submit(p, app, t)
            changed()
        }
    }

    /** إلغاء كل المهام الجارية والمنتظرة. */
    fun cancelAll() {
        var any = false
        for (t in tasks.toList()) {
            if (t.state == S.WAITING || t.state == S.RUNNING) {
                t.gen++
                t.state = S.CANCELED
                any = true
            }
        }
        if (any) changed()
    }

    /** إعادة محاولة كل المهام الفاشلة دفعة وحدة. */
    fun retryFailed(ctx: Context) {
        val list = tasks.filter { it.state == S.FAILED }
        if (list.isEmpty()) return
        val app = ctx.applicationContext
        appCtx = app
        val p = pool(app)
        for (t in list) {
            t.gen++
            t.state = S.WAITING
            t.progress = 0
            t.error = null
            t.note = ""
        }
        startService(app)
        for (t in list) submit(p, app, t)
        changed()
    }

    /** إزالة مهمة منتهية من القائمة (ما بتحذف الملف من الجهاز). */
    fun remove(t: Task) {
        if (t.state != S.WAITING && t.state != S.RUNNING) {
            tasks.remove(t)
            dropCovers(listOf(t))
            changed()
        }
    }

    /** مسح المنتهي: المكتمل والملغى والمتخطّى. الفاشل بيضل لتقدر تعيده. */
    fun clearFinished() {
        val gone = tasks.filter { it.state == S.DONE || it.state == S.SKIPPED || it.state == S.CANCELED }
        tasks.removeAll(gone)
        dropCovers(gone)
        changed()
    }

    /** يحذف ملف الغلاف المصغّر إذا ما عاد في مهمة تستخدمه. */
    private fun dropCovers(removed: List<Task>) {
        for (r in removed) {
            val path = r.cover ?: continue
            if (tasks.none { it.cover == path }) try { File(path).delete() } catch (e: Exception) { }
        }
    }

    fun hasFinished(): Boolean =
        tasks.any { it.state == S.DONE || it.state == S.SKIPPED || it.state == S.CANCELED }

    private fun startService(app: Context) {
        app.startForegroundService(Intent(app, DownloadService::class.java))
    }

    private fun submit(p: ExecutorService, app: Context, t: Task) {
        val g = t.gen
        p.execute { run(app, t, g) }
    }

    private fun post(t: Task, g: Int, save: Boolean = true, block: Task.() -> Unit) {
        main.post {
            if (t.gen == g) {
                t.block()
                changed(save)
            }
        }
    }

    /** true لو الشبكة الحالية مسموحة (كل الشبكات، أو غير محدودة فقط لو "واي فاي فقط" مفعّل). */
    private fun networkAllowed(app: Context): Boolean {
        if (!Store.wifiOnly(app)) return true
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** ينتظر لحد ما تتوفر شبكة مسموحة. false لو انلغت المهمة أثناء الانتظار. */
    private fun waitForNetwork(app: Context, t: Task, g: Int): Boolean {
        var shown = false
        while (!networkAllowed(app)) {
            if (t.gen != g) return false
            if (!shown) {
                shown = true
                post(t, g, false) { note = "بانتظار الواي فاي…" }
            }
            try { Thread.sleep(3000) } catch (ie: InterruptedException) { return false }
        }
        if (shown) post(t, g, false) { note = "" }
        return t.gen == g
    }

    private fun run(app: Context, t: Task, g: Int) {
        if (t.gen != g) return
        if (!waitForNetwork(app, t, g)) return
        post(t, g) { state = S.RUNNING; progress = 0 }
        var attempt = 0
        while (true) {
            try {
                if (attempt > 0 && !waitForNetwork(app, t, g)) return
                val r = Engine.download(
                    app, t.url, t.fmt, t.quality,
                    onTitle = { n -> post(t, g) { title = n } },
                    onMeta = { a, d, c -> post(t, g) { artist = a; duration = d; cover = c } },
                    onProgress = { pct -> post(t, g, false) { progress = pct } },
                    isCanceled = { t.gen != g },
                )
                Store.markDone(app, Engine.archiveKey(t.url, t.fmt))
                post(t, g) {
                    state = S.DONE
                    progress = 100
                    note = r.note
                    size = r.bytes
                    uri = r.uri.toString()
                    doneSince++
                    lastDone = this
                }
                return
            } catch (e: Engine.Canceled) {
                return
            } catch (e: Throwable) {
                attempt++
                if (e is IOException && attempt <= 2 && t.gen == g) {
                    try { Thread.sleep(2000L * attempt) } catch (ie: InterruptedException) { return }
                    continue
                }
                post(t, g) { state = S.FAILED; error = Engine.explain(e); failedSince++ }
                return
            }
        }
    }

    private fun changed(save: Boolean = true) {
        if (save) persist()
        val act = tasks.filter { it.state == S.WAITING || it.state == S.RUNNING }
        if (act.isEmpty()) {
            appCtx?.let { it.stopService(Intent(it, DownloadService::class.java)) }
            if (doneSince + failedSince > 0) {
                appCtx?.let { notifyFinished(it) }
                doneSince = 0
                failedSince = 0
                lastDone = null
            }
        } else {
            val pct = act.sumOf { it.progress } / act.size
            val running = act.firstOrNull { it.state == S.RUNNING }
            notifier?.invoke(pct, "المتبقي ${act.size}" + (running?.let { " • ${it.title}" } ?: ""))
        }
    }

    /** إشعار واحد لما تخلص كل التنزيلات: لو مقطع واحد بيفتحه بالضغط، وإلا بيفتح التطبيق. */
    private fun notifyFinished(app: Context) {
        try {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel("done", "اكتمال التنزيل", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val done = doneSince
            val failed = failedSince
            val last = lastDone
            val lastUri = last?.uri
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

            val title: String
            val text: String
            val pi: PendingIntent
            if (done == 1 && failed == 0 && last != null && lastUri != null) {
                title = "اكتمل التنزيل"
                text = last.title
                val mime = when {
                    last.fmt.startsWith("mp4") -> "video/mp4"
                    last.fmt == "mp3" -> "audio/mpeg"
                    else -> "audio/mp4"
                }
                val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(lastUri), mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                pi = PendingIntent.getActivity(app, 2, view, flags)
            } else {
                title = when {
                    done == 0 -> "فشل التنزيل"
                    failed == 0 -> "اكتمل تنزيل $done مقطع"
                    else -> "اكتمل $done وفشل $failed"
                }
                text = if (done == 0) "$failed مقطع" else "اضغط لفتح التطبيق"
                pi = PendingIntent.getActivity(app, 3, Intent(app, MainActivity::class.java), flags)
            }
            nm.notify(
                2,
                Notification.Builder(app, "done")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: Exception) {
            // الإشعار best-effort
        }
    }
}
