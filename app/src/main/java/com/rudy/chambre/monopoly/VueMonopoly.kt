package com.rudy.chambre.monopoly

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * Le plateau dessine : ses quarante cases en carre, leurs bandeaux de couleur,
 * les maisons, les hotels et les jetons.
 *
 * Sa grille fait onze sur onze : les coins sont des cases pleines, les neuf
 * cases d'un cote se partagent le reste.
 */
class VueMonopoly(ctx: Context, private val partie: Partie) : View(ctx) {

    var surCase: ((Int) -> Unit)? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val texte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private var cx = 0f; private var cy = 0f; private var cote = 0f
    private var unite = 0f                 // la largeur d'une case de bord

    /** Son « cote » : a quel bord appartient la case. */
    private fun bord(i: Int) = when {
        i == 0 || i == 10 || i == 20 || i == 30 -> "coin"
        i < 10 -> "bas"
        i < 20 -> "gauche"
        i < 30 -> "haut"
        else -> "droite"
    }

    /** Son « grille » : la ligne et la colonne d'une case, de 1 a 11. */
    private fun grille(i: Int): Pair<Int, Int> = when {
        i <= 10 -> 11 to (11 - i)
        i <= 20 -> (11 - (i - 10)) to 1
        i <= 30 -> 1 to (1 + (i - 20))
        else -> (1 + (i - 30)) to 11
    }

    /** Le rectangle d'une case a l'ecran. */
    fun rectangleDe(i: Int): RectF {
        val (r, c) = grille(i)
        // les coins font une unite et demie, comme sur un vrai plateau
        fun position(n: Int): Float = when {
            n == 1 -> 0f
            n == 11 -> cote - unite * 1.5f
            else -> unite * 1.5f + (n - 2) * unite
        }
        fun taille(n: Int) = if (n == 1 || n == 11) unite * 1.5f else unite
        return RectF(cx + position(c), cy + position(r),
                     cx + position(c) + taille(c), cy + position(r) + taille(r))
    }

    private fun mesurer() {
        cote = min(width.toFloat(), height.toFloat()) * .96f
        cx = (width - cote) / 2f; cy = (height - cote) / 2f
        unite = cote / 12f          // neuf cases + deux coins d'une unite et demie
    }

    override fun onDraw(c: Canvas) {
        mesurer()
        c.drawColor(0xFF1A2B1F.toInt())

        // le tapis du plateau
        p.color = 0xFFCFE3CC.toInt()
        c.drawRect(cx, cy, cx + cote, cy + cote, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0xFF2B2823.toInt()
        c.drawRect(cx, cy, cx + cote, cy + cote, p)
        p.style = Paint.Style.FILL

        for (i in partie.plateau.indices) dessinerCase(c, i)
        dessinerJetons(c)
        titreCentral(c)
    }

    private fun dessinerCase(c: Canvas, i: Int) {
        val r = rectangleDe(i)
        val cas = partie.plateau[i]
        p.color = 0xFFF6F1E3.toInt()
        c.drawRect(r, p)

        // le bandeau de couleur d'un terrain, du cote interieur
        if (cas.type == "terrain") {
            val g = GROUPES[cas.groupe]
            if (g != null) {
                p.color = g.couleur
                val e = min(r.width(), r.height()) * .26f
                when (bord(i)) {
                    "bas" -> c.drawRect(r.left, r.top, r.right, r.top + e, p)
                    "haut" -> c.drawRect(r.left, r.bottom - e, r.right, r.bottom, p)
                    "gauche" -> c.drawRect(r.right - e, r.top, r.right, r.bottom, p)
                    "droite" -> c.drawRect(r.left, r.top, r.left + e, r.bottom, p)
                }
            }
        }

        // le proprietaire : un lisere de sa couleur
        if (cas.proprio >= 0) {
            val j = partie.joueurs.getOrNull(cas.proprio)
            if (j != null) {
                p.style = Paint.Style.STROKE; p.strokeWidth = unite * .10f
                p.color = j.couleur
                c.drawRect(r.left + 2f, r.top + 2f, r.right - 2f, r.bottom - 2f, p)
                p.style = Paint.Style.FILL
            }
        }

        // l'hypotheque : la case est barree
        if (cas.hypothequee) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0xAAB0281F.toInt()
            c.drawLine(r.left, r.top, r.right, r.bottom, p)
            p.style = Paint.Style.FILL
        }

        // le nom, ecrit petit
        texte.color = 0xFF2B2823.toInt()
        texte.textSize = unite * .17f
        val mots = cas.nom.split(" ")
        var y = r.centerY() - (mots.size - 1) * texte.textSize * .55f + texte.textSize * .3f
        for (mot in mots) {
            c.drawText(mot, r.centerX(), y, texte)
            y += texte.textSize * 1.05f
        }

        // le prix, en bas de la case
        if (cas.prix > 0) {
            texte.textSize = unite * .16f
            c.drawText("${cas.prix} €", r.centerX(), r.bottom - unite * .08f, texte)
        }

        // les maisons, ou l'hotel
        if (cas.maisons in 1..4) {
            val taille = unite * .16f
            for (k in 0 until cas.maisons) {
                p.color = 0xFF2F7A3E.toInt()
                val x = r.left + unite * .12f + k * taille * 1.3f
                c.drawRect(x, r.top + unite * .05f, x + taille, r.top + unite * .05f + taille, p)
            }
        } else if (cas.maisons == 5) {
            p.color = 0xFFB93C2F.toInt()
            c.drawRect(r.centerX() - unite * .22f, r.top + unite * .05f,
                       r.centerX() + unite * .22f, r.top + unite * .05f + unite * .18f, p)
        }

        // le cadre de la case
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = 0xFF2B2823.toInt()
        c.drawRect(r, p)
        p.style = Paint.Style.FILL
    }

    /** Les jetons : plusieurs sur une meme case se decalent en eventail. */
    private fun dessinerJetons(c: Canvas) {
        val parCase = partie.joueurs.filter { !it.ruine }.groupBy { it.pos }
        for ((pos, liste) in parCase) {
            val r = rectangleDe(pos)
            liste.forEachIndexed { k, j ->
                val decalage = (k - (liste.size - 1) / 2f) * unite * .22f
                val cx2 = r.centerX() + decalage
                val cy2 = r.centerY() + unite * .18f
                p.color = j.couleur
                c.drawCircle(cx2, cy2, unite * .17f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0xFF241F18.toInt()
                c.drawCircle(cx2, cy2, unite * .17f, p)
                p.style = Paint.Style.FILL
                texte.color = Color.WHITE; texte.textSize = unite * .19f
                c.drawText(j.nom.take(1), cx2, cy2 + unite * .07f, texte)
            }
        }
    }

    private fun titreCentral(c: Canvas) {
        texte.color = 0xFF2B2823.toInt()
        texte.textSize = cote * .055f
        c.save()
        c.rotate(-45f, cx + cote / 2f, cy + cote / 2f)
        c.drawText("MONOPOLY", cx + cote / 2f, cy + cote / 2f, texte)
        c.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
        for (i in partie.plateau.indices)
            if (rectangleDe(i).contains(e.x, e.y)) { surCase?.invoke(i); return true }
        return true
    }
}
