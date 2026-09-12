package com.rudy.chambre.dsui

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
    /** Ecran du haut, et ecran du bas — celui qui est tactile. */
    const val ECRAN_HAUT = "ecranHaut"
    const val ECRAN_BAS = "ecranBas"
    const val CROIX = "croix"
    const val A = "A"; const val B = "B"; const val X = "X"; const val Y = "Y"
    const val L = "L"; const val R = "R"
    const val SELECT = "select"; const val START = "start"
    const val FF = "ff"
    const val CHANGE = "change"
    const val MENU = "menu"; const val JEUX = "jeux"
    /** Touches qui commandent l'application, pas la console. */
    val FONCTIONS = listOf(MENU, JEUX, CHANGE)
    /** Pieces directionnelles : elles basculent au lieu de s'enfoncer. */
    val DIRECTIONNELS = listOf(CROIX)
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
        fun depuisSkin(ctx: Context, dossier: String): Disposition {
            val texte = ctx.assets.open("skins/ds/skin/$dossier/positions.json").bufferedReader().use { it.readText() }
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
    // La dalle noire elle-meme, mesuree sur les images du skin : l'image de
    // jeu la remplit sans deborder sur le cadre.
    /**
     * Les cinq habillages, avec le rectangle de chaque ecran.
     *
     * En vertical, la console se tient droite et montre ses deux ecrans. En
     * horizontal, le bouton CHANGE fait defiler quatre presentations, dans
     * l'ordre voulu : deux ecrans egaux, deux ecrans inegaux, l'ecran du haut
     * seul, puis l'ecran du bas seul.
     */
    data class Habillage(val dossier: String, val libelle: String,
                         val haut: Rect4?, val bas: Rect4?)

    val PORTRAIT = Habillage("portrait", "Vertical",
        Rect4(116f, 40f, 618f, 512f), Rect4(116f, 660f, 618f, 511f))

    val PAYSAGES = listOf(
        Habillage("paysage_deux_centre", "Deux écrans",
            Rect4(535f, 25f, 658f, 324f), Rect4(534f, 423f, 659f, 340f)),
        Habillage("paysage_deux_haut", "Deux écrans, grand en haut",
            Rect4(464f, 22f, 886f, 349f), Rect4(643f, 445f, 525f, 322f)),
        Habillage("paysage_un_carre", "Écran du haut",
            Rect4(472f, 71f, 867f, 675f), null),
        Habillage("paysage_un_large", "Écran du bas",
            null, Rect4(462f, 137f, 892f, 578f)),
    )


    private const val PREFS = "chambre_rudy"

    /** L'habillage a utiliser : le vertical, ou l'un des quatre horizontaux. */
    fun habillage(paysage: Boolean, rang: Int): Habillage =
        if (!paysage) PORTRAIT else PAYSAGES[((rang % PAYSAGES.size) + PAYSAGES.size) % PAYSAGES.size]

    fun parDefaut(ctx: Context, h: Habillage): Disposition {
        val d = Disposition.depuisSkin(ctx, h.dossier)
        h.haut?.let { d.items[Ids.ECRAN_HAUT] = it.copy4() }
        h.bas?.let { d.items[Ids.ECRAN_BAS] = it.copy4() }
        return d
    }

    fun charger(ctx: Context, h: Habillage): Disposition {
        val defaut = parDefaut(ctx, h)
        val brut = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("dispo_" + h.dossier, null) ?: return defaut
        return try {
            val d = Disposition.fromJson(JSONObject(brut), defaut.marges, defaut.courses)
            for ((id, r) in defaut.items) if (!d.items.containsKey(id)) d.items[id] = r.copy4()
            d
        } catch (_: Exception) { defaut }
    }

    fun enregistrer(ctx: Context, h: Habillage, d: Disposition) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("dispo_" + h.dossier, d.toJson().toString()).apply()
    }

    fun reinitialiser(ctx: Context, h: Habillage) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("dispo_" + h.dossier).apply()
    }
}
