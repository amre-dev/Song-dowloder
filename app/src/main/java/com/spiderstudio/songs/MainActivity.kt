package com.spiderstudio.songs

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** تجربة جدوى: رابط واحد -> ملف m4a بمجلد Music/SongsDownloader. */
class MainActivity : Activity() {

    private lateinit var input: EditText
    private lateinit var btn: Button
    private lateinit var bar: ProgressBar
    private lateinit var logView: TextView
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        input = EditText(this).apply {
            hint = "الصق رابط يوتيوب هنا"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        btn = Button(this).apply {
            text = "⬇ تنزيل m4a"
            setOnClickListener { start() }
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        logView = TextView(this).apply { textSize = 15f }

        root.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(btn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val url = Engine.findUrl(i.getStringExtra(Intent.EXTRA_TEXT)) ?: return
            input.setText(url)
            start()
        }
    }

    private fun say(s: String) {
        logView.append(s + "\n\n")
    }

    private fun start() {
        if (busy) return
        val url = Engine.findUrl(input.text.toString())
        if (url == null) {
            say("❌ الصق رابط صالح (يبدأ بـ http)")
            return
        }
        busy = true
        btn.isEnabled = false
        bar.progress = 0
        logView.text = ""

        Thread {
            try {
                val r = Engine.downloadAudio(
                    applicationContext, url,
                    onStatus = { s -> ui.post { say(s) } },
                    onProgress = { p -> ui.post { bar.progress = p } },
                )
                ui.post { say("✅ تم (${r.bytes / 1024} KB)\n📁 Music/SongsDownloader") }
            } catch (e: Throwable) {
                Log.e("SongsDownloader", "download failed", e)
                val cause = e.cause?.let { "\n↳ ${it.javaClass.simpleName}: ${it.message}" } ?: ""
                ui.post { say("❌ ${e.javaClass.simpleName}: ${e.message}$cause") }
            } finally {
                ui.post { busy = false; btn.isEnabled = true }
            }
        }.start()
    }
}
