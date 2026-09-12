plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rudy.chambre"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rudy.chambre"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        // les coeurs libretro sont fournis pour ces deux architectures
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    // le coeur doit etre extrait sur le disque pour etre ouvert par son chemin
    packaging { jniLibs { useLegacyPackaging = true } }
    androidResources { noCompress += listOf("png", "jpg", "webp", "mp3", "webm", "mp4", "html") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
}
