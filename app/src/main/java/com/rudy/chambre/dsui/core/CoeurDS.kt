package com.rudy.chambre.dsui.core

import android.content.Context
import java.io.File

/**
 * Facade Kotlin du coeur melonDS.
 *
 * Contrairement aux autres consoles, l'emulation ne rend pas une image en
 * memoire : elle dessine en OpenGL. Les trois fonctions natGl* doivent donc
 * etre appelees depuis le fil du rendu, jamais depuis le fil principal.
 */
class CoeurDS(ctx: Context) {

    companion object {
        init { System.loadLibrary("pont_ds") }
        /** Noms de BIOS reconnus par melonDS. Les deux premiers sont indispensables. */
        /** Resolutions internes, de la native au x8. */
        /**
         * Resolutions internes acceptees par le coeur, mot pour mot.
         *
         * Un coeur libretro refuse en silence toute valeur qu'il n'a pas
         * declaree. J'en proposais huit, dont cinq n'existent pas dans sa
         * liste — 2560x1920 et au-dela — et elles etaient purement ignorees.
         */
        /**
         * Facteurs de resolution interne acceptes par melonDS. La console
         * dessine en 256 x 192 par ecran ; le facteur multiplie cette taille.
         */
        val RESOLUTIONS = listOf("1", "2", "3", "4", "5", "6", "7", "8")
        val LIBELLES = listOf("Natif", "Natif ×2", "Natif ×3", "Natif ×4",
                              "Natif ×5", "Natif ×6", "Natif ×7", "Natif ×8")
    }

    private external fun natInit(cheminCoeur: String, dossier: String, cache: String): Boolean
    private external fun natVariable(cle: String, valeur: String)
    private external fun natGlInit(w: Int, h: Int): Boolean
    private external fun natGlTaille(w: Int, h: Int): Boolean
    private external fun natGlImage(boutons: Int, gx: Int, gy: Int, dx: Int, dy: Int,
                                    gl: Int, gr: Int, taille: IntArray): Int
    private external fun natCharger(chemin: String): Boolean
    private external fun natRenduMateriel(): Boolean
    private external fun natLirePixels(sortie: IntArray): Int
    private external fun natStylet(fx: Float, fy: Float)
    private external fun natSonVider()
    private external fun natSon(sortie: ShortArray): Int
    private external fun natMaxL(): Int
    private external fun natMaxH(): Int
    private external fun natFrequence(): Double
    private external fun natFps(): Double
    private external fun natReset()
    private external fun natEjecter()
    private external fun natSauver(): ByteArray?
    private external fun natRestaurer(donnees: ByteArray): Boolean

    val dossierSysteme: File = File(ctx.filesDir, "systeme").apply { mkdirs() }
    /** La Nintendo 64 n'a pas de BIOS : le dossier ne sert qu'au catalogue
     *  des ROMs, que le coeur y cherche. */
    val dossierBios: File = dossierSysteme
    val pret: Boolean
    var romChargee = false
        private set
    var derniereErreur = ""
        private set
    var cle = ""
        private set

    /** Taille utile de l'image, renseignee par le coeur a chaque tour. */
    val taille = IntArray(2)
    /** Pixels de la derniere image relue, en ARGB. */
    val pixels = IntArray(1280 * 1024)
    var imageL = 0
        private set
    var imageH = 0
        private set
    private val tamponSon = ShortArray(48000 * 2)

    init {
        val chemin = File(ctx.applicationInfo.nativeLibraryDir, "libmelonds.so")
        // le cache sert de dossier temporaire au coeur : il y a besoin d'un
        // endroit ou creer ses fichiers de memoire virtuelle
        val cache = File(ctx.cacheDir, "n64").apply { mkdirs() }
        pret = if (chemin.exists())
                   natInit(chemin.absolutePath, dossierSysteme.absolutePath, cache.absolutePath)
               else false
        if (!pret) derniereErreur = "Coeur melonDS introuvable (${chemin.name})"
    }

    fun chargerJeu(f: File): Boolean {
        if (!pret) return false

        if (!natCharger(f.absolutePath)) {
            derniereErreur = "Jeu refusé par melonDS (${f.name})"; return false
        }
        romChargee = true
        cle = f.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]"), "_") + "_" + f.length()
        derniereErreur = ""
        return true
    }

    // ---------- appels depuis le fil OpenGL ----------
    fun glInit(w: Int, h: Int) = natGlInit(w, h)
    fun glTaille(w: Int, h: Int) = natGlTaille(w, h)

    /** Avance d'une image, renvoie l'identifiant de texture (0 si rien). */
    fun glImage(boutons: Int, gx: Float, gy: Float, dx: Float, dy: Float,
                gl: Float, gr: Float): Int {
        if (!romChargee) return 0
        fun q(v: Float) = (v.coerceIn(-1f, 1f) * 32767f).toInt()
        return natGlImage(boutons, q(gx), q(gy), q(dx), q(dy), q(gl), q(gr), taille)
    }

    /**
     * Relit l'image rendue par le coeur. A appeler depuis le fil OpenGL,
     * juste apres [glImage]. Renvoie true si une image est disponible.
     */
    fun lireImage(): Boolean {
        val r = natLirePixels(pixels)
        if (r == 0) return false
        imageL = (r ushr 16) and 0xFFFF
        imageH = r and 0xFFFF
        return true
    }

    /**
     * Position du stylet sur l'ecran du bas, en fractions du rectangle.
     * Une valeur negative signifie que le doigt s'est leve.
     */
    fun stylet(fx: Float, fy: Float) = natStylet(fx, fy)

    // ---------- reglages ----------
    /** Resolution interne : 1 = native (640x480), jusqu'a 8. */
    /**
     * Resolution interne.
     *
     * Elle n'a d'effet qu'avec un moteur graphique materiel. Angrylion, le
     * moteur logiciel, reproduit exactement la puce de la console : il dessine
     * a la resolution d'origine et ne sait pas faire autrement. C'est le prix
     * de son exactitude.
     */
    fun qualite(facteur: Int) {
        val i = (facteur - 1).coerceIn(0, RESOLUTIONS.size - 1)
        natVariable("melonds_opengl_resolution", RESOLUTIONS[i])
        natVariable("melonds_render_mode", "opengl")
        derniereResolution = RESOLUTIONS[i]
    }

    /** Derniere resolution demandee, pour le journal. */
    var derniereResolution = "1"
        private set

    /**
     * Reglages qu'on peut essayer quand un jeu refuse de demarrer. Les noms
     * viennent de la liste que le coeur reclame, relevee dans le journal.
     */
    fun option(cle: String, valeur: String) = natVariable(cle, valeur)

    fun largeurEtendue(actif: Boolean) {
        natVariable("melonds_screen_filter", if (actif) "linear" else "nearest")
    }

    fun son(): Int = natSon(tamponSon).coerceAtLeast(0)

    /** Jette les echantillons en attente : a faire a chaque reprise. */
    fun sonVider() = natSonVider()

    /** Le tampon rempli par [son], a lire sur la longueur renvoyee. */
    val echantillons: ShortArray get() = tamponSon

    /** true si le coeur dessine lui-meme en OpenGL, false s'il rend en memoire. */
    val rendulMateriel: Boolean get() = natRenduMateriel()

    /** Taille maximale que le coeur peut dessiner : le tampon doit la couvrir. */
    val maxLargeur: Int get() = natMaxL()
    val maxHauteur: Int get() = natMaxH()

    val frequence: Int get() = natFrequence().toInt()
    val imagesParSeconde: Double get() = natFps()

    fun reinitialiser() { if (romChargee) natReset() }
    fun eteindre() { natEjecter(); romChargee = false; cle = "" }
    fun sauverEtat(): ByteArray? = if (romChargee) natSauver() else null
    fun restaurerEtat(o: ByteArray): Boolean = romChargee && natRestaurer(o)
}

/**
 * Bits de la manette Nintendo 64, dans les identifiants libretro.
 *
 * Les quatre boutons C se presentent au coeur comme le second stick : c'est la
 * convention de Mupen64Plus, et elle permet de les jouer aussi bien au doigt
 * qu'au stick droit d'une manette physique.
 */
object Pad {
    /** Bits de la manette Nintendo DS, dans les identifiants libretro. */
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
