package com.rudy.chambre

/**
 * Le registre des consoles.
 *
 * Chaque emulateur reste tel que Rudy l'a ecrit, dans son propre paquet : on se
 * contente ici de savoir quel ecran ouvrir, et ou cet emulateur range le dossier
 * de ROMs qu'on lui choisit depuis la chambre.
 */
data class Console(
    val id: String,
    val nom: String,
    val activite: String,
    /** Fichier de reglages de l'emulateur, et cle du dossier dedans. */
    val prefs: String,
    val cle: String,
    val extensions: List<String>,
    /** Fichier du coeur attendu par cet emulateur, s'il en charge un. */
    val coeur: String? = null,
    /** Application installee a part qui doit prendre la main, s'il y en a une. */
    val paquetVoisin: String? = null
)

object Consoles {
    val TOUTES = listOf(
        Console("nes",  "NES",              "com.skinnes.app.MainActivity",
                "skin_nes",  "dossier_roms",  listOf(".nes", ".zip")),
        Console("snes", "Super Nintendo",   "com.skinsnes.app.MainActivity",
                "skin_snes", "dossier_roms",  listOf(".sfc", ".smc", ".fig", ".swc", ".zip"), coeur = "libsnes9x.so"),
        Console("gb",   "Game Boy / Color", "com.gbgbc.app.MainActivity",
                "gbgbc",     "dossier_jeux",  listOf(".gb", ".gbc", ".zip"), coeur = "libgambatte.so"),
        Console("gba",  "Game Boy Advance", "com.skingba.app.MainActivity",
                "skingba",   "dossier_jeux",  listOf(".gba", ".zip"), coeur = "libmgba.so"),
        Console("md",   "Mega Drive",       "com.skinmd.app.MainActivity",
                "skinmd",    "dossier_jeux",  listOf(".md", ".gen", ".smd", ".bin", ".68k", ".sgd", ".zip"), coeur = "libgenesisplusgx.so"),
        Console("ps1",  "PlayStation",      "com.skinps1.app.MainActivity",
                "skin_ps1",  "dossier_roms",  listOf(".cue", ".bin", ".chd", ".pbp", ".iso", ".m3u"), coeur = "libpcsx.so"),
        Console("dc",   "Dreamcast",        "com.skindc.app.MainActivity",
                "skin_dc",   "dossier_roms",  listOf(".gdi", ".cdi", ".chd", ".cue"), coeur = "libflycast.so"),
        Console("ds",   "Nintendo DS",      "com.skinds.app.MainActivity",
                "skin_ds",   "dossier_roms",  listOf(".nds", ".zip"), coeur = "libmelonds.so"),
        Console("gc",   "GameCube / Wii",   "com.skingc.app.MainActivity",
                "skin_gc",   "dossier_roms",  listOf(".iso", ".gcm", ".gcz", ".rvz", ".ciso",
                                                     ".wbfs", ".wad", ".dol", ".elf", ".zip"),
                coeur = "libdolphin.so"),
        Console("psp",  "PSP",              "com.skinpsp.app.JeuActivity",
                "skin_psp",  "dossier_roms",  listOf(".iso", ".cso", ".pbp", ".chd", ".elf"),
                coeur = "libppsspp_jni.so"),
        Console("3ds",  "Nintendo 3DS",     "com.skin3ds.app.MainActivity",
                "skin_3ds",  "dossier_roms",  listOf(".3ds", ".cci", ".cxi", ".app"), coeur = "libcitra.so")
    )

    fun parId(id: String): Console? = TOUTES.firstOrNull { it.id == id }
}
