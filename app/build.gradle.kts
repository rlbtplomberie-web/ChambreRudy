import java.io.File

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
        // Tes projets Dreamcast, PSP, GameCube, 3DS, DS, Mega Drive et Game Boy
        // visent tous Android 9. Ce n'est pas un detail : au-dela, Android
        // interdit d'executer du code fraichement ecrit en memoire, ce que font
        // les coeurs qui compilent a la volee — Flycast, Dolphin, PPSSPP, Citra.
        // Viser plus haut les fait quitter des qu'un jeu demarre.
        targetSdk = 28
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

    lint {
        /* Ce controle sert a publier sur le Play Store, ou une cible recente
           est exigee. Nous visons volontairement Android 9, sans quoi les
           coeurs qui compilent a la volee — Dolphin, Citra, PPSSPP — sont
           tues des qu'un jeu demarre. On ecarte donc cette regle. */
        disable += "ExpiredTargetSdkVersion"
        abortOnError = false
        checkReleaseBuilds = false
    }

    buildTypes {
        release { isMinifyEnabled = false }
        /* L'APK qu'on installe est celui-ci. Par defaut il tourne en mode
           « mise au point », qui surveille chaque operation et ralentit
           l'affichage. On le desactive : l'application garde sa signature
           d'essai, donc elle s'installe toujours, mais elle tourne vite. */
        debug { isDebuggable = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    // les coeurs doivent etre extraits sur le disque pour etre ouverts par leur chemin
    // les coeurs doivent etre poses sur le disque pour etre ouverts par leur chemin
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // si deux bibliotheques C++ se presentent, on garde la premiere
            // au lieu d'arreter la compilation
            pickFirsts += "**/libc++_shared.so"
        }
        resources.excludes += setOf("META-INF/*")   // repris de ton projet PSP
    }
    androidResources {
        // Ce dossier vient des ressources de PPSSPP et porte le meme nom que
        // celui qu'Android fabrique lui-meme : on l'ecarte une fois pour toutes.
        ignoreAssetsPatterns += listOf("dexopt")
    }
    androidResources { noCompress += listOf("png", "jpg", "webp", "bin", "txt", "app", "romfs", "tmd", "bcfnt", "zim", "pgf", "ini", "meta", "json") }
}

dependencies {
    /*
     * L'emulateur N64 de Rudy : Mupen64Plus, prepare par le workflow dans
     * m64base/. On regarde le dossier lui-meme, ce qui est vrai des la lecture
     * du fichier ; sans cette ligne il etait prepare, puis ignore.
     */
    if (File(rootDir, "m64base/app").isDirectory) implementation(project(":m64"))

    // reclamee par la facade de PPSSPP : PpssppActivity en herite
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // sert les fichiers de la chambre comme un site local, pour que la page
    // puisse charger ses musiques, ses videos et ses jeux
    implementation("androidx.webkit:webkit:1.11.0")
}
