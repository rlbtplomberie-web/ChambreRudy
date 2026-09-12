package com.skingba.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * La bibliotheque de jeux : le dossier choisi, et ce qu'il contient.
 */
object Bibliotheque {

    /** Formats de la Game Boy Advance, plus les archives. */
    private val EXTENSIONS = listOf(".gba", ".agb", ".bin", ".zip")

    private const val PREFS = "skingba"
    private const val CLE = "dossier_jeux"

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

    /**
     * Ordre de preference dans une archive.
     *
     * Ces extensions servent DEUX fois : pour retenir un fichier du dossier,
     * et pour choisir quoi sortir d'un ZIP. Une archive dont aucune entree ne
     * porte l'une d'elles ressort vide, et le jeu semble illisible alors que
     * l'archive est saine — c'est ce qui arrivait tant que la liste etait
     * restee celle de la Game Boy.
     *
     * Le .bin vient en dernier : c'est une extension fourre-tout, on ne s'en
     * sert que si l'archive ne contient rien de mieux.
     */
    val PRIORITE = listOf("gba", "agb", "bin")

    /**
     * Vrai chemin d'un fichier choisi par le selecteur d'Android.
     *
     * Une cartouche Game Boy Advance monte a 32 Mo : la recopier coute un
     * instant, d'ou ce raccourci quand il fonctionne.
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
