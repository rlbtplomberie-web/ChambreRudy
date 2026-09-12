package com.rudy.chambre.mdui

import android.content.Context
import org.json.JSONObject

/** Rectangle modifiable, en coordonnees du skin. */
data class Rect4(var x: Float, var y: Float, var w: Float, var h: Float) {
    fun copie() = Rect4(x, y, w, h)
}

/** Identifiants des pieces, tels qu'ils figurent dans les habillages. */
object Ids {
    const val ECRAN = "ecran"
    const val CROIX = "croix"
    const val A = "A"
    const val B = "B"
    const val C = "C"
    const val X = "X"
    const val Y = "Y"
    const val Z = "Z"
    const val FF = "ff"
    /** La pastille ovale, comme sur la manette six boutons. */
    const val START = "start"
    /** Fait passer d'un habillage au suivant. */
    const val MANETTE = "manette"
    const val JEUX = "jeux"
    const val MENU = "menu"

    /** Touches qui commandent l'application, pas la console. */
    val FONCTIONS = listOf(MANETTE, JEUX, MENU)
    /** Pieces qui basculent au lieu de s'enfoncer. */
    val DIRECTIONNELS = listOf(CROIX)
}

/** Une disposition : la taille du skin et la place de chaque piece. */
class Disposition(
    val sw: Float, val sh: Float,
    val items: MutableMap<String, Rect4>,
    val marges: Map<String, Int>,
    val courses: Map<String, Float>
) {
    fun copie() = Disposition(sw, sh, items.mapValues { it.value.copie() }.toMutableMap(),
                              marges, courses)

    fun versJson(): JSONObject {
        val o = JSONObject()
        for ((k, r) in items) {
            o.put(k, JSONObject().put("x", r.x.toDouble()).put("y", r.y.toDouble())
                                 .put("w", r.w.toDouble()).put("h", r.h.toDouble()))
        }
        return o
    }

    companion object {
        fun depuisSkin(ctx: Context, dossier: String, ecran: Rect4): Disposition {
            val texte = ctx.assets.open("skins/md/skin/$dossier/positions.json")
                .bufferedReader().use { it.readText() }
            val o = JSONObject(texte)
            val items = mutableMapOf<String, Rect4>()
            val marges = mutableMapOf<String, Int>()
            val courses = mutableMapOf<String, Float>()
            val tab = o.getJSONArray("elements")
            for (i in 0 until tab.length()) {
                val e = tab.getJSONObject(i)
                val id = e.getString("id")
                items[id] = Rect4(e.getDouble("x").toFloat(), e.getDouble("y").toFloat(),
                                  e.getDouble("w").toFloat(), e.getDouble("h").toFloat())
                marges[id] = e.optInt("marge", 0)
                if (e.has("course")) courses[id] = e.getDouble("course").toFloat()
            }
            items[Ids.ECRAN] = ecran
            return Disposition(o.getDouble("largeur").toFloat(),
                               o.getDouble("hauteur").toFloat(), items, marges, courses)
        }

        fun depuisJson(o: JSONObject, modele: Disposition): Disposition {
            val d = modele.copie()
            for (k in d.items.keys.toList()) {
                val r = o.optJSONObject(k) ?: continue
                d.items[k] = Rect4(r.getDouble("x").toFloat(), r.getDouble("y").toFloat(),
                                   r.getDouble("w").toFloat(), r.getDouble("h").toFloat())
            }
            return d
        }
    }
}

/**
 * Les cinq habillages : trois verticaux, deux horizontaux.
 *
 * Le bouton manette fait passer au suivant, dans l'orientation ou l'on se
 * trouve : les trois verticaux tournent entre eux, les deux horizontaux
 * entre eux. Chacun garde sa propre disposition.
 *
 * Les ecrans ont ete mesures sur chaque habillage puis rentres de quelques
 * pixels, pour que l'image ne touche jamais le cadre dessine.
 */
object Dispositions {

    /** nom du dossier, libelle affiche, rectangle de l'ecran */
    /*
     * Le rectangle donne ici est l'INTERIEUR de la dalle dessinee.
     *
     * Il est en retrait de 24 pixels sur le pourtour : au ras du bord,
     * l'image mordait sur le lisere et masquait les coins arrondis du cadre,
     * qui font tout l'aspect de ces boitiers.
     *
     * Les trois dalles n'ont pas le meme rapport — 1,83 sur la premiere,
     * 1,53 sur la seconde, 0,80 en portrait — alors que la console en a un
     * seul. C'est SkinView qui cale l'image au plus grand a l'interieur, en
     * gardant le 4:3, et qui peint le reste en noir : ainsi l'image remplit
     * chaque dalle autant que possible, sans jamais deborder sur le boitier
     * ni se deformer d'un habillage a l'autre.
     */
    /** L'habillage sans console : le seul dont les touches s'effacent. */
    const val TRANSPARENT = "md_paysage3"

    val VERTICAUX = listOf(
        Triple("md_portrait", "Mega Drive portrait", Rect4(92f, 129f, 669f, 847f)))

    val HORIZONTAUX = listOf(
        Triple("md_paysage", "Mega Drive", Rect4(454f, 149f, 922f, 482f)),
        Triple("md_paysage2", "Mega Drive 2", Rect4(429f, 146f, 972f, 620f)),
        /*
         * Le troisieme n'est pas une console : rien qu'un trace de touches
         * pose sur le jeu, qui occupe alors tout l'habillage. Son opacite se
         * regle dans le mode MODIFIER.
         */
        Triple(TRANSPARENT, "Touches transparentes", Rect4(0f, 0f, 1774f, 887f)))


    private const val PREFS = "skinmd"
    private const val CLE_V = "skin_vertical"
    private const val CLE_H = "skin_horizontal"
    private const val CLE_OPACITE = "opacite_touches"

    /**
     * Opacite des touches de l'habillage transparent : 0,12 a peine visible,
     * 1 bien marquee. Les autres habillages n'en tiennent pas compte.
     */
    fun opacite(ctx: Context): Float =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(CLE_OPACITE, 0.85f).coerceIn(0.12f, 1f)

    fun poserOpacite(ctx: Context, v: Float) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(CLE_OPACITE, v.coerceIn(0.12f, 1f)).apply()
    }

    /** Rang de l'habillage en cours, dans l'orientation donnee. */
    fun rang(ctx: Context, paysage: Boolean): Int {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val n = if (paysage) HORIZONTAUX.size else VERTICAUX.size
        return p.getInt(if (paysage) CLE_H else CLE_V, 0).coerceIn(0, n - 1)
    }

    fun poserRang(ctx: Context, paysage: Boolean, r: Int) {
        val n = if (paysage) HORIZONTAUX.size else VERTICAUX.size
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(if (paysage) CLE_H else CLE_V, ((r % n) + n) % n).apply()
    }

    /** Passe a l'habillage suivant et renvoie son libelle. */
    fun suivant(ctx: Context, paysage: Boolean): String {
        val liste = if (paysage) HORIZONTAUX else VERTICAUX
        val r = (rang(ctx, paysage) + 1) % liste.size
        poserRang(ctx, paysage, r)
        return liste[r].second
    }

    fun dossier(ctx: Context, paysage: Boolean): String =
        (if (paysage) HORIZONTAUX else VERTICAUX)[rang(ctx, paysage)].first

    fun libelle(ctx: Context, paysage: Boolean): String =
        (if (paysage) HORIZONTAUX else VERTICAUX)[rang(ctx, paysage)].second

    /** Tous les dossiers, pour les charger d'avance. */
    fun tous(): List<String> = (VERTICAUX + HORIZONTAUX).map { it.first }

    /*
     * "dispo_md_" et non "dispo_" : les habillages verticaux ayant ete
     * allonges, une disposition enregistree avec les anciennes coordonnees
     * replacerait les touches la ou elles ne sont plus.
     */
    private fun cle(ctx: Context, paysage: Boolean) =
        "dispo_md_" + dossier(ctx, paysage)

    fun parDefaut(ctx: Context, paysage: Boolean): Disposition {
        val liste = if (paysage) HORIZONTAUX else VERTICAUX
        val (dossier, _libelle, ecran) = liste[rang(ctx, paysage)]
        return Disposition.depuisSkin(ctx, dossier, ecran.copie())
    }

    fun charger(ctx: Context, paysage: Boolean): Disposition {
        val defaut = parDefaut(ctx, paysage)
        val brut = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(cle(ctx, paysage), null) ?: return defaut
        return try { Disposition.depuisJson(JSONObject(brut), defaut) }
        catch (_: Exception) { defaut }
    }

    fun enregistrer(ctx: Context, paysage: Boolean, d: Disposition) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(cle(ctx, paysage), d.versJson().toString()).apply()
    }
}
