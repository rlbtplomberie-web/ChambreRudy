package com.rudy.chambre

/**
 * Le lien entre la radio de la chambre et les jeux ouverts par-dessus.
 *
 * La chambre s'y annonce a son demarrage ; les jeux s'en servent pour
 * adoucir la musique pendant une partie, puis la remettre en sortant.
 */
object SonPartage {

    var radio: SonChambre? = null

    /**
     * L'instant ou une console vient d'etre lancee.
     *
     * En fermant la vitrine, la chambre repasse devant une fraction de
     * seconde avant que l'emulateur ne la recouvre : elle relancait alors sa
     * musique, qui se melait ensuite a celle du jeu. On retient donc l'heure
     * du depart, et la chambre ne reprend pas sa musique dans la foulee.
     */
    var consoleLanceeA = 0L

    /** Vrai si une console vient de partir a l'instant. */
    fun consoleVientDePartir() = System.currentTimeMillis() - consoleLanceeA < 4000L

    fun volume(v: Float) {
        try { radio?.majVolume(v) } catch (_: Throwable) {}
    }
}
