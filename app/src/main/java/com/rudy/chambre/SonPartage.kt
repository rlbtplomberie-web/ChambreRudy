package com.rudy.chambre

/**
 * Le lien entre la radio de la chambre et les jeux ouverts par-dessus.
 *
 * La chambre s'y annonce a son demarrage ; les jeux s'en servent pour
 * adoucir la musique pendant une partie, puis la remettre en sortant.
 */
object SonPartage {

    var radio: SonChambre? = null

    fun volume(v: Float) {
        try { radio?.majVolume(v) } catch (_: Throwable) {}
    }
}
