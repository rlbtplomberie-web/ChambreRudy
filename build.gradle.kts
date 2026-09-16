plugins {
    // Les bibliotheques AndroidX de Dolphin 2606 demandent au minimum 8.6.
    id("com.android.application") version "8.6.1" apply false
    // Dolphin 2606 et ses dependances sont compiles avec Kotlin 2.3.
    // RetroRom doit lire la meme metadata pour les assembler.
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
}
