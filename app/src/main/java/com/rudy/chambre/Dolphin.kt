package com.rudy.chambre

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Prepare un jeu pour Dolphin.
 *
 * L'activite de Dolphin accepte un chemin de fichier reel dans
 * "SelectedGames".  La chambre, elle, recupere les jeux avec le selecteur
 * Android et n'a donc au depart qu'une adresse content://.  Cette adresse
 * fonctionne pour lister le jeu mais pas pour son moteur natif.
 *
 * GameCube et Wii passent ici toutes les deux : le moteur est le meme, seul
 * le choix de l'interface et de la plateforme reste fait par l'appelant.
 */
object Dolphin {
    private const val PAQUET = "org.dolphinemu.dolphinemu"
    private const val ECRAN_JEU = "org.dolphinemu.dolphinemu.activities.EmulationActivity"

    /**
     * Le Dolphin complet est une application installee a part. Cette intention
     * vise son vrai ecran de jeu : elle ne cherche jamais cette classe dans
     * RetroRom, ce qui est la cause du message « ecran absent dans cet APK ».
     */
    fun intentionDeJeu(ctx: Context, uriTexte: String, wii: Boolean): Intent? {
        val chemin = cheminAccessibleParDolphin(ctx, uriTexte) ?: return null
        val i = Intent().setComponent(ComponentName(PAQUET, ECRAN_JEU))
        if (i.resolveActivity(ctx.packageManager) == null) return null
        return i.apply {
            putExtra("SelectedGames", arrayOf(chemin))
            putExtra("SelectedTitle", if (wii) "Wii" else "GameCube")
            putExtra("Riivolution", false)
            putExtra("SystemMenu", false)
        }
    }

    private fun cheminAccessibleParDolphin(ctx: Context, uriTexte: String): String? {
        val uri = try { Uri.parse(uriTexte) } catch (_: Throwable) { return null }
        if (uri.scheme == "file") return uri.path
        // Le selecteur de fichiers Android donne normalement cette forme :
        // primary:Roms/...  Le fichier reste alors dans le stockage commun,
        // donc lisible par le Dolphin deja installe et non dans RetroRom.
        if (DocumentsContract.isDocumentUri(ctx, uri)) {
            try {
                val id = DocumentsContract.getDocumentId(uri)
                val deuxPoints = id.indexOf(':')
                if (deuxPoints >= 0) {
                    val volume = id.substring(0, deuxPoints)
                    val relatif = id.substring(deuxPoints + 1)
                    if (volume.equals("primary", true))
                        return "/storage/emulated/0/$relatif"
                }
            } catch (_: Throwable) { }
        }
        return preparerJeu(ctx, uriTexte)
    }

    fun preparerJeu(ctx: Context, uriTexte: String): String? {
        if (uriTexte.isBlank()) return null
        val uri = try { Uri.parse(uriTexte) } catch (_: Throwable) { return null }
        if (uri.scheme == "file") return uri.path

        return try {
            val nomBrut = DocumentFile.fromSingleUri(ctx, uri)?.name ?: "jeu.iso"
            val nom = nomBrut
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .replace(Regex("_+"), "_")
            val dossier = File(ctx.filesDir, "jeux/dolphin").apply { mkdirs() }
            val copie = File(dossier, nom)

            if (!copie.isFile || copie.length() < 1024) {
                ctx.contentResolver.openInputStream(uri)?.use { entree ->
                    copie.outputStream().use { sortie -> entree.copyTo(sortie) }
                } ?: return null
            }
            if (copie.isFile && copie.length() >= 1024) copie.absolutePath else null
        } catch (_: Throwable) { null }
    }
}
