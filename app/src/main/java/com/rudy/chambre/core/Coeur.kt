package com.rudy.chambre.core

/**
 * Ce que l'interface attend d'un coeur d'emulation, qu'il vienne de libretro
 * ou qu'il soit ecrit en Kotlin comme celui de la NES.
 */
interface Coeur {
    val tampon: IntArray
    val largeur: Int
    val hauteur: Int
    val romChargee: Boolean
    val imagesParSeconde: Double
    /** Frequence d'echantillonnage du son, en hertz. */
    val frequence: Int
    val pret: Boolean
    val derniereErreur: String
    val cle: String

    fun chargerRom(donnees: ByteArray): Boolean
    fun imageSuivante(boutons: Int)
    fun son(): ShortArray
    fun reinitialiser()
    fun eteindre()
    fun sauverEtat(): ByteArray?
    fun restaurerEtat(donnees: ByteArray): Boolean
}
