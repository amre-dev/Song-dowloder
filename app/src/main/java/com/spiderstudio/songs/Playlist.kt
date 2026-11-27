package com.spiderstudio.songs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val PL_FORMATS = listOf(
    "m4a" to "m4a",
    "mp3" to "mp3",
    "mp4:360" to "MP4 360p",
    "mp4:480" to "MP4 480p",
    "mp4:720" to "MP4 720p",
    "mp4:1080" to "MP4 1080p",
)

/** ورقة اختيار أغاني قائمة تشغيل + الصيغة، ثم تنزيل المحدد. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistSheet(onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var fmt by remember { mutableStateOf(Store.lastFmt(ctx)) }
    val d = UiState.playlist
    val err = UiState.playlistError

    ModalBottomSheet(
        onDismissRequest = { UiState.playlistOpen = false },
        sheetState = state,
        containerColor = C.Card,
        contentColor = C.Text,
    ) {
        val box = if (d != null) Modifier.fillMaxHeight(0.92f) else Modifier
        Column(box.fillMaxWidth().padding(horizontal = 16.dp)) {
            when {
                err != null -> {
                    Text("❌ $err", color = C.Red, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
                    TextButton(onClick = { UiState.playlistOpen = false }) { Text("إغلاق", color = C.Teal) }
                    Spacer(Modifier.height(24.dp))
                }
                d == null -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = C.Teal, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("جاري تحميل القائمة…", color = C.Muted, fontSize = 14.sp)
                }
                else -> {
                    // الترويسة
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(C.Teal.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.List, contentDescription = null, tint = C.Teal) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(d.title.ifEmpty { "قائمة تشغيل" }, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val meta = listOfNotNull(
                                d.owner.ifEmpty { null },
                                "${d.items.size} مقطع" + if (d.total > d.items.size) " من ${d.total}" else "",
                            ).joinToString(" • ")
                            Text(meta, color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    // الصيغة
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((key, label) in PL_FORMATS) {
                            val sel = fmt == key
                            Box(
                                Modifier.clip(RoundedCornerShape(20.dp))
                                    .background(if (sel) C.Teal else C.Card2)
                                    .clickable { fmt = key }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                Text(label, color = if (sel) C.OnTeal else C.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (fmt.startsWith("mp4")) {
                        Text("لو الدقة المختارة مو متوفرة لفيديو، بينزل بأقرب دقة أقل", color = C.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    }

                    // تحديد الكل
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = {
                            if (UiState.selected.size == d.items.size) {
                                UiState.selected.clear()
                            } else {
                                UiState.selected.clear()
                                UiState.selected.addAll(d.items.map { it.url })
                            }
                        }) {
                            Text(if (UiState.selected.size == d.items.size) "إلغاء التحديد" else "تحديد الكل", color = C.Teal, fontSize = 13.sp)
                        }
                        Text("المحدد: ${UiState.selected.size} من ${d.items.size}", color = C.Muted, fontSize = 12.sp)
                    }

                    // القائمة
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(d.items, key = { it.url }) { hit -> PlaylistItemRow(hit) }
                    }

                    Button(
                        onClick = { onPick(fmt) },
                        enabled = UiState.selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp).height(52.dp),
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = C.Teal, contentColor = C.OnTeal,
                            disabledContainerColor = C.Card2, disabledContentColor = C.Muted,
                        ),
                    ) {
                        Icon(AppIcons.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("تنزيل المحدد (${UiState.selected.size})", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistItemRow(hit: Engine.SearchHit) {
    val checked = hit.url in UiState.selected
    val toggle = { if (checked) UiState.selected.remove(hit.url) else UiState.selected.add(hit.url); Unit }
    val id = remember(hit.url) { Engine.videoId(hit.url) }
    val bmp by produceState<ImageBitmap?>(initialValue = Thumbs.cached(id), id) {
        if (value == null) value = withContext(Dispatchers.IO) { Thumbs.fetch(id) }
    }

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { toggle() }.padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { toggle() },
            colors = CheckboxDefaults.colors(checkedColor = C.Teal, checkmarkColor = C.OnTeal, uncheckedColor = C.Muted),
        )
        Box(Modifier.width(88.dp).height(50.dp).clip(RoundedCornerShape(8.dp)).background(C.Card2)) {
            val b = bmp
            if (b != null) {
                Image(bitmap = b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (hit.durationSec > 0) {
                Text(
                    fmtDur(hit.durationSec), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Color(0xCC000000)).padding(horizontal = 4.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(hit.title, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (hit.channel.isNotEmpty()) Text(hit.channel, color = C.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
