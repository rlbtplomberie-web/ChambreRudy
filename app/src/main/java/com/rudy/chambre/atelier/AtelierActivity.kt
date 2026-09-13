package com.rudy.chambre.atelier

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.*
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * L'atelier de dessin de Rudy : sa trousse de huit crayons, sa gomme, son
 * zoom jusqu'a six fois, et le bruit du crayon sur le papier.
 */
class AtelierActivity : ComponentActivity() {

    private lateinit var papier: Papier
    private lateinit var niveau: TextView
    private var son: SonCrayon? = null

    override fun onCreate(e: Bundle?) {
        super.onCreate(e)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        son = SonCrayon()
        papier = Papier(this)

        niveau = TextView(this).apply {
            text = "100 %"; textSize = 12f; setTextColor(Color.WHITE)
            setOnClickListener { papier.remettreLeZoom() }
        }

        papier.surFrottement = { vitesse -> son?.frotter(vitesse, papier.outil, papier.gomme) }
        papier.surSilence = { son?.silence() }
        papier.surZoom = { z -> niveau.text = "${Math.round(z * 100)} %" }

        val racine = FrameLayout(this)
        racine.setBackgroundColor(0xFFF3E7D2.toInt())      // son fond de page, beige
        val largeurTrousse = (resources.displayMetrics.widthPixels * .22f).toInt()
        papier.margeGauche = largeurTrousse.toFloat()
        racine.addView(papier, FrameLayout.LayoutParams(-1, -1))
        racine.addView(trousse(), FrameLayout.LayoutParams(largeurTrousse, -1, Gravity.START))
        racine.addView(barreDuHaut())
        racine.addView(tailles())
        setContentView(racine)
        com.rudy.chambre.Ambiance.musiqueDuJeu(this, "atelier/musique_atelier.webm", 1f)
    }

    /** Sa trousse verte, avec les huit crayons et la gomme. */
    /**
     * Ses crayons, poses a plat sur le bureau, a gauche de la feuille :
     * un corps de couleur, une pointe de bois taillee et une mine. Le feutre
     * a un capuchon, le pinceau des poils. La gomme est en bas.
     */
    /**
     * Sa trousse, reproduite trait pour trait depuis son SVG.
     *
     * Le dessin vit dans son repere d'origine — 118 sur 296, decale de 6 vers
     * le bas — et l'on met simplement ce repere a l'echelle. Chaque crayon est
     * pose a (40 ; 46 + 32 i) et incline de trois degres par rang, la pochette
     * verte est peinte par-dessus, et le crayon choisi sort de seize points.
     */
    private fun trousse(): View {
        val vue = object : View(this) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            private val bord = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                strokeJoin = Paint.Join.ROUND
                color = 0xFF17140F.toInt()
            }

            /** Son repere : viewBox « 0 6 118 296 ». */
            private fun repere(c: Canvas) {
                val k = kotlin.math.min(width / 118f, height / 296f)
                c.translate((width - 118f * k) / 2f, (height - 296f * k) / 2f)
                c.scale(k, k)
                c.translate(0f, -6f)
            }

            private fun rect(c: Canvas, x: Float, y: Float, l: Float, h: Float,
                             couleur: Int, contour: Boolean = true, r: Float = 0f) {
                p.style = Paint.Style.FILL; p.color = couleur
                if (r > 0f) c.drawRoundRect(RectF(x, y, x + l, y + h), r, r, p)
                else c.drawRect(x, y, x + l, y + h, p)
                if (contour) {
                    if (r > 0f) c.drawRoundRect(RectF(x, y, x + l, y + h), r, r, bord)
                    else c.drawRect(x, y, x + l, y + h, bord)
                }
            }

            private fun triangle(c: Canvas, pts: FloatArray, couleur: Int) {
                val f = Path()
                f.moveTo(pts[0], pts[1]); f.lineTo(pts[2], pts[3]); f.lineTo(pts[4], pts[5])
                f.close()
                p.style = Paint.Style.FILL; p.color = couleur
                c.drawPath(f, p)
                c.drawPath(f, bord)
            }

            override fun onDraw(c: Canvas) {
                c.save()
                repere(c)

                // ---- les crayons, un par rang ----
                OUTILS.forEachIndexed { i, o ->
                    val y = 46f + i * 32f
                    val angle = (i - (OUTILS.size - 1) / 2f) * 3f
                    val sorti = if (i == papier.iOutil && !papier.gomme) 16f else 0f
                    c.save()
                    c.translate(40f + sorti, y)
                    c.rotate(angle)

                    rect(c, 0f, -8f, 46f, 16f, o.corps)                 // le corps
                    rect(c, 4f, -4.5f, 38f, 3f, 0x4DFFFFFF, contour = false)  // le reflet
                    rect(c, 46f, -8f, 7f, 16f, 0xFFE6E2D8.toInt())      // la bague
                    triangle(c, floatArrayOf(53f, -8f, 53f, 8f, 71f, 0f), 0xFFECCFA6.toInt())
                    triangle(c, floatArrayOf(65f, -3.5f, 65f, 3.5f, 71f, 0f), o.mine)
                    c.restore()
                }

                // ---- la pochette verte, peinte par-dessus ----
                rect(c, 6f, 18f, 52f, 272f, 0xFF1F4B33.toInt(), r = 22f)

                // sa couture : un trait sombre double d'un pointille clair
                p.style = Paint.Style.STROKE
                p.color = 0xFF0E2B1D.toInt(); p.strokeWidth = 6f
                c.drawLine(24f, 22f, 24f, 286f, p)
                p.color = 0xFF5C8F70.toInt(); p.strokeWidth = 2f
                p.pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
                c.drawLine(24f, 22f, 24f, 286f, p)
                p.pathEffect = null

                // ---- la gomme, dans sa pochette ----
                rect(c, 14f, 248f, 22f, 24f,
                     if (papier.gomme) 0xFFFFFFFF.toInt() else 0xFFD8D3C4.toInt(), r = 5f)
                p.style = Paint.Style.STROKE; p.color = 0xFF17140F.toInt(); p.strokeWidth = 3f
                c.drawLine(14f, 260f, 3f, 260f, p)

                // le pli du tissu
                p.color = 0x800E2B1D.toInt(); p.strokeWidth = 4f
                p.strokeCap = Paint.Cap.ROUND
                val pli = Path()
                pli.moveTo(40f, 44f)
                pli.quadTo(51f, 90f, 49f, 156f)
                c.drawPath(pli, p)
                p.style = Paint.Style.FILL

                c.restore()
            }

            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
                // on repasse du doigt vers son repere
                val k = kotlin.math.min(width / 118f, height / 296f)
                val y = (e.y - (height - 296f * k) / 2f) / k + 6f
                if (y > 244f) {
                    papier.gomme = true
                } else {
                    val i = kotlin.math.round((y - 46f) / 32f).toInt()
                    if (i in OUTILS.indices) { papier.iOutil = i; papier.gomme = false }
                }
                invalidate()
                return true
            }
        }
        return vue
    }

    /** Ses boutons : annuler, vider, garder, zoom, son. */
    /** Ses trois epaisseurs de trait : 0,6 — 1 — 1,9. */
    private fun tailles(): LinearLayout {
        val dens = resources.displayMetrics.density
        fun pastille(taille: Float, points: Float) = Button(this).apply {
            text = "●"
            textSize = points
            setTextColor(0xFF17140F.toInt())
            setBackgroundColor(0xFFFDFBF3.toInt())
            setOnClickListener { papier.epaisseur = taille }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pastille(0.6f, 8f))
            addView(pastille(1f, 13f))
            addView(pastille(1.9f, 19f))
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = (14 * dens).toInt() }
        }
    }

    private fun barreDuHaut(): LinearLayout {
        fun bouton(titre: String, action: () -> Unit) = Button(this).apply {
            text = titre; textSize = 11f
            setTextColor(Color.WHITE); setBackgroundColor(0xCC2A2118.toInt())
            setOnClickListener { action() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(bouton("← Bureau") { finish() })
            addView(bouton("Annuler") { papier.annuler() })
            addView(bouton("Vider") { papier.vider() })
            addView(bouton("−") { papier.zoomer(papier.zoom / 1.4f) })
            addView(niveau)
            addView(bouton("+") { papier.zoomer(papier.zoom * 1.4f) })
            addView(bouton("Son") { son?.actif = !(son?.actif ?: true) })
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
                .apply { topMargin = 14; rightMargin = 14 }
        }
    }

    override fun onDestroy() { com.rudy.chambre.Ambiance.rendreLaMusique(); son?.liberer(); super.onDestroy() }
}

/**
 * La feuille : le trace au doigt, la gomme, l'annulation, le zoom a deux
 * doigts — avec ses reglages d'epaisseur et de transparence.
 */
class Papier(ctx: Context) : View(ctx) {

    /** Le calque de dessin : il a exactement la taille de la feuille. */
    private var calque: Bitmap? = null
    private var pinceauCalque: Canvas? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val copie = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Ou commence la feuille : juste apres la trousse. */
    var margeGauche = 0f

    /** Le multiplicateur d'epaisseur : 0,6 — 1 — 1,9, ses trois pastilles. */
    var epaisseur = 1f

    var iOutil = 0
    var gomme = false
    var surFrottement: ((Float) -> Unit)? = null
    var surSilence: (() -> Unit)? = null
    var surZoom: ((Float) -> Unit)? = null

    /** L'outil en main, pour la teinte du bruit de crayon. */
    val outil get() = OUTILS[iOutil.coerceIn(0, OUTILS.size - 1)]

    /** Le grossissement de la feuille, de 1 a 6 comme chez lui. */
    var zoom = 1f
        private set

    fun zoomer(v: Float) {
        zoom = v.coerceIn(1f, 6f)
        surZoom?.invoke(zoom)
        invalidate()
    }

    fun remettreLeZoom() = zoomer(1f)

    // le trait en cours
    private var trace = false
    private var largeurDeBase = 4f      // l'epaisseur voulue, avant la vitesse
    private var ax = 0f; private var ay = 0f
    private var bx = 0f; private var by = 0f

    private val annulations = ArrayDeque<Bitmap>()

    /** Le rectangle de la feuille a l'ecran : un vrai format A4, 210 sur 297. */
    fun cadreA4(): RectF {
        val libre = RectF(margeGauche + 14f, 58f, width - 14f, height - 96f)
        if (libre.width() <= 0f || libre.height() <= 0f) return RectF()
        val k = kotlin.math.min(libre.width() / 210f, libre.height() / 297f)
        val l = 210f * k; val h = 297f * k
        return RectF(libre.centerX() - l / 2f, libre.centerY() - h / 2f,
                     libre.centerX() + l / 2f, libre.centerY() + h / 2f)
    }

    override fun onSizeChanged(l: Int, h: Int, al: Int, ah: Int) {
        super.onSizeChanged(l, h, al, ah)
        val page = cadreA4()
        val pl = page.width().toInt(); val ph = page.height().toInt()
        if (pl <= 0 || ph <= 0) return
        val neuf = Bitmap.createBitmap(pl, ph, Bitmap.Config.ARGB_8888)
        val c = Canvas(neuf)
        calque?.let { c.drawBitmap(it, null, RectF(0f, 0f, pl.toFloat(), ph.toFloat()), copie) }
        calque = neuf
        pinceauCalque = c
    }

    override fun onDraw(c: Canvas) {
        // son fond de page : un beige avec deux trames tres legeres,
        // l'une penchee de 2 degres, l'autre de 1,5 dans l'autre sens
        c.drawColor(0xFFF3E7D2.toInt())
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = 0x1A3C1E0A
        var y = -30f
        while (y < height + 30f) { c.drawLine(0f, y, width.toFloat(), y + width * .035f, p); y += 7f }
        p.color = 0x1AFFE1BE
        y = -30f
        while (y < height + 30f) { c.drawLine(0f, y, width.toFloat(), y - width * .026f, p); y += 13f }
        p.style = Paint.Style.FILL

        val page = cadreA4()
        if (page.isEmpty) return

        // l'ombre portee de la feuille sur le bureau
        p.color = 0x55231104
        c.drawRect(page.left + 8f, page.top + 10f, page.right + 8f, page.bottom + 10f, p)

        // la feuille, son papier creme
        p.color = 0xFFFDFBF3.toInt()
        c.drawRect(page, p)

        // le grain du papier : de fines lignes a peine visibles
        p.color = 0x0A17140F
        p.style = Paint.Style.STROKE
        var g = page.top + 4f
        while (g < page.bottom) { c.drawLine(page.left, g, page.right, g, p); g += 6f }
        p.style = Paint.Style.FILL

        // le dessin, au grossissement choisi
        c.save()
        c.clipRect(page)
        if (zoom > 1f) c.scale(zoom, zoom, page.centerX(), page.centerY())
        calque?.let { c.drawBitmap(it, null, page, copie) }
        c.restore()

        // le contour d'encre
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = 0xFF17140F.toInt()
        c.drawRect(page, p)
        p.style = Paint.Style.FILL

        // ses deux bouts de scotch, en haut
        for (cote in intArrayOf(-1, 1)) {
            c.save()
            val sx = if (cote < 0) page.left + 12f else page.right - 74f
            c.rotate(if (cote < 0) -26f else 24f, sx + 31f, page.top)
            val r = RectF(sx, page.top - 11f, sx + 62f, page.top + 11f)
            p.color = 0xD9F6EECD.toInt(); c.drawRect(r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f; p.color = 0xFF17140F.toInt()
            c.drawRect(r, p)
            p.style = Paint.Style.FILL
            c.restore()
        }
    }

    /** Du doigt vers le calque : une simple soustraction, rien de plus. */
    private fun surLaFeuille(x: Float, y: Float): Pair<Float, Float> {
        val page = cadreA4()
        // on defait le grossissement avant de reporter sur le calque
        val vx = (x - page.centerX()) / zoom + page.centerX()
        val vy = (y - page.centerY()) / zoom + page.centerY()
        return (vx - page.left) to (vy - page.top)
    }

    private fun reglages() {
        p.reset()
        p.isAntiAlias = true
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        // l'unite : un millieme de la largeur de la feuille, pour que le
        // trait ait la meme allure sur tous les ecrans
        val unite = kotlin.math.max(1f, (calque?.width ?: 1000) / 210f)
        if (gomme) {
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            p.strokeWidth = 7f * unite * epaisseur
        } else {
            p.xfermode = null
            p.color = outil.couleur
            p.alpha = (255 * outil.alpha).toInt().coerceIn(28, 255)
            // ses tailles vont de 2,4 a 22 : on les ramene en millimetres
            p.strokeWidth = outil.taille * .42f * unite * epaisseur
        }
        largeurDeBase = p.strokeWidth
    }

    private fun memoriser() {
        calque?.let {
            annulations.addLast(it.copy(Bitmap.Config.ARGB_8888, false))
            if (annulations.size > 12) annulations.removeFirst()
        }
    }

    fun annuler() {
        val avant = annulations.removeLastOrNull() ?: return
        calque = avant
        pinceauCalque = Canvas(avant)
        invalidate()
    }

    fun vider() {
        memoriser()
        calque?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val page = cadreA4()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!page.contains(e.x, e.y)) return true
                memoriser(); reglages(); trace = true
                val (x, y) = surLaFeuille(e.x, e.y)
                ax = x; ay = y; bx = x; by = y
                // un point pose : le depart du trait
                val c = pinceauCalque ?: return true
                c.drawPoint(x, y, p)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!trace) return true
                val c = pinceauCalque ?: return true
                val (x, y) = surLaFeuille(e.x, e.y)
                // un vrai crayon appuie moins quand la main va vite
                val vitesse = hypot(x - bx, y - by)
                val large = largeurDeBase * (1f - (vitesse / 90f).coerceIn(0f, .38f))
                p.strokeWidth = kotlin.math.max(largeurDeBase * .55f, large)

                // un trait lisse : la courbe passe par le milieu des deux points
                val chemin = Path()
                chemin.moveTo(ax, ay)
                chemin.quadTo(bx, by, (bx + x) / 2f, (by + y) / 2f)
                c.drawPath(chemin, p)

                // le grain du crayon : quelques points en marge du trait
                if (outil.grain && !gomme) {
                    val g = Paint(p)
                    g.style = Paint.Style.FILL
                    g.alpha = (p.alpha * .35f).toInt().coerceIn(10, 120)
                    var k = 0
                    while (k < 3) {
                        val t2 = k / 3f
                        val gx = bx + (x - bx) * t2 + (Math.random().toFloat() - .5f) * largeurDeBase * 1.4f
                        val gy = by + (y - by) * t2 + (Math.random().toFloat() - .5f) * largeurDeBase * 1.4f
                        c.drawCircle(gx, gy, largeurDeBase * .16f, g)
                        k++
                    }
                }
                ax = (bx + x) / 2f; ay = (by + y) / 2f
                bx = x; by = y
                surFrottement?.invoke(hypot(x - ax, y - ay))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (trace) {
                    val c = pinceauCalque
                    if (c != null) { c.drawPoint(bx, by, p); invalidate() }
                }
                trace = false
                surSilence?.invoke()
                return true
            }
        }
        return true
    }
}
