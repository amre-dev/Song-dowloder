plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.spiderstudio.songs"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spiderstudio.songs"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
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

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")
}
