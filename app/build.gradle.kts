import java.io.File
import java.net.URI
import java.net.URLConnection
import java.io.InputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rudy.chambre"
    // Dolphin 2606 est compile avec Android 36. RetroRom doit connaitre ce
    // niveau pour accepter sa bibliotheque ; cela ne modifie aucun moteur.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rudy.chambre"
        /*
         * Android 29 au minimum.
         *
         * Le fusionneur de manifestes refusait une bibliotheque de
         * Mupen64Plus : « use a compatible library with a minSdk of at most
         * 26 ». Elle en reclame donc davantage. 29 couvre largement, et rend
         * au passage le sucrage inutile.
         */
        minSdk = 29
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
        /* Mupen64Plus apporte des milliers de classes : avec les notres, on
           depasse la limite d'un seul fichier. On autorise donc la repartition
           en plusieurs, ce qu'Android sait faire seul depuis la version 5. */
        multiDexEnabled = true
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
        /*
         * Le sucrage a ete retire.
         *
         * Il traduisait les fonctions Java recentes pour les vieux telephones,
         * mais c'est lui qui declenchait « android.jar is located outside the
         * root directory » : un defaut connu quand il s'applique a des modules
         * bibliotheque. En montant le minimum d'Android, ces fonctions sont
         * disponibles d'origine et la traduction ne sert plus a rien.
         */
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Dolphin l'exige dans son AAR. Cela ajoute la compatibilite Java
        // dans l'enveloppe RetroRom, sans modifier les autres moteurs.
        isCoreLibraryDesugaringEnabled = true
    }
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
    androidResources { noCompress += listOf("png", "jpg", "webp", "bin", "txt", "html", "app", "romfs", "tmd", "bcfnt", "zim", "pgf", "ini", "meta", "json") }
}

// Syntaxe Kotlin 2.x (l'ancienne kotlinOptions/jvmTarget est refusee).
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Même version que Dolphin 2606 : elle est imposee par son AAR.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    /*
     * L'emulateur N64 de Rudy : Mupen64Plus, prepare par le workflow dans
     * m64base/. On regarde le dossier lui-meme, ce qui est vrai des la lecture
     * du fichier ; sans cette ligne il etait prepare, puis ignore.
     */
    if (File(rootDir, "m64base/app").isDirectory) implementation(project(":m64"))

    /*
     * Dolphin, pour la GameCube et la Wii.
     *
     * Il n'est pas rattache comme un module : il est compile A PART, avec sa
     * propre version des outils, et nous arrive sous forme de bibliotheque —
     * un .aar qui porte ses classes, ses ressources, ses assets et ses
     * bibliotheques natives. La Chambre garde ainsi les siens, et rien de ce
     * qui fait tourner les douze autres consoles ne bouge.
     */
    if (File(projectDir, "libs/dolphin.aar").isFile)
        implementation(files("libs/dolphin.aar"))

    // reclamee par la facade de PPSSPP : PpssppActivity en herite
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Les ecrans et reglages de Dolphin 2606 utilisent Material 3.
    implementation("com.google.android.material:material:1.13.0")
    // Un AAR local ne transmet pas ses dependances Gradle. Voici, en un seul
    // bloc, toutes celles du projet Dolphin 2606 (menus, reglages, TV,
    // selecteur de fichiers et ecran de demarrage).
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.slidingpanelayout:slidingpanelayout:1.2.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.leanback:leanback:1.2.0")
    implementation("androidx.tvprovider:tvprovider:1.1.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.nononsenseapps:filepicker:4.2.1")
    // sert les fichiers de la chambre comme un site local, pour que la page
    // puisse charger ses musiques, ses videos et ses jeux
    implementation("androidx.webkit:webkit:1.11.0")
    // Decompression locale de PariBoxe : l'archive de mise a jour reste
    // assez petite pour etre deposee depuis le telephone, sans retirer une
    // seule animation du combat.
}

/*
 * Shinato : le moteur 3D (three.js r128) du Bras de fer, de Five Fight et du
 * pistolet a eau de Gisele. Avant, ces jeux le telechargeaient sur Internet a
 * chaque lancement : sans connexion, ecran noir. On le recupere une seule fois
 * ici, pendant la compilation sur GitHub, et il est range dans l'APK
 * (assets/shinato/lib/three.min.js). Si le telechargement echoue, la
 * compilation continue : les jeux essaieront alors Internet, comme avant.
 */
val cibleThreeShinato = file("src/main/assets/shinato/lib/three.min.js")
val moteur3dShinato = tasks.register("moteur3dShinato") {
    doLast {
        if (cibleThreeShinato.isFile && cibleThreeShinato.length() > 100000L) {
            logger.lifecycle("three.js deja present pour Shinato")
            return@doLast
        }
        cibleThreeShinato.parentFile.mkdirs()
        val adresses = listOf(
            "https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js",
            "https://cdn.jsdelivr.net/npm/three@0.128.0/build/three.min.js",
            "https://unpkg.com/three@0.128.0/build/three.min.js"
        )
        for (adresse in adresses) {
            try {
                // « java.net... » ecrit en entier est pris ici pour le reglage Java de
                // Gradle : on passe donc par les imports, en haut du fichier.
                val connexion: URLConnection = URI(adresse).toURL().openConnection()
                connexion.setConnectTimeout(20000)
                connexion.setReadTimeout(60000)
                val flux: InputStream = connexion.getInputStream()
                val octets: ByteArray = flux.use { f -> f.readBytes() }
                if (octets.size > 100000) {
                    cibleThreeShinato.writeBytes(octets)
                    logger.lifecycle("three.js recupere pour Shinato : $adresse")
                    return@doLast
                }
            } catch (e: Exception) {
                logger.warn("three.js : echec depuis $adresse (${e.message})")
            }
        }
        logger.warn("three.js non recupere : les jeux 3D de Shinato passeront par Internet")
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(moteur3dShinato) }
