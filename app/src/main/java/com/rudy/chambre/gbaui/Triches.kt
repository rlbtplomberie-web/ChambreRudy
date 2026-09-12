package com.rudy.chambre.gbaui

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
 * mGBA reconnait lui-meme le format de chaque code, il n'y a rien a declarer.
 * Sur Game Boy Advance on rencontre principalement :
 *   — GameShark et Action Replay : deux blocs de huit caracteres ;
 *   — CodeBreaker : un bloc de huit, puis un de quatre.
 *
 * Un code sur plusieurs lignes se colle tel quel : le coeur prend chaque
 * ligne pour une partie du meme code.
 */
object Triches {

    private const val PREFS = "skingba_triches"

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
     *
     * Sur Game Boy Advance un code est fait de blocs hexadecimaux relies par
     * des «+», parfois separes par une espace ou un retour a la ligne :
     *
     *     GameShark / Action Replay   8201A454+07B7
     *     CodeBreaker                 3300397F+0001
     *     code long                   3200E924+0096+330034B8+0096
     *
     * On accepte donc toute suite de blocs de quatre a seize caracteres
     * hexadecimaux, et l'on rend le code avec des «+» pour seul separateur,
     * qui est ce que le coeur attend.
     */
    fun forme(code: String): String? {
        val c = code.trim().uppercase()
            .replace(Regex("[\\s:,;]+"), "+")
            .replace(Regex("\\++"), "+")
            .trim('+')
        if (c.isEmpty()) return null
        val blocs = c.split("+")
        if (blocs.any { !Regex("^[0-9A-F]{4,16}$").matches(it) }) return null
        return blocs.joinToString("+")
    }

    // ================= base embarquee =================

    private var base: JSONObject? = null

    /**
     * La base de codes, chargee a la demande.
     *
     * Elle couvre 507 jeux de Game Boy Advance et un peu plus de sept mille
     * codes, tires des fichiers .cht de libretro. On ne la lit qu'une fois,
     * et seulement si l'on en a besoin.
     */
    private fun base(ctx: Context): JSONObject {
        base?.let { return it }
        val o = try {
            val texte = ctx.assets.open("triches_gba.json")
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
