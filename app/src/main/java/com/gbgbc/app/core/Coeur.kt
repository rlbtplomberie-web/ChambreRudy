package com.gbgbc.app.core

import android.content.Context
import java.io.File

/**
 * Manette de la Game Boy, dans les identifiants attendus par le coeur.
 *
 * La console n'a que huit touches : la croix, A, B, SELECT et START.
 */
object Pad {
    const val B = 1 shl 0
    const val SELECT = 1 shl 2
    const val START = 1 shl 3
    const val HAUT = 1 shl 4
    const val BAS = 1 shl 5
    const val GAUCHE = 1 shl 6
    const val DROITE = 1 shl 7
    const val A = 1 shl 8
}

/**
 * Facade du coeur Gambatte.
 *
 * Le coeur calcule ses images lui-meme et nous remet ses pixels : il n'y a ni
 * contexte graphique a partager, ni tampon a relire. On rend l'image dans un
 * tableau, et c'est l'habillage qui decide ou la peindre.
 */
class Coeur(ctx: Context) {

    private external fun natInit(chemin: String, dossier: String): Boolean
    private external fun natCharger(jeu: String): Boolean
    private external fun natDecharger()
    private external fun natImage(boutons: Int)
    private external fun natLireImage(sortie: IntArray): Int
    private external fun natSonLire(sortie: ShortArray): Int
    private external fun natVariable(cle: String, valeur: String)
    private external fun natReinitialiser()
    private external fun natImagesParSeconde(): Double
    private external fun natFrequence(): Int
    private external fun natClarte(): Int
    private external fun natImagesEmulees(): Int
    private external fun natSauver(): ByteArray?
    private external fun natRestaurer(etat: ByteArray): Boolean
    private external fun natTrichesEffacer()
    private external fun natTricheAjouter(rang: Int, code: String)
    private external fun natTrichesPossibles(): Boolean

    companion object {
        init { System.loadLibrary("gbgbc") }

        /** Palettes proposees pour les jeux Game Boy, en noir et blanc. */
        val PALETTES = listOf(
            "GBC - Grayscale", "GB - DMG", "GB - Pocket", "GB - Light",
            "GBC - Blue", "GBC - Brown", "GBC - Dark Blue", "GBC - Dark Green",
            "GBC - Green", "GBC - Inverted", "GBC - Orange", "GBC - Pastel Mix",
            "GBC - Red", "GBC - Yellow")
        val PALETTES_FR = listOf(
            "Gris", "Game Boy d'origine", "Game Boy Pocket", "Game Boy Light",
            "Bleu", "Brun", "Bleu foncé", "Vert foncé",
            "Vert", "Inversé", "Orange", "Pastel",
            "Rouge", "Jaune")

        /** Cadences proposees. Zero : celle de la console. */
        val CADENCES = listOf(0.0, 30.0, 60.0, 120.0)
        val CADENCES_FR = listOf("Native", "30 images/s", "60 images/s", "120 images/s")

        /** Echelles de vitesse. */
        val ECHELLES = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

        /** Ecran de la console : 160 sur 144, double au plus par le coeur. */
        const val TAMPON = 512 * 512
    }

    val dossier: File = File(ctx.filesDir, "systeme").apply { mkdirs() }

    /** Pixels de la derniere image, au format de l'ecran. */
    val pixels = IntArray(TAMPON)
    var imageL = 0; private set
    var imageH = 0; private set

    var pret = false; private set
    var romChargee = false; private set
    var derniereErreur: String? = null; private set

    init {
        val chemin = File(ctx.applicationInfo.nativeLibraryDir, "libgambatte.so")
        pret = if (!chemin.exists()) {
            derniereErreur = "Cœur Gambatte introuvable (${chemin.name})"
            false
        } else {
            natInit(chemin.absolutePath, dossier.absolutePath).also {
                if (!it) derniereErreur = "Le cœur Gambatte n'a pas pu s'ouvrir"
            }
        }
    }

    fun chargerJeu(f: File): Boolean {
        if (!pret) return false
        romChargee = natCharger(f.absolutePath)
        if (!romChargee) derniereErreur = "Jeu refusé (${f.name})"
        return romChargee
    }

    fun decharger() { if (romChargee) { natDecharger(); romChargee = false } }

    fun image(boutons: Int) { if (romChargee) natImage(boutons) }

    /** Relit l'image produite. Vrai si une nouvelle image est prete. */
    fun lireImage(): Boolean {
        if (!romChargee) return false
        val r = natLireImage(pixels)
        if (r == 0) return false
        imageL = (r shr 16) and 0xFFFF
        imageH = r and 0xFFFF
        return imageL > 0 && imageH > 0
    }

    fun lireSon(sortie: ShortArray) = natSonLire(sortie)

    fun reglage(cle: String, valeur: String) { if (pret) natVariable(cle, valeur) }

    fun palette(i: Int) {
        if (!pret) return
        natVariable("gambatte_gb_internal_palette",
                    PALETTES[i.coerceIn(0, PALETTES.size - 1)])
        /* La palette ne s'applique qu'aux jeux Game Boy : on demande donc au
           coeur de ne PAS deviner tout seul. */
        natVariable("gambatte_gb_colorization", "internal")
    }

    fun couleursAutomatiques() {
        if (pret) natVariable("gambatte_gb_colorization", "auto")
    }

    fun reinitialiser() { if (romChargee) natReinitialiser() }

    /** Pose la liste des codes actifs. Le coeur les interprete lui-meme. */
    fun poserTriches(codes: List<String>) {
        if (!pret) return
        natTrichesEffacer()
        for ((i, c) in codes.withIndex()) {
            val propre = c.trim().uppercase()
            if (propre.isNotEmpty()) natTricheAjouter(i, propre)
        }
    }

    val trichesPossibles: Boolean get() = pret && natTrichesPossibles()
    val imagesParSeconde: Double get() = if (pret) natImagesParSeconde() else 59.7275
    val frequence: Int get() = if (pret) natFrequence() else 32768
    val clarte: Int get() = if (pret) natClarte() else -1
    val imagesEmulees: Int get() = if (pret) natImagesEmulees() else 0

    fun sauverEtat(): ByteArray? = if (romChargee) natSauver() else null
    fun restaurerEtat(e: ByteArray) = romChargee && natRestaurer(e)
}
