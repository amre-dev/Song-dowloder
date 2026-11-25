import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.spiderstudio.songs"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spiderstudio.songs"
        minSdk = 29
        targetSdk = 34
        versionCode = 9
        versionName = "0.7"

        // كود MP3 الأصلي (LAME): نكتفي بمعمارية الموبايلات الحديثة
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // مفتاح debug ثابت (مرفوع بالمستودع) حتى تتحدّث النسخ فوق بعضها بدون حذف
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        // release لاحقاً: تصغير + shrinkResources (بعد ما نضبط قواعد R8 لـ NewPipeExtractor)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true   // NewPipeExtractor يحتاجها
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")
}

// ---- LAME (مشفّر MP3): مصدره بينزل تلقائياً أول مرة (مو مخزّن بالمستودع) ----
val lameDir = layout.projectDirectory.dir("src/main/cpp/lame").asFile
val fetchLame = tasks.register("fetchLame") {
    onlyIf { !File(lameDir, "lame.h").exists() }
    doLast {
        val work = layout.buildDirectory.dir("lame-src").get().asFile
        work.deleteRecursively()
        work.mkdirs()
        val tgz = File(work, "lame.tar.gz")
        val urls = listOf(
            "https://deb.debian.org/debian/pool/main/l/lame/lame_3.100.orig.tar.gz",
            "https://archive.ubuntu.com/ubuntu/pool/universe/l/lame/lame_3.100.orig.tar.gz",
            "https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz",
        )
        var ok = false
        for (u in urls) {
            try {
                URL(u).openStream().use { input -> tgz.outputStream().use { out -> input.copyTo(out) } }
                if (tgz.length() > 500_000) { ok = true; break }
            } catch (e: Exception) {
                println("LAME: failed to download from $u: $e")
            }
        }
        if (!ok) throw GradleException("Could not download the LAME sources from any mirror")
        println("LAME: downloaded ${tgz.length()} bytes")

        val ext = File(work, "x")
        copy { from(tarTree(resources.gzip(tgz))); into(ext) }
        val root = ext.listFiles()!!.first { it.isDirectory }
        copy { from(File(root, "libmp3lame")) { include("*.c", "*.h", "vector/*.h") }; into(lameDir) }
        copy { from(File(root, "include/lame.h")); into(lameDir) }
    }
}
tasks.configureEach {
    if (name == "preBuild" || name.startsWith("configureCMake")) dependsOn(fetchLame)
}
