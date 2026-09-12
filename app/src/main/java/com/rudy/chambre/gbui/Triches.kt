package com.rudy.chambre.gbui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Les codes de triche, ranges PAR JEU.
 *
 * Chaque jeu a sa propre liste, gardee sous son nom de fichier. On peut en
 * ajouter, en retirer, et cocher ceux qu'on veut voir agir : rien n'est
 * impose, et la liste d'un jeu ne se melange jamais a celle d'un autre.
 *
 * Gambatte accepte deux formats, qu'il reconnait lui-meme :
 *   — Game Genie : quatre caracteres, un tiret, trois, un tiret, deux ;
 *   — GameShark : huit caracteres.
 */
object Triches {

    private const val PREFS = "gbgbc_triches"

    /** Un code, avec son intitule et son etat. */
    data class Code(val nom: String, val code: String, var actif: Boolean)

    /**
     * Clef d'un jeu : son titre, debarrasse de tout ce qui varie.
     *
     * On retire l'extension, puis tout ce qui figure entre parentheses ou
     * crochets — region, langue, revision — et l'on ne garde que les lettres
     * et les chiffres. Une cartouche « Zelda (Europe) (Rev 1).gbc » et une
     * « Zelda (USA).gbc » aboutissent ainsi a la meme clef.
     */
    private fun clef(jeu: String): String {
        var n = jeu.substringBeforeLast('.')
        n = n.replace(Regex("\\([^)]*\\)"), " ")
        n = n.replace(Regex("\\[[^\\]]*\\]"), " ")
        return n.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    fun lister(ctx: Context, jeu: String): MutableList<Code> {
        val brut = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(clef(jeu), null) ?: return mutableListOf()
        return try {
            val t = JSONArray(brut)
            val sortie = mutableListOf<Code>()
            for (i in 0 until t.length()) {
                val o = t.getJSONObject(i)
                sortie.add(Code(o.getString("nom"), o.getString("code"),
                                o.optBoolean("actif", true)))
            }
            sortie
        } catch (_: Exception) { mutableListOf() }
    }

    fun enregistrer(ctx: Context, jeu: String, codes: List<Code>) {
        val t = JSONArray()
        for (c in codes) {
            t.put(JSONObject().put("nom", c.nom).put("code", c.code)
                              .put("actif", c.actif))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(clef(jeu), t.toString()).apply()
    }

    /** Les codes coches, dans l'ordre, prets a etre poses. */
    fun actifs(ctx: Context, jeu: String): List<String> =
        lister(ctx, jeu).filter { it.actif }.map { it.code }

    /**
     * Verifie qu'un code a une forme reconnue.
     *
     * On ne juge pas de son effet — seul le coeur le sait — mais une saisie
     * manifestement fautive vaut mieux d'etre signalee tout de suite.
     */
    fun forme(code: String): String? {
        val c = code.trim().uppercase()
        if (Regex("^[0-9A-F]{3}-[0-9A-F]{3}-[0-9A-F]{3}$").matches(c)) return c
        if (Regex("^[0-9A-F]{4}-[0-9A-F]{3}-[0-9A-F]{2}$").matches(c)) return c
        if (Regex("^[0-9A-F]{8}$").matches(c)) return c
        if (Regex("^[0-9A-F]{9}$").matches(c))
            return c.substring(0, 3) + "-" + c.substring(3, 6) + "-" + c.substring(6)
        return null
    }

    // ================= base embarquee =================

    private var base: JSONObject? = null

    /**
     * La base de codes, chargee a la demande.
     *
     * Elle couvre plus de douze cents jeux de Game Boy et Game Boy Color.
     * On ne la lit qu'une fois, et seulement si l'on en a besoin.
     */
    private fun base(ctx: Context): JSONObject {
        base?.let { return it }
        val o = try {
            val texte = ctx.assets.open("triches_gb.json")
                .bufferedReader().use { it.readText() }
            JSONObject(texte).getJSONObject("jeux")
        } catch (_: Exception) { JSONObject() }
        base = o
        return o
    }

    fun nombreJeux(ctx: Context): Int = base(ctx).length()

    /**
     * Les codes connus pour un jeu.
     *
     * Le meme jeu se presente sous des noms differents selon la copie —
     * region, langue, revision. La clef les ignore, si bien qu'une cartouche
     * europeenne retrouve les codes indexes sous le nom americain.
     */
    fun connus(ctx: Context, jeu: String): List<Code> {
        val b = base(ctx)
        val k = clef(jeu)
        var trouve = b.optJSONObject(k)
        if (trouve == null) {
            /* Rien d'exact : on cherche le titre le plus proche, a condition
               qu'il commence pareil et soit assez long pour etre sur. */
            var meilleur: String? = null
            val cles = b.keys()
            while (cles.hasNext()) {
                val c = cles.next()
                if (k.length >= 8 && (c.startsWith(k) || k.startsWith(c)) &&
                    c.length >= 8) {
                    if (meilleur == null || c.length < meilleur!!.length) meilleur = c
                }
            }
            if (meilleur != null) trouve = b.optJSONObject(meilleur)
        }
        val o = trouve ?: return emptyList()
        val t = o.optJSONArray("c") ?: return emptyList()
        val sortie = ArrayList<Code>()
        for (i in 0 until t.length()) {
            val e = t.getJSONObject(i)
            sortie.add(Code(e.optString("d"), e.optString("c"), false))
        }
        return sortie
    }

    /** Nom du jeu tel que la base le connait, ou null. */
    fun titreConnu(ctx: Context, jeu: String): String? =
        base(ctx).optJSONObject(clef(jeu))?.optString("n")

    /** Description des formats, pour l'aide affichee. */
    const val AIDE =
        "Deux formats sont reconnus :\n\n" +
        "  • Game Genie — 019-14D-E6E ou 00A-17B-C49\n" +
        "  • GameShark — 011556C1\n\n" +
        "Chaque jeu garde sa propre liste. Coche les codes que tu veux voir " +
        "agir ; ils sont posés au chargement du jeu."
}
