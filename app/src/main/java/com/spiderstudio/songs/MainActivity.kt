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
                    onOptions = ::openOptions,
                    onPick = ::pickFormat,
                    onPickPlaylist = ::pickPlaylist,
                    onPaste = ::pasteFromClipboard,
                    onOpen = ::openFile,
                    onSearch = ::runSearch,
                    onFilter = ::changeFilter,
                    onMoreResults = ::loadMoreResults,
                    onHitOpen = { if (it.isPlaylist) openPlaylistFor(it.url) else openOptionsFor(it.url) },
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
            openOptions()
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
            openPlaylistFor(url)
            return
        }
        val seq = ++analyzeSeq
        UiState.analysis = null
        UiState.sheetError = null
        UiState.sheetOpen = true
        Thread {
            try {
                val a = Engine.analyze(this, url)
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
        val s = Engine.SearchSession(q, SearchState.filter)
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

    /** اختيار صيغة من الورقة: لو نزّله المستخدم قبل (بنفس النوع) بنطلب تأكيد. */
    private fun pickFormat(fmt: String) {
        val a = UiState.analysis ?: return
        val video = fmt.startsWith("mp4")
        val prior = if (video) a.priorVideo else a.priorAudio
        val go = {
            requestNotificationPermissionIfNeeded()
            Queue.add(this, listOf(Engine.Item(a.url, a.title)), fmt)
            UiState.sheetOpen = false
            UiState.url = ""
            UiState.status = ""
            Toast.makeText(this, "أُضيف للتنزيلات", Toast.LENGTH_SHORT).show()
        }
        if (prior == null) {
            go()
        } else {
            UiState.confirm = Confirm(
                title = "موجود عندك مسبقاً",
                message = if (video) "هذا المقطع منزّل عندك قبل كفيديو ($prior). تنزّله مرة ثانية؟"
                else "هذا المقطع منزّل عندك قبل بصيغة صوتية ($prior). تنزّله مرة ثانية؟",
                positive = "نزّل مرة ثانية",
                onPositive = go,
            )
        }
    }

    private fun changeFilter(f: String) {
        if (SearchState.filter == f) return
        SearchState.filter = f
        if (SearchState.query.isNotBlank()) runSearch(SearchState.query)
    }

    // ---------- قوائم التشغيل ----------

    private var playlistSeq = 0

    private fun openPlaylistFor(url: String) {
        val seq = ++playlistSeq
        UiState.playlist = null
        UiState.playlistError = null
        UiState.selected.clear()
        UiState.playlistOpen = true
        Thread {
            try {
                val d = Engine.playlistDetails(url)
                runOnUiThread {
                    if (seq == playlistSeq && UiState.playlistOpen) {
                        UiState.playlist = d
                        UiState.selected.clear()
                        UiState.selected.addAll(d.items.map { it.url })   // كل المقاطع محددة افتراضياً
                    }
                }
            } catch (e: Throwable) {
                Log.e("Amrtune", "playlist failed", e)
                runOnUiThread { if (seq == playlistSeq && UiState.playlistOpen) UiState.playlistError = Engine.explain(e) }
            }
        }.start()
    }

    /** تنزيل المقاطع المحددة من القائمة بالصيغة المختارة، مع تنبيه لو بعضها منزّل قبل. */
    private fun pickPlaylist(fmt: String) {
        val d = UiState.playlist ?: return
        val chosen = d.items.filter { it.url in UiState.selected }
        if (chosen.isEmpty()) return
        val video = fmt.startsWith("mp4")
        Thread {
            val keys = Store.doneKeys(this)
            val audioNames = Engine.libraryNames(this, false)
            val videoNames = Engine.libraryNames(this, true)
            val dupUrls = chosen.filter {
                val info = Engine.dupInfo(keys, audioNames, videoNames, it.url, it.title)
                (if (video) info.video else info.audio) != null
            }.map { it.url }.toHashSet()
            runOnUiThread {
                if (dupUrls.isEmpty()) {
                    addPlaylistItems(chosen, fmt)
                } else {
                    val fresh = chosen.filter { it.url !in dupUrls }
                    val onNew: (() -> Unit)? = if (fresh.isNotEmpty()) ({ addPlaylistItems(fresh, fmt) }) else null
                    UiState.confirm = Confirm(
                        title = "بعض المقاطع موجودة مسبقاً",
                        message = "${dupUrls.size} من ${chosen.size} مقطع منزّلة عندك قبل " +
                            (if (video) "كفيديو." else "بصيغة صوتية (m4a أو mp3)."),
                        positive = "تنزيل الكل (${chosen.size})",
                        onPositive = { addPlaylistItems(chosen, fmt) },
                        neutral = if (fresh.isNotEmpty()) "تنزيل الجديدة فقط (${fresh.size})" else null,
                        onNeutral = onNew,
                    )
                }
            }
        }.start()
    }

    private fun addPlaylistItems(items: List<Engine.SearchHit>, fmt: String) {
        requestNotificationPermissionIfNeeded()
        Queue.add(this, items.map { Engine.Item(it.url, it.title) }, fmt)
        Store.setLastFmt(this, fmt)
        UiState.playlistOpen = false
        UiState.url = ""
        UiState.status = ""
        Toast.makeText(this, "أُضيف ${items.size} مقطع للتنزيلات", Toast.LENGTH_SHORT).show()
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
