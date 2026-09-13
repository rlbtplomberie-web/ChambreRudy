package com.rudy.chambre.atelier

/**
 * Les crayons de la trousse de Rudy, avec leurs couleurs, leurs epaisseurs et
 * le timbre de leur frottement sur le papier.
 */
class Outil(
    val nom: String,
    val corps: Int,
    val mine: Int,
    val couleur: Int,
    val taille: Float,
    val alpha: Float,
    val grain: Boolean = false,
    val sonF: Float,
    val sonQ: Float,
    val sonV: Float
)

val OUTILS = listOf(
    Outil("Crayon gris", 0xFF8D9298.toInt(), 0xFF4A4A4A.toInt(), 0xFF3A3A3A.toInt(),
          3.4f, .85f, grain = true, sonF = 2800f, sonQ = 1.1f, sonV = .30f),
    Outil("Crayon gras", 0xFF2B2B2B.toInt(), 0xFF111111.toInt(), 0xFF141414.toInt(),
          10f, .95f, sonF = 900f, sonQ = .7f, sonV = .55f),
    Outil("Rouge", 0xFFD8352F.toInt(), 0xFFA71F1A.toInt(), 0xFFD8352F.toInt(),
          5f, .9f, sonF = 1700f, sonQ = .9f, sonV = .40f),
    Outil("Orange", 0xFFEF8321.toInt(), 0xFFC05F0C.toInt(), 0xFFEF8321.toInt(),
          5f, .9f, sonF = 1700f, sonQ = .9f, sonV = .40f),
    Outil("Jaune", 0xFFF2C531.toInt(), 0xFFC69A14.toInt(), 0xFFF2C531.toInt(),
          5f, .9f, sonF = 1800f, sonQ = .9f, sonV = .40f),
    Outil("Vert", 0xFF2F9E5B.toInt(), 0xFF1C6C3C.toInt(), 0xFF2F9E5B.toInt(),
          5f, .9f, sonF = 1600f, sonQ = .9f, sonV = .40f),
    Outil("Bleu", 0xFF2F6FD0.toInt(), 0xFF1C4A95.toInt(), 0xFF2F6FD0.toInt(),
          5f, .9f, sonF = 1600f, sonQ = .9f, sonV = .40f),
    Outil("Violet", 0xFF7B4FD6.toInt(), 0xFF54329B.toInt(), 0xFF7B4FD6.toInt(),
          5f, .9f, sonF = 1500f, sonQ = .9f, sonV = .40f),
    Outil("Feutre", 0xFF1B1B1B.toInt(), 0xFF101010.toInt(), 0xFF121212.toInt(),
          14f, 1f, sonF = 700f, sonQ = .6f, sonV = .48f),
    Outil("Pinceau", 0xFF7A4A25.toInt(), 0xFF3B2410.toInt(), 0xFF2B2B2B.toInt(),
          22f, .62f, sonF = 520f, sonQ = .5f, sonV = .42f)
)

/** Un feutre et un pinceau, pour dessiner autrement que le crayon. */
val FEUTRE = Outil("Feutre", 0xFF1B1B1B.toInt(), 0xFF101010.toInt(), 0xFF121212.toInt(),
                   14f, 1f, sonF = 700f, sonQ = .6f, sonV = .48f)
val PINCEAU = Outil("Pinceau", 0xFF7A4A25.toInt(), 0xFF3B2410.toInt(), 0xFF2B2B2B.toInt(),
                    22f, .62f, sonF = 520f, sonQ = .5f, sonV = .42f)

/** Le timbre de la gomme, plus grave et plus sourd. */
val SON_GOMME = Triple(480f, .6f, .65f)
