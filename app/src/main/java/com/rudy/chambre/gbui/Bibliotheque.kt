package com.rudy.chambre.gbui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * La bibliotheque de jeux : le dossier choisi, et ce qu'il contient.
 */
object Bibliotheque {

    /** Formats de la Game Boy et de la Game Boy Color, plus les archives. */
    private val EXTENSIONS = listOf(".gb", ".gbc", ".sgb", ".dmg", ".zip")

    private const val PREFS = "chambre_rudy"
    private const val CLE = "dossier_roms_gb"

    data class Jeu(val nom: String, val uri: Uri)

    fun dossier(ctx: Context): Uri? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CLE, null)?.let { Uri.parse(it) }

    fun poserDossier(ctx: Context, uri: Uri) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CLE, uri.toString()).apply()
    }

    fun lister(ctx: Context): List<Jeu> {
        val racine = dossier(ctx) ?: return emptyList()
        val doc = DocumentFile.fromTreeUri(ctx, racine) ?: return emptyList()
        val sortie = ArrayList<Jeu>()
        for (f in doc.listFiles()) {
            if (!f.isFile) continue
            val n = f.name ?: continue
            if (EXTENSIONS.any { n.lowercase().endsWith(it) }) sortie.add(Jeu(n, f.uri))
        }
        return sortie.sortedBy { it.nom.lowercase() }
    }

    fun estArchive(nom: String) = nom.lowercase().endsWith(".zip")

    /** Ordre de preference dans une archive. */
    val PRIORITE = listOf("gbc", "gb", "sgb", "dmg")

    /**
     * Vrai chemin d'un fichier choisi par le selecteur d'Android.
     *
     * Une cartouche Game Boy est petite, donc la recopier ne coute rien : ce
     * chemin n'est qu'un raccourci quand il fonctionne.
     */
    fun cheminReel(ctx: Context, uri: Uri): File? {
        return try {
            val id = DocumentsContract.getDocumentId(uri)
            val bout = id.split(':', limit = 2)
            if (bout.size != 2) return null
            val bases = ArrayList<File>()
            if (bout[0].equals("primary", true))
                bases.add(android.os.Environment.getExternalStorageDirectory())
            bases.add(File("/storage/" + bout[0]))
            for (b in bases) {
                val f = File(b, bout[1])
                if (f.exists() && f.canRead()) return f
            }
            null
        } catch (_: Throwable) { null }
    }
}
