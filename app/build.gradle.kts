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
        versionName = "1.0"
        // Flycast, melonDS et Citra n'existent qu'en arm64 : on s'aligne sur eux,
        // comme le faisaient tes projets Dreamcast, DS et 3DS
        ndk { abiFilters += listOf("arm64-v8a") }
        // Citra reclame la bibliotheque C++ partagee : elle doit etre dans l'APK
        externalNativeBuild {
            cmake { arguments("-DANDROID_STL=c++_shared") }
        }
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
    // les coeurs doivent etre extraits sur le disque pour etre ouverts par leur chemin
    packaging { jniLibs { useLegacyPackaging = true } }
    androidResources { noCompress += listOf("png", "jpg", "webp", "bin", "txt", "app", "romfs", "tmd", "bcfnt") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
}
