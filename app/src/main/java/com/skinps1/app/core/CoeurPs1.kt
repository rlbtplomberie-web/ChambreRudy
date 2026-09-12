package com.skinps1.app.core

import android.content.Context
import java.io.File

/**
 * Facade Kotlin du coeur PCSX-ReARMed.
 *
 * Le jeu est charge par son chemin : le coeur lit lui-meme l'image de disque,
 * qui peut peser des centaines de Mo et se composer de plusieurs fichiers.
 * Le BIOS, s'il y en a un, doit etre dans [dossierSysteme].
 */
class CoeurPs1(ctx: Context) {

    companion object {
        init { System.loadLibrary("skinps1") }
        const val MAX_L = 1280
        const val MAX_H = 1024
        /** Noms de BIOS que le coeur sait reconnaitre, europeens en tete. */
        val NOMS_BIOS = listOf("scph5502.bin", "scph7502.bin", "scph1002.bin", "scph5552.bin",
                               "scph5501.bin", "scph7001.bin", "scph1001.bin", "scph101.bin",
                               "scph5500.bin", "scph7000.bin", "scph1000.bin", "psxonpsp660.bin")
    }

    private external fun natInit(cheminCoeur: String, dossier: String): Boolean
    private external fun natCharger(chemin: String): Boolean
    private external fun natVariable(cle: String, valeur: String)
    private external fun natImage(boutons: Int, gx: Int, gy: Int, dx: Int, dy: Int, sortie: IntArray): Int
    private external fun natSon(sortie: ShortArray): Int
    private external fun natFrequence(): Double
    private external fun natFps(): Double
    private external fun natReset()
    private external fun natEjecter()
    private external fun natSauver(): ByteArray?
    private external fun natRestaurer(donnees: ByteArray): Boolean

    val tampon = IntArray(MAX_L * MAX_H)
    var largeur = 320
        private set
    var hauteur = 240
        private set

    val dossierSysteme: File = ctx.filesDir
    val pret: Boolean
    var romChargee = false
        private set
    var derniereErreur = ""
        private set
    var cle = ""
        private set

    private val tamponSon = ShortArray(48000 * 2)

    init {
        val chemin = File(ctx.applicationInfo.nativeLibraryDir, "libpcsx.so")
        pret = if (chemin.exists()) natInit(chemin.absolutePath, dossierSysteme.absolutePath) else false
        if (!pret) derniereErreur = "Coeur PCSX-ReARMed introuvable (${chemin.name})"
        java.util.Arrays.fill(tampon, 0xFF000000.toInt())
    }

    /** Le BIOS present dans le dossier systeme, ou null si on tourne en BIOS emule. */
    fun biosPresent(): String? = NOMS_BIOS.firstOrNull { File(dossierSysteme, it).length() >= 512 * 1024 }

    /** [chemin] est un fichier lisible directement : .cue, .chd, .pbp, .bin, .img, .iso, .m3u. */
    fun chargerJeu(chemin: File): Boolean {
        if (!pret) return false
        if (!natCharger(chemin.absolutePath)) {
            derniereErreur = "Jeu refusé par PCSX-ReARMed (${chemin.name})"; return false
        }
        romChargee = true
        cle = chemin.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]"), "_") + "_" + chemin.length()
        derniereErreur = ""
        return true
    }

    /**
     * Amelioration interne du coeur. PCSX-ReARMed n'en propose qu'une : un
     * doublement reserve aux processeurs 32 bits avec NEON. Sur un appareil
     * 64 bits elle n'existe pas, et le reglage reste sans effet visible.
     * Une vraie montee en resolution demande un coeur a rendu materiel.
     */
    fun ameliorationInterne(active: Boolean) {
        natVariable("pcsx_rearmed_neon_enhancement_enable", if (active) "enabled" else "disabled")
        natVariable("pcsx_rearmed_neon_enhancement_no_main", if (active) "enabled" else "disabled")
    }

    /** true si l'amelioration interne peut avoir un effet sur cet appareil. */
    val ameliorationPossible: Boolean =
        android.os.Build.SUPPORTED_32_BIT_ABIS.any { it.startsWith("armeabi") } &&
        android.os.Build.SUPPORTED_64_BIT_ABIS.isEmpty()

    fun imageSuivante(boutons: Int, gx: Float, gy: Float, dx: Float, dy: Float): Boolean {
        if (!romChargee) return false
        fun q(v: Float) = (v.coerceIn(-1f, 1f) * 32767f).toInt()
        val r = natImage(boutons, q(gx), q(gy), q(dx), q(dy), tampon)
        if (r == 0) return false
        largeur = (r ushr 16) and 0xFFFF
        hauteur = r and 0xFFFF
        return true
    }

    fun son(): ShortArray {
        val n = natSon(tamponSon)
        return if (n <= 0) ShortArray(0) else tamponSon.copyOf(n)
    }

    val frequence: Int get() = natFrequence().toInt()
    val imagesParSeconde: Double get() = natFps()

    fun reinitialiser() { if (romChargee) natReset() }

    fun eteindre() {
        natEjecter()
        romChargee = false
        cle = ""
        java.util.Arrays.fill(tampon, 0xFF000000.toInt())
    }

    fun sauverEtat(): ByteArray? = if (romChargee) natSauver() else null
    fun restaurerEtat(o: ByteArray): Boolean = romChargee && natRestaurer(o)
}

/** Bits de la manette, identifiants libretro. */
object Pad {
    const val B = 1 shl 0        // croix
    const val Y = 1 shl 1        // carre
    const val SELECT = 1 shl 2
    const val START = 1 shl 3
    const val HAUT = 1 shl 4
    const val BAS = 1 shl 5
    const val GAUCHE = 1 shl 6
    const val DROITE = 1 shl 7
    const val A = 1 shl 8        // rond
    const val X = 1 shl 9        // triangle
    const val L1 = 1 shl 10
    const val R1 = 1 shl 11
    const val L2 = 1 shl 12
    const val R2 = 1 shl 13
}
