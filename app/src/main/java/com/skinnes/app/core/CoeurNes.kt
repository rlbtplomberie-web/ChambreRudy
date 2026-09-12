package com.skinnes.app.core

import com.skinnes.app.nes.Ines
import com.skinnes.app.nes.Nes
import com.skinnes.app.nes.Pad as PadNes

/**
 * Relie le moteur d'emulation a l'interface attendue par la couche skin.
 * C'est le seul fichier a changer si un jour on remplace le moteur.
 */
class CoeurNes : NesCore {

    private var console: Nes? = null
    override val tampon = IntArray(256 * 240)
    var derniereErreur: String = ""
        private set

    val romChargee: Boolean get() = console != null

    /** Identifie la ROM en cours : sert a nommer son fichier de sauvegarde. */
    var cle: String = ""
        private set

    override fun chargerRom(donnees: ByteArray): Boolean {
        val cart = Ines.charger(donnees)
        if (cart == null) { derniereErreur = Ines.erreur; return false }
        console = Nes(cart)
        cle = empreinte(donnees)
        derniereErreur = ""
        return true
    }

    override fun reinitialiser() { console?.cpu?.reset() }

    /** Coupe le courant : la console est retiree et l'ecran redevient noir. */
    fun eteindre() {
        console = null
        cle = ""
        java.util.Arrays.fill(tampon, 0xFF000000.toInt())
    }

    fun sauverEtat(): ByteArray? = console?.sauverEtat()

    fun restaurerEtat(octets: ByteArray): Boolean = console?.restaurerEtat(octets) ?: false

    /** Empreinte simple : taille plus somme de controle. Suffit a ne pas
     *  melanger les sauvegardes de deux jeux differents. */
    private fun empreinte(d: ByteArray): String {
        var h = -3750763034362895579L
        for (b in d) { h = h xor (b.toLong() and 0xFF); h *= 1099511628211L }
        return d.size.toString() + "_" + java.lang.Long.toHexString(h)
    }

    override fun imageSuivante(boutons: Int) {
        val c = console ?: return
        c.boutons(0, traduire(boutons))
        c.imageSuivante()
        c.imageArgb(tampon)
    }

    /** Les echantillons a envoyer au haut-parleur pour l'image ecoulee. */
    fun son(): ShortArray = console?.son() ?: ShortArray(0)

    /** L'ordre des bits de la couche skin n'est pas celui de la manette NES. */
    private fun traduire(b: Int): Int {
        var r = 0
        if (b and Pad.A != 0) r = r or PadNes.A
        if (b and Pad.B != 0) r = r or PadNes.B
        if (b and Pad.SELECT != 0) r = r or PadNes.SELECT
        if (b and Pad.START != 0) r = r or PadNes.START
        if (b and Pad.HAUT != 0) r = r or PadNes.HAUT
        if (b and Pad.BAS != 0) r = r or PadNes.BAS
        if (b and Pad.GAUCHE != 0) r = r or PadNes.GAUCHE
        if (b and Pad.DROITE != 0) r = r or PadNes.DROITE
        return r
    }
}
