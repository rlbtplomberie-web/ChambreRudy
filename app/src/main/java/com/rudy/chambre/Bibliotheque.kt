package com.rudy.chambre

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

    /** Une cle de reglage par console : chacune garde son propre dossier. */
    private fun cle(console: String) = "dossier_roms_" + console

    fun dossier(ctx: Context, console: String): Uri? {
        val brut = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(cle(console), null) ?: return null
        return try { Uri.parse(brut) } catch (_: Exception) { null }
    }

    /** Garde l'acces au dossier au-dela de cette session. */
    fun retenir(ctx: Context, console: String, uri: Uri) {
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
            .putString(cle(console), uri.toString()).apply()
    }

    fun oublier(ctx: Context, console: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(cle(console)).apply()
    }

    /**
     * Liste les ROMs de cette console dans le dossier, sous-dossiers compris.
     * Lent sur un gros dossier : a appeler hors du fil principal.
     */
    fun lister(ctx: Context, console: String): List<Rom> {
        val exts = Consoles.parId(console)?.extensions ?: return emptyList()
        val racine = dossier(ctx, console) ?: return emptyList()
        val doc = try { DocumentFile.fromTreeUri(ctx, racine) } catch (_: Exception) { null }
            ?: return emptyList()
        val trouvees = ArrayList<Rom>()
        parcourir(doc, exts, trouvees, 0)
        trouvees.sortBy { it.nom.lowercase() }
        return trouvees
    }

    private fun parcourir(doc: DocumentFile, exts: List<String>, sortie: MutableList<Rom>, profondeur: Int) {
        if (profondeur > 3 || sortie.size > 3000) return
        val enfants = try { doc.listFiles() } catch (_: Exception) { return }
        for (f in enfants) {
            if (f.isDirectory) {
                parcourir(f, exts, sortie, profondeur + 1)
            } else {
                val n = f.name ?: continue
                if (exts.any { n.lowercase().endsWith(it) }) sortie.add(Rom(n, f.uri))
            }
        }
    }
}
