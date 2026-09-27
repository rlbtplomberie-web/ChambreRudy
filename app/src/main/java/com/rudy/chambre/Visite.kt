package com.rudy.chambre

import android.content.Context
import android.graphics.RectF
import android.view.MotionEvent

/**
 * La visite d'une fille chez Rudy.
 *
 * « demandee » : elle vient de dire oui au téléphone (dans la chambre ou dans la rue).
 * « ici »      : elle est dans la chambre en ce moment. Tant qu'elle est là, pas de
 *                voyage en ville : il faut sortir avec elle, ou lui dire de rentrer.
 */
object Visite {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("visite", Context.MODE_PRIVATE)

    fun demander(ctx: Context, id: String) =
        prefs(ctx).edit().putString("demandee", id.filter { it.isLetter() }).apply()

    fun prendreDemande(ctx: Context): String {
        val id = prefs(ctx).getString("demandee", "") ?: ""
        if (id.isNotEmpty()) prefs(ctx).edit().remove("demandee").apply()
        return id
    }

    fun arrivee(ctx: Context, id: String) = prefs(ctx).edit().putString("ici", id).apply()
    fun partie(ctx: Context) = prefs(ctx).edit().remove("ici").apply()
    fun ici(ctx: Context): String = prefs(ctx).getString("ici", "") ?: ""
}

/**
 * Le calque de la visite, posé sur la chambre.
 *
 * Il est transparent et ne garde le doigt que sur ses zones (la fille, son menu,
 * ses discussions, la balade en plein écran). Ailleurs, l'appui passe à la chambre
 * en dessous : bureau, consoles, tiroirs, télé, radio… tout marche comme avant.
 */
class CalqueVisite(ctx: Context) : android.webkit.WebView(ctx) {
    @Volatile private var zones: List<RectF> = emptyList()

    fun majZones(json: String, densite: Float) {
        val l = ArrayList<RectF>()
        try {
            val a = org.json.JSONArray(json)
            for (i in 0 until a.length()) {
                val r = a.getJSONArray(i)
                l.add(RectF(r.getDouble(0).toFloat() * densite, r.getDouble(1).toFloat() * densite,
                            r.getDouble(2).toFloat() * densite, r.getDouble(3).toFloat() * densite))
            }
        } catch (_: Throwable) { }
        zones = l
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        // un appui hors de ses zones : il n'est pas pour elle, la chambre le reçoit
        if (e.actionMasked == MotionEvent.ACTION_DOWN && zones.none { it.contains(e.x, e.y) }) return false
        return super.dispatchTouchEvent(e)
    }
}
