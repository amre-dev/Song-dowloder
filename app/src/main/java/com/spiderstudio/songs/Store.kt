package com.spiderstudio.songs

import android.content.Context

/** إعدادات بسيطة + سجل الأغاني المنزّلة (SharedPreferences). */
object Store {
    private fun p(c: Context) = c.getSharedPreferences("songs", Context.MODE_PRIVATE)

    fun quality(c: Context): String = p(c).getString("quality", "high") ?: "high"
    fun setQuality(c: Context, v: String) = p(c).edit().putString("quality", v).apply()

    fun parallel(c: Context): Int = p(c).getInt("parallel", 2).coerceIn(1, 3)
    fun setParallel(c: Context, v: Int) = p(c).edit().putInt("parallel", v).apply()

    fun skipDupes(c: Context): Boolean = p(c).getBoolean("skip", true)
    fun setSkipDupes(c: Context, v: Boolean) = p(c).edit().putBoolean("skip", v).apply()

    fun tags(c: Context): Boolean = p(c).getBoolean("tags", true)
    fun setTags(c: Context, v: Boolean) = p(c).edit().putBoolean("tags", v).apply()

    /** عند مشاركة رابط من يوتيوب: true = افتح ورقة الخيارات، false = نزّل صوت مباشرة. */
    fun shareOptions(c: Context): Boolean = p(c).getBoolean("share_options", false)
    fun setShareOptions(c: Context, v: Boolean) = p(c).edit().putBoolean("share_options", v).apply()

    /** صيغة زر "تنزيل صوت" السريع (ومشاركة الرابط المباشرة): m4a أو mp3. */
    fun quickFmt(c: Context): String = p(c).getString("quick_fmt", "m4a") ?: "m4a"
    fun setQuickFmt(c: Context, v: String) = p(c).edit().putString("quick_fmt", v).apply()

    fun searchHistory(c: Context): List<String> =
        (p(c).getString("search_history", "") ?: "").split("\n").filter { it.isNotBlank() }

    fun addSearch(c: Context, q: String) {
        val clean = q.replace("\n", " ").trim()
        if (clean.isEmpty()) return
        val list = (listOf(clean) + searchHistory(c).filter { !it.equals(clean, ignoreCase = true) }).take(12)
        p(c).edit().putString("search_history", list.joinToString("\n")).apply()
    }

    fun clearSearch(c: Context) = p(c).edit().remove("search_history").apply()

    fun isDone(c: Context, id: String): Boolean =
        p(c).getStringSet("done", emptySet())?.contains(id) == true

    fun clearDone(c: Context) = p(c).edit().remove("done").apply()

    @Synchronized
    fun markDone(c: Context, id: String) {
        val s = HashSet(p(c).getStringSet("done", emptySet()) ?: emptySet())
        s.add(id)
        p(c).edit().putStringSet("done", s).apply()
    }
}
