package com.rudy.chambre

import android.graphics.*

/**
 * Les objets poses sur le decor : le carton, la radio, la serrure, la pastille
 * du livre. Ils ne sont pas sur la photo — c'est ici qu'ils prennent forme.
 */
object Objets {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val texte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    /**
     * Le carton de Rudy.
     * [ouverture] va de 0, rabats fermes, a 1, grands ouverts.
     */
    fun carton(c: Canvas, r: RectF, ouverture: Float, tremble: Float = 0f) {
        c.save()
        if (tremble != 0f) c.rotate(tremble, r.centerX(), r.bottom)

        val h = r.height()
        // les quatre rabats, qui se relevent
        if (ouverture > 0f) {
            p.style = Paint.Style.FILL
            p.shader = null
            p.color = 0xFFB07C54.toInt()
            val ouv = h * 0.55f * ouverture
            val chemin = Path()
            chemin.moveTo(r.left, r.top); chemin.lineTo(r.centerX(), r.top - ouv * .7f)
            chemin.lineTo(r.centerX(), r.top); chemin.close()
            c.drawPath(chemin, p)
            p.color = 0xFF8A5E3C.toInt()
            val chemin2 = Path()
            chemin2.moveTo(r.right, r.top); chemin2.lineTo(r.centerX(), r.top - ouv * .7f)
            chemin2.lineTo(r.centerX(), r.top); chemin2.close()
            c.drawPath(chemin2, p)
        }

        // le corps, en carton brun
        p.shader = LinearGradient(r.left, r.top, r.right, r.bottom,
            intArrayOf(0xFFC08A5E.toInt(), 0xFF9B6A44.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, h * .06f, h * .06f, p)
        p.shader = null

        // la bande du haut, plus sombre
        p.color = 0x33000000
        c.drawRect(r.left, r.top, r.right, r.top + h * .16f, p)

        // le nom, ecrit au feutre
        texte.color = 0xFFF6EFE2.toInt()
        texte.textSize = h * .30f
        c.drawText("RUDY", r.centerX(), r.centerY() + h * .10f, texte)
        c.restore()
    }

    /** La petite radio posee a gauche de la tele. */
    fun radio(c: Canvas, r: RectF, allumee: Boolean) {
        val h = r.height()
        p.shader = LinearGradient(r.left, r.top, r.left, r.bottom,
            intArrayOf(0xFF4A4652.toInt(), 0xFF2A2731.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, h * .22f, h * .22f, p)
        p.shader = null

        // la grille du haut-parleur
        p.color = 0xFF17151C.toInt()
        val g = RectF(r.left + r.width() * .07f, r.top + h * .18f,
                      r.left + r.width() * .46f, r.bottom - h * .18f)
        c.drawRoundRect(g, h * .10f, h * .10f, p)
        p.color = 0x22FFFFFF
        var y = g.top + h * .08f
        while (y < g.bottom - h * .04f) {
            c.drawLine(g.left + h * .06f, y, g.right - h * .06f, y, p); y += h * .10f
        }

        // les deux molettes
        p.color = 0xFFC9C4D2.toInt()
        c.drawCircle(r.right - r.width() * .28f, r.centerY(), h * .16f, p)
        c.drawCircle(r.right - r.width() * .12f, r.centerY(), h * .12f, p)

        // le temoin, vert quand la musique tourne
        p.color = if (allumee) 0xFF5CE07A.toInt() else 0xFFFF5F47.toInt()
        c.drawCircle(r.left + r.width() * .55f, r.top + h * .24f, h * .07f, p)
    }

    /** La serrure doree, sous la poignee de la baie vitree. */
    fun serrure(c: Canvas, r: RectF) {
        val l = r.width()
        p.color = 0xFFD9AB45.toInt()
        p.style = Paint.Style.STROKE
        p.strokeWidth = l * .16f
        c.drawArc(RectF(r.left + l * .22f, r.top, r.right - l * .22f, r.top + l * .62f),
                  180f, 180f, false, p)
        p.style = Paint.Style.FILL
        p.color = 0xFFC8922F.toInt()
        c.drawRoundRect(RectF(r.left, r.top + l * .34f, r.right, r.bottom), l * .18f, l * .18f, p)
        p.color = 0xFF5A3C0E.toInt()
        c.drawCircle(r.centerX(), r.top + l * .66f, l * .13f, p)
        c.drawRect(r.centerX() - l * .05f, r.top + l * .66f, r.centerX() + l * .05f, r.bottom - l * .12f, p)
    }

    /** La pastille ronde posee sur la rangee de mangas. */
    fun pastilleLivre(c: Canvas, r: RectF, battement: Float) {
        val cx = r.centerX(); val cy = r.centerY()
        val rayon = r.width() / 2f * (1f + .12f * battement)
        p.color = 0xB8140C1E.toInt()
        c.drawCircle(cx, cy, rayon, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = rayon * .12f
        p.color = 0xFFF0D79A.toInt()
        c.drawCircle(cx, cy, rayon * .92f, p)
        p.style = Paint.Style.FILL
        // un livre ouvert, deux pages
        val chemin = Path()
        chemin.moveTo(cx - rayon * .48f, cy - rayon * .28f)
        chemin.quadTo(cx - rayon * .10f, cy - rayon * .45f, cx, cy - rayon * .22f)
        chemin.lineTo(cx, cy + rayon * .42f)
        chemin.quadTo(cx - rayon * .12f, cy + rayon * .22f, cx - rayon * .48f, cy + rayon * .34f)
        chemin.close()
        c.drawPath(chemin, p)
        val chemin2 = Path()
        chemin2.moveTo(cx + rayon * .48f, cy - rayon * .28f)
        chemin2.quadTo(cx + rayon * .10f, cy - rayon * .45f, cx, cy - rayon * .22f)
        chemin2.lineTo(cx, cy + rayon * .42f)
        chemin2.quadTo(cx + rayon * .12f, cy + rayon * .22f, cx + rayon * .48f, cy + rayon * .34f)
        chemin2.close()
        p.color = 0xFFE0C078.toInt()
        c.drawPath(chemin2, p)
    }

    /** La façade du tiroir, avec sa poignee. */
    fun tiroir(c: Canvas, r: RectF) {
        p.shader = LinearGradient(r.left, r.top, r.left, r.bottom,
            intArrayOf(0xFF8A5A32.toInt(), 0xFF5E3A1E.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, r.height() * .12f, r.height() * .12f, p)
        p.shader = null
        p.color = 0xFFC8922F.toInt()
        val h = r.height()
        c.drawRoundRect(RectF(r.centerX() - r.width() * .18f, r.centerY() - h * .07f,
                              r.centerX() + r.width() * .18f, r.centerY() + h * .07f),
                        h * .07f, h * .07f, p)
    }
}
