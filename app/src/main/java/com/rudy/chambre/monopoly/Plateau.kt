package com.rudy.chambre.monopoly

/**
 * Le plateau du Monopoly de Rudy : ses quarante cases, ses huit groupes de
 * couleurs, ses prix et ses loyers, repris un par un de sa page.
 */

/** Un groupe de terrains : sa couleur, et le prix d'une maison. */
data class Groupe(val couleur: Int, val maison: Int)

val GROUPES = mapOf(
    "brun" to Groupe(0xFF7B4A25.toInt(), 50),
    "ciel" to Groupe(0xFF8FC9E8.toInt(), 50),
    "rose" to Groupe(0xFFD1508F.toInt(), 100),
    "orange" to Groupe(0xFFE08B2A.toInt(), 100),
    "rouge" to Groupe(0xFFC8352F.toInt(), 150),
    "jaune" to Groupe(0xFFE8C92E.toInt(), 150),
    "vert" to Groupe(0xFF2E8B52.toInt(), 200),
    "bleu" to Groupe(0xFF2A4FA8.toInt(), 200)
)

/** Une case du plateau. */
class Case(
    val type: String,
    val nom: String,
    val sous: String = "",
    val groupe: String = "",
    val prix: Int = 0,
    val loyers: List<Int> = emptyList(),
    val montant: Int = 0,
    val picto: String = ""
) {
    var maisons = 0
    var proprio = -1
    var hypothequee = false
}

private fun T(nom: String, groupe: String, prix: Int, loyers: List<Int>) =
    Case("terrain", nom, groupe = groupe, prix = prix, loyers = loyers)
private fun G(nom: String) = Case("gare", nom, prix = 200, picto = "gare")
private fun S(nom: String, picto: String) = Case("service", nom, prix = 150, picto = picto)

/** Ses quarante cases, dans l'ordre. */
fun nouveauPlateau(): List<Case> = listOf(
    Case("depart", "Départ", "Touchez 200 €", picto = "depart"),
    T("Rue des Lilas", "brun", 60, listOf(2, 10, 30, 90, 160, 250)),
    Case("coffre", "Coffre de la ville", picto = "coffre"),
    T("Rue du Fournil", "brun", 60, listOf(4, 20, 60, 180, 320, 450)),
    Case("taxe", "Taxe municipale", montant = 200, picto = "taxe"),
    G("Gare de l'Écluse"),
    T("Avenue du Verger", "ciel", 100, listOf(6, 30, 90, 270, 400, 550)),
    Case("sort", "Coup du sort", picto = "sort"),
    T("Avenue des Tilleuls", "ciel", 100, listOf(6, 30, 90, 270, 400, 550)),
    T("Avenue du Moulin", "ciel", 120, listOf(8, 40, 100, 300, 450, 600)),
    Case("prison", "Prison", "En visite", picto = "prison"),
    T("Rue des Halles", "rose", 140, listOf(10, 50, 150, 450, 625, 750)),
    S("Usine des Eaux", "eau"),
    T("Rue du Marché", "rose", 140, listOf(10, 50, 150, 450, 625, 750)),
    T("Rue Saint-Gilles", "rose", 160, listOf(12, 60, 180, 500, 700, 900)),
    G("Gare du Pont-Neuf"),
    T("Bd des Acacias", "orange", 180, listOf(14, 70, 200, 550, 750, 950)),
    Case("coffre", "Coffre de la ville", picto = "coffre"),
    T("Bd du Beffroi", "orange", 180, listOf(14, 70, 200, 550, 750, 950)),
    T("Bd de l'Arsenal", "orange", 200, listOf(16, 80, 220, 600, 800, 1000)),
    Case("parc", "Square public", "Repos", picto = "parc"),
    T("Av. de la Comédie", "rouge", 220, listOf(18, 90, 250, 700, 875, 1050)),
    Case("sort", "Coup du sort", picto = "sort"),
    T("Av. des Thermes", "rouge", 220, listOf(18, 90, 250, 700, 875, 1050)),
    T("Av. du Grand Théâtre", "rouge", 240, listOf(20, 100, 300, 750, 925, 1100)),
    G("Gare de la Citadelle"),
    T("Cours des Platanes", "jaune", 260, listOf(22, 110, 330, 800, 975, 1150)),
    T("Cours de l'Observatoire", "jaune", 260, listOf(22, 110, 330, 800, 975, 1150)),
    S("Centrale électrique", "ampoule"),
    T("Cours du Belvédère", "jaune", 280, listOf(24, 120, 360, 850, 1025, 1200)),
    Case("allez", "Allez en prison", picto = "allez"),
    T("Prom. des Cygnes", "vert", 300, listOf(26, 130, 390, 900, 1100, 1275)),
    T("Prom. du Palais", "vert", 300, listOf(26, 130, 390, 900, 1100, 1275)),
    Case("coffre", "Coffre de la ville", picto = "coffre"),
    T("Prom. des Ambassades", "vert", 320, listOf(28, 150, 450, 1000, 1200, 1400)),
    G("Gare Maritime"),
    Case("sort", "Coup du sort", picto = "sort"),
    T("Quai des Lumières", "bleu", 350, listOf(35, 175, 500, 1100, 1300, 1500)),
    Case("taxe", "Taxe de luxe", montant = 100, picto = "taxe"),
    T("Esplanade Royale", "bleu", 400, listOf(50, 200, 600, 1400, 1700, 2000))
)

/** Ses six jetons. */
val JETONS = listOf("Chapeau", "Voiture", "Bateau", "Chien", "Brouette", "Bougeoir")

/** Ses couleurs de joueurs et ses noms d'adversaires. */
val COULEURS = listOf(0xFFE8483C.toInt(), 0xFF3D7FD6.toInt(), 0xFFE5A72C.toInt(),
                      0xFF6F4BD8.toInt(), 0xFF2FA37A.toInt(), 0xFFD84F9C.toInt())
val NOMS_IA = listOf("Camille", "Hugo", "Nadia", "Ludo", "Sacha")
