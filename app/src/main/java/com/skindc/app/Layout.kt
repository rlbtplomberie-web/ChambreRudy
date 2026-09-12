package com.skindc.app

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
    const val STICK = "stick"
    const val A = "A"; const val B = "B"; const val X = "X"; const val Y = "Y"
    const val START = "start"
    const val L = "L"; const val R = "R"
    const val FF = "ff"
    const val MENU = "menu"; const val JEUX = "jeux"; const val CHEAT = "cheat"; const val QUIT = "quit"
    /** Bascule entre les deux presentations horizontales. */
    const val MANETTE = "manette"
    /** Touches qui commandent l'application, pas la console. */
    val FONCTIONS = listOf(MENU, JEUX, CHEAT, QUIT, MANETTE)
    /** Pieces directionnelles : elles basculent au lieu de s'enfoncer. */
    val DIRECTIONNELS = listOf(CROIX, STICK)
}

/**
 * Disposition pour une orientation : le rectangle de chaque element, la taille
 * native du skin, et la marge dont les images d'appui debordent du repos.
 */
class Disposition(val sw: Float, val sh: Float, val items: MutableMap<String, Rect4>,
                  val marges: Map<String, Int>, val courses: Map<String, Float> = emptyMap()) {

    fun copyOf() = Disposition(sw, sh, items.mapValues { it.value.copy4() }.toMutableMap(), marges, courses)

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
            val texte = ctx.assets.open("dc/skin/$dossier/positions.json").bufferedReader().use { it.readText() }
            val o = JSONObject(texte)
            val items = mutableMapOf<String, Rect4>()
            val marges = mutableMapOf<String, Int>()
            val courses = mutableMapOf<String, Float>()
            val els = o.getJSONArray("elements")
            for (i in 0 until els.length()) {
                val e = els.getJSONObject(i)
                val id = e.getString("id")
                items[id] = Rect4(e.getDouble("x").toFloat(), e.getDouble("y").toFloat(),
                                  e.getDouble("w").toFloat(), e.getDouble("h").toFloat())
                marges[id] = e.optInt("marge", 0)
                if (e.has("course")) courses[id] = e.getDouble("course").toFloat()
            }
            items[Ids.SCREEN] = ecran
            return Disposition(o.getDouble("largeur").toFloat(), o.getDouble("hauteur").toFloat(),
                               items, marges, courses)
        }

        fun fromJson(o: JSONObject, marges: Map<String, Int>, courses: Map<String, Float>): Disposition {
            val items = mutableMapOf<String, Rect4>()
            val src = o.getJSONObject("items")
            src.keys().forEach { k ->
                val r = src.getJSONObject(k)
                items[k] = Rect4(r.getDouble("x").toFloat(), r.getDouble("y").toFloat(),
                                 r.getDouble("w").toFloat(), r.getDouble("h").toFloat())
            }
            return Disposition(o.getDouble("sw").toFloat(), o.getDouble("sh").toFloat(), items, marges, courses)
        }
    }
}

object Dispositions {
    /** Rectangle de jeu dans chaque skin, mesure sur les images. */
    // La dalle noire elle-meme, mesuree sur les images du skin. Les valeurs
    // precedentes etaient celles du cadre sombre qui l'entoure : l'image
    // debordait donc sur le cadre et masquait ses arrondis.
    private val ECRAN_PORTRAIT = Rect4(45f, 104f, 762f, 880f)
    private val ECRAN_PAYSAGE = Rect4(373f, 116f, 1100f, 538f)
    /**
     * Grand ecran de la seconde presentation horizontale.
     *
     * Legerement rentre par rapport a la dalle mesuree — (294, 0, 1249, 844) —
     * pour que l'image ne deborde jamais du cadre dessine.
     */
    private val ECRAN_PAYSAGE_LARGE = Rect4(302f, 8f, 1233f, 828f)
    /**
     * Troisieme presentation : une couche de touches posee sur le jeu. Le
     * rectangle de jeu couvre tout l'habillage, les touches se dessinent
     * dessus.
     */
    private val ECRAN_TOUCHES = Rect4(0f, 0f, 1774f, 887f)

    private const val PREFS = "skin_dc"
    private const val CLE_P = "dispo_portrait"
    private const val CLE_L = "dispo_paysage"
    private const val CLE_LL = "dispo_paysage_large"
    private const val CLE_T = "dispo_paysage_touches"
    private const val CLE_OPACITE = "opacite_touches"
    private const val CLE_VARIANTE = "variante_paysage"

    /** L'habillage sans console : le seul dont les touches s'effacent. */
    const val TRANSPARENT = "paysage_touches"

    /**
     * Presentation horizontale en cours : 0 pour l'ecran normal, 1 pour le
     * grand ecran. Le bouton manette passe de l'une a l'autre ; la verticale
     * n'est pas concernee.
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
     * 1 bien marquee. Les deux autres presentations n'en tiennent pas compte.
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
        else when (variante(ctx)) { 1 -> "paysage_large"; 2 -> TRANSPARENT; else -> "paysage" }

    private fun cle(ctx: Context, paysage: Boolean): String =
        if (!paysage) CLE_P
        else when (variante(ctx)) { 1 -> CLE_LL; 2 -> CLE_T; else -> CLE_L }

    fun parDefaut(ctx: Context, paysage: Boolean) =
 when {
        !paysage -> Disposition.depuisSkin(ctx, "portrait", ECRAN_PORTRAIT.copy4())
        variante(ctx) == 1 ->
            Disposition.depuisSkin(ctx, "paysage_large", ECRAN_PAYSAGE_LARGE.copy4())
        variante(ctx) == 2 ->
            Disposition.depuisSkin(ctx, TRANSPARENT, ECRAN_TOUCHES.copy4())
        else -> Disposition.depuisSkin(ctx, "paysage", ECRAN_PAYSAGE.copy4())
    }

    fun charger(ctx: Context, paysage: Boolean): Disposition {
        val defaut = parDefaut(ctx, paysage)
        val brut = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(cle(ctx, paysage), null) ?: return defaut
        return try {
            val d = Disposition.fromJson(JSONObject(brut), defaut.marges, defaut.courses)
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
