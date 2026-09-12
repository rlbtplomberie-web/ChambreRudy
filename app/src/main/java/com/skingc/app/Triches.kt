package com.skingc.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * Un code de triche.
 *
 * Ces codes n'utilisent pas le mecanisme du coeur : ils posent une valeur a
 * une adresse de la memoire vive de la console. Certains la posent plusieurs
 * fois de suite, en avancant d'un pas — c'est ainsi qu'on remplit un
 * inventaire entier, par exemple.
 */
/**
 * Un code de triche GameCube, au format GameShark.
 *
 * Contrairement a ceux de la Dreamcast, il n'est pas interprete ici : on le
 * transmet tel quel au coeur, qui sait le lire. Les codes a plusieurs lignes
 * sont separes par un plus.
 */
data class CodeTriche(val nom: String, val code: String, var actif: Boolean = false)

/** Une entree de la base : un jeu et ses codes. */
data class JeuTriche(val nom: String, val cle: String, val codes: List<CodeTriche>)

/**
 * Base de codes de triche GameCube, tiree de libretro-database.
 *
 * Les variantes regionales d'un meme jeu sont conservees separement : leurs
 * adresses different, et un code prevu pour une region corrompt souvent la
 * memoire d'une autre.
 */
object Triches {

    private var base: List<JeuTriche>? = null

    private fun cle(nom: String): String {
        val sans = Normalizer.normalize(nom, Normalizer.Form.NFKD)
            .replace(Regex("\\p{Mn}+"), "").lowercase()
            .replace(Regex("\\([^)]*\\)|\\[[^\\]]*\\]"), " ")
        return sans.replace(Regex("[^a-z0-9]+"), "")
    }

    private fun charger(ctx: Context): List<JeuTriche> {
        base?.let { return it }
        val out = ArrayList<JeuTriche>()
        try {
            val texte = ctx.assets.open("gc/triches_gc.json").bufferedReader().use { it.readText() }
            val arr = JSONObject(texte).getJSONArray("jeux")
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                val cs = j.getJSONArray("c")
                val codes = ArrayList<CodeTriche>(cs.length())
                for (k in 0 until cs.length()) {
                    val c = cs.getJSONObject(k)
                    codes.add(CodeTriche(c.optString("d", "Code ${k + 1}"), c.getString("c")))
                }
                out.add(JeuTriche(j.getString("n"), j.getString("k"), codes))
            }
        } catch (_: Exception) {}
        base = out
        return out
    }

    /** Nombre de jeux couverts par la base. */
    fun nombreJeux(ctx: Context) = charger(ctx).size

    /**
     * Entrees correspondant au nom d'un fichier de jeu. Plusieurs peuvent
     * remonter quand un jeu existe en plusieurs regions : c'est a
     * l'utilisateur de choisir la sienne.
     */
    fun pourJeu(ctx: Context, nomFichier: String): List<JeuTriche> {
        val k = cle(nomFichier.substringBeforeLast('.'))
        if (k.isEmpty()) return emptyList()
        val tout = charger(ctx)
        val exact = tout.filter { it.cle == k }
        if (exact.isNotEmpty()) return exact
        // repli : le nom du fichier contient celui du jeu, ou l'inverse
        return tout.filter { it.cle.length > 5 && (k.contains(it.cle) || it.cle.contains(k)) }
                   .sortedByDescending { it.cle.length }
                   .take(6)
    }

    // ---------- codes actifs, memorises par jeu ----------
    private fun fichier(ctx: Context, cle: String) =
        File(File(ctx.filesDir, "triches").apply { mkdirs() }, "$cle.json")

    fun chargerActifs(ctx: Context, cle: String): Set<String> {
        val f = fichier(ctx, cle)
        if (!f.exists()) return emptySet()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { a.getString(it) }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    fun enregistrerActifs(ctx: Context, cle: String, noms: Set<String>) {
        if (cle.isEmpty()) return
        val a = JSONArray()
        noms.forEach { a.put(it) }
        fichier(ctx, cle).writeText(a.toString())
    }
}
