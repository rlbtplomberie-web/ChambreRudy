package com.rudy.chambre.balle

/**
 * Les mesures du terrain, reprises telles quelles du jeu de Rudy.
 *
 * Tout est exprime en fractions de l'ecran : le terrain garde ses proportions
 * sur n'importe quel telephone, exactement comme dans sa version d'origine.
 */
object Terrain {

    // les deux prisons, sur les cotes
    const val BORD_PRISON_G = 0.125f
    const val BORD_PRISON_D = 0.875f
    const val MILIEU = 0.5f

    // la bande de jeu, en hauteur
    const val HAUT = 0.50f
    const val BAS = 0.84f

    // la mise en place : trois joueurs par equipe, en triangle
    data class Depart(val equipe: Int, val x: Float, val y: Float, val heros: Boolean, val nom: String)

    val DEPARTS = listOf(
        Depart(0, 0.15f, 0.69f, true,  "Rudy"),     // milieu arriere
        Depart(0, 0.28f, 0.56f, false, "Sophie"),   // haut
        Depart(0, 0.28f, 0.80f, false, "Mathis"),   // bas
        Depart(1, 0.72f, 0.56f, false, "Shanna"),   // haut
        Depart(1, 0.85f, 0.69f, false, "Théo"),     // milieu arriere
        Depart(1, 0.72f, 0.80f, false, "Carlos")    // bas
    )

    // la physique de la balle, valeurs d'origine
    const val PESANTEUR = 390f          // chute, en pixels par seconde carree
    const val REBOND = 0.38f            // part de vitesse gardee au rebond
    const val FROTTEMENT_FORT = 0.76f   // au rebond
    const val FROTTEMENT_DOUX = 0.90f   // quand elle roule
    const val TIR_Z = 130f              // elan vertical d'un lancer
    const val TIR_Z_CHARGE = 155f       // lancer charge
    const val PASSE_VITESSE = 390f
    const val PASSE_Z = 35f
    const val RAYON = 9f

    // les temps, en secondes
    const val DECOMPTE = 4.15f
    const val DUREE_LANCER = 0.38f
    const val DUREE_ATTRAPE = 0.50f
    const val DUREE_ESQUIVE = 0.38f
    const val DUREE_CHUTE = 0.82f
    const val DUREE_RELEVE = 0.82f
    const val DUREE_TOUCHE = 0.35f
}
