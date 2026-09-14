package com.skin3ds.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** Un jeu trouve dans le dossier choisi par l'utilisateur. */
data class Rom(val nom: String, val uri: Uri)

/**
 * Catalogue des jeux.
 *
 * L'utilisateur designe un dossier une fois ; on y relit la liste a chaque
 * ouverture, sans jamais copier les fichiers.
 */
object Bibliotheque {

    private const val PREFS = "skin_3ds"
    private const val CLE = "dossier_jeux"

    /** Formats de la Nintendo 3DS, plus les archives. */
    private val EXTENSIONS = listOf(".3ds", ".cci", ".cxi", ".cia", ".3dsx", ".app", ".elf", ".zip")

    fun dossier(ctx: Context): Uri? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CLE, null)?.let { Uri.parse(it) }

    fun poserDossier(ctx: Context, uri: Uri) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CLE, uri.toString()).apply()
    }

    fun lister(ctx: Context): List<Rom> {
        val uri = dossier(ctx) ?: return emptyList()
        val racine = try { DocumentFile.fromTreeUri(ctx, uri) } catch (_: Exception) { null }
            ?: return emptyList()
        val sortie = ArrayList<Rom>()
        fun parcourir(d: DocumentFile, profondeur: Int) {
            if (profondeur > 3) return
            for (f in d.listFiles()) {
                if (f.isDirectory) { parcourir(f, profondeur + 1); continue }
                val n = f.name ?: continue
                if (EXTENSIONS.any { n.lowercase().endsWith(it) }) sortie.add(Rom(n, f.uri))
            }
        }
        parcourir(racine, 0)
        return sortie.sortedBy { it.nom.lowercase() }
    }
}
