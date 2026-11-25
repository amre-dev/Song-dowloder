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
        SearchState.history = Store.searchHistory(this)
        setContent {
            AmrtuneTheme {
                AppRoot(
                    onAdd = ::addFromInput,
                    onOptions = ::openOptions,
                    onPick = ::pickFormat,
                    onPaste = ::pasteFromClipboard,
                    onOpen = ::openFile,
                    onSearch = ::runSearch,
                    onMoreResults = ::loadMoreResults,
                    onHitOpen = { openOptionsFor(it.url) },
                    onHitQuick = ::quickDownload,
                    onClearSearch = { Store.clearSearch(this); SearchState.history = emptyList() },
                )
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
            if (Store.shareOptions(this)) openOptions() else addFromInput()
        }
    }

    private var analyzeSeq = 0

    /** يفتح ورقة الخيارات للرابط اللي بحقل الإدخال. */
    private fun openOptions() {
        val url = Engine.findUrl(UiState.url)
        if (url == null) {
            UiState.status = "❌ الصق رابط صالح (يبدأ بـ http)"
            return
        }
        openOptionsFor(url)
    }

    /** يحلّل الرابط ويعرض الصيغ والدقات المتاحة بورقة الخيارات. */
    private fun openOptionsFor(url: String) {
        if (Engine.isPlaylist(url)) {
            UiState.url = url
            UiState.status = "قوائم التشغيل بتنزل صوت حالياً"
            addFromInput()
            return
        }
        val seq = ++analyzeSeq
        UiState.analysis = null
        UiState.sheetError = null
        UiState.sheetOpen = true
        Thread {
            try {
                val a = Engine.analyze(url)
                runOnUiThread { if (seq == analyzeSeq && UiState.sheetOpen) UiState.analysis = a }
            } catch (e: Throwable) {
                Log.e("Amrtune", "analyze failed", e)
                runOnUiThread { if (seq == analyzeSeq && UiState.sheetOpen) UiState.sheetError = Engine.explain(e) }
            }
        }.start()
    }

    // ---------- البحث ----------

    private var searchSeq = 0
    private var session: Engine.SearchSession? = null

    private fun runSearch(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) return
        // رابط يوتيوب ملصوق بحقل البحث: نفتح خياراته مباشرة
        Engine.findUrl(q)?.takeIf { it.contains("youtu") }?.let { openOptionsFor(it); return }

        SearchState.query = q
        Store.addSearch(this, q)
        SearchState.history = Store.searchHistory(this)
        val seq = ++searchSeq
        val s = Engine.SearchSession(q)
        session = s
        SearchState.results.clear()
        SearchState.error = null
        SearchState.hasMore = false
        SearchState.searched = true
        SearchState.loading = true
        Thread {
            try {
                val hits = Engine.searchFirst(s)
                runOnUiThread {
                    if (seq == searchSeq) {
                        SearchState.results.addAll(hits)
                        SearchState.hasMore = s.hasMore
                        SearchState.loading = false
                    }
                }
            } catch (e: Throwable) {
                Log.e("Amrtune", "search failed", e)
                runOnUiThread {
                    if (seq == searchSeq) {
                        SearchState.error = Engine.explain(e)
                        SearchState.loading = false
                    }
                }
            }
        }.start()
    }

    private fun loadMoreResults() {
        val s = session ?: return
        if (SearchState.loading || !s.hasMore) return
        val seq = searchSeq
        SearchState.loading = true
        Thread {
            try {
                val hits = Engine.searchMore(s)
                runOnUiThread {
                    if (seq == searchSeq) {
                        val have = SearchState.results.map { it.url }.toHashSet()
                        SearchState.results.addAll(hits.filter { it.url !in have })
                        SearchState.hasMore = s.hasMore
                        SearchState.loading = false
                    }
                }
            } catch (e: Throwable) {
                Log.e("Amrtune", "search more failed", e)
                runOnUiThread {
                    if (seq == searchSeq) {
                        SearchState.hasMore = false
                        SearchState.loading = false
                    }
                }
            }
        }.start()
    }

    /** زر التنزيل السريع بنتيجة البحث: بالصيغة الافتراضية للتنزيل السريع. */
    private fun quickDownload(hit: Engine.SearchHit) {
        requestNotificationPermissionIfNeeded()
        Queue.add(this, listOf(Engine.Item(hit.url, hit.title)), Store.quickFmt(this))
        Toast.makeText(this, "أُضيفت للتنزيلات", Toast.LENGTH_SHORT).show()
    }

    /** اختيار صيغة من الورقة: يضيف المهمة بالصيغة المطلوبة. */
    private fun pickFormat(fmt: String) {
        val a = UiState.analysis ?: return
        requestNotificationPermissionIfNeeded()
        Queue.add(this, listOf(Engine.Item(a.url, a.title)), fmt)
        UiState.sheetOpen = false
        UiState.url = ""
        UiState.status = ""
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
                    Queue.add(this, items, Store.quickFmt(this))
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
                    .setDataAndType(
                        Uri.parse(u),
                        when {
                            t.fmt.startsWith("mp4") -> "video/mp4"
                            t.fmt == "mp3" -> "audio/mpeg"
                            else -> "audio/mp4"
                        },
                    )
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "تعذّر فتح الملف (ممكن انحذف من الجهاز)", Toast.LENGTH_SHORT).show()
        }
    }
}
