package com.rudy.chambre.basket

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * Le jeu de paniers de Rudy, traduit de sa page.
 *
 * Sa scene est un cadre 16/9 centre a l'ecran : toutes ses mesures sont des
 * pourcentages de ce cadre, et on les garde telles quelles.
 */
class VueBasket(ctx: Context) : View(ctx) {

    var surTir: (() -> Unit)? = null
    var surPanier: (() -> Unit)? = null

    private val images = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    private val pinceau = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---- ses mesures, en pourcentages du cadre ----
    private val joueurGauche = 20f; private val joueurBas = 7f
    private val joueurLargeur = 28f; private val joueurHauteur = 78f
    // l'anneau orange du panier du fond, releve sur son image de terrain :
    // centre a 78,5 % de la largeur et 27 % de la hauteur. Notre panier se
    // pose exactement dessus, sur le cercle rouge.
    private val panierDroite = 17.2f; private val panierHaut = 25.5f
    private val panierLargeur = 8.5f; private val panierHauteur = 15f
    private val departBalle = 44f to 35f

    /** Le ballon dans les mains, sur les quatre premieres images du tir. */
    private val balleEnMain = arrayOf(53f to 36f, 53f to 33.5f, 53f to 31f, 53f to 28.5f)
    private val tailleBalleEnMain = 17f       // en pourcentage du cadre du joueur

    // ---- l'etat ----
    private var mode = "dribble"
    private var imageJoueur = 0
    private var tempsImage = 0f
    private var iDribble = 0
    private val suiteDribble = intArrayOf(0,1,2,3,4,5,6,7,0,1,2,3,4,5,6,7)
    private var pauseDribble = 0f
    private var iTir = 0
    private var volT = -1f          // temps de vol du ballon, negatif = pas de vol
    // d'ou le ballon quitte les mains : la derniere position tenue
    private var departVolX = -1f; private var departVolY = -1f
    private var chuteT = -1f
    private var filetT = -1f        // l'agitation du filet apres le panier
    private var dernier = 0L

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        Thread {
            charger("terrain.webp")
            for (i in 0 until 8) charger("dribble_$i.webp")
            for (i in 0 until 7) charger("tir_$i.webp")
            post { invalidate() }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun charger(nom: String): Bitmap? {
        images[nom]?.let { return it }
        return try {
            context.assets.open("basket/$nom").use {
                BitmapFactory.decodeStream(it)?.also { b -> images[nom] = b }
            }
        } catch (_: Throwable) { null }
    }

    // ---- le cadre 16/9 centre, comme son « #fit » ----
    private var cadreX = 0f; private var cadreY = 0f
    private var cadreL = 0f; private var cadreH = 0f

    private fun calculerCadre() {
        val l = width.toFloat(); val h = height.toFloat()
        cadreL = min(l, h * 16f / 9f)
        cadreH = min(h, l * 9f / 16f)
        cadreX = (l - cadreL) / 2f
        cadreY = (h - cadreH) / 2f
    }

    private fun px(pourcent: Float) = cadreX + cadreL * pourcent / 100f
    private fun py(pourcent: Float) = cadreY + cadreH * pourcent / 100f

    override fun onDraw(c: Canvas) {
        val maintenant = System.nanoTime()
        val dt = if (dernier == 0L) 0f else ((maintenant - dernier) / 1_000_000_000.0).toFloat()
        dernier = maintenant
        calculerCadre()
        avancer(min(dt, .05f))

        c.drawColor(Color.BLACK)
        charger("terrain.webp")?.let {
            c.drawBitmap(it, null, RectF(cadreX, cadreY, cadreX + cadreL, cadreY + cadreH), pinceau)
        }
        dessinerPanier(c)
        dessinerJoueur(c)
        dessinerBalleEnVol(c)
        invalidate()
    }

    // ---- l'animation, reprise de son code ----

    private fun avancer(dt: Float) {
        tempsImage += dt
        // securite : un tir qui s'eternise ne doit jamais bloquer le jeu
        if (mode == "tir" && tempsImage > 3f) { mode = "retour"; tempsImage = 0f }
        when (mode) {
            "dribble" -> {
                if (pauseDribble > 0f) {
                    pauseDribble -= dt
                    if (pauseDribble <= 0f) { iDribble = 0; imageJoueur = 0 }
                } else if (tempsImage >= .085f) {           // 85 ms chez lui
                    tempsImage = 0f
                    imageJoueur = suiteDribble[iDribble]
                    iDribble++
                    if (iDribble >= suiteDribble.size) {
                        iDribble = 0; imageJoueur = 7; pauseDribble = 2f   // sa pause de 2 s
                    }
                }
            }
            "tir" -> if (tempsImage >= .12f) {              // 120 ms chez lui
                tempsImage = 0f
                iTir++
                if (iTir == 4) { volT = 0f; surTir?.invoke() }   // le ballon part
                if (iTir >= 7) { mode = "retour"; tempsImage = 0f }
            }
            "retour" -> if (tempsImage >= .35f) {           // ses 350 ms
                mode = "dribble"; iDribble = 0; imageJoueur = 0; tempsImage = 0f
                departVolX = -1f; departVolY = -1f
            }
        }

        if (volT >= 0f) {
            volT += dt
            if (volT >= .78f) {                              // ses 780 ms de vol
                volT = -1f; chuteT = 0f; filetT = 0f
                surPanier?.invoke()
            }
        }
        if (chuteT >= 0f) { chuteT += dt; if (chuteT >= .36f) chuteT = -1f }
        if (filetT >= 0f) { filetT += dt; if (filetT >= .62f) filetT = -1f }
    }

    fun tirer() {
        if (mode != "dribble" || pauseDribble > 0f && false) return
        mode = "tir"; iTir = 0; tempsImage = 0f; pauseDribble = 0f
    }

    fun enDribble() = mode == "dribble"

    // ---- le dessin ----

    private fun dessinerJoueur(c: Canvas) {
        val nom = if (mode == "tir") "tir_${min(iTir, 6)}.webp" else "dribble_$imageJoueur.webp"
        val im = charger(nom) ?: return
        // son cadre de joueur, et l'image dedans a 94 % de la hauteur
        val gx = px(joueurGauche)
        val gl = cadreL * joueurLargeur / 100f
        val gh = cadreH * joueurHauteur / 100f
        val gb = py(100f - joueurBas)
        // son cadre fixe : 520 sur 760, le personnage toujours a la meme
        // hauteur dedans — « une seule hauteur pour TOUTES les images »
        val cadreRapport = 520f / 760f
        var cadreHauteur = gh
        var cadreLargeur = cadreHauteur * cadreRapport
        if (cadreLargeur > gl) { cadreLargeur = gl; cadreHauteur = cadreLargeur / cadreRapport }
        val fx = gx + (gl - cadreLargeur) / 2f
        val fy = gb - cadreHauteur

        // l'image garde ses proportions et occupe 94 % de la hauteur du cadre
        val hauteur = cadreHauteur * .94f
        val largeur = im.width * (hauteur / im.height)
        val x = fx + (cadreLargeur - largeur) / 2f
        val y = fy + cadreHauteur - hauteur
        c.drawBitmap(im, null, RectF(x, y, x + largeur, y + hauteur), pinceau)

        // le ballon dans les mains : ses coordonnees sont des pourcentages
        // de ce cadre, sur les quatre premieres images du tir
        if (mode == "tir" && iTir < 4) {
            val q = balleEnMain[iTir]
            val taille = cadreLargeur * tailleBalleEnMain / 100f
            ballon(c, fx + cadreLargeur * q.first / 100f,
                      fy + cadreHauteur * q.second / 100f, taille / 2f)
            departVolX = fx + cadreLargeur * q.first / 100f
            departVolY = fy + cadreHauteur * q.second / 100f
        }
    }

    /** Son ballon : orange, avec les deux traits noirs. */
    private fun ballon(c: Canvas, cx: Float, cy: Float, r: Float) {
        p.shader = RadialGradient(cx - r * .32f, cy - r * .46f, r * 1.8f,
            intArrayOf(0xFFFFBD62.toInt(), 0xFFF28A24.toInt(), 0xFFC8580D.toInt()),
            floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = r * .10f; p.color = 0xFF2C1608.toInt()
        c.drawLine(cx - r, cy, cx + r, cy, p)
        c.drawLine(cx, cy - r, cx, cy + r, p)
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.FILL
    }

    /** Son panier : l'anneau orange et le filet blanc. */
    private fun dessinerPanier(c: Canvas) {
        val l = cadreL * panierLargeur / 100f
        val h = cadreH * panierHauteur / 100f
        val x = px(100f - panierDroite) - l
        val y = py(panierHaut)

        // l'attache au panneau
        p.color = 0xFFA63B0C.toInt()
        c.drawRect(x + l, y + h * .34f, x + l + l * .12f, y + h * .66f, p)

        // l'anneau
        p.style = Paint.Style.STROKE; p.strokeWidth = h * .055f
        p.color = 0xFFE56A20.toInt()
        c.drawOval(RectF(x, y, x + l, y + h * .17f), p)

        // le filet : des mailles en croix, qui s'agitent apres un panier
        val secousse = if (filetT >= 0f) (1f - filetT / .62f) * h * .06f else 0f
        p.strokeWidth = 1.4f; p.color = 0xF0FFFFFF.toInt()
        val profondeur = h * .62f
        for (i in 0 until 9) {
            val a = i / 8f
            val x0 = x + l * a
            val x1 = x + l * (.22f + .56f * a)
            val bas = y + h * .09f + profondeur
            c.drawLine(x0, y + h * .09f, x1 + secousse * (a - .5f) * 2f, bas, p)
            val x2 = x + l * (1f - a)
            val x3 = x + l * (.78f - .56f * a)
            c.drawLine(x2, y + h * .09f, x3 + secousse * (.5f - a) * 2f, bas, p)
        }
        p.style = Paint.Style.FILL
    }

    /** Sa trajectoire en cloche, puis la chute dans le filet. */
    private fun dessinerBalleEnVol(c: Canvas) {
        val l = cadreL * panierLargeur / 100f
        val h = cadreH * panierHauteur / 100f
        val panierCX = px(100f - panierDroite) - l / 2f
        val panierCY = py(panierHaut) + h * .12f
        val r = cadreL * .032f / 2f          // son ballon de vol fait 3,2 %

        if (volT >= 0f) {
            val q = volT / .78f
            val dx = if (departVolX > 0f) departVolX else px(departBalle.first)
            val dy = if (departVolY > 0f) departVolY else py(departBalle.second)
            val x = dx + (panierCX - dx) * q
            val yBase = dy + (panierCY - dy) * q
            val cloche = -25f * 4f * q * (1f - q) * cadreH / 100f    // son arc
            ballon(c, x, yBase + cloche, r)
        } else if (chuteT >= 0f) {
            val q = min(1f, chuteT / .28f)
            val echelle = 1f - .18f * q
            ballon(c, panierCX, panierCY + cadreH * .10f * q, r * echelle)
        }
    }
}
