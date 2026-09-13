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
        lecture = livre; pages = chemins; page = 0; invalidate()
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

    private fun dessinerLecture(c: Canvas) {
        c.drawColor(0xFF120D08.toInt())
        val deboutSeul = height > width          // telephone debout : une seule page

        val cadre = if (deboutSeul)
            RectF(width * .04f, height * .06f, width * .96f, height * .94f)
        else RectF(width * .06f, height * .08f, width * .94f, height * .92f)

        // le livre : un fond creme, et l'epaisseur des feuilles
        p.color = 0xFFE9DFC9.toInt()
        c.drawRoundRect(RectF(cadre.left - 6f, cadre.top - 6f, cadre.right + 6f, cadre.bottom + 6f), 10f, 10f, p)
        p.color = 0xFFF7F2E8.toInt()
        c.drawRect(cadre, p)

        if (deboutSeul) {
            pageDessinee(c, page, cadre)
        } else {
            val milieu = cadre.centerX()
            pageDessinee(c, page, RectF(cadre.left, cadre.top, milieu, cadre.bottom))
            pageDessinee(c, page + 1, RectF(milieu, cadre.top, cadre.right, cadre.bottom))
            // la reliure
            p.color = 0x66000000
            c.drawRect(milieu - 7f, cadre.top, milieu + 7f, cadre.bottom, p)
        }

        // la page qui se souleve pendant qu'on tourne
        if (tourne > 0f) {
            val avance = if (sensTourne > 0) tourne else 1f - tourne
            p.color = Color.argb((120 * (1f - abs(.5f - avance) * 2f)).toInt(), 0, 0, 0)
            val x = cadre.left + cadre.width() * avance
            c.drawRect(x - cadre.width() * .06f, cadre.top, x, cadre.bottom, p)
        }

        texte.color = 0xFFCFC7B7.toInt(); texte.textSize = height * .028f
        val total = pages.size
        c.drawText("${page + 1} / $total", width / 2f, height * .985f, texte)
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
                    val pas = if (height > width) 1 else 2
                    if (dx < -width * .06f || e.x > width * .65f) tournerVers(page + pas)
                    else if (dx > width * .06f || e.x < width * .35f) tournerVers(page - pas)
                }
            }
        }
        return true
    }

    private fun tournerVers(n: Int) {
        val cible = n.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        if (cible == page) return
        sensTourne = if (cible > page) 1 else -1
        page = cible
        surPageTournee?.invoke()
        // la petite animation de la feuille qui se souleve
        tourne = .001f
        val depart = System.nanoTime()
        post(object : Runnable {
            override fun run() {
                val t = (System.nanoTime() - depart) / 1_000_000_000f
                tourne = (t / .28f).coerceAtMost(1f)
                invalidate()
                if (tourne < 1f) postDelayed(this, 16) else tourne = 0f
            }
        })
    }
}
