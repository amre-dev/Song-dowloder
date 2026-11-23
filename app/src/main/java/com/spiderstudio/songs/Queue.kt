package com.spiderstudio.songs

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * قائمة التنزيل (بذاكرة التطبيق). كل تعديل على الحالة يصير على الـ main thread فقط.
 * كل مهمة لها "جيل" (gen): الإلغاء/الإعادة بيزيدوه، وأي عامل قديم بيتوقف وبتتجاهل رسائله.
 */
object Queue {

    enum class S { WAITING, RUNNING, DONE, FAILED, CANCELED, SKIPPED }

    class Task(val id: Int, val url: String, var title: String, val quality: String) {
        var state = S.WAITING
        var progress = 0
        var error: String? = null
        var note = ""
        @Volatile var gen = 0
    }

    private val main = Handler(Looper.getMainLooper())
    val tasks = ArrayList<Task>()
    var onChange: (() -> Unit)? = null
    /** يضبطه DownloadService لتحديث الإشعار: (نسبة التقدم، النص). */
    var notifier: ((Int, String) -> Unit)? = null

    private var appCtx: Context? = null
    private var nextId = 1
    private var pool: ExecutorService? = null
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

    fun add(ctx: Context, items: List<Engine.Item>) {
        val app = ctx.applicationContext
        appCtx = app
        val p = pool(app)
        val quality = Store.quality(app)
        val skip = Store.skipDupes(app)
        val fresh = ArrayList<Task>()
        for (it in items) {
            val t = Task(nextId++, it.url, it.title ?: it.url, quality)
            if (skip && Store.isDone(app, Engine.videoId(it.url))) {
                t.state = S.SKIPPED
                t.error = "نُزّلت سابقاً"
            } else {
                fresh.add(t)
            }
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

    fun clearFinished() {
        tasks.removeAll { it.state == S.DONE || it.state == S.SKIPPED || it.state == S.CANCELED }
        changed()
    }

    private fun startService(app: Context) {
        app.startForegroundService(Intent(app, DownloadService::class.java))
    }

    private fun submit(p: ExecutorService, app: Context, t: Task) {
        val g = t.gen
        p.execute { run(app, t, g) }
    }

    private fun post(t: Task, g: Int, block: Task.() -> Unit) {
        main.post {
            if (t.gen == g) {
                t.block()
                changed()
            }
        }
    }

    private fun run(app: Context, t: Task, g: Int) {
        if (t.gen != g) return
        post(t, g) { state = S.RUNNING; progress = 0 }
        var attempt = 0
        while (true) {
            try {
                val r = Engine.download(
                    app, t.url, t.quality,
                    onTitle = { n -> post(t, g) { title = n } },
                    onProgress = { p -> post(t, g) { progress = p } },
                    isCanceled = { t.gen != g },
                )
                Store.markDone(app, Engine.videoId(t.url))
                post(t, g) { state = S.DONE; progress = 100; note = r.note }
                return
            } catch (e: Engine.Canceled) {
                return
            } catch (e: Throwable) {
                attempt++
                if (e is IOException && attempt <= 2 && t.gen == g) {
                    try { Thread.sleep(2000L * attempt) } catch (ie: InterruptedException) { return }
                    continue
                }
                post(t, g) { state = S.FAILED; error = Engine.explain(e) }
                return
            }
        }
    }

    private fun changed() {
        onChange?.invoke()
        val act = tasks.filter { it.state == S.WAITING || it.state == S.RUNNING }
        if (act.isEmpty()) {
            appCtx?.let { it.stopService(Intent(it, DownloadService::class.java)) }
        } else {
            val pct = act.sumOf { it.progress } / act.size
            val running = act.firstOrNull { it.state == S.RUNNING }
            notifier?.invoke(pct, "${act.size} بالانتظار" + (running?.let { " • ${it.title}" } ?: ""))
        }
    }
}
