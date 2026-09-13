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

    /** Les cases des planches : une ligne par action, comme chez lui. */
    private val lignes = mapOf(
        "walk" to 0, "run" to 1, "throw" to 2, "dodge" to 3, "catch" to 4,
        "fall" to 5, "rise" to 6, "hit" to 7, "pass" to 8
    )

    /** Chaque personnage a sa planche et la taille de ses cases. */
    private class Planche(val fichier: String, val sw: Int, val sh: Int)

    private val planches = mapOf(
        1 to Planche("blonde_atlas.webp", 320, 370),   // Sophie
        2 to Planche("newguy_atlas.webp", 360, 390),   // Mathis
        3 to Planche("girl_atlas.webp", 360, 390),     // Shanna
        4 to Planche("bald_atlas.webp", 320, 370),     // Theo
        5 to Planche("fat_atlas.webp", 360, 390)       // Carlos
    )

    /** La position de la balle dans les mains, pose par pose (« CATCH_BALL_POS »). */
    private val posBalle = arrayOf(
        58f to 50f, 58f to 50f, 75.48828f to 40.613144f, 73.046875f to 39.11691f,
        69.01855f to 40.994003f, 63.52539f to 41.266045f,
        56.61621f to 45.047436f, 55.517578f to 43.98647f
    )

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
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
        partie.W = l.toFloat(); partie.H = h.toFloat()
        partie.reset()
    }

    // ================= la boucle =================

    override fun onDraw(c: Canvas) {
        val maintenant = System.nanoTime()
        val dt = if (dernier == 0L) 0f else ((maintenant - dernier) / 1_000_000_000.0).toFloat()
        dernier = maintenant
        partie.update(min(dt, 0.05f))

        dessinerTerrain(c)
        // les plus loin d'abord, pour que les plus proches passent devant
        for (j in partie.P.sortedBy { it.y }) { personnage(c, j); eclairDeCharge(c, j) }
        jaugeEnergie(c)
        decompte(c)
        impact(c)
        balle(c)
        petitMot(c)

        if (partie.over && !finAnnoncee) { finAnnoncee = true; surFin?.invoke(partie.msg) }
        invalidate()
    }

    /** Son « drawBG » : l'image couvre l'ecran sans se deformer. */
    private fun dessinerTerrain(c: Canvas) {
        val bg = terrain
        if (bg == null) { c.drawColor(0xFF0B2A12.toInt()); return }
        val s = max(width.toFloat() / bg.width, height.toFloat() / bg.height)
        val dw = bg.width * s; val dh = bg.height * s
        c.drawBitmap(bg, null,
            RectF((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f),
            pinceau)
        p.color = 0x22001020
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
    }

    /** La hauteur commune a tous les personnages, comme chez lui. */
    private fun hauteurCible() = min(118f, max(92f, height * .145f))

    /** L'action en cours et la pose correspondante (son « person »). */
    private fun actionEtPose(j: Partie.Joueur): Pair<String, Int> {
        fun pose(avancement: Float) = min(7, floor(avancement * 8f).toInt().coerceAtLeast(0))
        return when {
            j.fallA > 0f -> "fall" to pose(1f - j.fallA / .82f)
            j.riseA > 0f -> "rise" to pose(1f - j.riseA / .82f)
            j.hitA > 0f -> "hit" to pose(1f - j.hitA / .35f)
            j.pickupA > 0f -> "catch" to pose(1f - j.pickupA / .48f)
            j.passA > 0f -> "pass" to pose(1f - j.passA / .42f)
            j.throwA > 0f -> "throw" to pose(1f - j.throwA / .38f)
            j.catchA > 0f && partie.B.held === j -> "catch" to pose(1f - j.catchA / .50f)
            j.dodge > 0f -> "dodge" to pose(1f - j.dodge / .38f)
            partie.B.held === j -> "catch" to 7
            hypot(j.vx, j.vy) > 150f -> "run" to (floor(partie.T * 12f).toInt().mod(8))
            hypot(j.vx, j.vy) > 12f -> "walk" to (floor(partie.T * 8f).toInt().mod(8))
            else -> "walk" to 0
        }
    }

    // ce que le dessin retient pour placer la balle dans les mains
    private var heroDW = 0f; private var heroDH = 0f; private var heroDY = 0f
    private var heroPose = -1

    private fun personnage(c: Canvas, j: Partie.Joueur) {
        val (action, pose) = actionEtPose(j)
        val cible = hauteurCible()
        // le saut du ramassage, comme dans son code
        val saut = if (j.pickupA > 0f) sin((1f - j.pickupA / .48f) * PI).toFloat() * 12f else 0f

        if (j.h) {
            val im = charger("hero_${action}_$pose.webp") ?: return
            val reference = (charger("hero_walk_0.webp")?.height ?: im.height).toFloat()
            val echelle = cible / reference
            val dh = im.height * echelle; val dw = im.width * echelle
            val dy = j.y - dh
            heroDW = dw; heroDH = dh; heroDY = dy
            heroPose = if (action == "catch") pose else -1
            dessinerImage(c, im, j, dw, dh, dy + saut)
            texte.color = Color.WHITE; texte.textSize = 10f * resources.displayMetrics.density * .8f
            c.drawText("TOI" + (if (j.prison) " • PRISON" else ""), j.x, dy - 4f, texte)
            return
        }

        val pl = planches[j.id + (if (j.team == 0) 0 else 2)] ?: planches[1]!!
        val planche = charger(pl.fichier) ?: return
        // les planches ont ete allegees de moitie : les cases suivent
        val ech = planche.width / (8f * pl.sw)
        val sw = (pl.sw * ech); val sh = (pl.sh * ech)
        val ligne = lignes[action] ?: 0
        val src = Rect((pose * sw).toInt(), (ligne * sh).toInt(),
                       ((pose + 1) * sw).toInt(), ((ligne + 1) * sh).toInt())
        val echelle = cible / pl.sh
        val dh = pl.sh * echelle; val dw = pl.sw * echelle
        val dy = j.y - dh
        dessinerCase(c, planche, src, j, dw, dh, dy + saut)
        texte.color = if (j.prison) 0xFFFFB3A0.toInt() else 0xFFF2E6C8.toInt()
        texte.textSize = 10f * resources.displayMetrics.density * .8f
        c.drawText(j.nom + (if (j.prison) " • PRISON" else ""), j.x, dy - 4f, texte)
    }

    private fun dessinerImage(c: Canvas, im: Bitmap, j: Partie.Joueur,
                              dw: Float, dh: Float, dy: Float) {
        c.save()
        if (cos(j.face) < 0f) c.scale(-1f, 1f, j.x, 0f)
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
        if (porteur != null && porteur.h && heroDH > 0f) {
            if (porteur.catchA > 0f && heroPose >= 0) {
                if (heroPose < 2) bx = null           // les deux premieres poses sont sans balle
                else {
                    val q = posBalle[heroPose]
                    bx = porteur.x + (q.first - 50f) / 25f * heroDW
                    by = (heroDY + heroDH * .5f) + (q.second - 50f) / 25f * heroDH
                }
            } else {
                val q = posBalle[7]
                bx = porteur.x + (q.first - 50f) / 25f * heroDW
                by = (heroDY + heroDH * .5f) + (q.second - 50f) / 25f * heroDH
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
            val pulse = 1f + sin(partie.T * 20f) * .18f
            c.save(); c.translate(x0, by)
            for (k in 0 until 9) {
                val a = k * (PI * 2 / 9).toFloat() + partie.T * 3.2f
                val rr = (r + 5f) * pulse
                c.save(); c.rotate(Math.toDegrees(a.toDouble()).toFloat())
                p.color = if (k % 2 == 1) 0xFFFF9A19.toInt() else 0xFFFF4D00.toInt()
                val flamme = Path()
                flamme.moveTo(rr - 3f, -3.4f)
                flamme.quadTo(rr + 7f, 0f, rr - 3f, 3.4f)
                flamme.close()
                c.drawPath(flamme, p)
                c.restore()
            }
            c.restore()
        }

        p.style = Paint.Style.FILL; p.color = Color.WHITE
        c.drawCircle(x0, by, r, p)
        p.style = Paint.Style.STROKE; p.color = 0xFF111111.toInt(); p.strokeWidth = 1f
        c.drawCircle(x0, by, r, p)

        // ses trois rayures
        p.strokeWidth = .72f
        val arc1 = RectF(x0 - r * .40f - r * .78f, by - r * .78f, x0 - r * .40f + r * .78f, by + r * .78f)
        c.drawArc(arc1, Math.toDegrees(-1.18).toFloat(), Math.toDegrees(2.36).toFloat(), false, p)
        val arc2 = RectF(x0 + r * .40f - r * .78f, by - r * .78f, x0 + r * .40f + r * .78f, by + r * .78f)
        c.drawArc(arc2, Math.toDegrees(PI - 1.18).toFloat(), Math.toDegrees(2.36).toFloat(), false, p)
        val courbe = Path()
        courbe.moveTo(x0 - r * .82f, by - r * .18f)
        courbe.quadTo(x0, by + r * .30f, x0 + r * .82f, by - r * .18f)
        c.drawPath(courbe, p)
        p.style = Paint.Style.FILL
    }

    // ================= la jauge d'energie =================

    /** Son « drawEnergyHUD » : trois jauges a gauche, trois a droite. */
    private fun jaugeEnergie(c: Canvas) {
        val marge = 12f; val ecart = 7f
        val groupe = min(width * .43f, 330f)
        val bw = (groupe - ecart * 2f) / 3f
        val y = 10f; val bh = 9f

        fun une(j: Partie.Joueur, i: Int, gauche: Boolean) {
            val x0 = if (gauche) marge + i * (bw + ecart)
                     else width - marge - groupe + i * (bw + ecart)
            val e = j.energy.coerceIn(0f, 100f)

            texte.textSize = 11f * resources.displayMetrics.density * .8f
            texte.style = Paint.Style.STROKE; texte.strokeWidth = 3f
            texte.color = 0xB8000000.toInt()
            c.drawText(j.nom, x0 + bw / 2f, y + texte.textSize, texte)
            texte.style = Paint.Style.FILL; texte.color = Color.WHITE
            c.drawText(j.nom, x0 + bw / 2f, y + texte.textSize, texte)

            val by = y + 15f + texte.textSize
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

    // ================= le decompte =================

    /** Son « drawCountdown » : jaune, gros contour rouge, ombre noire. */
    private fun decompte(c: Canvas) {
        if (partie.countdown <= 0f) return
        val elapsed = 4.15f - partie.countdown
        val txt = if (elapsed < 1f) "3" else if (elapsed < 2f) "2" else if (elapsed < 3f) "1" else "GO!"
        val pulse = 1f + .08f * sin(partie.T * 14f)
        c.save()
        c.translate(width / 2f, height * .46f)
        c.scale(pulse, pulse)
        texte.textSize = min(width, height) * .17f
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

    /** L'eclair qui parcourt un joueur en train de charger son tir. */
    private fun eclairDeCharge(c: Canvas, j: Partie.Joueur) {
        val charge = if (j.h) (if (partie.charging && partie.B.held === j) partie.shotCharge else 0f)
                     else j.cpuCharge
        if (charge <= 0f || j.energy < 100f) return
        val force = min(1f, charge / 3f)
        val h = hauteurCible()
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.6f + force * 1.8f
        p.color = if (charge >= 3f) 0xFFFFF36A.toInt() else 0xFF9ED8FF.toInt()
        val hasard = java.util.Random((partie.T * 60f).toInt().toLong() + j.id * 31L)
        for (brin in 0 until 3) {
            val chemin = Path()
            var y0 = j.y - h
            chemin.moveTo(j.x + (hasard.nextFloat() - .5f) * 14f, y0)
            while (y0 < j.y) {
                y0 += h / 6f
                chemin.lineTo(j.x + (hasard.nextFloat() - .5f) * 20f * force, y0)
            }
            c.drawPath(chemin, p)
        }
        p.style = Paint.Style.FILL
    }

    /** Son petit mot, en haut de l'ecran. */
    private fun petitMot(c: Canvas) {
        if (partie.note.isEmpty()) return
        texte.textSize = 16f * resources.displayMetrics.density * .8f
        texte.style = Paint.Style.STROKE; texte.strokeWidth = 4f; texte.color = 0xCC000000.toInt()
        c.drawText(partie.note, width / 2f, height * .12f, texte)
        texte.style = Paint.Style.FILL; texte.color = 0xFFFFE9A8.toInt()
        c.drawText(partie.note, width / 2f, height * .12f, texte)
    }

    fun rejouer() { finAnnoncee = false; partie.reset(); invalidate() }
}
