package com.skindc.app.core

import android.content.Context
import com.skindc.app.CodeTriche
import java.io.File

/**
 * Facade Kotlin du coeur Flycast.
 *
 * Contrairement aux autres consoles, l'emulation ne rend pas une image en
 * memoire : elle dessine en OpenGL. Les trois fonctions natGl* doivent donc
 * etre appelees depuis le fil du rendu, jamais depuis le fil principal.
 */
class CoeurDc(ctx: Context) {

    companion object {
        init { System.loadLibrary("skindc") }
        /** Noms de BIOS reconnus par Flycast. Les deux premiers sont indispensables. */
        val NOMS_BIOS = listOf("dc_boot.bin", "dc_flash.bin", "naomi_boot.bin")
        /** Resolutions internes proposees, de la native au x8. */
        val RESOLUTIONS = listOf("640x480", "1280x960", "1920x1440", "2560x1920",
                                 "3200x2400", "3840x2880", "4480x3360", "5120x3840")
    }

    private external fun natInit(cheminCoeur: String, dossier: String, cache: String): Boolean
    private external fun natVariable(cle: String, valeur: String)
    private external fun natGlInit(w: Int, h: Int): Boolean
    private external fun natGlTaille(w: Int, h: Int): Boolean
    private external fun natGlImage(boutons: Int, gx: Int, gy: Int, dx: Int, dy: Int,
                                    gl: Int, gr: Int, taille: IntArray): Int
    private external fun natCharger(chemin: String): Boolean
    private external fun natLirePixels(sortie: IntArray): Int
    private external fun natSon(sortie: ShortArray): Int
    private external fun natMaxL(): Int
    private external fun natMaxH(): Int
    private external fun natFrequence(): Double
    private external fun natFps(): Double
    private external fun natReset()
    private external fun natEjecter()
    private external fun natTricheDispo(): Boolean
    private external fun natMemoireTaille(): Int
    private external fun natTricheVider()
    private external fun natTricheAjouter(adresse: Int, valeur: Int, taille: Int,
                                          grosBoutiste: Boolean, repetitions: Int,
                                          pasAdresse: Int, pasValeur: Int)
    private external fun natSauver(): ByteArray?
    private external fun natRestaurer(donnees: ByteArray): Boolean

    val dossierSysteme: File = File(ctx.filesDir, "systeme").apply { mkdirs() }
    /** Flycast cherche le BIOS dans un sous-dossier "dc" du dossier systeme. */
    val dossierBios: File = File(dossierSysteme, "dc").apply { mkdirs() }
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
        val chemin = File(ctx.applicationInfo.nativeLibraryDir, "libflycast.so")
        // le cache sert de dossier temporaire au coeur : il y a besoin d'un
        // endroit ou creer ses fichiers de memoire virtuelle
        val cache = File(ctx.cacheDir, "flycast").apply { mkdirs() }
        pret = if (chemin.exists())
                   natInit(chemin.absolutePath, dossierSysteme.absolutePath, cache.absolutePath)
               else false
        if (!pret) derniereErreur = "Coeur Flycast introuvable (${chemin.name})"
    }

    private fun tailleBios(nom: String) =
        maxOf(File(dossierBios, nom).length(), File(dossierSysteme, nom).length())

    fun biosPresent(): Boolean =
        tailleBios("dc_boot.bin") >= 2 * 1024 * 1024 && tailleBios("dc_flash.bin") >= 128 * 1024

    fun biosManquants(): List<String> =
        NOMS_BIOS.take(2).filter { tailleBios(it) < 1000 }

    /** Etat detaille du BIOS : chemins reels et tailles trouvees. */
    fun etatBios(): String {
        val l = StringBuilder()
        l.append("dossier systeme : ").append(dossierSysteme.absolutePath).append('\n')
        for (d in listOf(dossierBios, dossierSysteme)) {
            l.append(if (d == dossierBios) "  dc/  " else "  ./   ")
            val f = d.listFiles()?.filter { it.name.endsWith(".bin") }
            if (f.isNullOrEmpty()) l.append("(aucun .bin)")
            else l.append(f.joinToString(", ") { it.name + " " + (it.length() / 1024) + " Ko" })
            l.append('\n')
        }
        return l.toString()
    }

    fun chargerJeu(f: File): Boolean {
        if (!pret) return false
        // Sans BIOS, Flycast leve une exception qui traverse le pont natif et
        // fait tomber l'application au lieu de renvoyer une erreur. On refuse
        // donc le chargement nous-memes.
        if (!biosPresent()) {
            derniereErreur = "BIOS manquant : " + biosManquants().joinToString(", ")
            return false
        }
        if (!natCharger(f.absolutePath)) {
            derniereErreur = "Jeu refusé par Flycast (${f.name})"; return false
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

    // ---------- reglages ----------
    /** Resolution interne : 1 = native (640x480), jusqu'a 8. */
    fun qualite(facteur: Int) {
        val i = (facteur - 1).coerceIn(0, RESOLUTIONS.size - 1)
        natVariable("reicast_internal_resolution", RESOLUTIONS[i])
    }

    /**
     * Recompilateur dynamique. Il suppose une disposition memoire que Flycast
     * ne peut pas mettre en place sur cet Android : desactive, l'emulation est
     * plus lente mais stable.
     */
    /**
     * Reglages qu'on peut essayer quand un jeu refuse de demarrer. Les noms
     * viennent de la liste que le coeur reclame, relevee dans le journal.
     */
    fun option(cle: String, valeur: String) = natVariable(cle, valeur)

    fun largeurEtendue(actif: Boolean) {
        natVariable("reicast_widescreen_hack", if (actif) "enabled" else "disabled")
    }

    // ---------- triche ----------
    /**
     * Les codes ecrivent directement dans la memoire vive de la console, et
     * doivent y etre reposes a chaque image : le jeu les remplace sinon.
     * La disponibilite depend donc du coeur, qui doit exposer cette memoire.
     */
    val tricheDisponible: Boolean get() = natTricheDispo()
    val memoireTaille: Int get() = natMemoireTaille()

    fun tricheAppliquer(codes: List<CodeTriche>) {
        natTricheVider()
        for (c in codes) if (c.actif)
            natTricheAjouter(c.adresse, c.valeur, c.taille, c.grosBoutiste,
                             c.repetitions, c.pasAdresse, c.pasValeur)
    }

    /**
     * Remplit [tamponSon] et renvoie le nombre d'echantillons.
     *
     * On ne cree plus de tableau a chaque image : soixante allocations de
     * trois kilo-octets par seconde declenchaient un ramasse-miettes toutes
     * les quelques secondes, et c'etait la l'a-coup regulier.
     */
    fun son(): Int = natSon(tamponSon).coerceAtLeast(0)

    /** Le tampon rempli par [son], a lire sur la longueur renvoyee. */
    val echantillons: ShortArray get() = tamponSon

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

/** Bits de la manette Dreamcast, dans les identifiants libretro. */
object Pad {
    /*
     * Le coeur recoit les touches a la mode libretro, ou les quatre boutons
     * sont ranges par POSITION et non par nom : bit 0 en bas, bit 8 a droite,
     * bit 1 a gauche, bit 9 en haut.
     *
     * Sur la Dreamcast, A est en bas et B a droite, X a gauche et Y en haut.
     * Les valeurs precedentes suivaient les noms de la manette libretro : on
     * appuyait sur B et la console recevait A. On suit donc la position.
     */
    const val A = 1 shl 0
    const val B = 1 shl 8
    const val X = 1 shl 1
    const val Y = 1 shl 9
    const val START = 1 shl 3
    const val HAUT = 1 shl 4
    const val BAS = 1 shl 5
    const val GAUCHE = 1 shl 6
    const val DROITE = 1 shl 7
    /* Les gachettes de la Dreamcast sont analogiques : le coeur les attend
       sous L2 et R2, et non sous L et R qui ne correspondent a rien sur cette
       console. */
    const val L = 1 shl 12
    const val R = 1 shl 13
}
