package com.rudy.chambre

import android.graphics.*

/**
 * Les objets poses sur le decor : le carton, la radio, la serrure, la pastille
 * du livre. Ils ne sont pas sur la photo — c'est ici qu'ils prennent forme.
 */
object Objets {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Un pinceau remis a neuf : opacite pleine, aucun degrade en cours. */
    private fun neuf() {
        p.reset()
        p.isAntiAlias = true
        p.style = Paint.Style.FILL
        p.alpha = 255
    }
    private val texte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }



    /**
     * Le carton de la photo, redessine par-dessus lui-meme pour que ses rabats
     * s'ouvrent. On cherche ici la matiere : fibres verticales, cannelures sur
     * la tranche, ruban adhesif, coins uses, et la lumiere chaude du bureau qui
     * vient de la droite.
     */
    fun cartonPhoto(c: Canvas, face: RectF, profondeur: Float, ouverture: Float) {
        neuf()
        val h = face.height()
        val l = face.width()
        p.style = Paint.Style.FILL

        // ---- la tranche droite, en fuite ----
        val cote = Path()
        cote.moveTo(face.right, face.top)
        cote.lineTo(face.right + profondeur, face.top + profondeur * .42f)
        cote.lineTo(face.right + profondeur, face.bottom + profondeur * .42f)
        cote.lineTo(face.right, face.bottom)
        cote.close()
        p.shader = LinearGradient(face.right, 0f, face.right + profondeur, 0f,
            intArrayOf(0xFF8E6340.toInt(), 0xFF6E4A2E.toInt()), null, Shader.TileMode.CLAMP)
        c.drawPath(cote, p)
        p.shader = null

        // ---- la face avant : kraft, eclairee par la droite ----
        p.shader = LinearGradient(face.left, face.top, face.right, face.bottom,
            intArrayOf(0xFFB98A5E.toInt(), 0xFFC79A6C.toInt(), 0xFF9A6C45.toInt()),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(face, p)
        p.shader = null

        // les fibres du carton : de fines rayures verticales, a peine visibles
        p.strokeWidth = kotlin.math.max(1f, l * .004f)
        p.style = Paint.Style.STROKE
        var x = face.left + l * .02f
        var i = 0
        while (x < face.right) {
            p.color = if (i % 3 == 0) 0x14000000 else 0x10FFE0C0
            c.drawLine(x, face.top, x, face.bottom, p)
            x += l * .028f; i++
        }
        p.style = Paint.Style.FILL

        // le pli du milieu, la ou les deux rabats se rejoignent
        p.color = 0x2E2A1608
        c.drawRect(face.centerX() - l * .006f, face.top, face.centerX() + l * .006f, face.bottom, p)

        // le ruban adhesif, plus clair et un peu brillant
        p.color = 0x3CFFF3DC
        c.drawRect(face.centerX() - l * .075f, face.top, face.centerX() + l * .075f, face.bottom, p)
        p.color = 0x22FFFFFF
        c.drawRect(face.centerX() - l * .075f, face.top, face.centerX() - l * .045f, face.bottom, p)

        // l'ombre portee sous le rebord haut, et le bas plus sombre
        p.shader = LinearGradient(0f, face.top, 0f, face.top + h * .18f,
            intArrayOf(0x4A1E0F05, 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawRect(face.left, face.top, face.right, face.top + h * .18f, p)
        p.shader = LinearGradient(0f, face.bottom - h * .22f, 0f, face.bottom,
            intArrayOf(0x00000000, 0x552A1408), null, Shader.TileMode.CLAMP)
        c.drawRect(face.left, face.bottom - h * .22f, face.right, face.bottom, p)
        p.shader = null

        // les coins uses, plus clairs, comme un carton deja transporte
        p.color = 0x3AF2DCBE
        c.drawCircle(face.left + l * .04f, face.bottom - h * .05f, l * .045f, p)
        c.drawCircle(face.right - l * .05f, face.bottom - h * .04f, l * .035f, p)
        c.drawCircle(face.right - l * .03f, face.top + h * .07f, l * .028f, p)

        // ---- l'interieur, des que ca s'ouvre ----
        if (ouverture > 0f) {
            p.shader = LinearGradient(0f, face.top - profondeur * .6f * ouverture, 0f, face.top + h * .22f,
                intArrayOf(0xFF1A0F06.toInt(), 0xFF3A2413.toInt()), null, Shader.TileMode.CLAMP)
            c.drawRect(face.left, face.top - profondeur * .6f * ouverture,
                       face.right, face.top + h * .12f, p)
            p.shader = null
        }

        // ---- les deux rabats ----
        if (ouverture > 0f) {
            val lev = h * .62f * ouverture
            for (cote2 in 0..1) {
                val chemin = Path()
                if (cote2 == 0) {
                    chemin.moveTo(face.left, face.top)
                    chemin.lineTo(face.centerX(), face.top)
                    chemin.lineTo(face.centerX() - h * .06f, face.top - lev)
                    chemin.lineTo(face.left - h * .10f, face.top - lev * .82f)
                } else {
                    // le rabat de droite se redresse : il penchait trop
                    chemin.moveTo(face.centerX(), face.top)
                    chemin.lineTo(face.right, face.top)
                    chemin.lineTo(face.right + h * .07f, face.top - lev * .94f)
                    chemin.lineTo(face.centerX() + h * .04f, face.top - lev)
                }
                chemin.close()
                p.shader = LinearGradient(0f, face.top - lev, 0f, face.top,
                    intArrayOf(if (cote2 == 0) 0xFFD2AA80.toInt() else 0xFFC49A6C.toInt(),
                               0xFF9E7048.toInt()), null, Shader.TileMode.CLAMP)
                c.drawPath(chemin, p)
                p.shader = null
                // la tranche cannelee du rabat : les vagues du carton ondule
                p.style = Paint.Style.STROKE
                p.strokeWidth = kotlin.math.max(1f, h * .012f)
                p.color = 0x55FFF0D8
                val y0 = face.top - lev
                val depart = if (cote2 == 0) face.left - h * .10f else face.centerX() + h * .06f
                val fin = if (cote2 == 0) face.centerX() - h * .06f else face.right + h * .10f
                val vague = Path()
                var vx = depart
                var haut = true
                vague.moveTo(vx, y0 + h * .02f)
                while (vx < fin) {
                    val suivant = kotlin.math.min(vx + l * .035f, fin)
                    vague.quadTo((vx + suivant) / 2f,
                                 y0 + (if (haut) -h * .015f else h * .05f), suivant, y0 + h * .02f)
                    vx = suivant; haut = !haut
                }
                c.drawPath(vague, p)
                p.style = Paint.Style.FILL
            }
        }

        // ---- le ruban adhesif qui ferme le carton ----
        if (ouverture < .25f) {
            p.style = Paint.Style.FILL
            p.color = 0x66C9AE84
            val ruban = h * .085f
            c.drawRect(face.centerX() - ruban / 2f, face.top, face.centerX() + ruban / 2f, face.bottom, p)
            p.color = 0x33FFFFFF
            c.drawRect(face.centerX() - ruban / 2f, face.top,
                       face.centerX() - ruban / 2f + ruban * .22f, face.bottom, p)
        }

        // ---- les traces d'usure : coins frottes et rayures ----
        p.style = Paint.Style.STROKE
        p.strokeWidth = h * .006f
        p.color = 0x22000000
        c.drawLine(face.left + l * .10f, face.top + h * .70f,
                   face.left + l * .34f, face.top + h * .66f, p)
        c.drawLine(face.right - l * .16f, face.top + h * .38f,
                   face.right - l * .05f, face.top + h * .44f, p)
        p.style = Paint.Style.FILL

        // ---- le nom au marqueur ----
        // deux passages : un trait epais, puis le plein par-dessus. C'est ce qui
        // donne le bord un peu bave d'un feutre sur du carton.
        texte.typeface = Typeface.create("casual", Typeface.BOLD)
        texte.textSize = h * .40f
        c.save()
        c.rotate(-3.5f, face.centerX(), face.centerY())
        texte.style = Paint.Style.STROKE
        texte.strokeWidth = h * .055f
        texte.strokeJoin = Paint.Join.ROUND
        texte.strokeCap = Paint.Cap.ROUND
        texte.color = 0xF2241608.toInt()
        c.drawText("Retro", face.centerX(), face.centerY() + h * .14f, texte)
        texte.style = Paint.Style.FILL
        texte.color = 0xFF2E1B0A.toInt()
        c.drawText("Retro", face.centerX(), face.centerY() + h * .14f, texte)
        c.restore()
    }


    /**
     * La radio, redessinee trait pour trait d'apres celle de la version web :
     * antenne, poignee, haut-parleur a cercles, cadran gradue avec son aiguille
     * rouge, deux molettes, temoin et pieds. Les mesures sont celles du dessin
     * d'origine, sur une grille de 300 par 210.
     */
    fun radio(c: Canvas, r: RectF, allumee: Boolean) {
        neuf()
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
        neuf()
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
        neuf()
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


    /**
     * Le tiroir du bureau : la cavite sombre au fond, la facade en bois qui
     * bascule vers l'avant. Couleurs reprises de la version web.
     */
    fun tiroir(c: Canvas, r: RectF, ouverture: Float) {
        neuf()
        // la cavite, toujours dessinee : c'est elle qu'on voit quand ca s'ouvre
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(r.left, r.top, r.left, r.bottom,
            intArrayOf(0xFF120704.toInt(), 0xFF25100A.toInt(), 0xFF160A06.toInt()),
            floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(r, p)
        p.shader = null

        // la facade, qui s'incline en glissant vers le bas
        val h = r.height()
        val avance = h * .62f * ouverture
        val facade = RectF(r.left, r.top + avance, r.right, r.bottom + avance * .35f)
        // la teinte du bureau, relevee sur sa photo : #602010, avec un haut
        // un peu plus clair la ou la lumiere tombe et un bas plus sombre
        p.shader = LinearGradient(facade.left, facade.top, facade.left, facade.bottom,
            intArrayOf(0xFF6E2814.toInt(), 0xFF602010.toInt(), 0xFF48160C.toInt()),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(facade, p)
        p.shader = null
        // le liset clair du haut et l'ombre du bas, comme dans la page
        p.color = 0xCCBE7E54.toInt()
        c.drawRect(facade.left, facade.top, facade.right, facade.top + h * .05f, p)
        p.color = 0x73301C04
        c.drawRect(facade.left, facade.bottom - h * .12f, facade.right, facade.bottom, p)
        // la poignee
        p.color = 0xFFC8922F.toInt()
        c.drawRoundRect(RectF(facade.centerX() - r.width() * .16f, facade.centerY() - h * .06f,
                              facade.centerX() + r.width() * .16f, facade.centerY() + h * .06f),
                        h * .06f, h * .06f, p)
    }

    /**
     * La telecommande de la tele, couchee dans le tiroir de droite.
     *
     * Un boitier noir mat, legerement en biais, avec sa croix directionnelle,
     * son gros bouton rouge en haut et deux rangees de touches. Elle glisse
     * avec le tiroir, comme le cahier de l'autre cote.
     */
    fun telecommande(c: Canvas, tiroir: RectF, avance: Float, ouverture: Float) {
        val l = tiroir.width() * .17f
        val h = l * 3.4f
        val cx = tiroir.left + tiroir.width() * .30f
        // plus haut dans le tiroir : la facade reste degagee pour le refermer
        val cy = tiroir.top + avance - h * .18f * ouverture + tiroir.height() * .02f

        c.save()
        c.rotate(-11f, cx, cy)
        val r = RectF(cx - l / 2, cy - h / 2, cx + l / 2, cy + h / 2)

        // son ombre sur le fond du tiroir
        p.style = Paint.Style.FILL
        p.color = 0x55000000
        c.drawRoundRect(RectF(r.left + l * .07f, r.top + l * .09f,
                              r.right + l * .07f, r.bottom + l * .09f),
                        l * .30f, l * .30f, p)

        // le boitier, plus clair sur le haut : la lumiere vient de la fenetre
        val corps = Paint(Paint.ANTI_ALIAS_FLAG)
        corps.shader = LinearGradient(r.left, r.top, r.right, r.bottom,
            intArrayOf(0xFF3A3A3E.toInt(), 0xFF1C1C1F.toInt()),
            null, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, l * .30f, l * .30f, corps)

        // le liserait clair du bord superieur
        p.style = Paint.Style.STROKE
        p.strokeWidth = l * .035f
        p.color = 0x33FFFFFF
        c.drawRoundRect(RectF(r.left + l * .05f, r.top + l * .05f,
                              r.right - l * .05f, r.bottom - l * .05f),
                        l * .26f, l * .26f, p)
        p.style = Paint.Style.FILL

        // le bouton rouge, tout en haut
        p.color = 0xFFC0392B.toInt()
        c.drawCircle(r.centerX(), r.top + h * .09f, l * .13f, p)

        // la croix directionnelle
        val cxx = r.centerX(); val cyy = r.top + h * .30f
        val br = l * .30f; val ep = l * .12f
        p.color = 0xFF55555A.toInt()
        c.drawRoundRect(RectF(cxx - br, cyy - ep / 2, cxx + br, cyy + ep / 2),
                        ep * .4f, ep * .4f, p)
        c.drawRoundRect(RectF(cxx - ep / 2, cyy - br, cxx + ep / 2, cyy + br),
                        ep * .4f, ep * .4f, p)
        p.color = 0xFF6E6E74.toInt()
        c.drawCircle(cxx, cyy, ep * .52f, p)

        // deux rangees de touches, en bas
        p.color = 0xFF4A4A4F.toInt()
        var rang = 0
        while (rang < 4) {
            val y = r.top + h * (.48f + rang * .11f)
            c.drawRoundRect(RectF(cxx - l * .28f, y, cxx - l * .04f, y + h * .055f),
                            l * .05f, l * .05f, p)
            c.drawRoundRect(RectF(cxx + l * .04f, y, cxx + l * .28f, y + h * .055f),
                            l * .05f, l * .05f, p)
            rang++
        }
        c.restore()
    }

    /**
     * La loupe, posee en travers a cote de la telecommande.
     *
     * Un manche de bois, une virole doree, et un verre a peine teinte qui
     * laisse voir le fond du tiroir a travers, avec un reflet en croissant.
     */
    fun loupe(c: Canvas, tiroir: RectF, avance: Float, ouverture: Float) {
        val d = tiroir.width() * .22f            // diametre du verre
        val cx = tiroir.left + tiroir.width() * .66f
        val cy = tiroir.top + avance + tiroir.height() * .12f - d * .12f * ouverture

        c.save()
        c.rotate(24f, cx, cy)

        // le manche de bois, sous le verre
        val ml = d * 1.25f; val me = d * .26f
        val manche = RectF(cx + d * .42f, cy - me / 2, cx + d * .42f + ml, cy + me / 2)
        p.style = Paint.Style.FILL
        p.color = 0x55000000
        c.drawRoundRect(RectF(manche.left, manche.top + me * .28f,
                              manche.right, manche.bottom + me * .28f),
                        me * .5f, me * .5f, p)
        val bois = Paint(Paint.ANTI_ALIAS_FLAG)
        bois.shader = LinearGradient(manche.left, manche.top, manche.left, manche.bottom,
            intArrayOf(0xFF9A6636.toInt(), 0xFF5E3A18.toInt()),
            null, Shader.TileMode.CLAMP)
        c.drawRoundRect(manche, me * .5f, me * .5f, bois)

        // la virole doree, entre le manche et le verre
        p.color = 0xFFC9A227.toInt()
        c.drawRoundRect(RectF(cx + d * .34f, cy - me * .62f,
                              cx + d * .52f, cy + me * .62f),
                        me * .2f, me * .2f, p)

        // l'ombre du verre sur le fond
        p.color = 0x44000000
        c.drawCircle(cx + d * .06f, cy + d * .08f, d * .52f, p)

        // le cercle de metal
        p.style = Paint.Style.STROKE
        p.strokeWidth = d * .10f
        p.color = 0xFF8C8C92.toInt()
        c.drawCircle(cx, cy, d * .50f, p)

        // le verre : a peine teinte, plus clair au centre
        p.style = Paint.Style.FILL
        val verre = Paint(Paint.ANTI_ALIAS_FLAG)
        verre.shader = RadialGradient(cx - d * .14f, cy - d * .16f, d * .55f,
            intArrayOf(0x33FFFFFF, 0x18AFC8D8, 0x22000000),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, d * .45f, verre)

        // le reflet en croissant, en haut a gauche
        p.style = Paint.Style.STROKE
        p.strokeWidth = d * .07f
        p.color = 0x66FFFFFF
        c.drawArc(RectF(cx - d * .36f, cy - d * .36f, cx + d * .36f, cy + d * .36f),
                  165f, 85f, false, p)
        p.style = Paint.Style.FILL
        c.restore()
    }
}
