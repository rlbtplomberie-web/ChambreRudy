import java.io.File

pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "ChambreRudy"
include(":app")

/*
 * Mupen64Plus-AE, l'emulateur N64 de Rudy.
 *
 * Il n'est pas fait d'un seul morceau : son ecran s'appuie sur des modules
 * voisins — le pont natif, les greffons. On les prend donc tous, sous leur
 * propre nom, sauf le principal qui devient « m64 » pour ne pas se confondre
 * avec le notre.
 */
/*
 * Dolphin, l'emulateur GameCube et Wii de Rudy.
 *
 * Son projet Android vit dans Source/Android. Comme pour la N64, on prend
 * tous ses modules ; le principal devient « dolphinapp ».
 */
val baseGC = file("dolphinbase/Source/Android")
if (baseGC.isDirectory) {
    baseGC.listFiles()?.sortedBy { it.name }?.forEach { d ->
        val aUnFichier = File(d, "build.gradle").exists() || File(d, "build.gradle.kts").exists()
        if (d.isDirectory && aUnFichier) {
            val nom = if (d.name == "app") ":dolphinapp" else ":dolphin-" + d.name
            include(nom)
            project(nom).projectDir = d
        }
    }
}

val baseN64 = file("m64base")
if (baseN64.isDirectory) {
    baseN64.listFiles()?.sortedBy { it.name }?.forEach { d ->
        val aUnFichier = File(d, "build.gradle").exists() || File(d, "build.gradle.kts").exists()
        if (d.isDirectory && aUnFichier) {
            val nom = if (d.name == "app") ":m64" else ":" + d.name
            include(nom)
            project(nom).projectDir = d
        }
    }
}
