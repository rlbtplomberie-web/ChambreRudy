package com.rudy.chambre

/**
 * Le decor de la chambre, en mesures.
 *
 * Toutes les positions sont des fractions de l'image : un objet reste a sa
 * place quelle que soit la taille de l'ecran. Ce sont les memes valeurs que
 * celles mises au point dans la version web.
 */
object Decor {

    /** Un rectangle pose sur l'image, en fractions. */
    data class Zone(val x: Float, val y: Float, val l: Float, val h: Float)

    // ----- les trois vues de la chambre -----
    const val RATIO_MUR = 1672f / 941f      // bureau et baie vitree
    const val RATIO_LIT = 960f / 1024f      // le mur du lit, en hauteur

    val VUES = listOf("bureau", "fenetre", "lit")

    // ----- l'intro, mesures reprises telles quelles de la version web -----
    const val RATIO_INTRO = 1536f / 1024f
    /** Le carton sur la photo : face avant, et sa profondeur. */
    val BOITE = Zone(0.7880f, 0.4330f, 0.1055f, 0.1065f)
    const val BOITE_PROFONDEUR = 0.0355f

    /**
     * Ou les consoles se posent quand elles jaillissent du carton.
     *
     * Ses quatorze places, en fractions d'ecran depuis le carton, avec
     * l'inclinaison de chaque console — c'est ce qui donne son etoile.
     */
    val ETOILE = listOf(
        Triple(-.60f, -.32f, 40f), Triple(-.30f, -.42f, 10f), Triple(-.02f, -.34f, -30f),
        Triple(-.66f, -.12f, 60f), Triple(-.34f, -.16f, 90f), Triple(-.04f, -.10f, 120f),
        Triple(-.62f, .10f, 20f), Triple(-.32f, .12f, 50f), Triple(-.02f, .16f, 80f),
        Triple(-.48f, -.28f, 150f), Triple(-.18f, -.26f, 170f), Triple(-.50f, .30f, -10f),
        Triple(-.16f, .30f, 30f), Triple(-.34f, -.02f, 110f)
    )
    const val ZOOM_INTRO = 1.55f          // rapprochement final
    const val TRAVELLING_MS = 7000L       // duree du travelling
    const val ATTENTE_MS = 1600L          // avant que la camera parte
    const val SECOUSSE_MS = 800L          // apres l'arrivee
    const val OUVERTURE_MS = 1900L        // le carton s'ouvre
    const val CONSOLES_MS = 2150L         // les consoles jaillissent

    // ----- les objets du bureau -----
    val TELE    = Zone(528f / 1672f, 437f / 941f, 294f / 1672f, 143f / 941f)
    val RADIO   = Zone(236f / 1672f, 498f / 941f, 158f / 1672f, 158f / 1672f * 0.62f)
    val CARTON  = Zone(1120f / 1672f, 456f / 941f, 196f / 1672f, 146f / 941f)
    val POSE    = Zone(872f / 1672f, 512f / 941f, 236f / 1672f, 104f / 941f)
    val TIROIR  = Zone(74f / 1672f, 712f / 941f, 268f / 1672f, 114f / 941f)
    val MEUBLE  = Zone(850f / 1672f, 26f / 941f, 510f / 1672f, 438f / 941f)

    // ----- les objets de la baie vitree -----
    val SERRURE = Zone(586f / 1672f, 520f / 941f, 26f / 1672f, 26f / 1672f * 1.3f)
    val LIVRE   = Zone(1298f / 1672f, 684f / 941f, 36f / 1672f, 36f / 1672f)

    /** Les treize consoles du carton, dans l'ordre ou elles sortent. */
    data class ConsolePosee(val id: String, val nom: String, val image: String, val video: String)

    val CONSOLES = listOf(
        ConsolePosee("nes",   "NES",                      "c_nes.webp",   "vid_nes.mp4"),
        ConsolePosee("gb",    "Game Boy / Game Boy Color","c_gbduo.webp", "vid_gbduo.mp4"),
        ConsolePosee("snes",  "Super Nintendo",           "c_snes.webp",  "vid_snes.mp4"),
        ConsolePosee("md",    "Mega Drive",               "c_md.webp",    "vid_md.mp4"),
        ConsolePosee("gba",   "Game Boy Advance",         "c_gba.webp",   "vid_gba.mp4"),
        ConsolePosee("ds",    "Nintendo DS",              "c_ds.webp",    "vid_ds.mp4"),
        ConsolePosee("ps1",   "PlayStation",              "c_ps1.webp",   "vid_ps1.mp4"),
        ConsolePosee("n64",   "Nintendo 64",              "c_n64.webp",   "vid_n64.mp4"),
        ConsolePosee("dc",    "Dreamcast",                "c_dc.webp",    "vid_dc.mp4"),
        ConsolePosee("psp",   "PSP",                      "c_psp.webp",   "vid_psp.mp4"),
        ConsolePosee("gc",    "GameCube",                 "c_gc.webp",    "vid_gc.mp4"),
        ConsolePosee("wii",   "Wii",                      "c_wii.webp",   "vid_wii.mp4"),
        ConsolePosee("3ds",   "Nintendo 3DS",             "c_3ds.webp",   "vid_3ds.mp4")
    )

    /** Les sept morceaux de la radio, dans l'ordre. */
    val MUSIQUES = listOf(
        "mus1.m4a" to "Ocarina of Time — File Select",
        "mus2.m4a" to "Ocarina of Time — Thème principal",
        "mus3.m4a" to "Ocarina of Time — Lon Lon Ranch",
        "mus4.m4a" to "Majora's Mask — Song of Healing",
        "mus5.m4a" to "Majora's Mask — Thème des Géants",
        "mus6.m4a" to "Ocarina of Time — Vallée Gerudo",
        "mus7.m4a" to "The Wind Waker — Ocean"
    )
}
