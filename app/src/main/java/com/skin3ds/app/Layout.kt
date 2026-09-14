package com.skin3ds.app

import android.content.Context
import org.json.JSONObject

/** Identifiants des pieces de l'habillage, tels qu'ils figurent dans positions.json. */
object Ids {
    /** Ecran du haut, et ecran du bas — celui qui est tactile. */
    const val ECRAN_HAUT = "ecranHaut"
    const val ECRAN_BAS = "ecranBas"
    const val CROIX = "croix"
    const val STICK_G = "stickG"; const val STICK_D = "stickD"
    const val A = "A"; const val B = "B"; const val X = "X"; const val Y = "Y"
    const val L = "L"; const val R = "R"; const val ZL = "ZL"; const val ZR = "ZR"
    const val SELECT = "select"; const val START = "start"
    const val FF = "ff"
    const val MENU = "menu"; const val JEUX = "jeux"; const val CHANGE = "change"
    /** Touches qui commandent l'application, pas la console. */
    val FONCTIONS = listOf(MENU, JEUX, CHANGE)
    val STICKS = listOf(STICK_G, STICK_D)
    val DIRECTIONNELS = listOf(CROIX, STICK_G, STICK_D)
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

    data class Habillage(val dossier: String, val libelle: String,
                         val haut: Rect4?, val bas: Rect4?)

    val PORTRAIT = Habillage("portrait", "Vertical",
        Rect4(125f, 121f, 602f, 487f), Rect4(125f, 718f, 602f, 477f))

    /**
     * Les quatre presentations horizontales, dans l'ordre du bouton CHANGE :
     * grand ecran plus petit, deux ecrans egaux, l'ecran du haut seul, puis
     * l'ecran du bas seul.
     */
    val PAYSAGES = listOf(
        Habillage("paysage_deux_large", "Grand écran et petit écran",
            Rect4(531f, 23f, 751f, 310f), Rect4(572f, 427f, 670f, 327f)),
        Habillage("paysage_deux_haut", "Deux écrans égaux",
            Rect4(470f, 25f, 875f, 315f), Rect4(583f, 424f, 664f, 352f)),
        // Les deux etaient interverties : la dalle large represente l'ecran
        // du bas de la console, la dalle presque carree celui du haut.
        Habillage("paysage_un_carre", "Écran du haut",
            Rect4(479f, 78f, 855f, 642f), null),
        Habillage("paysage_un_large", "Écran du bas",
            null, Rect4(471f, 121f, 869f, 578f)),
    )

    fun habillage(paysage: Boolean, rang: Int) =
        if (!paysage) PORTRAIT
        else PAYSAGES[((rang % PAYSAGES.size) + PAYSAGES.size) % PAYSAGES.size]

    private const val PREFS = "skin_3ds"

    /** Lit positions.json et fabrique la disposition d'origine. */
    fun parDefaut(ctx: Context, h: Habillage): Disposition {
        val texte = ctx.assets.open("skin/${h.dossier}/positions.json")
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
        h.haut?.let { d.items[Ids.ECRAN_HAUT] = it.copie() }
        h.bas?.let { d.items[Ids.ECRAN_BAS] = it.copie() }
        return d
    }

    /** Rang de la presentation horizontale, conserve d'une session a l'autre. */
    fun rang(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("rang_paysage", 0)

    fun poserRang(ctx: Context, r: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("rang_paysage", ((r % PAYSAGES.size) + PAYSAGES.size) % PAYSAGES.size)
            .apply()
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
