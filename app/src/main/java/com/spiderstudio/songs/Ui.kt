package com.spiderstudio.songs

import android.content.Context
import android.graphics.BitmapFactory
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** حالة حقل الرابط ورسالة الحالة (تعيش طول عمر التطبيق). */
object UiState {
    var url by mutableStateOf("")
    var status by mutableStateOf("")

    // ورقة الخيارات (الصيغ المتاحة للفيديو)
    var sheetOpen by mutableStateOf(false)
    var sheetError by mutableStateOf<String?>(null)
    var analysis by mutableStateOf<Engine.Analysis?>(null)
}

@Composable
fun AppRoot(
    onAdd: () -> Unit,
    onOptions: () -> Unit,
    onPick: (String) -> Unit,
    onPaste: () -> Unit,
    onOpen: (Queue.Task) -> Unit,
    onSearch: (String) -> Unit,
    onMoreResults: () -> Unit,
    onHitOpen: (Engine.SearchHit) -> Unit,
    onHitQuick: (Engine.SearchHit) -> Unit,
    onClearSearch: () -> Unit,
) {
    // الواجهة عربية: نفرض RTL بغض النظر عن لغة الجهاز
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        Scaffold(
            containerColor = C.Bg,
            bottomBar = { BottomBar(tab) { tab = it } },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> HomeScreen(onAdd, onOptions, onPaste, onOpen)
                    1 -> SearchScreen(onSearch, onMoreResults, onHitOpen, onHitQuick, onClearSearch)
                    else -> SettingsScreen()
                }
            }
        }
        if (UiState.sheetOpen) OptionsSheet(onPick)
    }
}

@Composable
private fun BottomBar(tab: Int, onTab: (Int) -> Unit) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = C.Teal,
        selectedTextColor = C.Teal,
        indicatorColor = C.Teal.copy(alpha = 0.15f),
        unselectedIconColor = C.Muted,
        unselectedTextColor = C.Muted,
    )
    NavigationBar(containerColor = C.Card) {
        NavigationBarItem(
            selected = tab == 0, onClick = { onTab(0) }, colors = colors,
            icon = { Icon(AppIcons.Download, contentDescription = null) },
            label = { Text("التنزيلات") },
        )
        NavigationBarItem(
            selected = tab == 1, onClick = { onTab(1) }, colors = colors,
            icon = { Icon(Icons.Default.Search, contentDescription = null) },
            label = { Text("بحث") },
        )
        NavigationBarItem(
            selected = tab == 2, onClick = { onTab(2) }, colors = colors,
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text("الإعدادات") },
        )
    }
}

// ======================= الشاشة الرئيسية =======================

@Composable
private fun HomeScreen(onAdd: () -> Unit, onOptions: () -> Unit, onPaste: () -> Unit, onOpen: (Queue.Task) -> Unit) {
    val sorted = Queue.tasks.sortedWith(compareByDescending<Queue.Task> { it.batch }.thenBy { it.id })

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Header() }
        item { LinkCard(onAdd, onOptions, onPaste) }
        item { SectionHeader() }
        if (sorted.isEmpty()) {
            item { EmptyState() }
        } else {
            items(sorted, key = { it.id }) { t -> TaskCard(t, onOpen) }
        }
    }
}

@Composable
private fun Header() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.ic_logo_mark), contentDescription = null, modifier = Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            buildAnnotatedString {
                append("Amr")
                withStyle(SpanStyle(color = C.Teal)) { append("tune") }
            },
            color = C.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun LinkCard(onAdd: () -> Unit, onOptions: () -> Unit, onPaste: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(C.Card).border(1.dp, C.Border, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextField(
            value = UiState.url,
            onValueChange = { UiState.url = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            textStyle = TextStyle(color = C.Text, fontSize = 15.sp, textDirection = TextDirection.Content),
            placeholder = { Text("ألصق رابط الأغنية أو قائمة التشغيل…", color = C.Muted, fontSize = 14.sp) },
            leadingIcon = { Icon(AppIcons.Link, contentDescription = null, tint = C.Muted) },
            trailingIcon = {
                if (UiState.url.isEmpty()) {
                    IconButton(onClick = onPaste) { Icon(AppIcons.Paste, contentDescription = "لصق", tint = C.Teal) }
                } else {
                    IconButton(onClick = { UiState.url = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "مسح", tint = C.Muted)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onAdd() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = C.Card2,
                unfocusedContainerColor = C.Card2,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                cursorColor = C.Teal,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onAdd,
                enabled = UiState.url.isNotBlank(),
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(26.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = C.Teal,
                    contentColor = C.OnTeal,
                    disabledContainerColor = C.Card2,
                    disabledContentColor = C.Muted,
                ),
            ) {
                Icon(AppIcons.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("تنزيل صوت", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            OutlinedButton(
                onClick = onOptions,
                enabled = UiState.url.isNotBlank(),
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(26.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                border = BorderStroke(1.dp, if (UiState.url.isNotBlank()) C.Teal else C.Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = C.Teal, disabledContentColor = C.Muted),
            ) {
                Icon(AppIcons.Video, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("فيديو وخيارات", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
        if (UiState.status.isNotEmpty()) {
            Text(
                UiState.status,
                color = if (UiState.status.startsWith("❌")) C.Red else C.Muted,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun SectionHeader() {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("التنزيلات", color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (Queue.tasks.isNotEmpty() && Queue.hasFinished()) {
            TextButton(onClick = { Queue.clearFinished() }) {
                Text("مسح المنتهي", color = C.Teal, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun EmptyState() {
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(C.Card).border(1.dp, C.Border, shape).padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(AppIcons.MusicNote, contentDescription = null, tint = C.Muted, modifier = Modifier.size(44.dp))
        Text("لا توجد تنزيلات حالياً", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("ابدأ بتنزيل أغنيتك الأولى الآن!", color = C.Muted, fontSize = 13.sp)
    }
}

@Composable
private fun TaskCard(t: Queue.Task, onOpen: (Queue.Task) -> Unit) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(18.dp)
    val active = t.state == Queue.S.WAITING || t.state == Queue.S.RUNNING
    val canOpen = t.state == Queue.S.DONE && t.uri != null

    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(C.Card)
            .border(1.dp, C.Border, shape)
            .clickable(enabled = canOpen) { onOpen(t) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(t)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                t.title, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val metaLine = listOfNotNull(
                t.artist.ifEmpty { null },
                when {
                    t.fmt.startsWith("mp4:") -> "MP4 ${t.fmt.substringAfter(':')}p"
                    t.fmt == "mp3" -> "MP3"
                    else -> null
                },
            ).joinToString(" • ")
            if (metaLine.isNotEmpty()) {
                Text(metaLine, color = C.Teal, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                subtitle(ctx, t),
                color = if (t.state == Queue.S.FAILED) C.Red else C.Muted,
                fontSize = 12.sp,
            )
            // نعرض الملاحظة فقط لو في مشكلة (مثلاً فشل تضمين الوسوم)
            if (t.state == Queue.S.DONE && t.note.startsWith("⚠")) {
                Text(t.note, color = C.Red, fontSize = 11.sp)
            }
            if (active) {
                Spacer(Modifier.height(3.dp))
                val bar = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape)
                if (t.state == Queue.S.RUNNING && t.progress > 0) {
                    LinearProgressIndicator(
                        progress = { t.progress / 100f },
                        modifier = bar, color = C.Teal, trackColor = C.Card2,
                    )
                } else {
                    LinearProgressIndicator(modifier = bar, color = C.Teal, trackColor = C.Card2)
                }
            }
        }
        when (t.state) {
            Queue.S.WAITING, Queue.S.RUNNING ->
                SmallAction(Icons.Default.Close, "إلغاء", C.Muted) { Queue.cancel(t) }
            Queue.S.FAILED, Queue.S.CANCELED -> {
                SmallAction(Icons.Default.Refresh, "إعادة المحاولة", C.Teal) { Queue.retry(ctx, t) }
                SmallAction(Icons.Default.Close, "إزالة", C.Muted) { Queue.remove(t) }
            }
            Queue.S.DONE, Queue.S.SKIPPED ->
                SmallAction(Icons.Default.Close, "إزالة من القائمة", C.Muted) { Queue.remove(t) }
        }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, desc: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = desc, tint = tint)
    }
}

/** غلاف الأغنية (مربّع بزوايا دائرية)، أو أيقونة بديلة لو ما في غلاف، مع شارة حالة صغيرة. */
@Composable
private fun Cover(t: Queue.Task) {
    val bmp = remember(t.cover) { t.cover?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    val shape = RoundedCornerShape(14.dp)

    Box(Modifier.size(60.dp)) {
        if (bmp != null) {
            Image(
                bitmap = bmp, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape),
            )
        } else {
            val failed = t.state == Queue.S.FAILED
            Box(
                Modifier.fillMaxSize().clip(shape)
                    .background(if (failed) C.Red.copy(alpha = 0.14f) else C.Teal.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.MusicNote, contentDescription = null,
                    tint = if (failed) C.Red else C.Teal, modifier = Modifier.size(26.dp),
                )
            }
        }
        val badge: Pair<ImageVector, androidx.compose.ui.graphics.Color>? = when (t.state) {
            Queue.S.DONE -> Icons.Default.CheckCircle to C.Teal
            Queue.S.FAILED -> Icons.Default.Warning to C.Red
            else -> null
        }
        if (badge != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(C.Card).padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(badge.first, contentDescription = null, tint = badge.second, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun fmtDuration(sec: Long): String =
    String.format(java.util.Locale.getDefault(), "%d:%02d", sec / 60, sec % 60)

private fun subtitle(ctx: Context, t: Queue.Task): String = when (t.state) {
    Queue.S.WAITING -> "بالانتظار"
    Queue.S.RUNNING -> if (t.progress > 0) "جاري التنزيل • ${t.progress}%" else "جاري التحضير…"
    Queue.S.DONE -> listOfNotNull(
        "اكتمل",
        if (t.duration > 0) fmtDuration(t.duration) else null,
        if (t.size > 0) Formatter.formatShortFileSize(ctx, t.size) else null,
    ).joinToString(" • ")
    Queue.S.FAILED -> t.error ?: "فشل التنزيل"
    Queue.S.CANCELED -> "أُلغي"
    Queue.S.SKIPPED -> t.error ?: "نُزّلت سابقاً"
}

// ======================= الإعدادات =======================

@Composable
private fun SettingsScreen() {
    val ctx = LocalContext.current
    var quality by remember { mutableStateOf(Store.quality(ctx)) }
    var parallel by remember { mutableIntStateOf(Store.parallel(ctx)) }
    var skip by remember { mutableStateOf(Store.skipDupes(ctx)) }
    var tags by remember { mutableStateOf(Store.tags(ctx)) }
    var shareOpts by remember { mutableStateOf(Store.shareOptions(ctx)) }
    var quickFmt by remember { mutableStateOf(Store.quickFmt(ctx)) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("الإعدادات", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))

        SettingsSection("التنزيل") {
            SettingRow("جودة الصوت", "أعلى = أفضل جودة، أقل = أصغر حجم") {
                Segmented(listOf("أعلى", "أقل"), if (quality == "high") 0 else 1) {
                    quality = if (it == 0) "high" else "low"
                    Store.setQuality(ctx, quality)
                }
            }
            HorizontalDivider(color = C.Border)
            SettingRow("التنزيلات المتزامنة", "بتنطبق لما ما يكون في تنزيلات جارية") {
                Segmented(listOf("1", "2", "3"), parallel - 1) {
                    parallel = it + 1
                    Store.setParallel(ctx, parallel)
                }
            }
            HorizontalDivider(color = C.Border)
            SettingRow("صيغة التنزيل السريع", "زر \"تنزيل صوت\" ومشاركة الرابط المباشرة") {
                Segmented(listOf("m4a", "mp3"), if (quickFmt == "mp3") 1 else 0) {
                    quickFmt = if (it == 1) "mp3" else "m4a"
                    Store.setQuickFmt(ctx, quickFmt)
                }
            }
            HorizontalDivider(color = C.Border)
            SettingRow("عند مشاركة رابط يوتيوب", "تنزيل صوت مباشرة، أو فتح ورقة الصيغ والدقات") {
                Segmented(listOf("مباشر", "خيارات"), if (shareOpts) 1 else 0) {
                    shareOpts = it == 1
                    Store.setShareOptions(ctx, shareOpts)
                }
            }
        }

        SettingsSection("الملفات") {
            SettingRow("غلاف ووسوم داخل الملف", "العنوان والفنان وصورة الغلاف") {
                Switch(
                    checked = tags,
                    onCheckedChange = { tags = it; Store.setTags(ctx, it) },
                    colors = switchColors(),
                )
            }
            HorizontalDivider(color = C.Border)
            SettingRow("تخطي المنزّل سابقاً", "ما يعيد تنزيل أغنية نزّلتها قبل") {
                Switch(
                    checked = skip,
                    onCheckedChange = { skip = it; Store.setSkipDupes(ctx, it) },
                    colors = switchColors(),
                )
            }
            HorizontalDivider(color = C.Border)
            SettingRow("سجل الأغاني المنزّلة", "مسحه بيخلّي التطبيق يعيد تنزيل أي أغنية") {
                TextButton(onClick = { confirmClear = true }) { Text("مسح", color = C.Red) }
            }
        }

        SettingsSection("حول") {
            val version = remember {
                try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
            }
            SettingRow("Amrtune", "النسخة $version") {}
            HorizontalDivider(color = C.Border)
            SettingRow("مكان الحفظ", "الموسيقى ← SongsDownloader") {}
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = C.Card,
            titleContentColor = C.Text,
            textContentColor = C.Muted,
            title = { Text("مسح سجل الأغاني؟") },
            text = { Text("بعدها التطبيق ما رح يتعرّف على الأغاني اللي نزّلتها قبل. ما بينحذف أي ملف من جهازك.") },
            confirmButton = {
                TextButton(onClick = { Store.clearDone(ctx); confirmClear = false }) { Text("مسح", color = C.Red) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("إلغاء", color = C.Teal) }
            },
        )
    }
}

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = C.OnTeal,
    checkedTrackColor = C.Teal,
    uncheckedThumbColor = C.Muted,
    uncheckedTrackColor = C.Card2,
    uncheckedBorderColor = C.Border,
)

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = C.Teal, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
        Column(
            Modifier.fillMaxWidth().clip(shape).background(C.Card).border(1.dp, C.Border, shape).padding(horizontal = 16.dp, vertical = 6.dp),
        ) { content() }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = C.Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(C.Card2).padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (sel) C.Teal else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (sel) C.OnTeal else C.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}


// ======================= ورقة الخيارات (صيغ ودقات) =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsSheet(onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { UiState.sheetOpen = false },
        sheetState = state,
        containerColor = C.Card,
        contentColor = C.Text,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val a = UiState.analysis
            val err = UiState.sheetError
            when {
                err != null -> {
                    Text("❌ $err", color = C.Red, fontSize = 14.sp)
                    TextButton(onClick = { UiState.sheetOpen = false }) { Text("إغلاق", color = C.Teal) }
                }
                a == null -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = C.Teal, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("جاري تحليل الرابط…", color = C.Muted, fontSize = 14.sp)
                }
                else -> {
                    SheetHeader(a)
                    Text("صوت", color = C.Teal, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    OptionRow(AppIcons.MusicNote, "m4a • أعلى جودة (بدون تحويل)", sizeText(ctx, a.audioSize)) { onPick("m4a") }
                    OptionRow(
                        AppIcons.MusicNote, "mp3 • 192 kbps (توافق أوسع)",
                        if (a.durationSec > 0) "≈ " + sizeText(ctx, a.durationSec * 24_000L) else "",
                    ) { onPick("mp3") }
                    Text("فيديو (MP4)", color = C.Teal, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    if (a.videos.isEmpty()) {
                        Text("ما في ستريم فيديو mp4 متاح لهذا الرابط", color = C.Muted, fontSize = 13.sp)
                    } else {
                        for (v in a.videos) {
                            OptionRow(AppIcons.Video, v.label, sizeText(ctx, v.size)) { onPick("mp4:${v.height}") }
                        }
                    }
                }
            }
        }
    }
}

private fun sizeText(ctx: Context, bytes: Long): String =
    if (bytes > 0) Formatter.formatShortFileSize(ctx, bytes) else ""

@Composable
private fun SheetHeader(a: Engine.Analysis) {
    val bmp = remember(a.url) { a.cover?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).clip(shape).background(C.Card2), contentAlignment = Alignment.Center) {
            if (bmp != null) {
                Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(AppIcons.MusicNote, contentDescription = null, tint = C.Muted)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(a.title, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(a.artist.ifEmpty { null }, if (a.durationSec > 0) fmtDuration(a.durationSec) else null).joinToString(" • ")
            if (meta.isNotEmpty()) Text(meta, color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun OptionRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(C.Card2).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(C.Teal.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = C.Teal, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (sub.isNotEmpty()) Text(sub, color = C.Muted, fontSize = 12.sp)
        }
        Icon(AppIcons.Download, contentDescription = null, tint = C.Teal, modifier = Modifier.size(20.dp))
    }
}
