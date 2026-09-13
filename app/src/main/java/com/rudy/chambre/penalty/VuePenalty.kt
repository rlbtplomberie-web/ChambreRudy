package com.rudy.chambre.penalty

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Le tir au but de Rudy, traduit de sa page.
 *
 * Sa course, le plongeon du gardien et le vol du ballon, avec ses mesures en
 * pourcentages de l'ecran.
 */
class VuePenalty(ctx: Context) : View(ctx) {

    var surSon: ((String) -> Unit)? = null
    var surFinDeFrappe: ((Boolean) -> Unit)? = null      // vrai si le gardien arrete

    private val images = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    private val pinceau = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Ses quatre cibles de balle, en pourcentages. */
    private val cibles = mapOf(
        "ul" to (39.5f to 40.5f), "ur" to (60.5f to 40.5f),
        "ll" to (40.5f to 50.0f), "lr" to (59.5f to 50.0f)
    )

    /** Ses six plongeons : ecart lateral, hauteur du saut, retombee. */
    private class Plongeon(val x: Float, val peak: Float, val endY: Float, val images: Int)
    private val plongeons = mapOf(
        "ul" to Plongeon(-14.0f, -11.5f, 4.2f, 12),
        "ur" to Plongeon(14.0f, -11.5f, 4.2f, 14),
        "l"  to Plongeon(-14.8f, -2.2f, 5.2f, 13),
        "r"  to Plongeon(14.8f, -2.2f, 5.2f, 12),
        "ll" to Plongeon(-14.2f, 1.0f, 7.0f, 15),
        "lr" to Plongeon(14.2f, 1.0f, 7.0f, 12)
    )

    /** Son ordre d'images de course et sa suite d'arret. */
    private val ordreCourse = intArrayOf(0, 5, 9, 13, 17, 19, 20, 21, 22, 23)
    private val dureesArret = floatArrayOf(.068f, .068f, .074f, .080f, .090f, .190f)

    // ---- l'etat de la sequence ----
    private var phase = "attente"     // attente, course, arret, vol, impact, roule
    private var t = 0f
    private var tTotal = 0f            // le temps qui passe, pour la respiration
    private var etape = 0
    private var tir = "ul"
    private var plongeonEnCours = "ul"
    private var arrete = false
    private var dernier = 0L

    private var rudyX = 20f; private var rudyY = 72f; private var rudyEchelle = 1f
    private var rudyImage = 0; private var rudyArret = -1
    private var gardienX = 50f; private var gardienY = 46.8f; private var gardienImage = 0
    private var balleX = 47f; private var balleY = 75.5f
    private var balleTaille = 3.8f; private var balleTour = 0f
    private var balleVisible = true

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        Thread {
            charger("terrain.webp")
            for (i in 0 until 24) charger(String.format("rudy_%02d.webp", i))
            for (i in 0 until 6) charger("stop_$i.webp")
            for ((d, pl) in plongeons) for (i in 0 until pl.images)
                charger(String.format("gardien_%s_%02d.webp", d, i))
            post { invalidate() }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun charger(nom: String): Bitmap? {
        images[nom]?.let { return it }
        return try {
            context.assets.open("penalty/$nom").use {
                BitmapFactory.decodeStream(it)?.also { b -> images[nom] = b }
            }
        } catch (_: Throwable) { null }
    }

    private fun px(v: Float) = width * v / 100f
    private fun py(v: Float) = height * v / 100f

    /** Lance la frappe : [direction] est ul, ur, ll ou lr. */
    fun frapper(direction: String, gardienArrete: Boolean, plongeonGardien: String) {
        tir = direction; arrete = gardienArrete; plongeonEnCours = plongeonGardien
        phase = "course"; t = 0f; etape = 0; tPlongeon = -.430f
        balleEnVol = false; pasJoue = false
        rudyX = 20f; rudyY = 72f; rudyEchelle = 1f; rudyArret = -1
        gardienX = 50f; gardienY = 46.8f; gardienImage = 0
        balleX = 47f; balleY = 75.5f; balleTaille = 3.8f; balleTour = 0f; balleVisible = true
    }

    fun auRepos() = phase == "attente"

    fun remettre() {
        phase = "attente"; tPlongeon = -.430f
        rudyX = 20f; rudyY = 72f; rudyEchelle = 1f
        rudyImage = 0; rudyArret = -1
        gardienX = 50f; gardienY = 46.8f; gardienImage = 0
        balleX = 47f; balleY = 75.5f; balleTaille = 3.8f; balleVisible = true
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val maintenant = System.nanoTime()
        val dt = if (dernier == 0L) 0f else ((maintenant - dernier) / 1_000_000_000.0).toFloat()
        dernier = maintenant
        avancer(min(dt, .05f))

        c.drawColor(0xFF0B2A12.toInt())
        charger("terrain.webp")?.let {
            val cadre = RectF(0f, 0f, width.toFloat(), height.toFloat())
            c.drawBitmap(it, null, cadre, pinceau)
            dessinerVent(c, it, cadre)      // les arbres et l'herbe bougent
        }
        dessinerGardien(c)
        if (balleVisible) dessinerBalle(c)
        dessinerRudy(c)
        invalidate()
    }

    // ================= le deroulement =================

    /**
     * Le deroulement, dans son ordre : Rudy court, le gardien part au bout de
     * 430 millisecondes, le ballon part a la huitieme image de course.
     */
    private fun avancer(dt: Float) {
        if (phase == "attente") return
        // le temps du gardien continue de courir meme apres la frappe :
        // sinon il restait fige en plein saut au lieu de retomber
        tTotal += dt
        tPlongeon += dt
        if (phase == "fini") return
        t += dt

        when (phase) {
            "course" -> course(dt)
            "arret" -> arretDeCourse()
        }
        if (balleEnVol) vol(dt)
        if (phase == "impact") impact()
        if (phase == "roule") roule()
    }

    /** Sa course : dix images, de 20 % a 45 %, avec le rebond et le retrecissement. */
    private fun course(dt: Float) {
        if (etape >= ordreCourse.size) {
            phase = "arret"; etape = 0; t = 0f; return
        }
        rudyImage = ordreCourse[etape]
        val duree = .080f * .63f
        val a = 20f + 25f * (etape - 1).coerceAtLeast(0) / (ordreCourse.size - 1f)
        val b = 20f + 25f * etape / (ordreCourse.size - 1f)
        val q = min(1f, t / duree)
        val avancement = (etape + q) / ordreCourse.size
        rudyX = a + (b - a) * q
        rudyY = 72f - 3.2f * sin(PI.toFloat() * avancement)
        rudyEchelle = 1f - .16f * avancement
        if (!pasJoue && (etape == 1 || etape == 3 || etape == 5 || etape == 7)) {
            surSon?.invoke("pas"); pasJoue = true
        }
        if (q >= 1f) {
            if (etape == 8) { surSon?.invoke("frappe"); partirEnVol() }
            etape++; t = 0f; pasJoue = false
        }
    }

    private var pasJoue = false

    /** Ses six images d'arret, avec leurs durees propres. */
    private fun arretDeCourse() {
        if (etape >= dureesArret.size) return
        rudyArret = etape
        rudyEchelle = .84f
        rudyY = 72f
        if (t >= dureesArret[etape]) {
            rudyX += if (etape < 3) .23f else .11f
            etape++; t = 0f
        }
    }

    private var tVol = 0f
    private var balleEnVol = false
    private var departX = 47f; private var departY = 75.5f
    private var finX = 0f; private var finY = 0f
    private var dureeVol = .470f
    private var rouleX = 0f; private var rouleY = 0f

    private fun partirEnVol() {
        val cible = cibles[tir] ?: (50f to 45f)
        departX = 47f; departY = 75.5f
        finX = if (arrete) departX + (cible.first - departX) * .76f else cible.first
        finY = if (arrete) departY + (cible.second - departY) * .76f else cible.second
        dureeVol = if (arrete) .365f else .470f
        tVol = 0f; balleEnVol = true
    }

    /** Son vol : la balle accelere, monte un peu, et retrecit. */
    private fun vol(dt: Float) {
        tVol += dt
        val q = min(1f, tVol / dureeVol)
        val douceur = 1f - (1f - q).pow(2.25f)
        balleX = departX + (finX - departX) * douceur
        balleY = departY + (finY - departY) * douceur -
                 (if (arrete) 3.5f else 5.2f) * sin(PI.toFloat() * q)
        balleTaille = 3.8f - (if (arrete) 1.15f else 1.8f) * q
        balleTour = q * (if (arrete) 520f else 760f)
        if (q >= 1f) {
            balleEnVol = false
            if (arrete) { surSon?.invoke("gant"); phase = "impact"; t = 0f }
            else { surSon?.invoke("filet"); phase = "fini"; surFinDeFrappe?.invoke(false) }
        }
    }

    /** L'arret : la balle s'ecrase sur les gants puis retombe. */
    private fun impact() {
        val r = min(1f, t / .170f)
        val rebond = sin(PI.toFloat() * r)
        val cote = if (tir == "ul" || tir == "ll") -1f else 1f
        balleX = finX - cote * .45f * rebond
        balleY = finY + 2.3f * r - 1.0f * rebond
        balleTour = 520f + cote * r * 150f
        if (r >= 1f) { phase = "roule"; t = 0f; rouleX = balleX; rouleY = balleY }
    }

    /** Le roule sur l'herbe : vif au debut, puis il s'arrete tout seul. */
    private fun roule() {
        val q = min(1f, t / .720f)
        val e = 1f - (1f - q).pow(3f)
        val cote = if (tir == "ul" || tir == "ll") -1f else 1f
        balleX = rouleX + cote * 4.2f * e
        balleY = rouleY + 2.8f * e - .45f * sin(PI.toFloat() * q)
        balleTour = 670f + cote * 720f * e
        if (q >= 1f) { phase = "fini"; surFinDeFrappe?.invoke(true) }
    }

    // ================= le dessin =================

    private fun dessinerRudy(c: Canvas) {
        val nom = if (rudyArret >= 0) "stop_${min(rudyArret, 5)}.webp"
                  else String.format("rudy_%02d.webp", rudyImage)
        val im = charger(nom) ?: return
        // sa respiration, « rudyBreathing » : 2,8 secondes, a peine visible
        var souffleY = 0f; var souffleX = 1f; var souffleH = 1f
        if (phase == "attente") {
            val q = (tTotal % 2.8f) / 2.8f
            val d = (1f - kotlin.math.cos(q * 2f * Math.PI.toFloat())) / 2f
            souffleY = -0.0018f * d
            souffleX = 1f + .003f * d
            souffleH = 1f + .009f * d
        }
        val h = height * .52f * rudyEchelle * souffleH   // le tireur, au premier plan
        val l = im.width * (h / im.height)
        val cx = px(rudyX); val cy = py(rudyY) + height * souffleY
        c.drawBitmap(im, null, RectF(cx - l / 2f, cy - h / 2f, cx + l / 2f, cy + h / 2f), pinceau)
    }

    /** Le plongeon : sa courbe de saut et sa retombee, selon la direction. */
    private fun dessinerGardien(c: Canvas) {
        val pl = plongeons[plongeonEnCours] ?: return
        if (tPlongeon <= 0f) {
            gardienX = 50f; gardienY = 46.8f; gardienImage = 0
        } else {
            val duree = maxOf(.820f, pl.images * .066f)
            val brut = min(1f, tPlongeon / duree)
            val e = if (brut < .5f) 4f * brut * brut * brut
                    else 1f - (-2f * brut + 2f).pow(3f) / 2f
            gardienImage = min(pl.images - 1, (brut * pl.images).toInt())
            // une fois son plongeon fini et son temps au sol ecoule,
            // il se remet debout au milieu du but
            if (brut >= 1f && tPlongeon > duree + attenteAuSol()) {
                gardienX = 50f; gardienY = 46.8f; gardienImage = 0
                val im0 = charger(String.format("gardien_%s_00.webp", plongeonEnCours))
                if (im0 != null) {
                    val h0 = height * .22f
                    val l0 = im0.width * (h0 / im0.height)
                    c.drawBitmap(im0, null, RectF(px(50f) - l0 / 2f, py(46.8f) - h0 / 2f,
                        px(50f) + l0 / 2f, py(46.8f) + h0 / 2f), pinceau)
                }
                return
            }
            gardienX = 50f + pl.x * e
            gardienY = if (plongeonEnCours == "ul" || plongeonEnCours == "ur")
                46.8f + pl.peak * sin(PI.toFloat() * min(1f, e)) + pl.endY * e.pow(3f)
            else
                46.8f + pl.peak * sin(PI.toFloat() * e) + pl.endY * e.pow(2f)
        }
        val im = charger(String.format("gardien_%s_%02d.webp", plongeonEnCours, gardienImage))
            ?: return
        val h = height * .22f                    // le gardien, au fond, plus petit
        val l = im.width * (h / im.height)
        val cx = px(gardienX); val cy = py(gardienY)
        c.drawBitmap(im, null, RectF(cx - l / 2f, cy - h / 2f, cx + l / 2f, cy + h / 2f), pinceau)
    }

    /** Il part 430 millisecondes apres le coup d'envoi de la course. */
    private var tPlongeon = -.430f

    /**
     * Le temps qu'il reste au sol avant de se relever : 850 millisecondes
     * apres un plongeon en hauteur, 520 apres un plongeon bas — ses valeurs.
     */
    private fun attenteAuSol() =
        if (plongeonEnCours == "ul" || plongeonEnCours == "ur") .850f else .520f

    /**
     * Un vrai ballon de football : le blanc n'est pas uniforme, les pentagones
     * noirs sont cousus, la lumiere des projecteurs frappe en haut a gauche et
     * une ombre s'etale au sol sous lui.
     */
    /**
     * Le terrain qui vit : les arbres du fond se balancent sous le vent, et
     * l'herbe frissonne par plaques, a des rythmes differents.
     */
    private fun dessinerVent(c: Canvas, fond: Bitmap, cadre: RectF) {
        // les arbres : la bande du fond, entre 30 et 48 % de la hauteur
        val balance = sin(tTotal * .9f) * 2.4f + sin(tTotal * 1.7f) * 1.1f
        c.save()
        c.clipRect(cadre.left, cadre.top + cadre.height() * .30f,
                   cadre.right, cadre.top + cadre.height() * .48f)
        c.translate(balance, sin(tTotal * 1.3f) * .7f)
        c.drawBitmap(fond, null, cadre, pinceau)
        c.restore()

        // l'herbe : quatre plaques qui frissonnent chacune a son rythme
        val plaques = arrayOf(
            floatArrayOf(.08f, .62f, .26f, .14f, 1.6f),
            floatArrayOf(.38f, .70f, .30f, .16f, 2.3f),
            floatArrayOf(.66f, .64f, .28f, .13f, 1.9f),
            floatArrayOf(.20f, .84f, .55f, .14f, 1.2f)
        )
        for (q in plaques) {
            val x0 = cadre.left + cadre.width() * q[0]
            val y0 = cadre.top + cadre.height() * q[1]
            val l = cadre.width() * q[2]
            val h = cadre.height() * q[3]
            c.save()
            c.clipRect(x0, y0, x0 + l, y0 + h)
            c.translate(sin(tTotal * q[4]) * 1.6f, 0f)
            pinceau.alpha = 170
            c.drawBitmap(fond, null, cadre, pinceau)
            pinceau.alpha = 255
            c.restore()
        }
    }

    private fun dessinerBalle(c: Canvas) {
        val r = px(balleTaille) / 2f
        val cx = px(balleX); val cy = py(balleY)

        // l'ombre au sol : elle s'aplatit et palit quand le ballon monte
        val hauteur = ((py(75.5f) - cy) / height).coerceIn(0f, .35f)
        p.style = Paint.Style.FILL
        p.color = Color.argb((70 * (1f - hauteur * 2f)).toInt().coerceIn(0, 70), 0, 0, 0)
        c.drawOval(RectF(cx - r * 1.05f, py(76.2f) - r * .28f,
                         cx + r * 1.05f, py(76.2f) + r * .28f), p)

        c.save()
        c.rotate(balleTour, cx, cy)

        // le cuir : un blanc qui s'assombrit vers le bas droit
        val cuir = Paint(Paint.ANTI_ALIAS_FLAG)
        cuir.shader = RadialGradient(cx - r * .35f, cy - r * .38f, r * 1.55f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFEDEDEA.toInt(), 0xFFB9B7B2.toInt()),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, cuir)

        // les pentagones noirs : un au centre, cinq autour
        p.color = 0xFF16161A.toInt()
        pentagone(c, cx, cy, r * .34f, 0f)
        for (i in 0 until 5) {
            val a = i * (2 * PI / 5).toFloat() - (PI / 2).toFloat()
            pentagone(c, cx + kotlin.math.cos(a) * r * .72f,
                         cy + sin(a) * r * .72f, r * .26f, a)
        }

        // les coutures qui relient les pieces
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * .05f
        p.color = 0x55000000
        for (i in 0 until 5) {
            val a = i * (2 * PI / 5).toFloat() - (PI / 2).toFloat()
            c.drawLine(cx + kotlin.math.cos(a) * r * .40f, cy + sin(a) * r * .40f,
                       cx + kotlin.math.cos(a) * r * .95f, cy + sin(a) * r * .95f, p)
        }
        p.style = Paint.Style.FILL
        c.restore()

        // le reflet des projecteurs, qui ne tourne pas avec le ballon
        val reflet = Paint(Paint.ANTI_ALIAS_FLAG)
        reflet.shader = RadialGradient(cx - r * .38f, cy - r * .42f, r * .60f,
            intArrayOf(0x99FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx - r * .30f, cy - r * .34f, r * .55f, reflet)

        // le bord, a peine marque
        p.style = Paint.Style.STROKE; p.strokeWidth = r * .06f
        p.color = 0x33000000
        c.drawCircle(cx, cy, r * .97f, p)
        p.style = Paint.Style.FILL
    }

    /** Un pentagone plein, pour les pieces du ballon. */
    private fun pentagone(c: Canvas, cx: Float, cy: Float, r: Float, tour: Float) {
        val f = Path()
        for (i in 0 until 5) {
            val a = tour + i * (2 * PI / 5).toFloat() - (PI / 2).toFloat()
            val x = cx + kotlin.math.cos(a) * r
            val y = cy + sin(a) * r
            if (i == 0) f.moveTo(x, y) else f.lineTo(x, y)
        }
        f.close()
        c.drawPath(f, p)
    }
}
