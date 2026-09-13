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
        val largeurTrousse = (resources.displayMetrics.widthPixels * .26f).toInt()
        papier.margeGauche = largeurTrousse.toFloat()
        racine.addView(papier, FrameLayout.LayoutParams(-1, -1))
        racine.addView(trousse(), FrameLayout.LayoutParams(largeurTrousse, -1, Gravity.START))
        racine.addView(barreDuHaut())
        racine.addView(tailles())
        setContentView(racine)
        com.rudy.chambre.Ambiance.adoucirLaMusique()
    }

    /** Sa trousse verte, avec les huit crayons et la gomme. */
    /**
     * Ses crayons, poses a plat sur le bureau, a gauche de la feuille :
     * un corps de couleur, une pointe de bois taillee et une mine. Le feutre
     * a un capuchon, le pinceau des poils. La gomme est en bas.
     */
    private fun trousse(): View {
        val vue = object : View(this) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)

            override fun onDraw(c: Canvas) {
                val l = width.toFloat(); val h = height.toFloat()
                val pas = h / (OUTILS.size + 1.6f)
                val corps = pas * .42f              // l'epaisseur d'un crayon
                val long = l * .78f

                OUTILS.forEachIndexed { i, o ->
                    val y = pas * (i + .8f)
                    val x0 = l * .10f
                    val choisi = i == papier.iOutil && !papier.gomme
                    val avance = if (choisi) l * .10f else 0f

                    // son ombre sur le bureau
                    p.color = 0x44231104
                    c.drawRoundRect(RectF(x0 + avance + 3f, y - corps / 2f + 4f,
                        x0 + avance + long + 3f, y + corps / 2f + 4f), corps / 2f, corps / 2f, p)

                    when (o.nom) {
                        "Pinceau" -> {
                            // le manche
                            p.color = 0xFF8A5A2B.toInt()
                            c.drawRoundRect(RectF(x0 + avance, y - corps / 2f,
                                x0 + avance + long * .62f, y + corps / 2f), corps / 2f, corps / 2f, p)
                            // la virole
                            p.color = 0xFFB9B3A6.toInt()
                            c.drawRect(x0 + avance + long * .60f, y - corps * .58f,
                                       x0 + avance + long * .74f, y + corps * .58f, p)
                            // les poils
                            p.color = o.trait
                            val poils = Path()
                            poils.moveTo(x0 + avance + long * .74f, y - corps * .55f)
                            poils.lineTo(x0 + avance + long, y)
                            poils.lineTo(x0 + avance + long * .74f, y + corps * .55f)
                            poils.close()
                            c.drawPath(poils, p)
                        }
                        "Feutre" -> {
                            p.color = 0xFF2B2B2B.toInt()
                            c.drawRoundRect(RectF(x0 + avance, y - corps * .58f,
                                x0 + avance + long * .72f, y + corps * .58f), corps * .3f, corps * .3f, p)
                            // le capuchon, de la couleur de l'encre
                            p.color = o.trait
                            c.drawRoundRect(RectF(x0 + avance + long * .70f, y - corps * .52f,
                                x0 + avance + long, y + corps * .52f), corps * .3f, corps * .3f, p)
                        }
                        else -> {
                            // le corps du crayon, peint de sa couleur
                            p.color = o.corps
                            c.drawRoundRect(RectF(x0 + avance, y - corps / 2f,
                                x0 + avance + long * .74f, y + corps / 2f), corps * .22f, corps * .22f, p)
                            // le bois taille
                            p.color = 0xFFE3C08A.toInt()
                            val bois = Path()
                            bois.moveTo(x0 + avance + long * .74f, y - corps / 2f)
                            bois.lineTo(x0 + avance + long * .94f, y)
                            bois.lineTo(x0 + avance + long * .74f, y + corps / 2f)
                            bois.close()
                            c.drawPath(bois, p)
                            // la mine
                            p.color = o.trait
                            val mine = Path()
                            mine.moveTo(x0 + avance + long * .90f, y - corps * .14f)
                            mine.lineTo(x0 + avance + long, y)
                            mine.lineTo(x0 + avance + long * .90f, y + corps * .14f)
                            mine.close()
                            c.drawPath(mine, p)
                        }
                    }
                }

                // la gomme, posee en bas
                val gy = pas * (OUTILS.size + .9f)
                val gl = l * .52f
                val choisieG = papier.gomme
                p.color = 0x44231104
                c.drawRoundRect(RectF(l * .12f + 3f, gy - pas * .30f + 4f,
                    l * .12f + gl + 3f, gy + pas * .30f + 4f), 6f, 6f, p)
                p.color = if (choisieG) 0xFFFFE0E6.toInt() else 0xFFF3D9DE.toInt()
                c.drawRoundRect(RectF(l * .12f, gy - pas * .30f,
                    l * .12f + gl, gy + pas * .30f), 6f, 6f, p)
                p.color = 0xFF9A7F84.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 2f
                c.drawRoundRect(RectF(l * .12f, gy - pas * .30f,
                    l * .12f + gl, gy + pas * .30f), 6f, 6f, p)
                p.style = Paint.Style.FILL
            }

            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
                val h = height.toFloat()
                val pas = h / (OUTILS.size + 1.6f)
                if (e.y > pas * (OUTILS.size + .55f)) {
                    papier.gomme = true
                } else {
                    val i = ((e.y / pas) - .8f + .5f).toInt()
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
        // le bureau : son bois, veine en diagonale
        c.drawColor(0xFF96613A.toInt())
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = 0x1A3C1E0A
        var y = -20f
        while (y < height + 20f) { c.drawLine(0f, y, width.toFloat(), y + 14f, p); y += 9f }
        p.color = 0x12FFE1BE
        y = -20f
        while (y < height + 20f) { c.drawLine(0f, y + 5f, width.toFloat(), y - 8f, p); y += 15f }
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
        if (gomme) {
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            p.strokeWidth = 26f * epaisseur
        } else {
            p.xfermode = null
            p.color = outil.trait
            p.alpha = (255 * outil.opacite).toInt().coerceIn(20, 255)
            p.strokeWidth = outil.taille * 1.6f * epaisseur
        }
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
                // un trait lisse : la courbe passe par le milieu des deux points
                val chemin = Path()
                chemin.moveTo(ax, ay)
                chemin.quadTo(bx, by, (bx + x) / 2f, (by + y) / 2f)
                c.drawPath(chemin, p)
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
