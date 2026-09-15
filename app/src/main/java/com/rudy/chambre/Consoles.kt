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
        /*
         * La Nintendo 64 ouvre son CATALOGUE, pas son ecran d'accueil.
         *
         * SplashActivity reclame l'acces aux fichiers avant toute chose, et
         * depuis Android 11 cette autorisation ne peut plus etre accordee :
         * sa verification ne peut jamais aboutir. C'est elle, et elle seule,
         * qui affichait « This app cannot proceed without these permissions ».
         *
         * Son catalogue, lui, ne demande rien. Il sait deja ou sont les jeux —
         * la Chambre lui a fait installer ses donnees au reveil de son
         * application — et c'est de la qu'on lance une partie.
         */
        Console("n64",  "Nintendo 64",      "paulscode.android.mupen64plusae.GalleryActivity",
                "skin_n64",  "dossier_roms",  listOf(".n64", ".z64", ".v64", ".zip"),
                // pas de moteur unique a verifier : Mupen64Plus apporte toute
                // une serie de bibliotheques, aucune ne portant ce nom
                coeur = null),
        Console("ps1",  "PlayStation",      "com.skinps1.app.MainActivity",
                "skin_ps1",  "dossier_roms",  listOf(".cue", ".bin", ".chd", ".pbp", ".iso", ".m3u"), coeur = "libpcsx.so"),
        Console("dc",   "Dreamcast",        "com.skindc.app.MainActivity",
                "skin_dc",   "dossier_roms",  listOf(".gdi", ".cdi", ".chd", ".cue"), coeur = "libflycast.so"),
        Console("ds",   "Nintendo DS",      "com.skinds.app.MainActivity",
                "skin_ds",   "dossier_roms",  listOf(".nds", ".zip"), coeur = "libmelonds.so"),
        /*
         * Ce sont les deux entrees du vrai Dolphin modifie par SkinGC.
         * Ne pas les rediriger vers com.skingc : cet ancien ecran libretro
         * n'a pas les menus Dolphin ni les pads installes dans Dolphin.
         */
        Console("gc",   "GameCube",         "org.dolphinemu.dolphinemu.activities.EmulationActivity",
                "skin_gc",   "dossier_roms",  listOf(".iso", ".gcm", ".gcz", ".rvz", ".ciso",
                                                     ".dol", ".elf", ".zip"),
                // Dolphin apporte ses propres bibliotheques, pas un fichier unique
                coeur = null),
        /* Meme Dolphin et meme interface complete, le format du jeu decide Wii/GC. */
        Console("wii",  "Wii",              "org.dolphinemu.dolphinemu.activities.EmulationActivity",
                "skin_wii",  "dossier_roms",  listOf(".iso", ".wbfs", ".wad", ".rvz", ".ciso",
                                                     ".gcz", ".dol", ".elf", ".zip"),
                coeur = null),
        Console("psp",  "PSP",              "com.skinpsp.app.JeuActivity",
                "skin_psp",  "dossier_roms",  listOf(".iso", ".cso", ".pbp", ".chd", ".elf"),
                coeur = "libppsspp_jni.so"),
        Console("3ds",  "Nintendo 3DS",     "com.skin3ds.app.MainActivity",
                "skin_3ds",  "dossier_roms",  listOf(".3ds", ".cci", ".cxi", ".app"), coeur = "libcitra.so")
    )

    fun parId(id: String): Console? = TOUTES.firstOrNull { it.id == id }
}
