package com.skinnes.app

import android.content.Context
import org.json.JSONObject

/** Un element pose sur le skin, en coordonnees de l'image du skin (pas en pixels ecran). */
data class Rect4(var x: Float, var y: Float, var w: Float, var h: Float) {
    fun copy4() = Rect4(x, y, w, h)
}

/** Identifiants des elements manipulables. */
object Ids {
    const val SCREEN = "screen"
    const val DPAD = "dpad"
    const val BTN_A = "btnA"
    const val BTN_B = "btnB"
    const val SS = "ss"
    const val FF = "ff"
    const val MENU = "menu"
    const val POWER = "power"
    const val RESET = "reset"
    const val JEUX = "jeux"
    const val QUIT = "quit"
    /** Bascule entre les deux presentations horizontales. */
    const val MANETTE = "manette"
    /** Touches dessinees dans l'image du skin, sans sprite a elles. */
    val TOUCHES_SKIN = listOf(MENU, POWER, RESET, JEUX, QUIT)
    val ALL = listOf(SCREEN, DPAD, BTN_A, BTN_B, SS, FF, MANETTE) + TOUCHES_SKIN
}

/**
 * Disposition pour une orientation. sw/sh sont les dimensions natives de l'image de skin :
 * toutes les positions sont exprimees dedans, donc elles restent valables quel que soit
 * l'ecran sur lequel on affiche.
 */
class Disposition(val sw: Float, val sh: Float, val items: MutableMap<String, Rect4>) {

    fun copyOf() = Disposition(sw, sh, items.mapValues { it.value.copy4() }.toMutableMap())

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("sw", sw.toDouble()); o.put("sh", sh.toDouble())
        val it0 = JSONObject()
        items.forEach { (k, r) ->
            it0.put(k, JSONObject()
                .put("x", r.x.toDouble()).put("y", r.y.toDouble())
                .put("w", r.w.toDouble()).put("h", r.h.toDouble()))
        }
        o.put("items", it0)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Disposition {
            val items = mutableMapOf<String, Rect4>()
            val src = o.getJSONObject("items")
            src.keys().forEach { k ->
                val r = src.getJSONObject(k)
                items[k] = Rect4(
                    r.getDouble("x").toFloat(), r.getDouble("y").toFloat(),
                    r.getDouble("w").toFloat(), r.getDouble("h").toFloat()
                )
            }
            return Disposition(
                o.getDouble("sw").toFloat(), o.getDouble("sh").toFloat(), items
            )
        }
    }
}

object Dispositions {

    /** Positions d'origine, relevees au pixel pres sur les deux skins. */
    fun portraitParDefaut() = Disposition(822f, 1733f, mutableMapOf(
        Ids.SCREEN to Rect4(4f, 18f, 812f, 602f),
        Ids.DPAD   to Rect4(23f, 976f, 301f, 303f),
        Ids.BTN_B  to Rect4(510f, 1057f, 135f, 135f),
        Ids.BTN_A  to Rect4(658f, 1057f, 135f, 135f),
        Ids.SS     to Rect4(242f, 1300f, 335f, 121f),
        Ids.FF     to Rect4(715f, 733f, 98f, 87f),
        Ids.MENU   to Rect4(338f, 1600f, 138f, 98f),
        Ids.POWER  to Rect4(25f, 1621f, 113f, 67f),
        Ids.RESET  to Rect4(153f, 1621f, 113f, 67f),
        Ids.JEUX   to Rect4(550f, 1621f, 113f, 67f),
        Ids.QUIT   to Rect4(677f, 1621f, 114f, 67f)
    ))

    fun paysageParDefaut() = Disposition(1844f, 853f, mutableMapOf(
        Ids.SCREEN to Rect4(401f, 47f, 1039f, 657f),
        Ids.DPAD   to Rect4(40f, 327f, 290f, 292f),
        Ids.BTN_B  to Rect4(1512f, 449f, 135f, 135f),
        Ids.BTN_A  to Rect4(1667f, 449f, 135f, 135f),
        Ids.SS     to Rect4(786f, 745f, 254f, 92f),
        Ids.FF     to Rect4(1610f, 638f, 90f, 80f),
        Ids.MENU   to Rect4(1393f, 757f, 109f, 69f),
        Ids.POWER  to Rect4(82f, 763f, 105f, 59f),
        Ids.RESET  to Rect4(205f, 763f, 105f, 59f),
        Ids.JEUX   to Rect4(1542f, 763f, 105f, 59f),
        Ids.QUIT   to Rect4(1664f, 763f, 105f, 59f),
        // Le bouton manette, pose en haut a droite : il fait passer a la
        // seconde presentation horizontale.
        Ids.MANETTE to Rect4(1690f, 20f, 100f, 100f)
    ))

    /**
     * Troisieme presentation horizontale : une couche transparente posee sur le
     * jeu. Pas de console dessinee autour, seulement le trace des touches.
     * Boites relevees au pixel pres sur l'image d'origine (1774 x 887).
     */
    fun paysage3ParDefaut() = Disposition(1774f, 887f, mutableMapOf(
        Ids.SCREEN  to Rect4(296f, 0f, 1182f, 887f),
        Ids.DPAD    to Rect4(35f, 248f, 384f, 370f),
        Ids.BTN_B   to Rect4(1253f, 440f, 199f, 198f),
        Ids.BTN_A   to Rect4(1517f, 440f, 206f, 203f),
        // SELECT et START tiennent dans une meme boite, comme sur les autres
        // presentations : la moitie gauche est SELECT, la droite START.
        Ids.SS      to Rect4(1234f, 736f, 506f, 89f),
        Ids.FF      to Rect4(1606f, 36f, 145f, 144f),
        Ids.MENU    to Rect4(30f, 688f, 179f, 176f),
        Ids.MANETTE to Rect4(40f, 40f, 110f, 110f)
    ))

    private const val PREFS = "skin_nes"
    private const val CLE_P = "dispo_portrait"
    private const val CLE_L = "dispo_paysage"
    private const val CLE_L2 = "dispo_paysage2"
    private const val CLE_L3 = "dispo_paysage3"
    private const val CLE_OPACITE = "opacite_boutons_p3"
    private const val CLE_VARIANTE = "variante_paysage"

    /**
     * Presentation horizontale en cours : 0 la console, 1 la manette, 2 la
     * couche transparente. Le bouton manette passe de l'une a la suivante ;
     * la verticale n'est pas concernee.
     */
    fun variante(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(CLE_VARIANTE, 0).coerceIn(0, 2)

    fun poserVariante(ctx: Context, v: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(CLE_VARIANTE, ((v % 3) + 3) % 3).apply()
    }

    /**
     * Opacite des touches de la troisieme presentation : 0,12 a peine visible,
     * 1 bien marquee. Reglee dans le mode Modifier.
     */
    fun opacite(ctx: Context): Float =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(CLE_OPACITE, 0.85f).coerceIn(0.12f, 1f)

    fun poserOpacite(ctx: Context, v: Float) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(CLE_OPACITE, v.coerceIn(0.12f, 1f)).apply()
    }

    /**
     * Seconde presentation horizontale.
     *
     * Boites relevees sur l'image. Le contour blanc du pave et les plaques
     * grises des boutons rouges ne bougent pas : ils restent dans le fond, et
     * seules les pieces mobiles figurent ici.
     */
    fun paysage2ParDefaut() = Disposition(1844f, 853f, mutableMapOf(
        Ids.SCREEN  to Rect4(333f, 15f, 1122f, 837f),
        Ids.DPAD    to Rect4(52f, 375f, 233f, 237f),
        Ids.BTN_B   to Rect4(1500f, 440f, 128f, 128f),
        Ids.BTN_A   to Rect4(1662f, 440f, 128f, 128f),
        Ids.SS      to Rect4(49f, 111f, 239f, 46f),
        Ids.FF      to Rect4(1600f, 596f, 94f, 90f),
        Ids.MANETTE to Rect4(1712f, 20f, 100f, 100f),
        Ids.MENU    to Rect4(1489f, 733f, 108f, 66f),
        Ids.POWER   to Rect4(36f, 733f, 118f, 66f),
        Ids.RESET   to Rect4(172f, 733f, 118f, 66f),
        Ids.JEUX    to Rect4(1607f, 733f, 108f, 66f),
        Ids.QUIT    to Rect4(1723f, 733f, 108f, 66f)
    ))

    /** Disposition par defaut d'une orientation, selon la presentation en cours. */
    fun defautDe(ctx: Context, paysage: Boolean): Disposition {
        if (!paysage) return portraitParDefaut()
        return when (variante(ctx)) {
            1 -> paysage2ParDefaut()
            2 -> paysage3ParDefaut()
            else -> paysageParDefaut()
        }
    }

    private fun cleDe(ctx: Context, paysage: Boolean): String {
        if (!paysage) return CLE_P
        return when (variante(ctx)) { 1 -> CLE_L2; 2 -> CLE_L3; else -> CLE_L }
    }

    fun charger(ctx: Context, paysage: Boolean): Disposition {
        val cle = cleDe(ctx, paysage)
        val brut = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(cle, null)
        val defaut = defautDe(ctx, paysage)
        if (brut != null) {
            try {
                val d = Disposition.fromJson(JSONObject(brut))
                // complete les elements apparus depuis l'enregistrement
                for (id in Ids.ALL) if (!d.items.containsKey(id)) {
                    defaut.items[id]?.let { d.items[id] = it.copy4() }
                }
                return d
            } catch (_: Exception) {}
        }
        return defaut
    }

    fun enregistrer(ctx: Context, paysage: Boolean, d: Disposition) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(cleDe(ctx, paysage), d.toJson().toString()).apply()
    }

    fun reinitialiser(ctx: Context, paysage: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(cleDe(ctx, paysage)).apply()
    }
}
