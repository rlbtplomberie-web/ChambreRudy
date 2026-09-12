package com.rudy.chambre.ps1ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** Une ROM trouvee dans le dossier choisi. */
data class Rom(val nom: String, val uri: Uri)

/**
 * Retient le dossier de ROMs choisi par l'utilisateur et sait le parcourir.
 *
 * Android ne donne pas de chemin de fichier sur un dossier choisi : il donne une
 * autorisation d'acces, qu'il faut demander a garder pour la retrouver au prochain
 * lancement. C'est tout l'objet de [retenir].
 */
object Bibliotheque {

    private const val PREFS = "chambre_rudy"
    private const val CLE_DOSSIER = "dossier_roms_ps1"
    private val EXTENSIONS = listOf(".cue", ".chd", ".pbp", ".m3u", ".img", ".iso", ".bin", ".zip")

    fun dossier(ctx: Context): Uri? {
        val brut = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CLE_DOSSIER, null) ?: return null
        return try { Uri.parse(brut) } catch (_: Exception) { null }
    }

    /** Garde l'acces au dossier au-dela de cette session. */
    fun retenir(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            // certains fournisseurs refusent l'autorisation durable : on continue,
            // le dossier restera simplement valable jusqu'a la fermeture
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CLE_DOSSIER, uri.toString()).apply()
    }

    fun oublier(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(CLE_DOSSIER).apply()
    }

    /**
     * Liste les ROMs Super Nintendo du dossier, sous-dossiers compris.
     * Lent sur un gros dossier : a appeler hors du fil principal.
     */
    fun lister(ctx: Context): List<Rom> {
        val racine = dossier(ctx) ?: return emptyList()
        val doc = try { DocumentFile.fromTreeUri(ctx, racine) } catch (_: Exception) { null }
            ?: return emptyList()
        val trouvees = ArrayList<Rom>()
        parcourir(doc, trouvees, 0)
        // un .bin accompagne d'un .cue du meme nom n'est qu'une piste : on ne
        // montre que le .cue, qui sait ou sont ses pistes
        val cues = trouvees.filter { it.nom.lowercase().endsWith(".cue") }
                           .map { it.nom.substringBeforeLast('.').lowercase() }.toSet()
        val propres = trouvees.filter {
            val n = it.nom.lowercase()
            !(n.endsWith(".bin") && (n.substringBeforeLast('.') in cues ||
              Regex("(.*) \\(track \\d+\\)$").matchEntire(n.substringBeforeLast('.'))?.groupValues?.get(1) in cues))
        }
        return propres.sortedBy { it.nom.lowercase() }
    }

    private fun parcourir(doc: DocumentFile, sortie: MutableList<Rom>, profondeur: Int) {
        if (profondeur > 3 || sortie.size > 3000) return
        val enfants = try { doc.listFiles() } catch (_: Exception) { return }
        for (f in enfants) {
            if (f.isDirectory) {
                parcourir(f, sortie, profondeur + 1)
            } else {
                val n = f.name ?: continue
                if (EXTENSIONS.any { n.lowercase().endsWith(it) }) sortie.add(Rom(n, f.uri))
            }
        }
    }
}
