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
 * Le workflow le recupere dans m64base/ et y applique son habillage. S'il est
 * la, il devient un morceau du projet ; sinon on compile sans lui, et la N64
 * reste simplement absente.
 */
val n64 = file("m64base/app")
if (n64.exists()) {
    include(":m64")
    project(":m64").projectDir = n64
}
