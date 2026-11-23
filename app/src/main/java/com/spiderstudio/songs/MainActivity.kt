package com.spiderstudio.songs

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** واجهة اختبار مؤقتة للمحرك (الواجهة الحقيقية بنبنيها لاحقاً). */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var qualityBtn: Button
    private lateinit var parallelBtn: Button
    private val rows = HashMap<Int, Row>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        input = EditText(this).apply {
            hint = "الصق رابط فيديو أو قائمة تشغيل"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        val addBtn = Button(this).apply {
            text = "➕ إضافة للتنزيل"
            setOnClickListener { addFromInput() }
        }

        qualityBtn = Button(this).apply {
            setOnClickListener {
                Store.setQuality(context, if (Store.quality(context) == "high") "low" else "high")
                syncSettings()
            }
        }
        parallelBtn = Button(this).apply {
            setOnClickListener {
                Store.setParallel(context, Store.parallel(context) % 3 + 1)
                syncSettings()
            }
        }
        val skipBox = CheckBox(this).apply {
            text = "تخطي المنزّل سابقاً"
            isChecked = Store.skipDupes(context)
            setOnCheckedChangeListener { _, v -> Store.setSkipDupes(context, v) }
        }
        val clearBtn = Button(this).apply {
            text = "🧹 مسح المنتهي"
            setOnClickListener { Queue.clearFinished() }
        }
        val settingsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(qualityBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(parallelBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }

        status = TextView(this).apply { textSize = 14f }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(addBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(settingsRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(skipBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(clearBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        syncSettings()
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onStart() {
        super.onStart()
        Queue.onChange = { refresh() }
        refresh()
    }

    override fun onStop() {
        Queue.onChange = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && input.text.isEmpty()) {
            val cm = getSystemService(ClipboardManager::class.java)
            val txt = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            Engine.findUrl(txt)?.takeIf { it.contains("youtu") }?.let { input.setText(it) }
        }
    }

    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val url = Engine.findUrl(i.getStringExtra(Intent.EXTRA_TEXT)) ?: return
            input.setText(url)
            addFromInput()
        }
    }

    private fun syncSettings() {
        qualityBtn.text = "الجودة: " + if (Store.quality(this) == "high") "أعلى" else "أقل"
        parallelBtn.text = "التوازي: ${Store.parallel(this)}"
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun addFromInput() {
        val url = Engine.findUrl(input.text.toString())
        if (url == null) {
            status.text = "❌ الصق رابط صالح (يبدأ بـ http)"
            return
        }
        askNotificationPermission()
        status.text = "🔎 جاري فحص الرابط..."
        Thread {
            try {
                val items = Engine.resolve(url)
                ui.post {
                    Queue.add(this, items)
                    status.text = if (items.size > 1) "أُضيفت ${items.size} أغنية" else ""
                    input.text.clear()
                }
            } catch (e: Throwable) {
                Log.e("SongsDownloader", "resolve failed", e)
                ui.post { status.text = "❌ " + Engine.explain(e) }
            }
        }.start()
    }

    // ---- القائمة الحية: كل مهمة صف يتحدّث مكانه ----

    private fun refresh() {
        val alive = Queue.tasks.map { it.id }.toSet()
        for (id in rows.keys.filter { it !in alive }) {
            rows.remove(id)?.let { list.removeView(it.view) }
        }
        for (t in Queue.tasks) {
            val r = rows.getOrPut(t.id) { Row(t).also { list.addView(it.view) } }
            r.update()
        }
    }

    private inner class Row(val t: Queue.Task) {
        val title = TextView(this@MainActivity).apply { textSize = 15f }
        val bar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val btn = Button(this@MainActivity)
        val view = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(title)
            addView(bar)
            addView(btn)
        }

        fun update() {
            val icon = when (t.state) {
                Queue.S.WAITING -> "⏳"
                Queue.S.RUNNING -> "⬇"
                Queue.S.DONE -> "✅"
                Queue.S.FAILED -> "❌"
                Queue.S.CANCELED -> "⛔"
                Queue.S.SKIPPED -> "⏭"
            }
            title.text = "$icon ${t.title}" + (t.error?.let { "\n$it" } ?: "")
            bar.progress = t.progress
            val busy = t.state == Queue.S.WAITING || t.state == Queue.S.RUNNING
            bar.visibility = if (busy) View.VISIBLE else View.GONE
            when {
                busy -> {
                    btn.visibility = View.VISIBLE
                    btn.text = "إلغاء"
                    btn.setOnClickListener { Queue.cancel(t) }
                }
                t.state == Queue.S.FAILED || t.state == Queue.S.CANCELED -> {
                    btn.visibility = View.VISIBLE
                    btn.text = "إعادة المحاولة"
                    btn.setOnClickListener { Queue.retry(this@MainActivity, t) }
                }
                else -> btn.visibility = View.GONE
            }
        }
    }
}
