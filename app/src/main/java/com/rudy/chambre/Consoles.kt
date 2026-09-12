package com.rudy.chambre

/**
 * Une console de la chambre : son habillage, son coeur d'emulation et les
 * extensions de ROM qu'elle accepte.
 *
 * [coeur] est le nom de la bibliotheque native placee par le workflow dans
 * jniLibs. La NES n'en a pas : son emulateur est ecrit en Kotlin.
 */
data class Console(
    val id: String,
    val nom: String,
    val coeur: String?,
    val extensions: List<String>
)

object Consoles {
    val TOUTES = listOf(
        Console("nes",  "NES",               null,                 listOf(".nes", ".zip")),
        Console("snes", "Super Nintendo",    "libsnes9x.so",       listOf(".sfc", ".smc", ".fig", ".swc", ".zip")),
        Console("gb",   "Game Boy",          "libgambatte.so",     listOf(".gb", ".gbc", ".zip")),
        Console("gba",  "Game Boy Advance",  "libmgba.so",         listOf(".gba", ".zip")),
        Console("md",   "Mega Drive",        "libgenesis.so",      listOf(".md", ".gen", ".smd", ".bin", ".zip")),
        Console("ps1",  "PlayStation",       "libpcsx.so",         listOf(".cue", ".bin", ".chd", ".pbp", ".iso", ".m3u")),
        Console("psp",  "PSP",               "libppsspp.so",       listOf(".iso", ".cso", ".pbp", ".chd")),
        Console("dc",   "Dreamcast",         "libflycast.so",      listOf(".gdi", ".cdi", ".chd", ".cue")),
        Console("ds",   "Nintendo DS",       "libmelonds.so",      listOf(".nds", ".zip")),
        Console("3ds",  "Nintendo 3DS",      "libcitra.so",        listOf(".3ds", ".cci", ".cxi"))
    )

    fun parId(id: String): Console? = TOUTES.firstOrNull { it.id == id }
}
