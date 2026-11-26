package com.spiderstudio.songs

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** حالة تبويب البحث (بتعيش طول عمر التطبيق). */
object SearchState {
    var query by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var searched by mutableStateOf(false)
    var filter by mutableStateOf("videos")        // videos | playlists
    var hasMore by mutableStateOf(false)
    var history by mutableStateOf<List<String>>(emptyList())
    val results = mutableStateListOf<Engine.SearchHit>()
}

/** صور مصغّرة لنتائج البحث (من i.ytimg.com) مع كاش بالذاكرة. */
object Thumbs {
    private val cache = android.util.LruCache<String, ImageBitmap>(120)
    private val missing = java.util.Collections.synchronizedSet(HashSet<String>())

    fun cached(id: String): ImageBitmap? = cache.get(id)

    fun fetch(id: String): ImageBitmap? {
        cache.get(id)?.let { return it }
        if (id in missing) return null
        return try {
            val req = okhttp3.Request.Builder()
                .url("https://i.ytimg.com/vi/$id/mqdefault.jpg")
                .header("User-Agent", AppDownloader.USER_AGENT)
                .build()
            val bytes = AppDownloader.instance.client.newCall(req).execute().use {
                if (it.isSuccessful) it.body?.bytes() else null
            }
            val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
            if (bmp != null) cache.put(id, bmp) else missing.add(id)
            bmp
        } catch (e: Exception) {
            null
        }
    }
}

internal fun fmtDur(sec: Long): String =
    if (sec < 0) "LIVE"
    else if (sec >= 3600) String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
    else String.format(java.util.Locale.getDefault(), "%d:%02d", sec / 60, sec % 60)

@Composable
fun SearchScreen(
    onSearch: (String) -> Unit,
    onMore: () -> Unit,
    onOpen: (Engine.SearchHit) -> Unit,
    onFilter: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxSize()) {
        TextField(
            value = SearchState.query,
            onValueChange = { SearchState.query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            singleLine = true,
            shape = RoundedCornerShape(26.dp),
            textStyle = TextStyle(color = C.Text, fontSize = 15.sp, textDirection = TextDirection.Content),
            placeholder = { Text("ابحث بيوتيوب أو الصق رابط…", color = C.Muted, fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = C.Muted) },
            trailingIcon = {
                if (SearchState.query.isNotEmpty()) {
                    IconButton(onClick = { SearchState.query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "مسح", tint = C.Muted)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focus.clearFocus()
                onSearch(SearchState.query)
            }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = C.Card2,
                unfocusedContainerColor = C.Card2,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = C.Teal,
            ),
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterPill("فيديوهات", SearchState.filter == "videos") { onFilter("videos") }
            FilterPill("قوائم تشغيل", SearchState.filter == "playlists") { onFilter("playlists") }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                SearchState.results.isNotEmpty() -> {
                    items(SearchState.results, key = { it.url }) { hit -> ResultRow(hit, onOpen) }
                    if (SearchState.hasMore) {
                        item(key = "more") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = C.Teal, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
                            }
                            // لما يوصل المستخدم لآخر القائمة بيبدأ تحميل الصفحة التالية
                            LaunchedEffect(SearchState.results.size) { onMore() }
                        }
                    }
                }
                SearchState.loading -> item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = C.Teal, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                    }
                }
                SearchState.error != null -> item {
                    Text("❌ ${SearchState.error}", color = C.Red, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp))
                }
                SearchState.searched -> item {
                    Text("ما في نتائج لهالبحث", color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp))
                }
                else -> {
                    if (SearchState.history.isNotEmpty()) {
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("عمليات البحث الأخيرة", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                TextButton(onClick = onClearHistory) { Text("مسح", color = C.Teal, fontSize = 13.sp) }
                            }
                        }
                        items(SearchState.history, key = { "h_$it" }) { q ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Card)
                                    .clickable { SearchState.query = q; onSearch(q) }.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, tint = C.Muted, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(q, color = C.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    } else {
                        item {
                            Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Search, contentDescription = null, tint = C.Muted, modifier = Modifier.size(44.dp))
                                Text("ابحث عن أي أغنية أو فيديو", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text("اضغط النتيجة لاختيار الصيغة، أو زر التنزيل للتنزيل السريع", color = C.Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (selected) C.Teal else C.Card2)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (selected) C.OnTeal else C.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ResultRow(hit: Engine.SearchHit, onOpen: (Engine.SearchHit) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val id = remember(hit.url) { Engine.videoId(hit.url) }
    val bmp by produceState<ImageBitmap?>(initialValue = Thumbs.cached(id), id) {
        if (!hit.isPlaylist && value == null) value = withContext(Dispatchers.IO) { Thumbs.fetch(id) }
    }

    Row(
        Modifier.fillMaxWidth().clip(shape).background(C.Card).border(1.dp, C.Border, shape)
            .clickable { onOpen(hit) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(10.dp)).background(C.Card2)) {
            val b = bmp
            if (hit.isPlaylist) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.List, contentDescription = null, tint = C.Teal, modifier = Modifier.size(26.dp))
                    if (hit.count > 0) Text("${hit.count} أغنية", color = C.Muted, fontSize = 11.sp)
                }
            } else if (b != null) {
                Image(bitmap = b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(AppIcons.MusicNote, contentDescription = null, tint = C.Muted, modifier = Modifier.align(Alignment.Center).size(24.dp))
            }
            if (!hit.isPlaylist && hit.durationSec != 0L) {
                Text(
                    fmtDur(hit.durationSec), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                        .clip(RoundedCornerShape(5.dp)).background(Color(0xCC000000)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(hit.title, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (hit.channel.isNotEmpty()) {
                Text(hit.channel, color = C.Teal, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(AppIcons.Download, contentDescription = null, tint = C.Teal, modifier = Modifier.padding(horizontal = 8.dp).size(20.dp))
    }
}
