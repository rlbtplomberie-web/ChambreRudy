package com.rudy.chambre.livre

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * L'etagere et le livre ouvert.
 *
 * Sur l'etagere, cinq emplacements : celui du milieu de face, les voisins
 * tournes, recules et estompes. En lecture, une page en portrait, deux en
 * paysage, et la page qui se souleve quand on la tourne.
 */
class VueLivre(ctx: Context) : View(ctx) {

    class Livre(val titre: String, val id: String) {
        var couverture: Bitmap? = null
        var demandee = false
        var pages: List<String>? = null
    }

    var surPageTournee: (() -> Unit)? = null
    var surTitre: ((String) -> Unit)? = null
    var surOuverture: ((Livre) -> Unit)? = null

    private var livres = listOf<Livre>()
    private var courant = 0
    private var lecture: Livre? = null
    private var pages = listOf<String>()
    private var page = 0

    private val images = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    private val pinceau = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val texte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    /** L'animation du feuilletage : de 0 a 1, puis la page change. */
    private var tourne = 0f
    private var sensTourne = 0

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    fun poser(liste: List<Livre>) { livres = liste; courant = 0; invalidate() }
    fun enLecture() = lecture != null

    fun lire(livre: Livre, chemins: List<String>) {
        lecture = livre; pages = chemins; page = 0; tourne = 0f; invalidate()
    }

    fun fermer() { lecture = null; pages = emptyList(); invalidate() }

    /** Une image de page, chargee depuis le dossier de l'application. */
    private fun image(chemin: String): Bitmap? {
        images[chemin]?.let { return it }
        return try {
            val fichier = java.io.File(chemin)
            BitmapFactory.decodeFile(fichier.absolutePath)?.also { images[chemin] = it }
        } catch (_: Throwable) { null }
    }

    private fun couvertureDe(l: Livre): Bitmap? {
        l.couverture?.let { return it }
        if (!l.demandee) {
            l.demandee = true
            Thread {
                val u = com.rudy.chambre.Livres.couverture(context, l.id)
                if (u.isNotEmpty()) {
                    val b = try { BitmapFactory.decodeFile(u) } catch (_: Throwable) { null }
                    if (b != null) { l.couverture = b; post { invalidate() } }
                }
            }.apply { priority = Thread.MIN_PRIORITY }.start()
        }
        return null
    }

    override fun onDraw(c: Canvas) {
        if (lecture != null) { dessinerLecture(c); return }
        dessinerEtagere(c)
    }

    // ================= l'etagere =================

    private fun dessinerEtagere(c: Canvas) {
        c.drawColor(0xFF3A2615.toInt())
        // le fond de l'etagere, en bois raye
        p.color = 0xFF4A3019.toInt()
        for (x in 0 until width step 26) c.drawRect(x.toFloat(), 0f, x + 13f, height.toFloat(), p)

        if (livres.isEmpty()) {
            texte.color = 0xFFF3D6A0.toInt(); texte.textSize = height * .04f
            c.drawText("Aucun livre", width / 2f, height / 2f, texte)
            return
        }

        val largeur = min(width * .52f, height * .48f)
        val hauteur = largeur / .70f

        // les voisins d'abord, du plus loin au plus proche
        for (d in listOf(-2, 2, -1, 1, 0)) {
            val i = courant + d
            val l = livres.getOrNull(i) ?: continue
            val opacite = if (d == 0) 1f else if (abs(d) == 1) .72f else .34f
            val ecart = d * largeur * .42f
            val recul = 1f - abs(d) * .18f
            val lg = largeur * recul; val ht = hauteur * recul
            val cx = width / 2f + ecart
            val cy = height / 2f

            c.save()
            // le livre de cote est tourne : on l'ecrase en largeur, comme en perspective
            if (d != 0) c.scale(.74f, 1f, cx, cy)

            val cadre = RectF(cx - lg / 2f, cy - ht / 2f, cx + lg / 2f, cy + ht / 2f)
            p.color = Color.argb((opacite * 255).toInt(), 18, 12, 24)
            c.drawRoundRect(cadre, 10f, 10f, p)
            val cov = couvertureDe(l)
            if (cov != null) {
                pinceau.alpha = (opacite * 255).toInt()
                c.drawBitmap(cov, null, cadre, pinceau)
                pinceau.alpha = 255
            }
            // la tranche
            p.color = Color.argb((opacite * 255).toInt(), 122, 92, 54)
            c.drawRect(cadre.left - lg * .05f, cadre.top + 4f, cadre.left, cadre.bottom - 4f, p)
            c.restore()
        }

        texte.color = 0xFFF3D6A0.toInt(); texte.textSize = height * .035f
        c.drawText(livres[courant].titre, width / 2f, height * .93f, texte)
    }

    // ================= la lecture =================

    /**
     * Le livre ouvert : toujours deux pages, comme un vrai livre qu'on tient
     * a deux mains. La page qui tourne se souleve, se plie et decouvre celle
     * d'en dessous.
     */
    private fun dessinerLecture(c: Canvas) {
        c.drawColor(0xFF120D08.toInt())

        val cadre = RectF(width * .05f, height * .07f, width * .95f, height * .93f)
        val milieu = cadre.centerX()
        val demi = cadre.width() / 2f

        // la tranche : les feuilles empilees, de chaque cote
        p.color = 0xFFE9DFC9.toInt()
        c.drawRoundRect(RectF(cadre.left - 8f, cadre.top - 5f,
            cadre.right + 8f, cadre.bottom + 5f), 12f, 12f, p)
        p.color = 0xFFF7F2E8.toInt()
        c.drawRect(cadre, p)

        // les deux pages du dessous : la gauche et la droite d'arrivee
        val gauche = RectF(cadre.left, cadre.top, milieu, cadre.bottom)
        val droite = RectF(milieu, cadre.top, cadre.right, cadre.bottom)
        if (tourne > 0f && sensTourne > 0) {
            // on avance : a gauche la page qu'on quitte, a droite celle qui arrive
            pageDessinee(c, page - 2, gauche)
            pageDessinee(c, page + 1, droite)
        } else if (tourne > 0f) {
            pageDessinee(c, page, gauche)
            pageDessinee(c, page + 3, droite)
        } else {
            pageDessinee(c, page, gauche)
            pageDessinee(c, page + 1, droite)
        }

        // la feuille en train de tourner, vue en perspective
        if (tourne > 0f) {
            val q = tourne
            // elle part a plat, se dresse, puis retombe de l'autre cote
            val largeurVisible = demi * abs(kotlin.math.cos(q * Math.PI).toFloat())
            val versLaGauche = q < .5f
            val avant = if (sensTourne > 0) (if (versLaGauche) page - 1 else page)
                        else (if (versLaGauche) page + 2 else page + 1)
            val bord = if (sensTourne > 0) {
                if (versLaGauche) RectF(milieu - largeurVisible, cadre.top, milieu, cadre.bottom)
                else RectF(milieu, cadre.top, milieu + largeurVisible, cadre.bottom)
            } else {
                if (versLaGauche) RectF(milieu, cadre.top, milieu + largeurVisible, cadre.bottom)
                else RectF(milieu - largeurVisible, cadre.top, milieu, cadre.bottom)
            }
            p.color = 0xFFFBF7EE.toInt()
            c.drawRect(bord, p)
            pageDessinee(c, avant, bord)
            // l'ombre que la feuille jette sur la page d'en dessous
            val ombre = (110 * (1f - abs(.5f - q) * 2f)).toInt()
            p.color = Color.argb(ombre, 0, 0, 0)
            if (versLaGauche) c.drawRect(bord.left - demi * .12f, cadre.top, bord.left, cadre.bottom, p)
            else c.drawRect(bord.right, cadre.top, bord.right + demi * .12f, cadre.bottom, p)
        }

        // la reliure, au centre
        p.shader = LinearGradient(milieu - 14f, 0f, milieu + 14f, 0f,
            intArrayOf(0x00000000, 0x77000000, 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawRect(milieu - 14f, cadre.top, milieu + 14f, cadre.bottom, p)
        p.shader = null

        texte.color = 0xFFCFC7B7.toInt(); texte.textSize = height * .026f
        c.drawText("${page + 1}-${page + 2} / ${pages.size}", width / 2f, height * .985f, texte)
    }

    private fun pageDessinee(c: Canvas, n: Int, cadre: RectF) {
        val chemin = pages.getOrNull(n) ?: return
        val im = image(chemin) ?: return
        val s = min(cadre.width() / im.width, cadre.height() / im.height)
        val l = im.width * s; val h = im.height * s
        c.drawBitmap(im, null,
            RectF(cadre.centerX() - l / 2f, cadre.centerY() - h / 2f,
                  cadre.centerX() + l / 2f, cadre.centerY() + h / 2f), pinceau)
    }

    // ================= le doigt =================

    private var xDepart = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> xDepart = e.x
            MotionEvent.ACTION_UP -> {
                val dx = e.x - xDepart
                if (lecture == null) {
                    // sur l'etagere : on fait defiler, ou on ouvre celui du milieu
                    if (abs(dx) > width * .08f) {
                        courant = (courant + (if (dx < 0) 1 else -1) + livres.size) % livres.size
                        surTitre?.invoke(livres[courant].titre)
                        invalidate()
                    } else if (livres.isNotEmpty()) {
                        surOuverture?.invoke(livres[courant])
                    }
                } else {
                    // en lecture : on tourne les pages
                    // un vrai livre tourne deux pages a la fois
                    if (dx < -width * .06f || e.x > width * .65f) tournerVers(page + 2)
                    else if (dx > width * .06f || e.x < width * .35f) tournerVers(page - 2)
                }
            }
        }
        return true
    }

    private fun tournerVers(n: Int) {
        if (tourne > 0f) return                       // une page tourne deja
        val cible = n.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        if (cible == page) return
        sensTourne = if (cible > page) 1 else -1
        val arrivee = cible
        surPageTournee?.invoke()
        // la petite animation de la feuille qui se souleve
        tourne = .001f
        val depart = System.nanoTime()
        post(object : Runnable {
            override fun run() {
                val t = (System.nanoTime() - depart) / 1_000_000_000f
                tourne = (t / .55f).coerceAtMost(1f)   // le temps de voir la feuille passer
                invalidate()
                if (tourne < 1f) postDelayed(this, 16)
                else { tourne = 0f; page = arrivee; invalidate() }
            }
        })
    }
}
