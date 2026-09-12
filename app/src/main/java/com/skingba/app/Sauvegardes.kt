package com.skingba.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Les sauvegardes d'etat, rangees PAR JEU et par case.
 *
 * Rien a voir avec la sauvegarde interne d'une cartouche, que le jeu gere
 * lui-meme : ici on fige la console entiere a l'instant present — memoire,
 * registres, position a l'ecran — pour y revenir plus tard, meme au milieu
 * d'un passage sans point de sauvegarde.
 */
object Sauvegardes {

    /** Quatre cases par jeu, plus une case rapide. */
    const val CASES = 4

    /**
     * Clef d'un jeu : son titre, debarrasse de tout ce qui varie.
     *
     * Meme regle que pour les triches : une cartouche europeenne et sa
     * jumelle americaine retrouvent les memes sauvegardes.
     */
    private fun clef(jeu: String): String {
        var n = jeu.substringBeforeLast('.')
        n = n.replace(Regex("\\([^)]*\\)"), " ")
        n = n.replace(Regex("\\[[^\\]]*\\]"), " ")
        val c = n.lowercase().replace(Regex("[^a-z0-9]"), "")
        return if (c.isEmpty()) "jeu" else c.take(48)
    }

    private fun dossier(ctx: Context) = File(ctx.filesDir, "etats").apply { mkdirs() }

    fun fichier(ctx: Context, jeu: String, case: Int) =
        File(dossier(ctx), clef(jeu) + "_" + case + ".etat")

    fun existe(ctx: Context, jeu: String, case: Int): Boolean {
        val f = fichier(ctx, jeu, case)
        return f.isFile && f.length() > 0
    }

    /** Date de la case, telle qu'on l'affiche dans la liste. */
    fun date(ctx: Context, jeu: String, case: Int): String? {
        val f = fichier(ctx, jeu, case)
        if (!f.isFile || f.length() == 0L) return null
        return SimpleDateFormat("d MMM yyyy 'à' HH:mm", Locale.FRANCE)
            .format(Date(f.lastModified()))
    }

    fun taille(ctx: Context, jeu: String, case: Int): Long =
        fichier(ctx, jeu, case).let { if (it.isFile) it.length() else 0L }

    /**
     * Ecrit une case, sans jamais abimer la precedente.
     *
     * On ecrit d'abord a cote, et l'on ne remplace l'ancienne sauvegarde que
     * si tout s'est bien passe. Une coupure en cours d'ecriture — batterie
     * vide, application tuee — laisse donc l'ancienne intacte plutot qu'un
     * fichier tronque, qui se chargerait a moitie et planterait le jeu.
     */
    fun ecrire(ctx: Context, jeu: String, case: Int, donnees: ByteArray): Boolean {
        val cible = fichier(ctx, jeu, case)
        val provisoire = File(cible.parentFile, cible.name + ".ecriture")
        return try {
            provisoire.outputStream().use { it.write(donnees); it.flush() }
            if (provisoire.length() != donnees.size.toLong()) {
                provisoire.delete(); false
            } else {
                cible.delete()
                provisoire.renameTo(cible)
            }
        } catch (_: Throwable) {
            provisoire.delete(); false
        }
    }

    fun lire(ctx: Context, jeu: String, case: Int): ByteArray? = try {
        val f = fichier(ctx, jeu, case)
        if (f.isFile && f.length() > 0) f.readBytes() else null
    } catch (_: Throwable) { null }

    fun effacer(ctx: Context, jeu: String, case: Int): Boolean =
        fichier(ctx, jeu, case).delete()

    /** Nombre de cases occupees, pour l'ecran d'etat. */
    fun occupees(ctx: Context, jeu: String): Int =
        (1..CASES).count { existe(ctx, jeu, it) }
}
