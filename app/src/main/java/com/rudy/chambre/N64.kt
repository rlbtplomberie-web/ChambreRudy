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
 * calcule en parcourant la bibliotheque.
 *
 * Le premier essai avait echoue parce que les donnees du moteur n'etaient pas
 * encore installees. Elles le sont maintenant : Rudy a ouvert son catalogue,
 * qui a fait ce travail une fois pour toutes. On peut donc viser la partie
 * directement — et si quoi que ce soit resiste, on retombe sur le catalogue.
 */
object N64 {

    /** Une ligne dans le journal que la tele sait relire. */
    private fun noter(ctx: Context, texte: String) {
        try {
            val d = File(ctx.filesDir, "systeme").apply { mkdirs() }
            File(d, "journal_appli.txt").appendText(texte + "\n")
        } catch (_: Throwable) {}
    }

    fun intentionDeJeu(ctx: Context, uri: String): Intent? {
        val fichier = poserSurLeDisque(ctx, uri) ?: return null
        val octets = try { fichier.readBytes() } catch (_: Throwable) { return null }
        if (octets.size < 0x40) return null

        val rom = remettreDansLOrdre(octets) ?: return null
        if (!rom.contentEquals(octets)) {
            try { fichier.writeBytes(rom) } catch (_: Throwable) { return null }
        }

        /*
         * Les valeurs de la cartouche, calculees par SON code.
         *
         * Mes deux premiers essais les recalculaient a ma facon. Il suffit
         * qu'une seule differe de ce que son catalogue aurait produit pour
         * que le moteur refuse la cartouche — et c'est ce qui arrivait.
         *
         * On appelle donc sa propre classe de lecture d'en-tete : les valeurs
         * sont alors identiques par construction. Si elle a change de nom
         * dans une autre revision, on retombe sur mon calcul.
         */
        var sonCrc: String? = null
        var sonNom: String? = null
        var sonPays: Byte? = null
        for (chemin in listOf(
            "paulscode.android.mupen64plusae.util.RomHeader",
            "paulscode.android.mupen64plusae.persistent.RomHeader")) {
            try {
                val classe = Class.forName(chemin)
                val entete = classe.getConstructor(File::class.java).newInstance(fichier)
                fun champ(vararg noms: String): Any? {
                    for (n in noms) {
                        try { return classe.getField(n).get(entete) } catch (_: Throwable) {}
                    }
                    return null
                }
                sonCrc = champ("crc") as? String
                sonNom = champ("name", "internalName") as? String
                sonPays = champ("countryCode") as? Byte
                break
            } catch (_: Throwable) {}
        }

        val ecran = try {
            Class.forName("paulscode.android.mupen64plusae.game.GameActivity")
        } catch (_: Throwable) { return null }

        // les noms exacts des renseignements, lus dans son propre code
        fun cle(nom: String): String? = try {
            Class.forName("paulscode.android.mupen64plusae.ActivityHelper\$Keys")
                .getField(nom).get(null) as? String
        } catch (_: Throwable) { null }

        // sans ces deux-la, son ecran se referme aussitot : inutile d'essayer
        val cleChemin = cle("ROM_PATH") ?: return null
        val cleEmpreinte = cle("ROM_MD5") ?: return null

        noter(ctx, "N64 : " + fichier.name +
              (if (sonCrc != null) " — valeurs lues par son code" else " — valeurs calculees"))

        val i = Intent(ctx, ecran)
        i.putExtra(cleChemin, fichier.absolutePath)
        i.putExtra(cleEmpreinte, empreinte(rom))
        cle("ROM_CRC")?.let { i.putExtra(it, sonCrc ?: sommeDeControle(rom)) }
        cle("ROM_HEADER_NAME")?.let { i.putExtra(it, sonNom ?: nomInterne(rom)) }
        cle("ROM_COUNTRY_CODE")?.let { i.putExtra(it, sonPays ?: rom[0x3E]) }
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
     * Le jeu vit dans le dossier de Rudy, parfois dans une archive. Son
     * emulateur veut un vrai fichier : on le pose donc a cote de nous, et
     * l'on n'y revient pas si c'est deja fait.
     */
    private fun poserSurLeDisque(ctx: Context, uri: String): File? {
        val dossier = File(ctx.filesDir, "jeux/n64").apply { mkdirs() }
        return try {
            val doc = androidx.documentfile.provider.DocumentFile
                .fromSingleUri(ctx, Uri.parse(uri))
            val nom = doc?.name ?: "jeu.z64"
            val estArchive = nom.endsWith(".zip", true)
            /*
             * Un nom simple, sans accents ni ponctuation.
             *
             * « Banjo-Kazooie (Europe) (En,Fr,De).z64 » passait tel quel au
             * moteur, qui ouvrait le fichier par son chemin en C : les
             * parentheses et les virgules ne lui plaisaient pas. On garde donc
             * les lettres, les chiffres, le point et le tiret.
             */
            val brut = if (estArchive) nom.dropLast(4) + ".z64" else nom
            val propre = brut.replace(Regex("[^A-Za-z0-9._-]"), "_")
                             .replace(Regex("_+"), "_")
            val cible = File(dossier, propre)
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
            a == 0x80 && b == 0x37 -> o
            a == 0x37 && b == 0x80 -> {
                val r = o.copyOf()
                var i = 0
                while (i + 1 < r.size) { val t = r[i]; r[i] = r[i + 1]; r[i + 1] = t; i += 2 }
                r
            }
            a == 0x40 && b == 0x12 && c == 0x37 && d == 0x80 -> {
                val r = o.copyOf()
                var i = 0
                while (i + 3 < r.size) {
                    val t0 = r[i]; val t1 = r[i + 1]
                    r[i] = r[i + 3]; r[i + 1] = r[i + 2]; r[i + 2] = t1; r[i + 3] = t0
                    i += 4
                }
                r
            }
            else -> null
        }
    }

    private fun empreinte(o: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(o).joinToString("") { "%02X".format(it) }

    /** Les deux sommes de controle de l'en-tete, comme il les ecrit. */
    private fun sommeDeControle(o: ByteArray): String =
        (0x10..0x17).joinToString("") { "%02X".format(o[it]) }
            .let { it.substring(0, 8) + " " + it.substring(8) }

    /** Le nom grave dans la cartouche, vingt caracteres. */
    private fun nomInterne(o: ByteArray): String =
        String(o, 0x20, 20, Charsets.ISO_8859_1).trim().trimEnd('\u0000').trim()
}
