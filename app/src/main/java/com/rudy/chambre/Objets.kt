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


    /**
     * La radio, redessinee trait pour trait d'apres celle de la version web :
     * antenne, poignee, haut-parleur a cercles, cadran gradue avec son aiguille
     * rouge, deux molettes, temoin et pieds. Les mesures sont celles du dessin
     * d'origine, sur une grille de 300 par 210.
     */
    fun radio(c: Canvas, r: RectF, allumee: Boolean) {
        val e = r.width() / 300f            // l'echelle du dessin d'origine
        fun x(v: Float) = r.left + v * e
        fun y(v: Float) = r.top + v * e
        fun t(v: Float) = v * e

        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND

        // antenne
        p.color = 0xFFC9C6CD.toInt(); p.strokeWidth = t(5f)
        c.drawLine(x(232f), y(66f), x(286f), y(8f), p)
        p.style = Paint.Style.FILL; p.color = 0xFFE6E3EA.toInt()
        c.drawCircle(x(286f), y(8f), t(5f), p)

        // poignee
        p.style = Paint.Style.STROKE; p.color = 0xFF3A3843.toInt(); p.strokeWidth = t(9f)
        val poignee = Path()
        poignee.moveTo(x(96f), y(54f))
        poignee.quadTo(x(150f), y(20f), x(204f), y(54f))
        c.drawPath(poignee, p)

        // corps, en bois clair
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(x(26f), y(58f), x(26f), y(190f),
            intArrayOf(0xFFD9C19A.toInt(), 0xFFB99A72.toInt(), 0xFF8D7250.toInt()),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        val corps = RectF(x(26f), y(58f), x(274f), y(190f))
        c.drawRoundRect(corps, t(16f), t(16f), p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.color = 0xFF6D5738.toInt(); p.strokeWidth = t(4f)
        c.drawRoundRect(corps, t(16f), t(16f), p)
        p.style = Paint.Style.FILL; p.color = 0x8CE4D0AA.toInt()
        c.drawRoundRect(RectF(x(26f), y(58f), x(274f), y(84f)), t(14f), t(14f), p)

        // haut-parleur
        p.shader = RadialGradient(x(78f), y(114f), t(44f),
            intArrayOf(0xFF6A6873.toInt(), 0xFF2A2930.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(x(92f), y(128f), t(44f), p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.color = 0xFF5D4C33.toInt(); p.strokeWidth = t(4f)
        c.drawCircle(x(92f), y(128f), t(44f), p)
        p.color = 0xCC15141A.toInt(); p.strokeWidth = t(3f)
        for (rayon in floatArrayOf(34f, 24f, 14f)) c.drawCircle(x(92f), y(128f), t(rayon), p)
        p.style = Paint.Style.FILL; p.color = 0xFF15141A.toInt()
        c.drawCircle(x(92f), y(128f), t(7f), p)

        // cadran
        p.shader = LinearGradient(x(150f), y(76f), x(258f), y(76f),
            intArrayOf(0xFF2B2A2F.toInt(), 0xFF46454C.toInt(), 0xFF232227.toInt()),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(x(150f), y(76f), x(258f), y(118f)), t(6f), t(6f), p)
        p.shader = null
        p.color = 0xFFF3D9A0.toInt()
        c.drawRoundRect(RectF(x(155f), y(81f), x(253f), y(113f)), t(4f), t(4f), p)
        p.style = Paint.Style.STROKE; p.color = 0xFF7A5A2A.toInt(); p.strokeWidth = t(2f)
        var i = 0
        for (gx in floatArrayOf(164f, 178f, 192f, 206f, 220f, 234f, 246f)) {
            val bas = if (i % 2 == 0) 108f else 102f
            c.drawLine(x(gx), y(86f), x(gx), y(bas), p); i++
        }
        p.color = 0xFFC9302C.toInt(); p.strokeWidth = t(3f)
        c.drawLine(x(199f), y(82f), x(199f), y(112f), p)

        // les deux molettes
        for (mx in floatArrayOf(168f, 212f)) {
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(x(mx), y(135f), x(mx), y(169f),
                intArrayOf(0xFFF0E2C4.toInt(), 0xFF9C8560.toInt()), null, Shader.TileMode.CLAMP)
            c.drawCircle(x(mx), y(152f), t(17f), p)
            p.shader = null
            p.style = Paint.Style.STROKE; p.color = 0xFF6D5738.toInt(); p.strokeWidth = t(3f)
            c.drawCircle(x(mx), y(152f), t(17f), p)
        }
        p.color = 0xFF4A3A20.toInt(); p.strokeWidth = t(3f)
        c.drawLine(x(168f), y(140f), x(168f), y(149f), p)
        c.drawLine(x(212f), y(152f), x(219f), y(145f), p)

        // le temoin : vert quand la musique tourne
        p.style = Paint.Style.FILL; p.color = 0xFF3A3843.toInt()
        c.drawRoundRect(RectF(x(238f), y(140f), x(264f), y(164f)), t(4f), t(4f), p)
        p.color = if (allumee) 0xFF5CE07A.toInt() else 0xFFFF5F47.toInt()
        c.drawCircle(x(251f), y(152f), t(4f), p)

        // pieds
        p.color = 0xFF3A3843.toInt()
        c.drawRoundRect(RectF(x(52f), y(188f), x(78f), y(197f)), t(4f), t(4f), p)
        c.drawRoundRect(RectF(x(222f), y(188f), x(248f), y(197f)), t(4f), t(4f), p)

        // la lumiere chaude du bureau a droite, la LED bleue a gauche
        p.color = 0x40FFB066
        c.drawRect(x(258f), y(62f), x(274f), y(190f), p)
        p.color = 0x385A7BFF
        c.drawRect(x(26f), y(62f), x(42f), y(190f), p)
        p.style = Paint.Style.FILL
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
