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
        racine.setBackgroundColor(0xFF96613A.toInt())      // son bois : --bois
        val largeurTrousse = (resources.displayMetrics.widthPixels * .16f).toInt()
        papier.margeGauche = largeurTrousse.toFloat()
        racine.addView(papier, FrameLayout.LayoutParams(-1, -1))
        racine.addView(trousse(), FrameLayout.LayoutParams(largeurTrousse, -1, Gravity.START))
        racine.addView(barreDuHaut())
        racine.addView(tailles())
        setContentView(racine)
        com.rudy.chambre.Ambiance.adoucirLaMusique()
    }

    /** Sa trousse verte, avec les huit crayons et la gomme. */
    private fun trousse(): View {
        val vue = object : View(this) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                val l = width.toFloat(); val h = height.toFloat()
                // le corps de la trousse
                p.color = 0xFF1F4B33.toInt()
                c.drawRoundRect(RectF(l * .10f, h * .06f, l * .92f, h * .94f), l * .35f, l * .35f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = 0xFF17140F.toInt()
                c.drawRoundRect(RectF(l * .10f, h * .06f, l * .92f, h * .94f), l * .35f, l * .35f, p)
                p.style = Paint.Style.FILL

                // les crayons, un par ligne, legerement inclines comme chez lui
                val epaisseur = h * .022f          // l'epaisseur d'un crayon
                OUTILS.forEachIndexed { i, o ->
                    val y = h * (.10f + i * .079f)
                    val angle = (i - (OUTILS.size - 1) / 2f) * 3f
                    c.save(); c.rotate(angle, l * .5f, y)
                    val choisi = !papier.gomme && papier.iOutil == i
                    val x0 = l * (if (choisi) .30f else .22f)
                    val larg = l * .58f
                    p.color = o.corps
                    c.drawRect(x0, y - epaisseur, x0 + larg * .70f, y + epaisseur, p)
                    p.color = 0xFFE6E2D8.toInt()
                    c.drawRect(x0 + larg * .70f, y - epaisseur, x0 + larg * .80f, y + epaisseur, p)
                    // la pointe
                    val pointe = Path()
                    pointe.moveTo(x0 + larg * .80f, y - epaisseur)
                    pointe.lineTo(x0 + larg * .80f, y + epaisseur)
                    pointe.lineTo(x0 + larg, y)
                    pointe.close()
                    p.color = 0xFFECCFA6.toInt(); c.drawPath(pointe, p)
                    val mine = Path()
                    mine.moveTo(x0 + larg * .93f, y - epaisseur * .42f)
                    mine.lineTo(x0 + larg * .93f, y + epaisseur * .42f)
                    mine.lineTo(x0 + larg, y)
                    mine.close()
                    p.color = o.mine; c.drawPath(mine, p)
                    c.restore()
                }

                // la gomme, en bas
                p.color = if (papier.gomme) 0xFFFFE16E.toInt() else 0xFFD8D3C4.toInt()
                c.drawRoundRect(RectF(l * .26f, h * .93f, l * .74f, h * .985f), 6f, 6f, p)
            }

            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
                val h = height.toFloat()
                if (e.y > h * .92f) { papier.gomme = true; invalidate(); return true }
                val i = ((e.y / h - .10f) / .079f + .5f).toInt()
                if (i in OUTILS.indices) { papier.gomme = false; papier.iOutil = i }
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

    /** Ou commence la feuille : juste apres la trousse. */
    var margeGauche = 0f

    /** Le multiplicateur d'epaisseur : 0,6 — 1 — 1,9, comme ses trois pastilles. */
    var epaisseur = 1f

    var iOutil = 0
    var gomme = false
    val outil get() = OUTILS[iOutil]
    var zoom = 1f; private set
    private var dx = 0f; private var dy = 0f

    var surFrottement: ((Float) -> Unit)? = null
    var surSilence: (() -> Unit)? = null
    var surZoom: ((Float) -> Unit)? = null

    private var feuille: Bitmap? = null
    private var pinceauFeuille: Canvas? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private val copie = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Son historique : douze retours en arriere, comme chez lui. */
    private val histoire = ArrayList<Bitmap>()

    private var trace = false
    private var ax = 0f; private var ay = 0f
    private var bx = 0f; private var by = 0f

    override fun onSizeChanged(l: Int, h: Int, al: Int, ah: Int) {
        super.onSizeChanged(l, h, al, ah)
        if (l <= 0 || h <= 0) return
        val neuve = Bitmap.createBitmap(l, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(neuve)
        feuille?.let { c.drawBitmap(it, 0f, 0f, null) }
        feuille = neuve
        pinceauFeuille = c
    }

    /** Le rectangle de la feuille : un vrai format A4, 210 sur 297. */
    fun cadreA4(): RectF {
        val libre = RectF(margeGauche + 12f, 60f, width - 12f, height - 90f)
        val parLargeur = libre.width() / 210f
        val parHauteur = libre.height() / 297f
        val k = kotlin.math.min(parLargeur, parHauteur)
        val l = 210f * k; val h = 297f * k
        return RectF(libre.centerX() - l / 2f, libre.centerY() - h / 2f,
                     libre.centerX() + l / 2f, libre.centerY() + h / 2f)
    }

    override fun onDraw(c: Canvas) {
        // le bureau : un bois veine, comme son fond de page
        c.drawColor(0xFF96613A.toInt())
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = 0x1A3C1E0A
        var y = 0f
        while (y < height) { c.drawLine(0f, y, width.toFloat(), y + 12f, p); y += 9f }
        p.style = Paint.Style.FILL

        val page = cadreA4()
        // l'ombre portee de la feuille sur le bureau
        p.color = 0x66231104
        c.drawRect(page.left + 7f, page.top + 9f, page.right + 7f, page.bottom + 9f, p)
        // la feuille
        p.color = 0xFFFDFBF3.toInt()
        c.drawRect(page, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = 0xFF17140F.toInt()
        c.drawRect(page, p)
        p.style = Paint.Style.FILL

        val f = feuille ?: return
        c.save()
        c.clipRect(page)
        c.translate(dx, dy)
        c.scale(zoom, zoom, width / 2f, height / 2f)
        c.drawBitmap(f, 0f, 0f, copie)
        c.restore()

        // les deux bouts de scotch, en haut de la feuille
        p.color = 0xD9F6EECD.toInt()
        for (cote in intArrayOf(-1, 1)) {
            c.save()
            val sx = if (cote < 0) page.left + 10f else page.right - 72f
            c.rotate(if (cote < 0) -26f else 24f, sx + 31f, page.top)
            c.drawRect(sx, page.top - 11f, sx + 62f, page.top + 11f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f; p.color = 0xFF17140F.toInt()
            c.drawRect(sx, page.top - 11f, sx + 62f, page.top + 11f, p)
            p.style = Paint.Style.FILL; p.color = 0xD9F6EECD.toInt()
            c.restore()
        }
    }

    /** Son « reglages » : la gomme efface, le crayon depose sa couleur. */
    private fun reglages() {
        if (gomme) {
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            p.alpha = 255
            p.strokeWidth = 26f * epaisseur
        } else {
            p.xfermode = null
            p.color = outil.couleur
            p.alpha = (outil.alpha * 255).toInt()
            p.strokeWidth = outil.taille * 1.6f * epaisseur
        }
    }

    private fun memoriser() {
        val f = feuille ?: return
        histoire.add(f.copy(Bitmap.Config.ARGB_8888, false))
        if (histoire.size > 12) histoire.removeAt(0).recycle()
    }

    fun annuler() {
        val d = histoire.removeLastOrNull() ?: return
        feuille = d
        pinceauFeuille = Canvas(d)
        invalidate()
    }

    fun vider() {
        memoriser()
        feuille?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    fun zoomer(nz: Float) {
        val z = max(1f, min(6f, nz))
        dx *= z / zoom; dy *= z / zoom
        zoom = z
        limiter()
        surZoom?.invoke(zoom)
        invalidate()
    }

    fun remettreLeZoom() { dx = 0f; dy = 0f; zoomer(1f) }

    private fun limiter() {
        val maxX = (zoom - 1f) * width / 2f
        val maxY = (zoom - 1f) * height / 2f
        dx = dx.coerceIn(-maxX, maxX)
        dy = dy.coerceIn(-maxY, maxY)
    }

    /** Le point du doigt, ramene dans le repere de la feuille. */
    private fun point(x: Float, y: Float): Pair<Float, Float> {
        val cx = width / 2f; val cy = height / 2f
        return ((x - dx - cx) / zoom + cx) to ((y - dy - cy) / zoom + cy)
    }

    private var ecartDepart = 0f
    private var zoomDepart = 1f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // on ne dessine que sur la feuille
                if (!cadreA4().contains(e.x, e.y)) return true
                memoriser(); reglages(); trace = true
                val (x, y) = point(e.x, e.y)
                ax = x; ay = y; bx = x; by = y
                pinceauFeuille?.drawPoint(x, y, p)
                surFrottement?.invoke(6f)
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // deux doigts : on arrete le trait et on passe au zoom
                trace = false
                surSilence?.invoke()
                if (e.pointerCount >= 2) {
                    ecartDepart = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    zoomDepart = zoom
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val ecart = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    if (ecartDepart > 0f) zoomer(zoomDepart * ecart / ecartDepart)
                    return true
                }
                if (!trace) return true
                val (x, y) = point(e.x, e.y)
                val vitesse = hypot(x - bx, y - by)
                // son trace : une courbe qui passe par le point du milieu
                val mx = (bx + x) / 2f; val my = (by + y) / 2f
                val chemin = Path()
                chemin.moveTo(ax, ay)
                chemin.quadTo(bx, by, mx, my)
                pinceauFeuille?.drawPath(chemin, p)
                // le grain du crayon gris : un second trait, decale et plus pale
                if (outil.grain && !gomme) {
                    val garde = p.alpha
                    p.alpha = (outil.alpha * .5f * 255).toInt()
                    val second = Path()
                    second.moveTo(ax + .8f, ay - .8f)
                    second.quadTo(bx + .8f, by - .8f, mx + .8f, my - .8f)
                    pinceauFeuille?.drawPath(second, p)
                    p.alpha = garde
                }
                ax = mx; ay = my; bx = x; by = y
                surFrottement?.invoke(vitesse)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                trace = false
                ecartDepart = 0f
                surSilence?.invoke()
            }
        }
        return true
    }
}
