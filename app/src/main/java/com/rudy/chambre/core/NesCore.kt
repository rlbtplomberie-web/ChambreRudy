package com.rudy.chambre.core

/**
 * Tout ce que la couche skin attend d'un moteur d'emulation.
 * L'ecran est toujours 256x240 en ARGB_8888.
 */
interface NesCore {
    val largeur: Int get() = 256   // redefini par l'interface commune
    val hauteur: Int get() = 240

    /** true si la ROM est acceptee. */
    fun chargerRom(donnees: ByteArray): Boolean

    /** Fait avancer d'une image avec l'etat courant des boutons. */
    fun imageSuivante(boutons: Int)

    /** Pixels de la derniere image, taille largeur*hauteur. */
    val tampon: IntArray

    fun reinitialiser()
}

/**
 * Moteur de remplacement : affiche une mire animee.
 * Il existe pour que l'application soit compilable et testable des maintenant,
 * avant le branchement du vrai coeur d'emulation.
 */
class CoeurMire : NesCore {
    override val tampon = IntArray(256 * 240)
    private var t = 0
    private var boutonsVus = 0

    override fun chargerRom(donnees: ByteArray) = donnees.size > 16

    override fun reinitialiser() { t = 0 }

    override fun imageSuivante(boutons: Int) {
        boutonsVus = boutons
        t++
        // bandes de couleur qui defilent, plus un carre pilote par la croix
        var i = 0
        for (y in 0 until 240) {
            for (x in 0 until 256) {
                val bande = ((x + t) / 32) % 8
                var c = COULEURS[bande]
                if (y < 24) c = 0xFF101010.toInt()
                tampon[i++] = c
            }
        }
        val cx = 128 + dx
        val cy = 130 + dy
        for (y in (cy - 10)..(cy + 10)) {
            if (y !in 24 until 240) continue
            for (x in (cx - 10)..(cx + 10)) {
                if (x !in 0 until 256) continue
                tampon[y * 256 + x] = if (boutons and (Pad.A or Pad.B) != 0)
                    0xFFFF2020.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        if (boutons and Pad.GAUCHE != 0) dx -= 2
        if (boutons and Pad.DROITE != 0) dx += 2
        if (boutons and Pad.HAUT != 0) dy -= 2
        if (boutons and Pad.BAS != 0) dy += 2
        dx = dx.coerceIn(-110, 110); dy = dy.coerceIn(-90, 100)
    }

    private var dx = 0
    private var dy = 0

    private companion object {
        val COULEURS = intArrayOf(
            0xFF6B6B6B.toInt(), 0xFFC8C800.toInt(), 0xFF00C8C8.toInt(), 0xFF00C800.toInt(),
            0xFFC800C8.toInt(), 0xFFC80000.toInt(), 0xFF0000C8.toInt(), 0xFF101010.toInt()
        )
    }
}
