package com.rudy.chambre.echecs

import android.app.AlertDialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.*
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.min

/**
 * L'ecran du jeu d'echecs : le choix de l'armee, le damier pose sur son
 * decor, et le choix de piece a la promotion.
 */
class EchecsActivity : ComponentActivity() {

    private lateinit var vue: VueEchecs
    private lateinit var etat: TextView
    private val jeu = Echiquier()
    private var son: SonEchecs? = null

    override fun onCreate(e: Bundle?) {
        super.onCreate(e)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        son = SonEchecs()
        jeu.surSon = { quoi -> son?.jouer(quoi) }
        jeu.commencer("H")

        vue = VueEchecs(this, jeu)
        vue.surCoup = { apresLeCoup() }

        etat = TextView(this).apply {
            textSize = 15f; setTextColor(0xFFF3E2B8.toInt())
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            text = jeu.etat
        }
        val racine = FrameLayout(this)
        racine.setBackgroundColor(Color.BLACK)
        racine.addView(vue, FrameLayout.LayoutParams(-1, -1))
        racine.addView(etat, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = 24 })
        racine.addView(Button(this).apply {
            text = "← Bureau"; textSize = 12f
            setTextColor(Color.WHITE); setBackgroundColor(0xCC150F24.toInt())
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
            .apply { topMargin = 18; leftMargin = 18 })
        racine.addView(Button(this).apply {
            text = "Nouvelle partie"; textSize = 12f
            setTextColor(Color.WHITE); setBackgroundColor(0xAA2B1B3A.toInt())
            setOnClickListener { choisirArmee() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
            .apply { topMargin = 18; rightMargin = 18 })
        setContentView(racine)

        choisirArmee()
    }

    /** Son ecran de depart : Hyrule ou Ganondorf. */
    private fun choisirArmee() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Choisis ton armée")
            .setItems(arrayOf("HYRULE", "GANONDORF")) { _, i ->
                jeu.commencer(if (i == 0) "H" else "G")
                etat.text = jeu.etat
                vue.invalidate()
            }
            .setCancelable(false)
            .show()
    }

    private fun apresLeCoup() {
        etat.text = jeu.etat
        vue.invalidate()
        if (jeu.promotion != null) { choisirPromotion(); return }
        if (jeu.occupe && !jeu.finie) vue.postDelayed({ tourDeLOrdinateur() }, 500)
    }

    private fun tourDeLOrdinateur() {
        val m = jeu.coupDeLOrdinateur()
        if (m == null) { etat.text = jeu.etat; vue.invalidate(); return }
        jeu.jouer(m)
        etat.text = jeu.etat
        vue.invalidate()
    }

    /** Sa promotion : le joueur choisit sa piece. */
    private fun choisirPromotion() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Promotion")
            .setItems(arrayOf("Dame", "Tour", "Fou", "Cavalier")) { _, i ->
                jeu.promouvoir(charArrayOf('q', 'r', 'b', 'n')[i])
                etat.text = jeu.etat
                vue.invalidate()
                if (jeu.occupe && !jeu.finie) vue.postDelayed({ tourDeLOrdinateur() }, 500)
            }
            .setCancelable(false)
            .show()
    }

    override fun onDestroy() { son?.liberer(); super.onDestroy() }
}

/**
 * L'affichage : son decor, et la grille posee dessus a 8,80 % de la gauche et
 * 22,66 % du haut, sur 82,20 par 47 pour cent.
 */
class VueEchecs(ctx: Context, private val jeu: Echiquier) : View(ctx) {

    var surCoup: (() -> Unit)? = null

    private val images = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    private val pinceau = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private var fx = 0f; private var fy = 0f; private var fl = 0f; private var fh = 0f

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        Thread {
            charger("fond.webp")
            for (camp in listOf("h", "g")) for (genre in listOf("pawn", "rook", "knight", "bishop", "queen", "king"))
                for (face in listOf("front", "back")) charger("${camp}_${genre}_$face.webp")
            post { invalidate() }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun charger(nom: String): Bitmap? {
        images[nom]?.let { return it }
        return try {
            context.assets.open("echecs/$nom").use {
                BitmapFactory.decodeStream(it)?.also { b -> images[nom] = b }
            }
        } catch (_: Throwable) { null }
    }

    /** Le decor garde ses proportions : c'est le repere de ses pourcentages. */
    private fun cadre() {
        val bg = charger("fond.webp") ?: return
        val s = min(width.toFloat() / bg.width, height.toFloat() / bg.height)
        fl = bg.width * s; fh = bg.height * s
        fx = (width - fl) / 2f; fy = (height - fh) / 2f
    }

    // la grille, dans le decor
    private fun grilleX() = fx + fl * .0880f
    private fun grilleY() = fy + fh * .2266f
    private fun grilleL() = fl * .8220f
    private fun grilleH() = fh * .4700f
    private fun caseL() = grilleL() / 8f
    private fun caseH() = grilleH() / 8f

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        cadre()
        charger("fond.webp")?.let {
            c.drawBitmap(it, null, RectF(fx, fy, fx + fl, fy + fh), pinceau)
        }

        // la case choisie et les coups possibles
        jeu.choisie?.let { (r, cc) ->
            p.style = Paint.Style.FILL; p.color = 0x29FFD746
            c.drawRect(grilleX() + cc * caseL(), grilleY() + r * caseH(),
                grilleX() + (cc + 1) * caseL(), grilleY() + (r + 1) * caseH(), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 3f
            p.color = 0xF2FFD746.toInt()
            c.drawRect(grilleX() + cc * caseL(), grilleY() + r * caseH(),
                grilleX() + (cc + 1) * caseL(), grilleY() + (r + 1) * caseH(), p)
            p.style = Paint.Style.FILL
        }
        for (m in jeu.possibles) {
            p.color = 0xD9FFE16E.toInt()
            c.drawCircle(grilleX() + (m.c + .5f) * caseL(),
                grilleY() + (m.r + .5f) * caseH(), caseL() * .09f, p)
        }

        // les pieces : elles sont un peu plus hautes que leur case, comme chez lui
        for (r in 0 until 8) for (cc in 0 until 8) {
            val piece = jeu.pos[r][cc] ?: continue
            val im = charger(jeu.imageDe(piece)) ?: continue
            val l = grilleL() * .094f
            val h = grilleH() * .104f
            val cx = grilleX() + (cc + .5f) * caseL()
            val cy = grilleY() + (r + .5f) * caseH()
            c.drawBitmap(im, null,
                RectF(cx - l / 2f, cy - h * .57f, cx + l / 2f, cy + h * .43f), pinceau)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
        val cc = ((e.x - grilleX()) / caseL()).toInt()
        val r = ((e.y - grilleY()) / caseH()).toInt()
        if (r !in 0..7 || cc !in 0..7) return true
        if (jeu.toucher(r, cc)) { invalidate(); surCoup?.invoke() }
        return true
    }
}
