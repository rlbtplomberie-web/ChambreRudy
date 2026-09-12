package com.rudy.chambre.core

import android.content.Context
import java.io.File

/**
 * Facade Kotlin du coeur Snes9x.
 *
 * Toute l'emulation se fait dans la bibliotheque native ; ici on ne garde
 * que l'etat utile a l'interface : l'image courante, sa taille, la ROM en
 * cours et son empreinte pour nommer les sauvegardes.
 */
class CoeurLibretro(ctx: Context, nomCoeur: String) : Coeur {

    companion object {
        init { System.loadLibrary("pont") }
        const val MAX_L = 1024
        const val MAX_H = 1024
    }

    private external fun natInit(cheminCoeur: String, dossier: String): Boolean
    private external fun natCharger(rom: ByteArray): Boolean
    private external fun natImage(boutons: Int, sortie: IntArray): Int
    private external fun natSon(sortie: ShortArray): Int
    private external fun natFrequence(): Double
    private external fun natFps(): Double
    private external fun natReset()
    private external fun natEjecter()
    private external fun natSauver(): ByteArray?
    private external fun natRestaurer(donnees: ByteArray): Boolean

    /** Pixels ARGB de la derniere image, largeur x hauteur en tete de tampon. */
    override val tampon = IntArray(MAX_L * MAX_H)
    override var largeur = 256
        private set
    override var hauteur = 224
        private set

    override val pret: Boolean
    override var romChargee = false
        private set
    override var derniereErreur = ""
        private set
    override var cle = ""
        private set

    private val tamponSon = ShortArray(32040 * 2)

    init {
        val chemin = File(ctx.applicationInfo.nativeLibraryDir, nomCoeur)
        pret = if (chemin.exists()) natInit(chemin.absolutePath, ctx.filesDir.absolutePath)
               else false
        if (!pret) derniereErreur = "Coeur Snes9x introuvable (${chemin.name})"
        java.util.Arrays.fill(tampon, 0xFF000000.toInt())
    }

    override fun chargerRom(donnees: ByteArray): Boolean {
        if (!pret) return false
        // certaines ROM ont un en-tete de copieur de 512 octets : le coeur le
        // detecte lui-meme, on lui passe tout
        if (!natCharger(donnees)) { derniereErreur = "ROM refusée par Snes9x"; return false }
        romChargee = true
        cle = empreinte(donnees)
        derniereErreur = ""
        return true
    }

    /** true si une nouvelle image est disponible dans [tampon]. */
    /** Avance d'une image. L'interface commune n'attend rien en retour. */
    override fun imageSuivante(boutons: Int) { imageSuivanteBool(boutons) }

    fun imageSuivanteBool(boutons: Int): Boolean {
        if (!romChargee) return false
        val r = natImage(boutons, tampon)
        if (r == 0) return false
        largeur = (r ushr 16) and 0xFFFF
        hauteur = r and 0xFFFF
        return true
    }

    /** Echantillons stereo entrelaces produits depuis le dernier appel. */
    override fun son(): ShortArray {
        val n = natSon(tamponSon)
        return if (n <= 0) ShortArray(0) else tamponSon.copyOf(n)
    }

    override val frequence: Int get() = natFrequence().toInt()
    override val imagesParSeconde: Double get() = natFps()

    override fun reinitialiser() { if (romChargee) natReset() }

    override fun eteindre() {
        natEjecter()
        romChargee = false
        cle = ""
        java.util.Arrays.fill(tampon, 0xFF000000.toInt())
    }

    override fun sauverEtat(): ByteArray? = if (romChargee) natSauver() else null
    override fun restaurerEtat(donnees: ByteArray): Boolean = romChargee && natRestaurer(donnees)

    private fun empreinte(d: ByteArray): String {
        var h = -3750763034362895579L
        for (b in d) { h = h xor (b.toLong() and 0xFF); h *= 1099511628211L }
        return d.size.toString() + "_" + java.lang.Long.toHexString(h)
    }
}

/** Bits de la manette, dans l'ordre des identifiants libretro. */
object Pad {
    const val B = 1 shl 0
    const val Y = 1 shl 1
    const val SELECT = 1 shl 2
    const val START = 1 shl 3
    const val HAUT = 1 shl 4
    const val BAS = 1 shl 5
    const val GAUCHE = 1 shl 6
    const val DROITE = 1 shl 7
    const val A = 1 shl 8
    const val X = 1 shl 9
    const val L = 1 shl 10
    const val R = 1 shl 11
}
