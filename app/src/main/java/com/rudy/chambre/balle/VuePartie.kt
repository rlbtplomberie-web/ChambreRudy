package com.rudy.chambre.balle

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Le dessin du jeu de Rudy, traduit de sa fonction « draw » et de sa
 * fonction « person ».
 *
 * Son terrain, ses six personnages avec leurs planches d'images, sa balle
 * rayee, ses flammes de tir charge, son eclair de charge, sa jauge d'energie
 * et son decompte.
 */
class VuePartie(ctx: Context) : View(ctx) {

    val partie = Partie(1f, 1f)
    var surFin: ((String) -> Unit)? = null

    private val images = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    private var terrain: Bitmap? = null

    private val pinceau = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val texte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private var dernier = 0L
    private var finAnnoncee = false

    /**
     * La finesse de l'ecran.
     *
     * Sa page raisonne en points d'ecran — innerWidth et innerHeight — et le
     * navigateur agrandit ensuite le dessin. On fait pareil : la partie
     * travaille en points, et on agrandit au moment de dessiner. Sans cela les
     * personnages sont deux a trois fois trop petits et tout va trop lentement.
     */
    private val finesse: Float get() = resources.displayMetrics.density

    /** L'ordre habituel des lignes : une par action. */
    private val lignes = mapOf(
        "walk" to 0, "run" to 1, "throw" to 2, "dodge" to 3, "catch" to 4,
        "fall" to 5, "rise" to 6, "hit" to 7, "pass" to 8
    )

    /**
     * La planche de Mathis est decalee d'un cran : sa marche se trouve en
     * derniere ligne, et toutes les autres remontent d'une place. Verifie en
     * mesurant les silhouettes case par case et en les comparant a celles des
     * autres planches.
     */
    private val lignesMathis = mapOf(
        "run" to 0, "throw" to 1, "dodge" to 2, "catch" to 3, "fall" to 4,
        "rise" to 5, "hit" to 6, "pass" to 7, "walk" to 8
    )

    /** L'ordre des lignes propre a ce personnage. */
    private fun lignesDe(nom: String) = if (nom == "Mathis") lignesMathis else lignes

    /** Chaque personnage a sa planche et la taille de ses cases. */
    private class Planche(val fichier: String, val sw: Int, val sh: Int)

    /**
     * Chaque personnage garde sa planche, par son nom : Sophie est blonde,
     * Mathis est le nouveau, Shanna est la fille, Theo est chauve et Carlos
     * est le costaud. C'est ainsi qu'il les avait repartis.
     */
    private val planches = mapOf(
        "Sophie" to Planche("blonde_atlas.webp", 320, 370),
        "Mathis" to Planche("newguy_atlas.webp", 360, 390),
        "Shanna" to Planche("girl_atlas.webp", 360, 390),
        "Théo"   to Planche("bald_atlas.webp", 320, 370),
        "Carlos" to Planche("fat_atlas.webp", 360, 390)
    )

    /** La position de la balle dans les mains, pose par pose (« CATCH_BALL_POS »). */
    private val posBalle = arrayOf(
        58f to 50f, 58f to 50f, 75.48828f to 40.613144f, 73.046875f to 39.11691f,
        69.01855f to 40.994003f, 63.52539f to 41.266045f,
        56.61621f to 45.047436f, 55.517578f to 43.98647f
    )

    init {
        // le halo des eclairs et des flammes demande la peinture logicielle
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        Thread {
            charger("terrain.webp")?.let { terrain = it; post { invalidate() } }
            for (pl in planches.values) charger(pl.fichier)
            for (action in lignes.keys) for (i in 0 until 8) charger("hero_${action}_$i.webp")
            post { invalidate() }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun charger(nom: String): Bitmap? {
        images[nom]?.let { return it }
        return try {
            context.assets.open("balle/$nom").use {
                BitmapFactory.decodeStream(it)?.also { b -> images[nom] = b }
            }
        } catch (_: Throwable) { null }
    }

    override fun onSizeChanged(l: Int, h: Int, al: Int, ah: Int) {
        super.onSizeChanged(l, h, al, ah)
        partie.W = l / finesse; partie.H = h / finesse
        partie.reset()
    }

    // ================= la boucle =================

    override fun onDraw(c: Canvas) {
        val maintenant = System.nanoTime()
        val dt = if (dernier == 0L) 0f else ((maintenant - dernier) / 1_000_000_000.0).toFloat()
        dernier = maintenant
        partie.update(min(dt, 0.05f))

        c.save()
        c.scale(finesse, finesse)          // on passe dans son repere

        dessinerTerrain(c)
        // les plus loin d'abord, pour que les plus proches passent devant
        for (j in partie.P.sortedBy { it.y }) { personnage(c, j); eclairDeCharge(c, j) }
        jaugeEnergie(c)
        tableauDeScore(c)
        decompte(c)
        impact(c)
        balle(c)
        petitMot(c)
        c.restore()

        if (partie.over && !finAnnoncee) { finAnnoncee = true; surFin?.invoke(partie.msg) }
        invalidate()
    }

    /**
     * Ses zones d'arbres et de banderoles, en coordonnees de son image
     * (1536 x 864). Elles ondulent sous le vent, comme dans son decor anime.
     */
    private val zonesArbres = listOf(
        "42,42 165,38 184,72 164,116 72,126 38,90",
        "211,78 281,54 353,66 411,105 501,151 514,188 441,193 370,173 311,183 250,158 211,127",
        "908,147 945,137 982,151 987,180 950,193 912,184",
        "1132,39 1285,41 1314,83 1302,138 1244,157 1153,145 1121,101",
        "1362,36 1442,32 1491,65 1496,121 1459,157 1380,157 1341,117"
    )
    private val zonesBanderoles = listOf(
        "304,217 470,213 496,249 489,319 449,339 354,333 309,306",
        "625,205 880,203 919,230 913,304 874,323 669,321 629,302",
        "996,219 1199,220 1232,246 1220,306 1183,325 1024,319 990,300"
    )

    /** Fabrique un contour a partir d'une suite de points, a l'echelle voulue. */
    private fun contour(points: String, ox: Float, oy: Float, s: Float): Path {
        val chemin = Path()
        points.trim().split(" ").forEachIndexed { i, pt ->
            val (a, b) = pt.split(",")
            val x = ox + a.toFloat() * s
            val y = oy + b.toFloat() * s
            if (i == 0) chemin.moveTo(x, y) else chemin.lineTo(x, y)
        }
        chemin.close()
        return chemin
    }

    /** Son « drawBG » : l'image couvre l'ecran sans se deformer. */
    private fun dessinerTerrain(c: Canvas) {
        val bg = terrain
        if (bg == null) { c.drawColor(0xFF0B2A12.toInt()); return }
        val s = max(partie.W / bg.width, partie.H / bg.height)
        val dw = bg.width * s; val dh = bg.height * s
        c.drawBitmap(bg, null,
            RectF((partie.W - dw) / 2f, (partie.H - dh) / 2f,
                  (partie.W + dw) / 2f, (partie.H + dh) / 2f), pinceau)
        // les arbres et les banderoles ondulent : on redessine ces zones
        // decalees, doucement pour les arbres, plus vivement pour le tissu
        val ox = (partie.W - dw) / 2f; val oy = (partie.H - dh) / 2f
        val cadre = RectF(ox, oy, ox + dw, oy + dh)
        // les arbres : deux rythmes croises, et le haut bouge plus que le bas
        arbresAuVent(c, bg, cadre, s, ox, oy)
        // les banderoles : elles claquent, chacune a son tempo
        banderolesAuVent(c, bg, cadre, s, ox, oy)

        p.color = 0x22001020
        c.drawRect(0f, 0f, partie.W, partie.H, p)
    }

    /**
     * Les arbres du fond. Chaque bosquet se balance a son propre rythme, et
     * la cime bouge plus que le tronc : on redessine la zone en trois tranches
     * horizontales, de plus en plus decalees vers le haut.
     */
    private fun arbresAuVent(c: Canvas, bg: Bitmap, cadre: RectF, s: Float, ox: Float, oy: Float) {
        zonesArbres.forEachIndexed { i, z ->
            val contour = contour(z, ox, oy, s)
            val boite = RectF()
            contour.computeBounds(boite, true)
            // a l'echelle du terrain : on voit les arbres respirer
            val souffle = sin(partie.T * (1.0f + i * .17f)) * (partie.W * .0055f) +
                          sin(partie.T * (2.3f + i * .11f)) * (partie.W * .0022f)
            for (tranche in 0 until 3) {
                val haut = boite.top + boite.height() * tranche / 3f
                val bas = boite.top + boite.height() * (tranche + 1) / 3f
                // la cime, tranche 0, prend tout le vent ; le pied presque rien
                val force = when (tranche) { 0 -> 1f; 1 -> .55f; else -> .18f }
                c.save()
                c.clipPath(contour)
                c.clipRect(boite.left - 20f, haut, boite.right + 20f, bas)
                c.translate(souffle * force, kotlin.math.abs(souffle) * force * .25f)
                c.drawBitmap(bg, null, cadre, pinceau)
                c.restore()
            }
        }
    }

    /**
     * Les banderoles accrochees au grillage : elles claquent au vent. Le
     * tissu ondule en vague, plus fort vers son bord libre.
     */
    private fun banderolesAuVent(c: Canvas, bg: Bitmap, cadre: RectF, s: Float, ox: Float, oy: Float) {
        zonesBanderoles.forEachIndexed { i, z ->
            val contour = contour(z, ox, oy, s)
            val boite = RectF()
            contour.computeBounds(boite, true)
            val tempo = 2.2f + i * .45f
            val bandes = 6
            for (k in 0 until bandes) {
                val x0 = boite.left + boite.width() * k / bandes
                val x1 = boite.left + boite.width() * (k + 1) / bandes
                // la vague parcourt le tissu de gauche a droite
                val phase = partie.T * tempo - k * .55f
                val onde = sin(phase) * 2.6f
                c.save()
                c.clipPath(contour)
                c.clipRect(x0, boite.top - 12f, x1, boite.bottom + 12f)
                c.translate(0f, onde)
                c.scale(1f, 1f + onde * .012f, 0f, boite.top)
                c.drawBitmap(bg, null, cadre, pinceau)
                c.restore()
            }
        }
    }

    /** Une zone du decor, redessinee legerement decalee : c'est le vent. */
    private fun ondulation(c: Canvas, bg: Bitmap, cadre: RectF, s: Float,
                           ox: Float, oy: Float, zones: List<String>,
                           decalage: Float, amplitude: Float) {
        for (z in zones) {
            c.save()
            c.clipPath(contour(z, ox, oy, s))
            c.translate(decalage * amplitude, sin(partie.T * 1.7f) * amplitude * .4f)
            c.drawBitmap(bg, null, cadre, pinceau)
            c.restore()
        }
    }

    /** La hauteur commune a tous les personnages, comme chez lui. */
    /** Un peu plus grands qu'avant, comme il l'a demande. */
    private fun hauteurCible() = min(140f, max(108f, partie.H * .172f))

    /** L'action en cours et la pose correspondante (son « person »). */
    /** Ce joueur est-il en train de charger son tir ? */
    private fun enCharge(j: Partie.Joueur): Boolean =
        if (j.h) partie.charging && partie.B.held === j && j.energy >= 100f
        else partie.B.held === j && j.energy >= 100f && j.cpuCharge > 0f

    private fun actionEtPose(j: Partie.Joueur): Pair<String, Int> {
        fun pose(avancement: Float) = min(7, floor(avancement * 8f).toInt().coerceAtLeast(0))
        return when {
            j.fallA > 0f -> "fall" to pose(1f - j.fallA / .82f)
            j.riseA > 0f -> "rise" to pose(1f - j.riseA / .82f)
            j.hitA > 0f -> "hit" to pose(1f - j.hitA / .35f)
            // il recoit une passe : rattrapage a l'envers, 7 puis 6, 5, 4, 3
            j.receptA > 0f -> {
                val avance = (1f - j.receptA / .50f).coerceIn(0f, 1f)
                val rang = (6 - (avance * 5f).toInt()).coerceIn(2, 6)
                "catch" to rang
            }
            // il donne la passe : sa planche « passe », en entier, a l'endroit
            j.passA > 0f -> "pass" to pose(1f - j.passA / .55f)
            j.pickupA > 0f -> "catch" to pose(1f - j.pickupA / .62f)
            // le bras part de l'image 4 — la ou il est arme — et deroule
            // jusqu'a la 8 : c'est la suite du geste de charge
            j.throwA > 0f -> {
                val avance = (1f - j.throwA / .55f).coerceIn(0f, 1f)
                "throw" to (3 + (avance * 4.99f).toInt()).coerceIn(3, 7)
            }
            j.catchA > 0f && partie.B.held === j -> "catch" to pose(1f - j.catchA / .62f)
            j.dodge > 0f -> "dodge" to pose(1f - j.dodge / .38f)
            // Il charge son tir : sa planche « lancer », image 4, le bras
            // arme derriere lui. La pose reste figee tant qu'il appuie.
            partie.B.held === j && enCharge(j) -> "throw" to 3

            // La balle en main sans charger : sa planche « rattrapage de
            // balle », image 5, les deux mains refermees dessus.
            partie.B.held === j -> "catch" to 4
            hypot(j.vx, j.vy) > 150f -> "run" to (floor(partie.T * 12f).toInt().mod(8))
            hypot(j.vx, j.vy) > 12f -> "walk" to (floor(partie.T * 8f).toInt().mod(8))
            else -> "walk" to 0
        }
    }

    // ce que le dessin retient pour placer la balle dans les mains,
    // pour chaque personnage et non plus pour le seul Rudy
    private class Mesures { var dw = 0f; var dh = 0f; var dy = 0f; var pose = -1; var face = 1f }
    private val mesures = HashMap<String, Mesures>()
    private fun mesuresDe(j: Partie.Joueur) = mesures.getOrPut(j.nom) { Mesures() }

    private var heroDW = 0f; private var heroDH = 0f; private var heroDY = 0f
    private var heroPose = -1

    private fun personnage(c: Canvas, j: Partie.Joueur) {
        val (action, pose) = actionEtPose(j)
        val cible = hauteurCible()
        // le saut du ramassage, comme dans son code
        var saut = if (j.pickupA > 0f) sin((1f - j.pickupA / .62f) * PI).toFloat() * 12f else 0f

        // Les pas du porteur : il garde la balle contre lui, mais son corps
        // monte et redescend a chaque foulee. Deux appuis par pas, comme
        // une vraie marche ; plus vif quand il court.
        if (partie.B.held === j) {
            val vitesse = hypot(j.vx, j.vy)
            if (vitesse > 12f) {
                val cadence = if (vitesse > 150f) 12f else 8f
                val amplitude = if (vitesse > 150f) 3.4f else 2.1f
                saut -= kotlin.math.abs(sin(partie.T * cadence * PI.toFloat() / 2f)) * amplitude
            }
        }

        if (j.h) {
            val im = charger("hero_${action}_$pose.webp") ?: return
            val reference = (charger("hero_walk_0.webp")?.height ?: im.height).toFloat()
            // le geste se voit mieux si le personnage ne change pas de taille
            val echelle = cible / reference
            val dh = im.height * echelle; val dw = im.width * echelle
            val dy = j.y - dh
            heroDW = dw; heroDH = dh; heroDY = dy
            heroPose = if (action == "catch") pose else -1
            mesuresDe(j).also { m ->
                m.dw = dw; m.dh = dh; m.dy = dy
                m.pose = if (action == "catch") pose else -1
                m.face = if (cos(j.face) < 0f) -1f else 1f
            }
            dessinerImage(c, im, j, dw, dh, dy + saut)
            texte.color = Color.WHITE; texte.textSize = 10f
            c.drawText("TOI" + (if (j.prison) " • PRISON" else ""), j.x, dy - 4f, texte)
            return
        }

        val pl = planches[j.nom] ?: return
        val planche = charger(pl.fichier) ?: return
        // les planches ont ete allegees de moitie : les cases suivent
        val ech = planche.width / (8f * pl.sw)
        val sw = (pl.sw * ech); val sh = (pl.sh * ech)
        val ligne = lignesDe(j.nom)[action] ?: 0
        val src = Rect((pose * sw).toInt(), (ligne * sh).toInt(),
                       ((pose + 1) * sw).toInt(), ((ligne + 1) * sh).toInt())
        val echelle = cible / pl.sh
        val dh = pl.sh * echelle; val dw = pl.sw * echelle
        val dy = j.y - dh
        mesuresDe(j).also { m ->
            m.dw = dw; m.dh = dh; m.dy = dy
            m.pose = if (action == "catch") pose else -1
            m.face = if (cos(j.face) < 0f) -1f else 1f
        }
        dessinerCase(c, planche, src, j, dw, dh, dy + saut)
        texte.color = if (j.prison) 0xFFFFB3A0.toInt() else 0xFFF2E6C8.toInt()
        texte.textSize = 10f
        c.drawText(j.nom + (if (j.prison) " • PRISON" else ""), j.x, dy - 4f, texte)
    }

    private fun dessinerImage(c: Canvas, im: Bitmap, j: Partie.Joueur,
                              dw: Float, dh: Float, dy: Float) {
        c.save()
        if (cos(j.face) < 0f) c.scale(-1f, 1f, j.x, 0f)
        // l'ombre au sol, qui ancre le personnage sur le terrain
        p.color = 0x55000000
        c.drawOval(RectF(j.x - dw * .28f, j.y - dh * .045f,
                         j.x + dw * .28f, j.y + dh * .045f), p)
        c.drawBitmap(im, null, RectF(j.x - dw / 2f, dy, j.x + dw / 2f, dy + dh), pinceau)
        c.restore()
    }

    private fun dessinerCase(c: Canvas, planche: Bitmap, src: Rect, j: Partie.Joueur,
                             dw: Float, dh: Float, dy: Float) {
        c.save()
        if (cos(j.face) < 0f) c.scale(-1f, 1f, j.x, 0f)
        c.drawBitmap(planche, src, RectF(j.x - dw / 2f, dy, j.x + dw / 2f, dy + dh), pinceau)
        c.restore()
    }

    // ================= la balle =================

    /** Son dessin de la balle : blanche, rayee, et enflammee quand elle est chargee. */
    private fun balle(c: Canvas) {
        val B = partie.B
        var bx: Float? = B.x
        var by = B.y - B.z
        var ombreX = B.x; var ombreY = B.y + 5f

        val porteur = B.held
        val m = if (porteur != null) mesures[porteur.nom] else null
        if (porteur != null && m != null && m.dh > 0f) {
            /*
             * La balle se pose dans les mains, pour tout le monde.
             *
             * Ses reperes sont donnes en pourcentages du corps ; quand le
             * personnage regarde a gauche, l'ecart lateral se retourne avec
             * lui, sinon la balle passerait derriere son dos.
             */
            val q = if (m.pose in 0..7 && porteur.catchA > 0f) {
                if (m.pose < 2) null else posBalle[m.pose]
            } else posBalle[7]

            if (q == null) bx = null
            else {
                val ecart = (q.first - 50f) / 25f * m.dw * m.face
                bx = porteur.x + ecart
                by = (m.dy + m.dh * .5f) + (q.second - 50f) / 25f * m.dh
            }
            ombreX = porteur.x; ombreY = porteur.y + 5f
        }

        p.color = 0x77000000
        c.drawOval(RectF(ombreX - 11f, ombreY - 5f, ombreX + 11f, ombreY + 5f), p)

        val x0 = bx ?: return
        val r = B.r

        // les flammes du tir charge
        val enFlammes = B.charged ||
            (partie.charging && B.held === partie.P[0] && partie.shotCharge >= 3f) ||
            (porteur != null && !porteur.h && porteur.cpuCharge >= 2.6f)
        if (enFlammes) {
            /*
             * Les flammes, dans l'esprit manga : un coeur blanc, une couronne
             * jaune, des langues orange qui montent en ondulant, et quelques
             * braises qui s'echappent derriere le ballon.
             */
            val T = partie.T
            val pulse = 1f + sin(T * 20f) * .16f
            c.save(); c.translate(x0, by)

            // la lueur chaude qui baigne le ballon
            val lueur = Paint(Paint.ANTI_ALIAS_FLAG)
            lueur.shader = RadialGradient(0f, 0f, r * 3.4f * pulse,
                intArrayOf(0x88FF7A18.toInt(), 0x33FF3D00, Color.TRANSPARENT),
                floatArrayOf(.25f, .55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(0f, 0f, r * 3.4f * pulse, lueur)

            // les langues de feu : deux tours, l'un lent, l'autre rapide
            p.style = Paint.Style.FILL
            p.setShadowLayer(14f, 0f, 0f, 0xFFFF5A00.toInt())
            for (tour in 0 until 2) {
                val combien = if (tour == 0) 11 else 7
                val vitesse = if (tour == 0) 3.2f else -4.6f
                for (k in 0 until combien) {
                    val a = k * (PI * 2 / combien).toFloat() + T * vitesse
                    // chaque langue respire a son propre rythme
                    val vie = .62f + .38f * sin(T * (9f + k * 1.7f) + tour * 2f)
                    val longue = (r * (if (tour == 0) 1.5f else 1.05f)) * vie * pulse
                    c.save()
                    c.rotate(Math.toDegrees(a.toDouble()).toFloat())
                    val flamme = Path()
                    val base = r * .78f
                    flamme.moveTo(base, -r * .42f)
                    // la langue ondule : deux courbes opposees jusqu'a la pointe
                    flamme.quadTo(base + longue * .55f, -r * .55f,
                                  base + longue, -r * .06f * sin(T * 11f + k))
                    flamme.quadTo(base + longue * .55f, r * .55f,
                                  base, r * .42f)
                    flamme.close()
                    p.color = if (tour == 0) {
                        if (k % 2 == 0) 0xFFFF4D00.toInt() else 0xFFFF7A18.toInt()
                    } else 0xFFFFC33A.toInt()
                    p.alpha = (255 * (.55f + .45f * vie)).toInt().coerceIn(0, 255)
                    c.drawPath(flamme, p)
                    c.restore()
                }
            }
            p.clearShadowLayer()

            // le coeur blanc, le point le plus chaud
            val coeur = Paint(Paint.ANTI_ALIAS_FLAG)
            coeur.shader = RadialGradient(0f, 0f, r * 1.25f,
                intArrayOf(0xEEFFFFFF.toInt(), 0x99FFE08A.toInt(), Color.TRANSPARENT),
                floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(0f, 0f, r * 1.25f, coeur)

            // les braises qui s'echappent derriere
            p.style = Paint.Style.FILL
            for (k in 0 until 7) {
                val phase = (T * (1.4f + k * .17f) + k * .41f) % 1f
                val a = k * 1.9f + T * .6f
                val d = r * (1.2f + phase * 2.6f)
                val bx2 = cos(a) * d
                val by2 = sin(a) * d - phase * r * 2.2f
                p.color = if (k % 2 == 0) 0xFFFFC33A.toInt() else 0xFFFF6A12.toInt()
                p.alpha = (230 * (1f - phase)).toInt().coerceIn(0, 255)
                c.drawCircle(bx2, by2, kotlin.math.max(.7f, r * .16f * (1f - phase)), p)
            }
            p.alpha = 255
            c.restore()
        }

        // ---- son ballon : blanc, avec de larges rayures noires ----
        c.save()
        // il tourne en roulant : le sens suit son deplacement
        c.rotate(B.tour, x0, by)

        // le cuir : un blanc qui se cambre, plus lumineux en haut a gauche
        val cuir = Paint(Paint.ANTI_ALIAS_FLAG)
        cuir.shader = RadialGradient(x0 - r * .34f, by - r * .36f, r * 1.6f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFF2F1EE.toInt(), 0xFFBDBBB6.toInt()),
            floatArrayOf(0f, .52f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x0, by, r, cuir)

        // les rayures : trois bandes courbes, larges, qui epousent la rondeur
        p.style = Paint.Style.STROKE
        p.color = 0xFF17171A.toInt()
        p.strokeCap = Paint.Cap.BUTT
        p.strokeWidth = r * .30f
        c.save()
        c.clipPath(Path().apply { addCircle(x0, by, r, Path.Direction.CW) })
        for (k in -1..1) {
            val bande = Path()
            val decalage = k * r * .74f
            bande.moveTo(x0 + decalage - r * .22f, by - r * 1.2f)
            bande.quadTo(x0 + decalage + r * .30f, by,
                         x0 + decalage - r * .22f, by + r * 1.2f)
            c.drawPath(bande, p)
        }
        c.restore()
        p.strokeCap = Paint.Cap.ROUND
        c.restore()

        // le reflet des projecteurs, qui ne tourne pas avec le ballon
        val reflet = Paint(Paint.ANTI_ALIAS_FLAG)
        reflet.shader = RadialGradient(x0 - r * .36f, by - r * .40f, r * .62f,
            intArrayOf(0x88FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawCircle(x0 - r * .28f, by - r * .32f, r * .58f, reflet)

        // le bord, a peine marque
        p.style = Paint.Style.STROKE; p.color = 0x44000000; p.strokeWidth = r * .07f
        c.drawCircle(x0, by, r * .97f, p)
        p.style = Paint.Style.FILL
    }

    // ================= la jauge d'energie =================

    /** Son « drawEnergyHUD » : trois jauges a gauche, trois a droite. */
    private fun jaugeEnergie(c: Canvas) {
        val marge = 12f; val ecart = 7f
        val groupe = min(partie.W * .43f, 330f)
        val bw = (groupe - ecart * 2f) / 3f
        val y = 10f; val bh = 9f

        fun une(j: Partie.Joueur, i: Int, gauche: Boolean) {
            val x0 = if (gauche) marge + i * (bw + ecart)
                     else partie.W - marge - groupe + i * (bw + ecart)
            val e = j.energy.coerceIn(0f, 100f)

            texte.textSize = 11f
            texte.style = Paint.Style.STROKE; texte.strokeWidth = 3f
            texte.color = 0xB8000000.toInt()
            c.drawText(j.nom, x0 + bw / 2f, y + 11f, texte)
            texte.style = Paint.Style.FILL; texte.color = Color.WHITE
            c.drawText(j.nom, x0 + bw / 2f, y + 11f, texte)

            val by = y + 15f
            p.color = 0x94000000.toInt()
            c.drawRect(x0 - 1f, by - 1f, x0 + bw + 1f, by + bh + 1f, p)
            p.color = if (e >= 100f) 0xFFFFF36A.toInt() else 0xFF57D7FF.toInt()
            c.drawRect(x0, by, x0 + bw * (e / 100f), by + bh, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = if (e >= 100f) 2f else 1f
            p.color = if (e >= 100f) 0xFFFF3B22.toInt() else 0xD9FFFFFF.toInt()
            c.drawRect(x0, by, x0 + bw, by + bh, p)
            if (e >= 100f) {
                p.alpha = (255 * (.55f + .35f * sin(partie.T * 10f))).toInt().coerceIn(0, 255)
                p.color = 0xFFFFFBD0.toInt()
                c.drawRect(x0 - 1f, by - 1f, x0 + bw + 1f, by + bh + 1f, p)
                p.alpha = 255
            }
            p.style = Paint.Style.FILL
        }

        for (i in 0 until 3) une(partie.P[i], i, true)
        for (i in 0 until 3) une(partie.P[i + 3], i, false)
    }

    /** Son « stats » : combien sur le terrain, combien en prison. */
    private fun tableauDeScore(c: Canvas) {
        val a = partie.P.count { it.team == 0 && !it.prison }
        val b = partie.P.count { it.team == 1 && !it.prison }
        val enPrisonA = partie.P.count { it.team == 0 && it.prison }
        val enPrisonB = partie.P.count { it.team == 1 && it.prison }
        val libelle = "🔵 $a terrain • $enPrisonA prison     🔴 $b terrain • $enPrisonB prison"
        texte.textSize = 12f
        val l = texte.measureText(libelle)
        val cx = partie.W / 2f
        p.color = 0xDD06101D.toInt()
        c.drawRoundRect(RectF(cx - l / 2f - 14f, 7f, cx + l / 2f + 14f, 7f + 26f), 8f, 8f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = 0x55FFFFFF
        c.drawRoundRect(RectF(cx - l / 2f - 14f, 7f, cx + l / 2f + 14f, 7f + 26f), 8f, 8f, p)
        p.style = Paint.Style.FILL
        texte.style = Paint.Style.FILL; texte.color = Color.WHITE
        c.drawText(libelle, cx, 7f + 18f, texte)
    }

    // ================= le decompte =================

    /** Son « drawCountdown » : jaune, gros contour rouge, ombre noire. */
    private fun decompte(c: Canvas) {
        if (partie.countdown <= 0f) return
        val elapsed = 4.15f - partie.countdown
        val txt = if (elapsed < 1f) "3" else if (elapsed < 2f) "2" else if (elapsed < 3f) "1" else "GO!"
        val pulse = 1f + .08f * sin(partie.T * 14f)
        c.save()
        c.translate(partie.W / 2f, partie.H * .46f)
        c.scale(pulse, pulse)
        texte.textSize = min(partie.W, partie.H) * .17f
        texte.style = Paint.Style.STROKE
        texte.strokeJoin = Paint.Join.ROUND
        texte.strokeWidth = 11f; texte.color = 0x8C000000.toInt()
        c.drawText(txt, 3f, 5f + texte.textSize * .35f, texte)
        texte.strokeWidth = 8f; texte.color = 0xFFD71920.toInt()
        c.drawText(txt, 0f, texte.textSize * .35f, texte)
        texte.style = Paint.Style.FILL; texte.color = 0xFFFFE52B.toInt()
        c.drawText(txt, 0f, texte.textSize * .35f, texte)
        c.restore()
    }

    // ================= l'impact et l'eclair =================

    /** Le cercle et les huit traits au moment ou un joueur est touche. */
    private fun impact(c: Canvas) {
        if (partie.IMPACT.t <= 0f) return
        val k = partie.IMPACT.t / .24f
        val rr = 13f + (1f - k) * 24f
        val ix = partie.IMPACT.x; val iy = partie.IMPACT.y - 18f
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f
        p.alpha = (255 * min(1f, k * 1.8f)).toInt().coerceIn(0, 255)
        p.color = 0xFFFFF7A8.toInt()
        c.drawCircle(ix, iy, rr, p)
        p.color = Color.WHITE
        for (i in 0 until 8) {
            val a = (i * PI / 4).toFloat()
            c.drawLine(ix + cos(a) * (rr + 4f), iy + sin(a) * (rr + 4f),
                       ix + cos(a) * (rr + 14f), iy + sin(a) * (rr + 14f), p)
        }
        p.alpha = 255; p.style = Paint.Style.FILL
    }

    /**
     * L'aura de charge, dans l'esprit manga.
     *
     * Trois couches se superposent : une lueur qui respire autour du corps,
     * des eclairs brises qui se resserrent au fil des trois secondes, et des
     * etincelles qui montent. Quand le tir est pret, tout vire au blanc dore
     * et un cercle d'energie bat autour de lui.
     */
    private fun eclairDeCharge(c: Canvas, j: Partie.Joueur) {
        var ch = 0f
        if (j.h && partie.charging && partie.B.held === j && j.energy >= 100f)
            ch = min(1f, partie.shotCharge / 3f)
        else if (!j.h && partie.B.held === j && j.energy >= 100f)
            ch = min(1f, j.cpuCharge / 3f)
        if (ch <= .02f) return

        val T = partie.T
        val cy = j.y - 47f
        val pret = ch >= .96f
        val battement = if (ch >= 1f) (.78f + sin(T * 24f) * .22f) else 1f
        val teinte = if (pret) 0xFFFFFBD0.toInt() else 0xFF9EE8FF.toInt()
        val halo = if (pret) 0xFFFFF7A8.toInt() else 0xFF7FDCFF.toInt()

        c.save()

        // ---- 1. la lueur qui enveloppe le corps ----
        val lueur = Paint(Paint.ANTI_ALIAS_FLAG)
        val rayonLueur = (46f + ch * 26f) * (1f + sin(T * 9f) * .04f)
        lueur.shader = RadialGradient(j.x, cy, rayonLueur,
            intArrayOf(Color.argb((70 * ch * battement).toInt().coerceIn(0, 255),
                                  Color.red(halo), Color.green(halo), Color.blue(halo)),
                       Color.TRANSPARENT),
            floatArrayOf(.35f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(j.x, cy, rayonLueur, lueur)

        // ---- 2. les eclairs brises ----
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.setShadowLayer(5f + ch * 14f, 0f, 0f, halo)

        val rayon = 74f - ch * 35f
        val opacite = .22f + ch * .68f
        val combien = 2 + (ch * 7f).toInt()
        for (k in 0 until combien) {
            val a = T * (1.6f + ch * 2.2f) + k * (2 * PI / combien).toFloat()
            val r1 = rayon + sin(T * 13f + k * 2.1f) * 4f
            val r2 = kotlin.math.max(23f, r1 - (11f + ch * 17f))
            val sx = j.x + cos(a) * r1
            val sy = cy + sin(a) * r1 * .72f
            val ondule = .20f * sin(T * 7f + k)
            val ex = j.x + cos(a + ondule) * r2
            val ey = cy + sin(a + ondule) * r2 * .72f

            // un eclair n'est pas un trait : il casse en quatre segments,
            // chacun decale d'un cote puis de l'autre
            val arc = Path()
            arc.moveTo(sx, sy)
            val morceaux = 4
            for (m in 1 until morceaux) {
                val q = m / morceaux.toFloat()
                val mx = sx + (ex - sx) * q
                val my = sy + (ey - sy) * q
                // la normale au trait, pour casser de biais
                val nx = -(ey - sy); val ny = (ex - sx)
                val nl = hypot(nx, ny).let { if (it == 0f) 1f else it }
                val ecart = sin(T * (17f + k * 3f) + m * 2.7f) * (3.5f + ch * 4f) *
                            (if (m % 2 == 0) -1f else 1f)
                arc.lineTo(mx + nx / nl * ecart, my + ny / nl * ecart)
            }
            arc.lineTo(ex, ey)

            p.color = teinte
            p.alpha = (255 * opacite * battement).toInt().coerceIn(0, 255)
            p.strokeWidth = 1.4f + ch * 2.2f
            c.drawPath(arc, p)

            // le coeur blanc de l'eclair, plus fin
            if (ch > .45f) {
                p.color = Color.WHITE
                p.alpha = (255 * opacite * .55f * battement).toInt().coerceIn(0, 255)
                p.strokeWidth = .6f + ch * .8f
                c.drawPath(arc, p)
            }
        }
        p.clearShadowLayer()

        // ---- 3. les etincelles qui montent ----
        p.style = Paint.Style.FILL
        val etincelles = (ch * 9f).toInt()
        for (k in 0 until etincelles) {
            val phase = (T * (1.1f + k * .13f) + k * .37f) % 1f
            val ex = j.x + sin(k * 2.3f + T * .8f) * (18f + ch * 22f)
            val ey = cy + 34f - phase * (58f + ch * 26f)
            val taille = (1.5f + ch * 1.8f) * (1f - phase)
            p.color = if (pret) 0xFFFFF3B0.toInt() else 0xFFBFF0FF.toInt()
            p.alpha = (235 * (1f - phase) * battement).toInt().coerceIn(0, 255)
            c.drawCircle(ex, ey, kotlin.math.max(.6f, taille), p)
        }

        // ---- 4. le cercle d'energie, quand c'est pret ----
        if (ch >= .995f) {
            p.style = Paint.Style.STROKE
            val rr = 31f + sin(T * 20f) * 3f
            p.color = 0xFFFFFBD0.toInt()
            p.strokeWidth = 2.4f
            p.alpha = (255 * (.65f + .25f * sin(T * 22f))).toInt().coerceIn(0, 255)
            c.drawOval(RectF(j.x - rr, cy - rr * 1.18f, j.x + rr, cy + rr * 1.18f), p)
            // un second cercle, plus large et plus pale, qui s'ouvre
            val r2 = rr * (1.25f + .12f * sin(T * 14f))
            p.strokeWidth = 1.2f
            p.alpha = (110 * (.6f + .4f * sin(T * 14f))).toInt().coerceIn(0, 255)
            c.drawOval(RectF(j.x - r2, cy - r2 * 1.18f, j.x + r2, cy + r2 * 1.18f), p)
        }

        p.alpha = 255
        p.style = Paint.Style.FILL
        c.restore()
    }

    /** Son petit mot, en haut de l'ecran. */
    private fun petitMot(c: Canvas) {
        if (partie.note.isEmpty()) return
        texte.textSize = 15f
        texte.style = Paint.Style.STROKE; texte.strokeWidth = 4f; texte.color = 0xCC000000.toInt()
        c.drawText(partie.note, partie.W / 2f, 58f, texte)
        texte.style = Paint.Style.FILL; texte.color = 0xFFFFE171.toInt()
        c.drawText(partie.note, partie.W / 2f, 58f, texte)
    }

    fun rejouer() { finAnnoncee = false; partie.reset(); invalidate() }
}
