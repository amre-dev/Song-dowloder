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

    fun isDone(c: Context, id: String): Boolean =
        p(c).getStringSet("done", emptySet())?.contains(id) == true

    @Synchronized
    fun markDone(c: Context, id: String) {
        val s = HashSet(p(c).getStringSet("done", emptySet()) ?: emptySet())
        s.add(id)
        p(c).edit().putStringSet("done", s).apply()
    }
}
