package com.rudy.chambre.balle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Les commandes de Rudy, reprises de sa page au point pres.
 *
 * Son manche a balai en bas a gauche — un rond de 126 points avec un bouton de
 * 56 — et ses quatre touches rondes de 62 points, aux memes places et aux memes
 * couleurs : esquive, attraper, passe, tir.
 */
class Commandes(ctx: Context, private val partie: Partie) : View(ctx) {

    var surEsquive: (() -> Unit)? = null
    var surAttraper: (() -> Unit)? = null
    var surPasse: (() -> Unit)? = null
    var surTirDebut: (() -> Unit)? = null
    var surTirFin: (() -> Unit)? = null

    private val finesse get() = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val texte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        color = Color.WHITE
    }

    /** Une touche : son centre en points, son rayon, sa couleur, son nom. */
    private class Touche(val cx: Float, val cy: Float, val r: Float,
                         val couleur: Int, val nom: String, val action: String)

    private var touches = listOf<Touche>()
    private var joyCX = 0f; private var joyCY = 0f
    private val joyR = 63f          // 126 points de diametre
    private val stickR = 28f        // 56 points de diametre

    private var doigtJoy = -1
    private var stickX = 0f; private var stickY = 0f
    private var doigtTir = -1

    override fun onSizeChanged(l: Int, h: Int, al: Int, ah: Int) {
        super.onSizeChanged(l, h, al, ah)
        val W = l / finesse; val H = h / finesse
        // ses positions : left/bottom et right/bottom, en points
        joyCX = 18f + joyR; joyCY = H - 17f - joyR
        stickX = joyCX; stickY = joyCY
        val r = 31f     // 62 points de diametre
        touches = listOf(
            Touche(W - 22f - r, H - 25f - r, r, 0xFFA92B31.toInt(), "TIR", "tir"),
            Touche(W - 22f - r, H - 142f - r, r, 0xFF25864C.toInt(), "ATTRAPER", "attraper"),
            Touche(W - 126f - r, H - 110f - r, r, 0xFF285F96.toInt(), "ESQUIVE", "esquive"),
            Touche(W - 124f - r, H - 36f - r, r, 0xFF7B3FA0.toInt(), "PASSE", "passe")
        )
    }

    override fun onDraw(c: Canvas) {
        c.save(); c.scale(finesse, finesse)

        // le manche a balai
        p.style = Paint.Style.FILL; p.color = 0x8807111C.toInt()
        c.drawCircle(joyCX, joyCY, joyR, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0x70FFFFFF
        c.drawCircle(joyCX, joyCY, joyR, p)
        p.style = Paint.Style.FILL; p.color = 0xDDEDF3F5.toInt()
        c.drawCircle(stickX, stickY, stickR, p)

        // les quatre touches, a demi transparentes comme chez lui
        for (t in touches) {
            p.style = Paint.Style.FILL
            p.color = t.couleur; p.alpha = 148            // opacite .58
            c.drawCircle(t.cx, t.cy, t.r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f
            p.color = Color.WHITE; p.alpha = 148
            c.drawCircle(t.cx, t.cy, t.r, p)
            p.alpha = 255
            texte.textSize = 9f
            texte.alpha = 220
            val libelle = if (t.action == "tir") texteTir else t.nom
            c.drawText(libelle, t.cx, t.cy + 3f, texte)
        }
        c.restore()
    }

    /** Le libelle du tir change pendant la charge : TIR, TIR 1/3, FEU ! */
    var texteTir = "TIR"
        set(v) { field = v; invalidate() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        val x = e.getX(i) / finesse; val y = e.getY(i) / finesse

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (hypot(x - joyCX, y - joyCY) <= joyR + 20f && doigtJoy < 0) {
                    doigtJoy = e.getPointerId(i); bougerStick(x, y)
                } else {
                    val t = touches.firstOrNull { hypot(x - it.cx, y - it.cy) <= it.r + 6f }
                    when (t?.action) {
                        "esquive" -> surEsquive?.invoke()
                        "attraper" -> surAttraper?.invoke()
                        "passe" -> surPasse?.invoke()
                        "tir" -> { doigtTir = e.getPointerId(i); surTirDebut?.invoke() }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (doigtJoy >= 0) {
                    val k = e.findPointerIndex(doigtJoy)
                    if (k >= 0) bougerStick(e.getX(k) / finesse, e.getY(k) / finesse)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = e.getPointerId(i)
                if (id == doigtJoy) { doigtJoy = -1; lacherStick() }
                if (id == doigtTir) { doigtTir = -1; surTirFin?.invoke() }
            }
        }
        return true
    }

    private fun bougerStick(x: Float, y: Float) {
        var dx = x - joyCX; var dy = y - joyCY
        val l = hypot(dx, dy)
        val max = joyR - stickR
        if (l > max) { dx = dx / l * max; dy = dy / l * max }
        stickX = joyCX + dx; stickY = joyCY + dy
        // son jx et jy vont de -1 a 1
        partie.jx = dx / max; partie.jy = dy / max
        invalidate()
    }

    private fun lacherStick() {
        stickX = joyCX; stickY = joyCY
        partie.jx = 0f; partie.jy = 0f
        invalidate()
    }
}
