package com.skinpsp.app.core

import android.content.Context
import java.io.File

/**
 * Bits de la manette PSP, dans les identifiants libretro.
 *
 * Le bouton nomme « croix » sur la console est le X : il ne faut pas le
 * confondre avec la croix directionnelle, qui a ses quatre fleches.
 */
object Pad {
    const val CROIX = 1 shl 0       // le bouton X
    const val CARRE = 1 shl 1
    const val SELECT = 1 shl 2
    const val START = 1 shl 3
    const val HAUT = 1 shl 4
    const val BAS = 1 shl 5
    const val GAUCHE = 1 shl 6
    const val DROITE = 1 shl 7
    const val ROND = 1 shl 8
    const val TRIANGLE = 1 shl 9
    const val L = 1 shl 10
    const val R = 1 shl 11
}

/**
 * Facade du coeur PPSSPP.
 *
 * Elle ne fait qu'ouvrir la bibliotheque et relayer les appels : toute la
 * mecanique libretro vit dans pont.c.
 */
class CoeurPSP(ctx: Context) {

    companion object {
        /*
         * Ces deux drapeaux sont declares AVANT le bloc d'initialisation.
         *
         * Kotlin execute le corps d'un objet compagnon dans l'ordre du
         * fichier : declares apres, ils auraient ete remis a leur valeur de
         * depart juste apres avoir ete renseignes, et le journal aurait
         * toujours annonce un coeur non charge.
         */
        /** Le coeur a-t-il ete charge par Android, avec son JNI_OnLoad ? */
        @JvmStatic var noyauCharge = false; private set
        @JvmStatic var erreurNoyau: String? = null; private set

        init {
            // PPSSPP est ecrit en C++ et reclame cette bibliotheque. On la
            // charge avant la notre, qui ouvrira le coeur ensuite.
            try { System.loadLibrary("c++_shared") } catch (_: Throwable) {}
            System.loadLibrary("skinpsp")

            /*
             * Le coeur PPSSPP doit etre charge PAR ANDROID, et pas seulement
             * ouvert par dlopen depuis le code natif.
             *
             * Android n'appelle JNI_OnLoad que pour les bibliotheques passees
             * par System.loadLibrary. PPSSPP y range le pointeur de machine
             * virtuelle dont il se sert pour rattacher ses fils de travail.
             * Ouvert seulement par dlopen, ce pointeur restait vide : le
             * journal du coeur repetait « Couldn't attach thread - g_attach
             * not set », puis l'emulation tombait sur un acces interdit au
             * moment ou le fil graphique demarrait.
             *
             * On le charge donc ici. Le dlopen qui suit, cote natif, retrouve
             * la meme bibliotheque deja en place et se contente d'y prendre
             * les fonctions libretro.
             */
            noyauCharge = try { System.loadLibrary("ppsspp"); true }
                          catch (e: Throwable) { erreurNoyau = e.toString(); false }
        }

        /**
         * Resolutions internes acceptees par PPSSPP, mot pour mot.
         *
         * La console dessine en 480 sur 272 : a x5 l'image est calculee en
         * 2400 sur 1360. La taille affichee ne change jamais, seule la
         * pixelisation diminue.
         */
        val RESOLUTIONS = listOf("1x", "2x", "3x", "4x", "5x", "6x", "8x", "10x")
        val LIBELLES = listOf("Natif", "Natif ×2", "Natif ×3", "Natif ×4",
                              "Natif ×5", "Natif ×6", "Natif ×8", "Natif ×10")
    }

    private external fun natInit(chemin: String, dossier: String, cache: String): Boolean
    private external fun natGlInit(w: Int, h: Int): Boolean
    private external fun natGlTaille(w: Int, h: Int): Boolean
    private external fun natCharger(fichier: String): Boolean
    private external fun natEjecter()
    private external fun natReset()
    private external fun natGlImage(boutons: Int, sx: Float, sy: Float): Int
    private external fun natGlDessiner(x: Int, y: Int, w: Int, h: Int,
                                       vueL: Int, vueH: Int): Boolean
    private external fun natLirePixels(sortie: IntArray): Int
    private external fun natSon(sortie: ShortArray): Int
    private external fun natSonVider()
    private external fun natVariable(cle: String, valeur: String)
    private external fun natRenduLogiciel(oui: Boolean)
    private external fun natReduction(largeur: Int)
    private external fun natLogiciel(): Int
    private external fun natClarte(): Int
    private external fun natImages(): Int
    private external fun natMaxL(): Int
    private external fun natMaxH(): Int
    private external fun natFps(): Float
    private external fun natFrequence(): Int
    private external fun natSauver(): ByteArray?
    private external fun natRestaurer(etat: ByteArray): Boolean

    val dossierSysteme: File = File(ctx.filesDir, "systeme").apply { mkdirs() }
    private val dossierCache: File = File(ctx.cacheDir, "psp").apply { mkdirs() }

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
        val chemin = File(dossierLib, "libppsspp.so")
        pret = if (chemin.exists())
                   try {
                       natInit(chemin.absolutePath, dossierSysteme.absolutePath,
                               dossierCache.absolutePath)
                   } catch (e: Throwable) {
                       derniereErreur = "ouverture impossible : " + e; false
                   }
               else false
        if (!pret && derniereErreur.isEmpty())
            derniereErreur = "Cœur PPSSPP indisponible (libppsspp.so)\n\n" +
                             "bibliothèques présentes : " + presentes
    }

    fun glInit(w: Int, h: Int) = natGlInit(w, h)
    fun glTaille(w: Int, h: Int) = natGlTaille(w, h)

    fun chargerJeu(f: File): Boolean {
        if (!pret) return false
        if (!natCharger(f.absolutePath)) {
            derniereErreur = "Jeu refusé par PPSSPP (${f.name})"
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
    fun dessiner(x: Int, y: Int, w: Int, h: Int, vueL: Int, vueH: Int) =
        natGlDessiner(x, y, w, h, vueL, vueH)

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
        natVariable("ppsspp_internal_resolution", RESOLUTIONS[i])
    }

    /** Lissage des textures : ce qui adoucit reellement l'image. */
    fun reglage(cle: String, valeur: String) = natVariable(cle, valeur)

    /**
     * Rendu logiciel : le coeur calcule ses images lui-meme et nous les remet.
     *
     * C'est la voie qu'empruntent la PlayStation et la Super Nintendo, qui
     * fonctionnent depuis toujours. A poser AVANT de charger un jeu.
     */
    fun renduLogiciel(oui: Boolean) = natRenduLogiciel(oui)

    /** Largeur a laquelle l'image est ramenee avant d'etre relue. */
    fun reduction(largeur: Int) = natReduction(largeur)

    fun sauverEtat(): ByteArray? = natSauver()
    fun restaurerEtat(e: ByteArray): Boolean = natRestaurer(e)
}
