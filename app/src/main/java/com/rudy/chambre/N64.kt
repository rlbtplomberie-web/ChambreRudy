package com.rudy.chambre

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Entrer directement dans un jeu Nintendo 64.
 *
 * Son ecran de jeu ne se contente pas d'un chemin : il reclame aussi
 * l'empreinte du fichier, sa somme de controle, le nom inscrit dans son
 * en-tete et son code de pays. Ce sont des renseignements que son catalogue
 * calcule lui-meme en parcourant la bibliotheque — et c'est pour cela qu'il
 * fallait passer par lui.
 *
 * On les calcule donc ici, et l'on entre dans la partie sans detour.
 */
object N64 {

    /**
     * Prepare le jeu et fabrique l'intention qui ouvre la partie.
     * Renvoie null si le fichier ne peut pas etre lu ou n'est pas une ROM.
     */
    fun intentionDeJeu(ctx: Context, uri: String): Intent? {
        // son ecran d'accueil installait les donnees du moteur ; on le saute,
        // il faut donc s'en charger nous-memes
        installerLesDonnees(ctx)

        val fichier = poserSurLeDisque(ctx, uri) ?: return null
        val octets = try { fichier.readBytes() } catch (_: Throwable) { return null }
        if (octets.size < 0x40) return null

        val rom = remettreDansLOrdre(octets) ?: return null
        if (!rom.contentEquals(octets)) {
            // le fichier etait dans un autre ordre d'octets : on garde la
            // version remise a l'endroit, sinon rien ne correspondrait
            try { fichier.writeBytes(rom) } catch (_: Throwable) { return null }
        }

        val ecran = try {
            Class.forName("paulscode.android.mupen64plusae.game.GameActivity")
        } catch (_: Throwable) { return null }

        val i = Intent(ctx, ecran)
        // les noms exacts des renseignements, lus dans son propre code :
        // ainsi ils restent justes meme si sa revision change
        fun cle(nom: String): String? = try {
            Class.forName("paulscode.android.mupen64plusae.ActivityHelper\$Keys")
                .getField(nom).get(null) as? String
        } catch (_: Throwable) { null }

        cle("ROM_PATH")?.let { i.putExtra(it, fichier.absolutePath) }
        cle("ROM_MD5")?.let { i.putExtra(it, empreinte(rom)) }
        cle("ROM_CRC")?.let { i.putExtra(it, sommeDeControle(rom)) }
        cle("ROM_HEADER_NAME")?.let { i.putExtra(it, nomInterne(rom)) }
        cle("ROM_COUNTRY_CODE")?.let { i.putExtra(it, rom[0x3E]) }
        cle("ROM_GOOD_NAME")?.let { i.putExtra(it, fichier.nameWithoutExtension) }
        cle("ROM_DISPLAY_NAME")?.let { i.putExtra(it, fichier.nameWithoutExtension) }
        cle("ROM_ART_PATH")?.let { i.putExtra(it, "") }
        cle("DO_RESTART")?.let { i.putExtra(it, false) }
        cle("NETPLAY_ENABLED")?.let { i.putExtra(it, false) }
        cle("NETPLAY_SERVER")?.let { i.putExtra(it, false) }
        cle("EXIT_GAME")?.let { i.putExtra(it, false) }
        cle("FORCE_EXIT_GAME")?.let { i.putExtra(it, false) }
        return i
    }

    /**
     * Installer les donnees du moteur.
     *
     * C'est le travail que faisait son ecran d'accueil : sans ces fichiers —
     * la liste des jeux connus, les reglages par defaut, les nuanciers — le
     * coeur refuse de charger la cartouche.
     *
     * On les cherche par leur contenu plutot que par leur nom : le dossier
     * qui contient « mupen64plus.ini » est le bon, quelle que soit la
     * revision. Et on demande a son propre code ou les poser.
     */
    private fun installerLesDonnees(ctx: Context) {
        val temoin = File(ctx.filesDir, ".donnees_n64_v1")
        if (temoin.exists()) return

        val source = trouverLesDonnees(ctx)
        val cible = ouLesPoser(ctx)
        if (source == null) {
            noter(ctx, "N64 : donnees du moteur INTROUVABLES dans l'application")
            return
        }
        try {
            copier(ctx, source, cible)
            temoin.writeText("ok")
            noter(ctx, "N64 : donnees posees — " + source + " vers " + cible.absolutePath)
        } catch (e: Throwable) {
            noter(ctx, "N64 : donnees non posees — " + e.toString().take(90))
        }
    }

    /** Une ligne dans le journal que la tele sait relire. */
    private fun noter(ctx: Context, texte: String) {
        try {
            val d = File(ctx.filesDir, "systeme").apply { mkdirs() }
            File(d, "journal_appli.txt").appendText(texte + "\n")
        } catch (_: Throwable) {}
    }

    /** Le dossier de nos ressources qui contient « mupen64plus.ini ». */
    private fun trouverLesDonnees(ctx: Context): String? {
        fun contient(dossier: String): Boolean =
            try { ctx.assets.list(dossier)?.any { it == "mupen64plus.ini" } == true }
            catch (_: Throwable) { false }

        return try {
            val racine = ctx.assets.list("") ?: return null
            racine.firstOrNull { contient(it) }
                ?: racine.firstNotNullOfOrNull { d ->
                    ctx.assets.list(d)?.firstOrNull { contient("$d/$it") }?.let { "$d/$it" }
                }
        } catch (_: Throwable) { null }
    }

    /** Ou son code attend ces fichiers : on le lui demande. */
    private fun ouLesPoser(ctx: Context): File {
        try {
            val classe = Class.forName("paulscode.android.mupen64plusae.persistent.AppData")
            val appData = classe.getConstructor(Context::class.java).newInstance(ctx)
            for (champ in classe.fields) {
                if (champ.type != String::class.java) continue
                val nom = champ.name.lowercase()
                if ("data" in nom && "dir" in nom) {
                    val chemin = champ.get(appData) as? String
                    if (!chemin.isNullOrEmpty()) return File(chemin)
                }
            }
        } catch (_: Throwable) {}
        // a defaut, l'endroit qu'il emploie habituellement
        return File(ctx.filesDir, "data")
    }

    private fun copier(ctx: Context, source: String, vers: File) {
        val entrees = ctx.assets.list(source) ?: return
        if (entrees.isEmpty()) {
            vers.parentFile?.mkdirs()
            ctx.assets.open(source).use { f -> vers.outputStream().use { f.copyTo(it) } }
            return
        }
        vers.mkdirs()
        for (e in entrees) copier(ctx, "$source/$e", File(vers, e))
    }

    /**
     * Le jeu choisi vit dans le dossier de Rudy, parfois dans une archive.
     * Son emulateur veut un vrai fichier : on le pose donc a cote de nous, et
     * l'on n'y revient pas si c'est deja fait.
     */
    private fun poserSurLeDisque(ctx: Context, uri: String): File? {
        val dossier = File(ctx.filesDir, "jeux/n64").apply { mkdirs() }
        return try {
            val doc = androidx.documentfile.provider.DocumentFile
                .fromSingleUri(ctx, Uri.parse(uri))
            val nom = doc?.name ?: "jeu.z64"
            val estArchive = nom.endsWith(".zip", true)
            val cible = File(dossier, if (estArchive) nom.dropLast(4) + ".z64" else nom)
            if (cible.isFile && cible.length() > 1024) return cible

            ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { flux ->
                if (estArchive) {
                    ZipInputStream(flux).use { zip ->
                        var e = zip.nextEntry
                        while (e != null) {
                            val n = e.name.lowercase()
                            if (n.endsWith(".z64") || n.endsWith(".n64") || n.endsWith(".v64")) {
                                cible.outputStream().use { zip.copyTo(it) }
                                return cible
                            }
                            e = zip.nextEntry
                        }
                    }
                    null
                } else {
                    cible.outputStream().use { flux.copyTo(it) }
                    cible
                }
            }
        } catch (_: Throwable) { null }
    }

    /**
     * Les cartouches se presentent dans trois ordres d'octets. L'emulateur
     * attend le premier ; on remet les deux autres a l'endroit.
     */
    private fun remettreDansLOrdre(o: ByteArray): ByteArray? {
        val a = o[0].toInt() and 0xFF
        val b = o[1].toInt() and 0xFF
        val c = o[2].toInt() and 0xFF
        val d = o[3].toInt() and 0xFF
        return when {
            a == 0x80 && b == 0x37 -> o                       // deja a l'endroit
            a == 0x37 && b == 0x80 -> {                       // deux par deux
                val r = o.copyOf()
                var i = 0
                while (i + 1 < r.size) { val t = r[i]; r[i] = r[i + 1]; r[i + 1] = t; i += 2 }
                r
            }
            a == 0x40 && b == 0x12 && c == 0x37 && d == 0x80 -> {  // quatre par quatre
                val r = o.copyOf()
                var i = 0
                while (i + 3 < r.size) {
                    val t0 = r[i]; val t1 = r[i + 1]
                    r[i] = r[i + 3]; r[i + 1] = r[i + 2]; r[i + 2] = t1; r[i + 3] = t0
                    i += 4
                }
                r
            }
            else -> null                                      // ce n'est pas une cartouche
        }
    }

    private fun empreinte(o: ByteArray): String {
        val m = MessageDigest.getInstance("MD5").digest(o)
        return m.joinToString("") { "%02X".format(it) }
    }

    /** Les deux sommes de controle de l'en-tete, collees comme il les ecrit. */
    private fun sommeDeControle(o: ByteArray): String =
        (0x10..0x17).joinToString("") { "%02X".format(o[it]) }
            .let { it.substring(0, 8) + " " + it.substring(8) }

    /** Le nom grave dans la cartouche, vingt caracteres. */
    private fun nomInterne(o: ByteArray): String =
        String(o, 0x20, 20, Charsets.ISO_8859_1).trim().trimEnd('\u0000').trim()
}
