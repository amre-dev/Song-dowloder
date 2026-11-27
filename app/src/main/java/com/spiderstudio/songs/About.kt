package com.spiderstudio.songs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private class Lib(val name: String, val license: String, val by: String)

private val LIBS = listOf(
    Lib("NewPipe Extractor", "GPL-3.0", "فريق NewPipe — استخراج روابط الميديا"),
    Lib("LAME", "LGPL-2.1", "مشروع LAME — تشفير MP3"),
    Lib("OkHttp", "Apache-2.0", "Square — الاتصال بالشبكة"),
    Lib("Jetpack Compose و Material 3", "Apache-2.0", "Google — الواجهة"),
    Lib("AndroidX Activity", "Apache-2.0", "Google"),
    Lib("Kotlin Coroutines", "Apache-2.0", "JetBrains"),
    Lib("Mozilla Rhino", "MPL-2.0", "Mozilla — (يستعملها NewPipe Extractor)"),
    Lib("jsoup", "MIT", "Jonathan Hedley — (يستعملها NewPipe Extractor)"),
    Lib("nanojson", "Apache-2.0", "(يستعملها NewPipe Extractor)"),
    Lib("Protocol Buffers (Lite)", "BSD-3-Clause", "Google — (يستعملها NewPipe Extractor)"),
)

/** شاشة "حول والتراخيص". */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val version = remember {
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = C.Text)
            }
            Text("حول والتراخيص", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Image(painterResource(R.drawable.ic_logo_mark), contentDescription = null, modifier = Modifier.size(72.dp))
            Text("Amrtune", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("النسخة $version", color = C.Muted, fontSize = 13.sp)
            Text("من spiderstudio", color = C.Teal, fontSize = 13.sp)
        }

        Card2 {
            Text(
                "تطبيق لتنزيل الفيديو والصوت من يوتيوب بصيغ مختلفة (m4a وmp3 وmp4 بدقات متعددة). " +
                    "بدون إعلانات، وبدون حسابات، وما بيجمع أي بيانات عنك.",
                color = C.Text, fontSize = 14.sp, lineHeight = 22.sp,
            )
        }

        Section("الترخيص") {
            Text(
                "Amrtune برنامج حر، مرخّص بموجب رخصة GNU العمومية الإصدار 3 (GPL-3.0)، لأنو بيعتمد على مكتبات بنفس الرخصة. " +
                    "من حقك استعماله ودراسته وتعديله ومشاركته بنفس الشروط، وهو بدون أي ضمان. " +
                    "نص الرخصة الكامل: gnu.org/licenses/gpl-3.0",
                color = C.Muted, fontSize = 13.sp, lineHeight = 21.sp,
            )
        }

        Section("استعمال مسؤول") {
            Text(
                "التطبيق غير تابع لـ YouTube أو Google. حمّل فقط المحتوى المسموح لك بتنزيله، " +
                    "والتزم بحقوق النشر وبشروط استخدام الخدمة. المسؤولية عن الاستعمال على المستخدم.",
                color = C.Muted, fontSize = 13.sp, lineHeight = 21.sp,
            )
        }

        Section("الخصوصية") {
            Text(
                "كل شي بيصير على جهازك. التطبيق بيطلب: الإنترنت (للتنزيل)، وحالة الشبكة (لخيار واي فاي فقط)، " +
                    "والإشعارات (لتقدّم التنزيل واكتماله). الملفات بتنحفظ بمجلدَي الموسيقى والأفلام.",
                color = C.Muted, fontSize = 13.sp, lineHeight = 21.sp,
            )
        }

        Section("المكتبات المستعملة") {
            LIBS.forEachIndexed { i, lib ->
                if (i > 0) HorizontalDivider(color = C.Border)
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(lib.name, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(lib.license, color = C.Teal, fontSize = 12.sp)
                    }
                    Text(lib.by, color = C.Muted, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Card2(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(C.Card).border(1.dp, C.Border, shape).padding(16.dp)) { content() }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = C.Teal, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
        Card2 {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}
