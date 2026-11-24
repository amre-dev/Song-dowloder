package com.spiderstudio.songs

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        Queue.load(this)
        setContent {
            AmrtuneTheme {
                AppRoot(onAdd = ::addFromInput, onPaste = ::pasteFromClipboard, onOpen = ::openFile)
            }
        }
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // لصق تلقائي لرابط يوتيوب منسوخ لما يكون الحقل فاضي
        if (hasFocus && UiState.url.isEmpty()) {
            Engine.findUrl(readClipboard())?.takeIf { it.contains("youtu") }?.let { UiState.url = it }
        }
    }

    private fun readClipboard(): String? {
        val cm = getSystemService(ClipboardManager::class.java)
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    }

    private fun pasteFromClipboard() {
        val text = readClipboard()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "الحافظة فاضية", Toast.LENGTH_SHORT).show()
        } else {
            UiState.url = Engine.findUrl(text) ?: text.trim()
        }
    }

    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val url = Engine.findUrl(i.getStringExtra(Intent.EXTRA_TEXT)) ?: return
            UiState.url = url
            addFromInput()
        }
    }

    private fun addFromInput() {
        val url = Engine.findUrl(UiState.url)
        if (url == null) {
            UiState.status = "❌ الصق رابط صالح (يبدأ بـ http)"
            return
        }
        requestNotificationPermissionIfNeeded()
        UiState.status = "🔎 جاري فحص الرابط…"
        Thread {
            try {
                val items = Engine.resolve(url)
                runOnUiThread {
                    Queue.add(this, items)
                    UiState.status = if (items.size > 1) "أُضيفت ${items.size} أغنية للقائمة" else ""
                    UiState.url = ""
                }
            } catch (e: Throwable) {
                Log.e("Amrtune", "resolve failed", e)
                runOnUiThread { UiState.status = "❌ " + Engine.explain(e) }
            }
        }.start()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun openFile(t: Queue.Task) {
        val u = t.uri ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(u), "audio/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "تعذّر فتح الملف (ممكن انحذف من الجهاز)", Toast.LENGTH_SHORT).show()
        }
    }
}
