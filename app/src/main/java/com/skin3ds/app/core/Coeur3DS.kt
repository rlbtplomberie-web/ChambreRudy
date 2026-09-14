package com.skin3ds.app.core

import android.content.Context
import java.io.File

/**
 * Bits de la manette Nintendo 3DS, dans les identifiants libretro.
 *
 * Le bouton nomme « croix » sur la console est le X : il ne faut pas le
 * confondre avec la croix directionnelle, qui a ses quatre fleches.
 */
object Pad {
    /** Bits de la manette Nintendo 3DS, dans les identifiants libretro. */
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
    /** Gachettes du fond, propres a la 3DS. */
    const val ZL = 1 shl 12
    const val ZR = 1 shl 13
}

/**
 * Facade du coeur Citra.
 *
 * Elle ne fait qu'ouvrir la bibliotheque et relayer les appels : toute la
 * mecanique libretro vit dans pont.c.
 */
class Coeur3DS(ctx: Context) {

    companion object {
        init {
            // Citra est ecrit en C++ et reclame cette bibliotheque. On la
            // charge avant la notre, qui ouvrira le coeur ensuite.
            try { System.loadLibrary("c++_shared") } catch (_: Throwable) {}
            System.loadLibrary("skin3ds")
        }

        /**
         * Resolutions internes acceptees par Citra, mot pour mot.
         *
         * La console dessine en 480 sur 272 : a x5 l'image est calculee en
         * 2400 sur 1360. La taille affichee ne change jamais, seule la
         * pixelisation diminue.
         */
        /**
         * Facteur de resolution interne accepte par Citra. L'ecran du haut est
         * dessine en 400 sur 240 : a x4 il est calcule en 1600 sur 960.
         */
        /* Valeurs exactes attendues par Citra : « 1x (Native) », puis « 2x »
           et suivants. Un libelle qu'il ne reconnait pas est ignore. */
        val RESOLUTIONS = listOf("1x (Native)", "2x", "3x", "4x",
                                 "5x", "6x", "7x", "8x")
        val LIBELLES = listOf("Natif", "Natif ×2", "Natif ×3", "Natif ×4",
                              "Natif ×5", "Natif ×6", "Natif ×7", "Natif ×8")
    }

    private external fun natInit(chemin: String, dossier: String, cache: String): Boolean
    private external fun natGlInit(w: Int, h: Int): Boolean
    private external fun natGlTaille(w: Int, h: Int): Boolean
    private external fun natCharger(fichier: String): Boolean
    private external fun natEjecter()
    private external fun natReset()
    private external fun natGlImage(boutons: Int, sx: Float, sy: Float): Int
    private external fun natGlDessiner(x: Int, y: Int, w: Int, h: Int,
                                       vueL: Int, vueH: Int, partie: Int): Boolean
    private external fun natStylet(fx: Float, fy: Float)
    private external fun natLirePixels(sortie: IntArray): Int
    private external fun natSon(sortie: ShortArray): Int
    private external fun natSonVider()
    private external fun natVariable(cle: String, valeur: String)
    private external fun natReduction(largeur: Int)
    private external fun natLogiciel(): Int
    private external fun natConvention(c: Int)
    private external fun natStyletEtat(): String
    private external fun natPointeurLu(): Int
    private external fun natClarte(): Int
    private external fun natImages(): Int
    private external fun natMaxL(): Int
    private external fun natMaxH(): Int
    private external fun natFps(): Float
    private external fun natFrequence(): Int
    private external fun natSauver(): ByteArray?
    private external fun natRestaurer(etat: ByteArray): Boolean

    val dossierSysteme: File = File(ctx.filesDir, "systeme").apply { mkdirs() }
    private val dossierCache: File = File(ctx.cacheDir, "3ds").apply { mkdirs() }

    var pret = false
        private set
    var romChargee = false
        private set
    var derniereErreur = ""
        private set
    var cle = ""
        private set

    /**
     * Pixels de la derniere image relue.
     *
     * Dimensionne pour l'image REDUITE, jamais pour la definition interne :
     * c'est la carte graphique qui ramene l'image a cette taille, si bien que
     * la finesse choisie ne change rien a ce tableau.
     */
    val pixels = IntArray(1024 * 640)
    var imageL = 0
        private set
    var imageH = 0
        private set

    val echantillons = ShortArray(48000 * 2)

    init {
        val dossierLib = File(ctx.applicationInfo.nativeLibraryDir)
        val presentes = try {
            (dossierLib.list() ?: emptyArray()).sorted().joinToString(", ")
        } catch (_: Exception) { "illisible" }
        val chemin = File(dossierLib, "libcitra.so")
        pret = if (chemin.exists())
                   try {
                       natInit(chemin.absolutePath, dossierSysteme.absolutePath,
                               dossierCache.absolutePath)
                   } catch (e: Throwable) {
                       derniereErreur = "ouverture impossible : " + e; false
                   }
               else false
        if (!pret && derniereErreur.isEmpty())
            derniereErreur = "Cœur Citra indisponible (libcitra.so)\n\n" +
                             "bibliothèques présentes : " + presentes
    }

    fun glInit(w: Int, h: Int) = natGlInit(w, h)
    fun glTaille(w: Int, h: Int) = natGlTaille(w, h)

    fun chargerJeu(f: File): Boolean {
        if (!pret) return false
        if (!natCharger(f.absolutePath)) {
            derniereErreur = "Jeu refusé par Citra (${f.name})"
            return false
        }
        romChargee = true
        cle = f.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]"), "_") + "_" + f.length()
        derniereErreur = ""
        return true
    }

    fun eteindre() { natEjecter(); romChargee = false }
    fun reinitialiser() = natReset()

    /** Avance d'une image. Renvoie zero si rien n'a ete produit. */
    fun image(boutons: Int, sx: Float, sy: Float) = natGlImage(boutons, sx, sy)

    /**
     * Dessine l'image du coeur dans le rectangle de l'ecran.
     *
     * Le trace est direct, comme dans l'application de reference : la texture
     * produite par le coeur va a l'ecran sans passer par une relecture pixel
     * par pixel.
     */
    /** 0 pour l'ecran du haut, 1 pour celui du bas, 2 pour l'image entiere. */
    fun dessiner(x: Int, y: Int, w: Int, h: Int, vueL: Int, vueH: Int, partie: Int) =
        natGlDessiner(x, y, w, h, vueL, vueH, partie)

    /** Position du stylet sur l'ecran du bas, en fractions du rectangle. */
    fun stylet(fx: Float, fy: Float) = natStylet(fx, fy)

    /** Relit l'image produite. Renvoie true si elle a change. */
    fun lireImage(): Boolean {
        val r = natLirePixels(pixels)
        if (r == 0) return false
        imageL = (r shr 16) and 0xFFFF
        imageH = r and 0xFFFF
        return imageL > 0 && imageH > 0
    }

    fun son(): Int = natSon(echantillons).coerceAtLeast(0)
    fun sonVider() = natSonVider()

    /** true si le coeur dessine en logiciel plutot qu'en materiel. */
    val enLogiciel: Boolean get() = natLogiciel() != 0

    /**
     * Repere des coordonnees du stylet : 0 pour l'image entiere, 1 pour
     * l'ecran du bas seul.
     */
    fun convention(c: Int) = natConvention(c)

    /** Etat du stylet en clair : position touchee et position transmise. */
    val styletEtat: String get() = natStyletEtat()

    /** Nombre de fois ou le coeur a interroge l'ecran tactile. */
    val pointeurLu: Int get() = natPointeurLu()

    /** Clarte moyenne de la derniere image : zero signifie image noire. */
    val clarte: Int get() = natClarte()
    /** Images emulees depuis le chargement. */
    val imagesEmulees: Int get() = natImages()

    val maxLargeur: Int get() = natMaxL()
    val maxHauteur: Int get() = natMaxH()
    val imagesParSeconde: Double get() = natFps().toDouble()
    val frequence: Int get() = natFrequence()

    /** Definition interne, de 1x a 10x. Lue par le coeur au chargement. */
    fun qualite(facteur: Int) {
        val i = (facteur - 1).coerceIn(0, RESOLUTIONS.size - 1)
        natVariable("citra_resolution_factor", RESOLUTIONS[i])
    }

    /** Lissage des textures : ce qui adoucit reellement l'image. */
    fun reglage(cle: String, valeur: String) = natVariable(cle, valeur)

    /** Largeur a laquelle l'image est ramenee avant d'etre relue. */
    fun reduction(largeur: Int) = natReduction(largeur)

    fun sauverEtat(): ByteArray? = natSauver()
    fun restaurerEtat(e: ByteArray): Boolean = natRestaurer(e)
}
