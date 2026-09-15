package com.rudy.chambre

import android.content.Context
import android.net.Uri
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
