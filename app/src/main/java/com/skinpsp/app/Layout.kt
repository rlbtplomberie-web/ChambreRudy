package com.skinpsp.app

import android.content.Context
import org.json.JSONObject

/** Identifiants des pieces de l'habillage, tels qu'ils figurent dans positions.json. */
object Ids {
    const val ECRAN = "ecran"
    const val HAUT = "haut"; const val BAS = "bas"
    const val GAUCHE = "gauche"; const val DROITE = "droite"
    const val STICK = "stick"
    const val TRIANGLE = "triangle"; const val ROND = "rond"
    const val CARRE = "carre"; const val CROIX = "croixB"
    const val L = "L"; const val R = "R"
    const val SELECT = "select"; const val START = "start"
    const val FF = "ff"
    const val MENU = "menu"; const val JEUX = "jeux"
    const val CHEAT = "cheat"; const val QUIT = "quit"

    /** Touches qui commandent l'application, pas la console. */
    val FONCTIONS = listOf(MENU, JEUX, CHEAT, QUIT)
    /** Les quatre fleches du pave, prises ensemble. */
    val FLECHES = listOf(HAUT, BAS, GAUCHE, DROITE)
}

/** Un rectangle en coordonnees du skin. */
class Rect4(var x: Float, var y: Float, var w: Float, var h: Float) {
    fun copie() = Rect4(x, y, w, h)
}

/**
 * Position et taille de chaque piece, dans le repere du skin.
 *
 * Les valeurs viennent de positions.json, produit lors de la decoupe. Elles
 * peuvent ensuite etre deplacees par l'utilisateur et sont alors conservees.
 */
class Disposition(val sw: Int, val sh: Int) {
    val items = HashMap<String, Rect4>()
    val marges = HashMap<String, Int>()

    fun versJson(): JSONObject {
        val o = JSONObject()
        o.put("sw", sw); o.put("sh", sh)
        val e = JSONObject()
        for ((id, r) in items) {
            val v = JSONObject()
            v.put("x", r.x.toDouble()); v.put("y", r.y.toDouble())
            v.put("w", r.w.toDouble()); v.put("h", r.h.toDouble())
            e.put(id, v)
        }
        o.put("items", e)
        return o
    }

    companion object {
        fun depuisJson(o: JSONObject, marges: Map<String, Int>): Disposition {
            val d = Disposition(o.getInt("sw"), o.getInt("sh"))
            val e = o.getJSONObject("items")
            for (id in e.keys()) {
                val v = e.getJSONObject(id)
                d.items[id] = Rect4(v.getDouble("x").toFloat(), v.getDouble("y").toFloat(),
                                    v.getDouble("w").toFloat(), v.getDouble("h").toFloat())
            }
            d.marges.putAll(marges)
            return d
        }
    }
}

/** Les deux habillages : console tenue droite, ou couchee. */
object Dispositions {

    data class Habillage(val dossier: String, val libelle: String, val ecran: Rect4)

    val PORTRAIT = Habillage("portrait", "Vertical", Rect4(48f, 170f, 758f, 706f))
    val PAYSAGE  = Habillage("paysage",  "Horizontal", Rect4(320f, 54f, 1203f, 633f))

    fun habillage(paysage: Boolean) = if (paysage) PAYSAGE else PORTRAIT

    private const val PREFS = "skin_psp"

    /** Lit positions.json et fabrique la disposition d'origine. */
    fun parDefaut(ctx: Context, h: Habillage): Disposition {
        val texte = ctx.assets.open("psp/skin/${h.dossier}/positions.json")
            .bufferedReader().use { it.readText() }
        val o = JSONObject(texte)
        val d = Disposition(o.getInt("largeur"), o.getInt("hauteur"))
        val els = o.getJSONArray("elements")
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val id = e.getString("id")
            d.items[id] = Rect4(e.getInt("x").toFloat(), e.getInt("y").toFloat(),
                                e.getInt("w").toFloat(), e.getInt("h").toFloat())
            d.marges[id] = e.optInt("marge", 0)
        }
        d.items[Ids.ECRAN] = h.ecran.copie()
        return d
    }

    fun charger(ctx: Context, h: Habillage): Disposition {
        val defaut = parDefaut(ctx, h)
        val brut = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("dispo_" + h.dossier, null) ?: return defaut
        return try {
            val d = Disposition.depuisJson(JSONObject(brut), defaut.marges)
            for ((id, r) in defaut.items) if (!d.items.containsKey(id)) d.items[id] = r.copie()
            d
        } catch (_: Exception) { defaut }
    }

    fun enregistrer(ctx: Context, h: Habillage, d: Disposition) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("dispo_" + h.dossier, d.versJson().toString()).apply()
    }

    fun reinitialiser(ctx: Context, h: Habillage) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("dispo_" + h.dossier).apply()
    }
}
