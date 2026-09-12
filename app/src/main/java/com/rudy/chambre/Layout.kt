package com.rudy.chambre

import android.content.Context
import org.json.JSONObject

/** Un element pose sur le skin, en coordonnees de l'image du skin (pas en pixels ecran). */
data class Rect4(var x: Float, var y: Float, var w: Float, var h: Float) {
    fun copy4() = Rect4(x, y, w, h)
}

/**
 * Identifiants des elements. Ce sont les noms des sprites dans positions.json,
 * plus l'ecran qui n'a pas de sprite.
 */
object Ids {
    const val SCREEN = "screen"
    const val CROIX = "croix"
    const val X = "X"; const val Y = "Y"; const val A = "A"; const val B = "B"
    const val SELECT = "select"; const val START = "start"
    const val L = "L"; const val R = "R"
    const val FF = "ff"
    const val POWER = "power"; const val RESET = "reset"; const val JEUX = "jeux"; const val MENU = "menu"
    /** Touches qui declenchent une action de l'application, pas un bouton de la console. */
    /** Bascule entre les deux presentations horizontales. */
    const val MANETTE = "manette"

    val FONCTIONS = listOf(POWER, RESET, JEUX, MENU, MANETTE)
}

/**
 * Disposition pour une orientation : le rectangle de chaque element, la taille
 * native du skin, et la marge dont les images d'appui debordent du repos.
 */
class Disposition(val sw: Float, val sh: Float, val items: MutableMap<String, Rect4>,
                  val marges: Map<String, Int>) {

    fun copyOf() = Disposition(sw, sh, items.mapValues { it.value.copy4() }.toMutableMap(), marges)

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("sw", sw.toDouble()); o.put("sh", sh.toDouble())
        val it0 = JSONObject()
        items.forEach { (k, r) ->
            it0.put(k, JSONObject().put("x", r.x.toDouble()).put("y", r.y.toDouble())
                .put("w", r.w.toDouble()).put("h", r.h.toDouble()))
        }
        o.put("items", it0)
        return o
    }

    companion object {
        /** Positions par defaut : celles du fichier positions.json du skin. */
        fun depuisSkin(ctx: Context, dossier: String, ecran: Rect4): Disposition {
            val texte = ctx.assets.open("skins/${EnCours.console}/skin/$dossier/positions.json").bufferedReader().use { it.readText() }
            val o = JSONObject(texte)
            val items = mutableMapOf<String, Rect4>()
            val marges = mutableMapOf<String, Int>()
            val els = o.getJSONArray("elements")
            for (i in 0 until els.length()) {
                val e = els.getJSONObject(i)
                items[e.getString("id")] = Rect4(
                    e.getDouble("x").toFloat(), e.getDouble("y").toFloat(),
                    e.getDouble("w").toFloat(), e.getDouble("h").toFloat())
                marges[e.getString("id")] = e.optInt("marge", 0)
            }
            items[Ids.SCREEN] = ecran
            return Disposition(o.getDouble("largeur").toFloat(), o.getDouble("hauteur").toFloat(),
                               items, marges)
        }

        fun fromJson(o: JSONObject, marges: Map<String, Int>): Disposition {
            val items = mutableMapOf<String, Rect4>()
            val src = o.getJSONObject("items")
            src.keys().forEach { k ->
                val r = src.getJSONObject(k)
                items[k] = Rect4(r.getDouble("x").toFloat(), r.getDouble("y").toFloat(),
                                 r.getDouble("w").toFloat(), r.getDouble("h").toFloat())
            }
            return Disposition(o.getDouble("sw").toFloat(), o.getDouble("sh").toFloat(), items, marges)
        }
    }
}

object Dispositions {
    /** Rectangle de jeu dans chaque skin, mesure sur les images. */
    private val ECRAN_PORTRAIT = Rect4(18f, 99f, 814f, 885f)
    private val ECRAN_PAYSAGE = Rect4(385f, 19f, 1071f, 663f)
    /**
     * Grand ecran de la seconde presentation horizontale.
     *
     * Legerement rentre par rapport a la dalle mesuree — (285, 6, 1270, 845) —
     * pour que l'image ne touche jamais le cadre dessine.
     */
    private val ECRAN_PAYSAGE2 = Rect4(293f, 14f, 1254f, 829f)
    /**
     * Troisieme presentation : une couche transparente posee sur le jeu. Le
     * rectangle de jeu occupe tout le skin, les touches se dessinent dessus.
     */
    private val ECRAN_PAYSAGE3 = Rect4(0f, 0f, 1774f, 887f)

    private const val PREFS = "chambre_rudy"
    private const val CLE_P = "dispo_portrait"
    private const val CLE_L = "dispo_paysage"
    private const val CLE_L2 = "dispo_paysage2"
    private const val CLE_L3 = "dispo_paysage3"
    private const val CLE_OPACITE = "opacite_paysage3"
    private const val CLE_VARIANTE = "variante_paysage"

    /**
     * Presentation horizontale en cours : 0 l'ecran normal, 1 le grand ecran,
     * 2 la couche transparente. Le bouton manette passe a la suivante ; la
     * verticale n'est pas concernee.
     */
    fun variante(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(CLE_VARIANTE, 0).coerceIn(0, 2)

    fun poserVariante(ctx: Context, v: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(CLE_VARIANTE, ((v % 3) + 3) % 3).apply()
    }

    /**
     * Opacite des touches de la couche transparente : 0,12 a peine visible,
     * 1 bien marquee. Elle ne concerne que cette presentation, les deux autres
     * dessinent une console.
     */
    fun opacite(ctx: Context): Float =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(CLE_OPACITE, 0.85f).coerceIn(0.12f, 1f)

    fun poserOpacite(ctx: Context, v: Float) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(CLE_OPACITE, v.coerceIn(0.12f, 1f)).apply()
    }

    /** Dossier de l'habillage, selon l'orientation et la variante. */
    fun dossier(ctx: Context, paysage: Boolean): String =
        if (!paysage) "portrait"
        else when (variante(ctx)) { 1 -> "paysage2"; 2 -> "paysage3"; else -> "paysage" }

    private fun cle(ctx: Context, paysage: Boolean): String =
        EnCours.console + "_" + (if (!paysage) CLE_P
            else when (variante(ctx)) { 1 -> CLE_L2; 2 -> CLE_L3; else -> CLE_L })

    fun parDefaut(ctx: Context, paysage: Boolean) = when {
        !paysage -> Disposition.depuisSkin(ctx, "portrait", ECRAN_PORTRAIT.copy4())
        variante(ctx) == 1 -> Disposition.depuisSkin(ctx, "paysage2", ECRAN_PAYSAGE2.copy4())
        variante(ctx) == 2 -> Disposition.depuisSkin(ctx, "paysage3", ECRAN_PAYSAGE3.copy4())
        else -> Disposition.depuisSkin(ctx, "paysage", ECRAN_PAYSAGE.copy4())
    }

    fun charger(ctx: Context, paysage: Boolean): Disposition {
        val defaut = parDefaut(ctx, paysage)
        val brut = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(cle(ctx, paysage), null) ?: return defaut
        return try {
            val d = Disposition.fromJson(JSONObject(brut), defaut.marges)
            for ((id, r) in defaut.items) if (!d.items.containsKey(id)) d.items[id] = r.copy4()
            d
        } catch (_: Exception) { defaut }
    }

    fun enregistrer(ctx: Context, paysage: Boolean, d: Disposition) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(cle(ctx, paysage), d.toJson().toString()).apply()
    }

    fun reinitialiser(ctx: Context, paysage: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(cle(ctx, paysage)).apply()
    }
}
