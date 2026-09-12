package com.rudy.chambre

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * La chambre, dessinee directement par le telephone.
 *
 * Trois murs se suivent : le bureau, la baie vitree, le lit. Le doigt promene
 * le regard ; en butee, la tete pivote vers le mur suivant. Les objets sont
 * poses en fractions de l'image, donc justes sur n'importe quel ecran.
 */
class VueChambre(ctx: Context) : View(ctx) {

    /** Ce que la vue demande a l'activite. */
    var surObjet: ((String) -> Unit)? = null

    private val images = HashMap<String, Bitmap>()
    private val peinture = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val ombre = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }

    // --- etat du regard ---
    private var vue = 0                 // 0 bureau, 1 baie vitree, 2 lit
    private var decX = 0f               // decalage horizontal dans le mur courant
    private var largeurMur = 0f
    private var hauteurMur = 0f
    private var enPivot = false

    // --- carton et console posee ---
    var consoleIdx = -1; private set
    private var sortie = 0f             // 0 rangee, 1 posee
    private var tremble = 0f

    // --- meuble ---
    var porteG = 0f; private set        // 0 fermee, 1 ouverte
    var porteD = 0f; private set
    private var devantMeuble = false

    // --- tiroir ---
    var tiroir = 0f; private set

    private val murs = arrayOf("salon.jpg", "salon2.jpg", "chambre3.jpg")

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        for (m in murs) charger(m)
    }

    private fun charger(nom: String): Bitmap? {
        images[nom]?.let { return it }
        return try {
            context.assets.open("chambre/$nom").use {
                val b = BitmapFactory.decodeStream(it)
                if (b != null) images[nom] = b
                b
            }
        } catch (_: Throwable) { null }
    }

    override fun onSizeChanged(l: Int, h: Int, al: Int, ah: Int) {
        super.onSizeChanged(l, h, al, ah)
        recalculer()
    }

    private fun recalculer() {
        hauteurMur = height.toFloat()
        largeurMur = if (vue == 2) hauteurMur * Decor.RATIO_LIT else hauteurMur * Decor.RATIO_MUR
        decX = decX.coerceIn(min(0f, width - largeurMur), 0f)
    }

    // ================= dessin =================

    override fun onDraw(c: Canvas) {
        if (etape == 1) { dessinerIntro(c); return }
        val fond = charger(murs[vue]) ?: return
        c.drawColor(Color.BLACK)
        val dest = RectF(decX, 0f, decX + largeurMur, hauteurMur)
        c.drawBitmap(fond, null, dest, peinture)
        if (vue == 0) dessinerBureau(c)
    }

    /** Un rectangle du decor, ramene aux pixels de l'ecran. */
    private fun zone(z: Decor.Zone): RectF = RectF(
        decX + z.x * largeurMur,
        z.y * hauteurMur,
        decX + (z.x + z.l) * largeurMur,
        (z.y + z.h) * hauteurMur
    )

    private fun dessinerBureau(c: Canvas) {
        // le tiroir coulisse
        if (tiroir > 0f) {
            val t = zone(Decor.TIROIR)
            val avance = t.height() * 0.55f * tiroir
            c.drawRect(t.left, t.top + avance, t.right, t.bottom + avance, ombre)
            charger("cahier.webp")?.let { cahier ->
                val l = t.width() * 1.1f
                val hh = l * cahier.height / cahier.width
                val cx = t.centerX()
                val cy = t.top + avance - hh * 0.55f * tiroir
                c.drawBitmap(cahier, null,
                    RectF(cx - l / 2, cy - hh / 2, cx + l / 2, cy + hh / 2), peinture)
            }
            charger("cartes.webp")?.let { cartes ->
                val l = t.width() * 0.5f
                val hh = l * cartes.height / cartes.width
                val cx = t.right + t.width() * 0.05f
                val cy = t.top + avance - hh * 0.35f * tiroir
                c.drawBitmap(cartes, null,
                    RectF(cx - l / 2, cy - hh / 2, cx + l / 2, cy + hh / 2), peinture)
            }
        }

        // la console posee sur le bureau
        if (consoleIdx >= 0 && sortie > 0f) {
            val console = Decor.CONSOLES[consoleIdx]
            charger(console.image)?.let { img ->
                val p = zone(Decor.POSE)
                val depart = zone(Decor.CARTON)
                val t = sortie
                val cx = depart.centerX() + (p.centerX() - depart.centerX()) * t
                val arc = -p.height() * 0.9f * (t * (1 - t) * 4f)      // un saut, puis la pose
                val cy = depart.centerY() + (p.centerY() - depart.centerY()) * t + arc
                val l = p.width() * (0.72f + 0.28f * t)
                val hh = l * img.height / img.width
                c.drawBitmap(img, null,
                    RectF(cx - l / 2, cy - hh * 0.8f, cx + l / 2, cy + hh * 0.2f), peinture)
            }
        }

        // le carton tressaille quand on le touche
        if (tremble > 0f) {
            val b = zone(Decor.CARTON)
            c.save()
            c.rotate(tremble * 3f, b.centerX(), b.centerY())
            c.restore()
        }

        // les portes du meuble
        if (porteG > 0f || porteD > 0f) dessinerPortes(c)
    }

    private val cam = Camera()
    private val mat = Matrix()

    /** Deux battants qui pivotent, dessines en perspective. */
    private fun dessinerPortes(c: Canvas) {
        val m = zone(Decor.MEUBLE)
        val demi = m.width() / 2f
        for (cote in 0..1) {
            val ouv = if (cote == 0) porteG else porteD
            if (ouv <= 0f) continue
            val gauche = if (cote == 0) m.left else m.left + demi
            val angle = 110f * ouv * (if (cote == 0) -1f else 1f)
            c.save()
            cam.save()
            cam.setLocation(0f, 0f, -8f * resources.displayMetrics.density * 25f)
            cam.rotateY(angle)
            cam.getMatrix(mat)
            cam.restore()
            val pivotX = if (cote == 0) gauche else gauche + demi
            mat.preTranslate(-pivotX, -m.centerY())
            mat.postTranslate(pivotX, m.centerY())
            c.concat(mat)
            val porte = RectF(gauche, m.top, gauche + demi, m.bottom)
            c.drawRect(porte, boisFonce)
            c.drawRect(porte.left + demi * .08f, porte.top + m.height() * .06f,
                       porte.right - demi * .08f, porte.bottom - m.height() * .06f, boisClair)
            c.drawCircle(if (cote == 0) porte.right - demi * .12f else porte.left + demi * .12f,
                         porte.centerY(), demi * .045f, laiton)
            c.restore()
        }
    }

    private val boisFonce = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5F3319.toInt() }
    private val boisClair = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7A4526.toInt() }
    private val laiton    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC8922F.toInt() }


    // ================= l'intro =================

    /** Ou en est l'ouverture : 0 avant, 1 pendant, 2 la chambre. */
    private var etape = 0
    private var tIntro = 0f              // 0 a 1 sur toute la scene
    private var secousse = 0f
    private val bouffees = ArrayList<FloatArray>()   // x, y, taille, retard
    private var voile = 0f
    var surEtapeIntro: ((String) -> Unit)? = null

    private val degrade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blanc = Paint()

    /** Lance l'ouverture du carton. Trois temps : on approche, ca s'ouvre, tout blanchit. */
    fun jouerIntro() {
        etape = 1; tIntro = 0f; voile = 0f
        bouffees.clear()
        val a = android.animation.ValueAnimator.ofFloat(0f, 1f)
        a.duration = 7200
        a.interpolator = android.view.animation.LinearInterpolator()
        a.addUpdateListener {
            tIntro = it.animatedValue as Float
            // le carton tressaille juste avant de s'ouvrir
            secousse = if (tIntro > .52f && tIntro < .62f)
                kotlin.math.sin(tIntro * 320f) * (1f - (tIntro - .52f) / .10f) * 2.4f else 0f
            if (tIntro > .60f && bouffees.isEmpty()) {
                surEtapeIntro?.invoke("ouverture")
                sortirLesConsoles()
            }
            if (tIntro > .74f) {
                val u = ((tIntro - .74f) / .26f).coerceIn(0f, 1f)
                voile = if (u < .45f) u / .45f else (1f - (u - .45f) / .55f).coerceAtLeast(0f)
                if (u > .45f && etape == 1) { etape = 2; surEtapeIntro?.invoke("chambre") }
            }
            invalidate()
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(an: android.animation.Animator) {
                etape = 2; voile = 0f; bouffees.clear(); invalidate()
            }
        })
        a.start()
    }

    /** Seize bouffees de fumee, chacune avec sa direction et son retard. */
    private fun sortirLesConsoles() {
        for (i in 0 until 16) {
            bouffees.add(floatArrayOf(
                (Math.random().toFloat() - .5f) * 1.1f,   // direction horizontale
                (Math.random().toFloat() - .5f) * .8f,    // direction verticale
                .55f + Math.random().toFloat() * .8f,     // taille
                i * 0.022f                                // retard
            ))
        }
    }

    /** Le premier decor : la chambre de nuit, vue de loin, puis on approche du carton. */
    private fun dessinerIntro(c: Canvas) {
        val fond = charger("room.jpg") ?: return
        val t = tIntro
        // travelling : on part de la gauche et on se rapproche du bureau
        val zoom = 1.12f + 0.34f * lisser(min(1f, t / .62f))
        val lFond = width * zoom * 1.25f
        val hFond = lFond * fond.height / fond.width
        val x = -(lFond - width) * (0.18f + 0.62f * lisser(min(1f, t / .62f)))
        val y = -(hFond - height) * 0.52f
        c.save()
        if (secousse != 0f) c.rotate(secousse, width / 2f, height / 2f)
        c.drawBitmap(fond, null, RectF(x, y, x + lFond, y + hFond), peinture)
        c.restore()

        // les consoles jaillissent du carton
        if (t > .60f) {
            val u = ((t - .60f) / .28f).coerceIn(0f, 1f)
            val cx = width * .52f
            val cy = height * .55f
            for ((i, console) in Decor.CONSOLES.withIndex()) {
                val img = charger(console.image) ?: continue
                val retard = i * .045f
                val p = ((u - retard) / (1f - retard)).coerceIn(0f, 1f)
                if (p <= 0f) continue
                val angle = (i.toFloat() / Decor.CONSOLES.size) * 6.2832f
                val d = width * .62f * lisser(p)
                val px = cx + kotlin.math.cos(angle) * d
                val py = cy + kotlin.math.sin(angle) * d * .7f - height * .12f * p
                val l = width * .17f * (0.35f + 0.65f * p)
                val hh = l * img.height / img.width
                peinture.alpha = (255 * (1f - p * .35f)).toInt()
                c.drawBitmap(img, null, RectF(px - l/2, py - hh/2, px + l/2, py + hh/2), peinture)
                peinture.alpha = 255
            }
        }

        // la fumee : des bouffees rondes aux bords fondus, puis un voile blanc
        if (bouffees.isNotEmpty()) {
            val u = ((t - .60f) / .40f).coerceIn(0f, 1f)
            for (b in bouffees) {
                val p = ((u - b[3]) / (1f - b[3])).coerceIn(0f, 1f)
                if (p <= 0f) continue
                val taille = width * b[2] * (0.25f + 1.15f * p)
                val px = width * .5f + b[0] * width * .5f * p
                val py = height * .55f + b[1] * height * .4f * p - height * .08f * p
                val opacite = (255 * (if (p < .25f) p / .25f else (1f - (p - .25f) / .75f))).toInt()
                degrade.shader = RadialGradient(px, py, taille,
                    intArrayOf(Color.WHITE, 0x66FFFFFF, 0x00FFFFFF),
                    floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
                degrade.alpha = opacite.coerceIn(0, 255)
                c.drawCircle(px, py, taille, degrade)
            }
            degrade.shader = null
        }
        if (voile > 0f) {
            blanc.color = Color.WHITE
            blanc.alpha = (255 * voile.coerceIn(0f, 1f)).toInt()
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), blanc)
        }
    }

    private fun lisser(t: Float) = t * t * (3f - 2f * t)

    // ================= le doigt =================

    private var xDepart = 0f
    private var yDepart = 0f
    private var xPrec = 0f
    private var bouge = false
    private var tempsDepart = 0L

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (etape == 1) return true                     // l'intro se joue, on laisse faire
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                xDepart = e.x; yDepart = e.y; xPrec = e.x; bouge = false
                tempsDepart = System.currentTimeMillis()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - xPrec
                xPrec = e.x
                if (abs(e.x - xDepart) > 12f || abs(e.y - yDepart) > 12f) bouge = true
                val avant = decX
                decX = (decX + dx * 1.6f).coerceIn(min(0f, width - largeurMur), 0f)
                if (!enPivot && abs(decX - avant) < 0.5f && abs(dx) > 2f) {
                    // en butee : le regard passe au mur suivant
                    pivoter(if (dx < 0) 1 else -1)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!bouge) toucher(e.x, e.y)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun pivoter(sens: Int) {
        enPivot = true
        vue = (vue + sens + 3) % 3
        recalculer()
        decX = if (sens > 0) 0f else min(0f, width - largeurMur)
        // un voile bref, comme une tete qui tourne vite
        alpha = 0.35f
        animate().alpha(1f).setDuration(220).withEndAction { enPivot = false }.start()
        invalidate()
    }

    private fun toucher(x: Float, y: Float) {
        if (vue == 0) {
            if (zone(Decor.CARTON).contains(x, y))  { surObjet?.invoke("carton"); return }
            if (zone(Decor.RADIO).contains(x, y))   { surObjet?.invoke("radio"); return }
            if (zone(Decor.TIROIR).contains(x, y))  { surObjet?.invoke("tiroir"); return }
            if (zone(Decor.MEUBLE).contains(x, y))  {
                val m = zone(Decor.MEUBLE)
                surObjet?.invoke(if (x < m.centerX()) "porteG" else "porteD"); return
            }
            if (zone(Decor.TELE).contains(x, y))    { surObjet?.invoke("tele"); return }
        } else if (vue == 1) {
            if (zone(Decor.SERRURE).contains(x, y)) { surObjet?.invoke("serrure"); return }
            if (zone(Decor.LIVRE).contains(x, y))   { surObjet?.invoke("livre"); return }
        }
    }

    // ================= animations =================

    private fun anime(duree: Long, surPas: (Float) -> Unit, fin: (() -> Unit)? = null) {
        val a = android.animation.ValueAnimator.ofFloat(0f, 1f)
        a.duration = duree
        a.addUpdateListener { surPas(it.animatedValue as Float); invalidate() }
        if (fin != null) a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(an: android.animation.Animator) { fin() }
        })
        a.start()
    }

    /** La console suivante sort du carton et se pose sur le bureau. */
    fun consoleSuivante(fin: (Decor.ConsolePosee) -> Unit) {
        if (consoleIdx >= 0) {
            anime(420, { t -> sortie = 1f - t }, {
                consoleIdx = (consoleIdx + 1) % Decor.CONSOLES.size
                anime(700, { t -> sortie = t }, { fin(Decor.CONSOLES[consoleIdx]) })
            })
        } else {
            consoleIdx = 0
            anime(700, { t -> sortie = t }, { fin(Decor.CONSOLES[consoleIdx]) })
        }
    }

    fun ouvrirTiroir(ouvert: Boolean, fin: (() -> Unit)? = null) {
        val depart = tiroir
        anime(520, { t -> tiroir = depart + ((if (ouvert) 1f else 0f) - depart) * t }, fin)
    }

    fun bougerPorte(gauche: Boolean, ouverte: Boolean, fin: (() -> Unit)? = null) {
        val depart = if (gauche) porteG else porteD
        val cible = if (ouverte) 1f else 0f
        anime(900, { t ->
            val v = depart + (cible - depart) * t
            if (gauche) porteG = v else porteD = v
        }, fin)
    }

    /** La console posee, s'il y en a une. */
    fun consolePosee(): Decor.ConsolePosee? =
        if (consoleIdx >= 0 && sortie > 0.5f) Decor.CONSOLES[consoleIdx] else null

    fun vueCourante(): String = Decor.VUES[vue]
}
