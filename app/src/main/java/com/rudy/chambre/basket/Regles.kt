package com.rudy.chambre.basket

/**
 * Les regles du jeu de paniers de Rudy.
 *
 * Dix lancers, sept paniers pour gagner. A chaque lancer, une jauge avec une
 * zone verte qui se deplace et retrecit : le tir part quand on rappuie.
 */
class Regles {

    /** Un reglage de jauge : centre du vert, largeur du vert, orange, rouge clair. */
    data class Jauge(val g: Float, val gs: Float, val o: Float, val lr: Float)

    /** Ses dix jauges, dans l'ordre, de la plus facile a la plus serree. */
    val jauges = listOf(
        Jauge(50f, 8f, 9f, 15f),   // 1 centre
        Jauge(25f, 8f, 9f, 15f),   // 2 gauche
        Jauge(90f, 7f, 8f, 14f),   // 3 extreme droite
        Jauge(50f, 4f, 7f, 13f),   // 4 centre, vert plus petit
        Jauge(70f, 9f, 7f, 13f),   // 5 droite, vert plus large
        Jauge(10f, 6f, 8f, 14f),   // 6 extreme gauche
        Jauge(38f, 5f, 6f, 12f),   // 7 gauche, difficile
        Jauge(62f, 7f, 10f, 12f),  // 8 droite
        Jauge(80f, 4f, 6f, 11f),   // 9 droite, tres petit
        Jauge(50f, 3f, 5f, 10f)    // 10 centre, minuscule
    )

    var essais = 0
    var paniers = 0
    var finie = false

    var vise = false
    var position = 0f          // l'aiguille, de 0 a 100
    private var sens = 1f

    fun jauge() = jauges[essais.coerceIn(0, jauges.size - 1)]

    /** Son aller-retour : la jauge parcourt 100 en 1,2 seconde. */
    fun avancer(dt: Float) {
        if (!vise) return
        position += sens * (dt / 1.2f) * 100f
        if (position >= 100f) { position = 100f; sens = -1f }
        if (position <= 0f) { position = 0f; sens = 1f }
    }

    fun commencerAViser() {
        if (finie || essais >= 10) return
        vise = true; position = 0f; sens = 1f
    }

    /** Son « classify » : ou l'aiguille s'est arretee. */
    fun juger(): String {
        val q = jauge()
        val d = kotlin.math.abs(position - q.g)
        return when {
            d <= q.gs / 2f -> "vert"
            d <= q.gs / 2f + q.o -> "orange"
            d <= q.gs / 2f + q.o + q.lr -> "rougeclair"
            else -> "rougefonce"
        }
    }

    /** Le tir part. Renvoie le jugement, et compte le panier. */
    fun tirer(): String {
        val resultat = juger()
        vise = false
        essais++
        if (resultat == "vert" || resultat == "orange") paniers++
        if (essais >= 10) finie = true
        return resultat
    }

    /** Ses messages, mot pour mot. */
    fun message(resultat: String) = when (resultat) {
        "vert" -> "PARFAIT — PANIER !"
        "orange" -> "CERCEAU — PANIER !"
        "rougeclair" -> "CERCEAU — RATÉ !"
        else -> "TROP COURT !"
    }

    fun gagne() = paniers >= 7
    fun titreFin() = if (gagne()) "GAGNÉ ! 🏀" else "PERDU"
    fun texteFin() =
        if (gagne()) "Tu as marqué $paniers paniers en $essais lancers."
        else "Tu as marqué $paniers paniers. Il fallait en marquer 7 sur 10."

    fun rejouer() { essais = 0; paniers = 0; finie = false; vise = false; position = 0f; sens = 1f }
}
