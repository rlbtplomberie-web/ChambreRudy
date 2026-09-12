package com.rudy.chambre

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** Un jeu trouve dans le dossier choisi. */
data class Jeu(val nom: String, val uri: Uri)

/**
 * Le pont entre la chambre et les emulateurs pour le choix des dossiers.
 *
 * Chaque emulateur a sa propre facon de retenir son dossier : on ecrit donc
 * directement dans SON fichier de reglages, avec SA cle. Ainsi, le dossier
 * choisi depuis une vitrine est exactement celui que l'emulateur ouvrira,
 * sans qu'une seule ligne de son code ait besoin de changer.
 */
object Dossiers {

    fun retenir(ctx: Context, console: String, uri: Uri) {
        val c = Consoles.parId(console) ?: return
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            // certains fournisseurs refusent l'autorisation durable : tant pis,
            // le dossier restera valable jusqu'a la fermeture
        }
        ctx.getSharedPreferences(c.prefs, Context.MODE_PRIVATE).edit()
            .putString(c.cle, uri.toString()).apply()
    }

    fun dossier(ctx: Context, console: String): Uri? {
        val c = Consoles.parId(console) ?: return null
        val brut = ctx.getSharedPreferences(c.prefs, Context.MODE_PRIVATE)
            .getString(c.cle, null) ?: return null
        return try { Uri.parse(brut) } catch (_: Exception) { null }
    }

    /** Liste les jeux du dossier de cette console, sous-dossiers compris. */
    fun lister(ctx: Context, console: String): List<Jeu> {
        val c = Consoles.parId(console) ?: return emptyList()
        val racine = dossier(ctx, console) ?: return emptyList()
        val doc = try { DocumentFile.fromTreeUri(ctx, racine) } catch (_: Exception) { null }
            ?: return emptyList()
        val trouves = ArrayList<Jeu>()
        parcourir(doc, c.extensions, trouves, 0)
        trouves.sortBy { it.nom.lowercase() }
        return trouves
    }

    private fun parcourir(doc: DocumentFile, exts: List<String>,
                          sortie: MutableList<Jeu>, profondeur: Int) {
        if (profondeur > 3 || sortie.size > 3000) return
        val enfants = try { doc.listFiles() } catch (_: Exception) { return }
        for (f in enfants) {
            if (f.isDirectory) parcourir(f, exts, sortie, profondeur + 1)
            else {
                val n = f.name ?: continue
                if (exts.any { n.lowercase().endsWith(it) }) sortie.add(Jeu(n, f.uri))
            }
        }
    }
}
