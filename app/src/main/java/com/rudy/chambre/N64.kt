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
        /*
         * Son coeur recoit DEUX chemins, pas un.
         *
         * « ROM_PATH » et « ZIP_PATH » : quand le jeu est dans une archive,
         * il attend le nom de l'entree A L'INTERIEUR de l'archive, et
         * l'archive elle-meme a cote. C'est lui qui l'ouvre — Rudy me l'a
         * dit, son emulateur sait lire les zip.
         *
         * Je faisais l'inverse : j'extrayais a sa place et je lui donnais un
         * fichier tout seul, en laissant la seconde case vide. D'ou son
         * « erreur lors de l'ouverture du fichier ROM ».
         */
        /*
         * Son dossier externe doit exister avant qu'il ne s'en serve.
         *
         * Le journal l'a dit : « Unable to make path /storage/emulated/0/
         * Android/data/com.rudy.chambre/files ». C'est la qu'il range ses
         * cartouches et ses reglages, mais Android ne cree ce dossier que
         * lorsqu'une application le demande. La Chambre ne le demandait
         * jamais : il n'existait donc pas, et rien ne pouvait s'y poser.
         *
         * Il suffit de le demander une fois — Android le cree alors, avec les
         * droits qu'il faut, sans aucune autorisation a accorder.
         */
        try {
            ctx.getExternalFilesDir(null)?.mkdirs()
            ctx.getExternalFilesDir("roms")?.mkdirs()
        } catch (_: Throwable) {}

        val pose = poserSurLeDisque(ctx, uri) ?: return null
        val fichier = pose.first          // ce qu'on a pose : l'archive ou la cartouche
        val dedans = pose.second          // le nom de l'entree, si c'est une archive

        val ecran = try {
            Class.forName("paulscode.android.mupen64plusae.game.GameActivity")
        } catch (_: Throwable) { return null }

        fun cle(nom: String): String? = try {
            Class.forName("paulscode.android.mupen64plusae.ActivityHelper\$Keys")
                .getField(nom).get(null) as? String
        } catch (_: Throwable) { null }

        val cleChemin = cle("ROM_PATH") ?: return null
        val cleEmpreinte = cle("ROM_MD5") ?: return null

        /*
         * L'empreinte et l'en-tete se lisent sur la CARTOUCHE, pas sur
         * l'archive. On sort donc ses octets en memoire, sans rien ecrire.
         */
        val octets = octetsDeLaCartouche(fichier, dedans) ?: return null
        if (octets.size < 0x40) return null
        val rom = remettreDansLOrdre(octets) ?: return null

        /*
         * Le chemin qu'il attend.
         *
         * Je lui ai donne l'entree de l'archive : il n'a pas su l'ouvrir. Et
         * pour cause — son coeur ne lit pas dans l'archive, il attend un
         * fichier POSE, dans le dossier ou il range ses cartouches sorties.
         *
         * On extrait donc nous-memes, mais chez LUI, a l'endroit qu'il
         * designe. On lui demande ce dossier ; a defaut, le notre fera.
         */
        val cartouche = if (dedans == null) fichier else {
            val chezLui = sonDossierDeCartouches(ctx)
            val sortie = File(chezLui, dedans.substringAfterLast('/'))
            if (!sortie.isFile || sortie.length() < 1024) {
                try {
                    sortie.parentFile?.mkdirs()
                    sortie.writeBytes(octets)
                } catch (_: Throwable) { return null }
            }
            sortie
        }

        val i = Intent(ctx, ecran)
        i.putExtra(cleChemin, cartouche.absolutePath)
        cle("ZIP_PATH")?.let { i.putExtra(it, "") }
        i.putExtra(cleEmpreinte, empreinte(rom))
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

        noter(ctx, "N64 : fichier " + fichier.name + " — " +
              (fichier.length() / 1024) + " Ko — existe " + fichier.isFile)
        noter(ctx, "N64 : ROM_PATH = " + cartouche.absolutePath +
              " — " + (cartouche.length() / 1024) + " Ko")
        noter(ctx, "N64 : empreinte " + empreinte(rom).take(8) +
              " — en-tete « " + nomInterne(rom) + " »")
        return i
    }

    /**
     * Le dossier ou il range ses cartouches sorties d'archive.
     *
     * On le lui demande, en lisant ses propres reglages : ainsi la cartouche
     * se trouve la ou il la cherche, meme si ce dossier change d'une revision
     * a l'autre.
     */
    private fun sonDossierDeCartouches(ctx: Context): File {
        try {
            val appDataClasse = Class.forName("paulscode.android.mupen64plusae.persistent.AppData")
            val appData = appDataClasse.getConstructor(Context::class.java).newInstance(ctx)
            val globalClasse = Class.forName("paulscode.android.mupen64plusae.persistent.GlobalPrefs")
            val global = globalClasse.getConstructor(Context::class.java, appDataClasse)
                .newInstance(ctx, appData)
            for (champ in globalClasse.fields) {
                if (champ.type != String::class.java) continue
                val nom = champ.name.lowercase()
                if ("unzip" in nom || ("rom" in nom && "dir" in nom)) {
                    val chemin = champ.get(global) as? String
                    if (!chemin.isNullOrEmpty()) return File(chemin)
                }
            }
        } catch (_: Throwable) {}
        return File(ctx.filesDir, "jeux/n64/sorties")
    }

    /** Les octets de la cartouche, qu'elle soit seule ou dans une archive. */
    private fun octetsDeLaCartouche(fichier: File, dedans: String?): ByteArray? = try {
        if (dedans == null) fichier.readBytes()
        else ZipInputStream(fichier.inputStream()).use { zip ->
            var e = zip.nextEntry
            var trouve: ByteArray? = null
            while (e != null) {
                if (e.name == dedans) { trouve = zip.readBytes(); break }
                e = zip.nextEntry
            }
            trouve
        }
    } catch (_: Throwable) { null }

    /**
     * Poser le jeu a cote de nous, tel quel.
     *
     * On ne l'extrait plus : son emulateur sait lire les archives. On copie
     * donc le fichier tel qu'il est, avec un nom simple — le coeur ouvre les
     * fichiers en C, et les parentheses et virgules le genaient.
     *
     * Renvoie le fichier pose, et le nom de l'entree si c'est une archive.
     */
    private fun poserSurLeDisque(ctx: Context, uri: String): Pair<File, String?>? {
        val dossier = File(ctx.filesDir, "jeux/n64").apply { mkdirs() }
        return try {
            val doc = androidx.documentfile.provider.DocumentFile
                .fromSingleUri(ctx, Uri.parse(uri))
            val nom = doc?.name ?: "jeu.z64"
            val propre = nom.replace(Regex("[^A-Za-z0-9._-]"), "_")
                            .replace(Regex("_+"), "_")
            val cible = File(dossier, propre)

            if (!cible.isFile || cible.length() < 1024) {
                ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { flux ->
                    cible.outputStream().use { flux.copyTo(it) }
                } ?: return null
            }

            if (!propre.endsWith(".zip", true)) return Pair(cible, null)

            // une archive : on cherche le nom de la cartouche a l'interieur
            ZipInputStream(cible.inputStream()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val n = e.name.lowercase()
                    if (n.endsWith(".z64") || n.endsWith(".n64") || n.endsWith(".v64")) {
                        return Pair(cible, e.name)
                    }
                    e = zip.nextEntry
                }
            }
            null
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
