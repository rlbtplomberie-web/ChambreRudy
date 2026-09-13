package com.rudy.chambre.dames

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.view.Gravity
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.hypot
import kotlin.math.min

/**
 * Le jeu de dames de Rudy, en natif : son damier, ses lunes contre Majora.
 */
class DamesActivity : ComponentActivity() {

    private lateinit var vue: VueDames
    private lateinit var etat: TextView
    private val jeu = Dames()
    private var son: SonDames? = null

    override fun onCreate(e: Bundle?) {
        super.onCreate(e)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        son = SonDames()
        jeu.surSon = { quoi -> son?.jouer(quoi) }

        vue = VueDames(this, jeu)
        vue.surCoupJoue = { apresLeJoueur() }

        etat = TextView(this).apply {
            textSize = 15f; setTextColor(0xFFF3E2B8.toInt())
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            text = jeu.message
        }
        val recommencer = Button(this).apply {
            text = "Recommencer"; textSize = 12f
            setTextColor(Color.WHITE); setBackgroundColor(0xAA2B1B3A.toInt())
            setOnClickListener { jeu.remettre(); etat.text = jeu.message; vue.invalidate() }
        }
        val retour = Button(this).apply {
            text = "← Bureau"; textSize = 12f
            setTextColor(Color.WHITE); setBackgroundColor(0xCC150F24.toInt())
            setOnClickListener { finish() }
        }

        val racine = FrameLayout(this)
        racine.setBackgroundColor(Color.BLACK)
        racine.addView(vue, FrameLayout.LayoutParams(-1, -1))
        racine.addView(etat, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = 24 })
        racine.addView(retour, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
            .apply { topMargin = 18; leftMargin = 18 })
        racine.addView(recommencer, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
            .apply { topMargin = 18; rightMargin = 18 })
        setContentView(racine)
        com.rudy.chambre.Ambiance.musiqueDuJeu(this, "dames/musique_dames.webm", 1f)
    }

    /** Apres le coup du joueur : Majora reflechit, puis joue, en enchainant ses prises. */
    private fun apresLeJoueur() {
        etat.text = jeu.message
        vue.invalidate()
        if (!jeu.occupe || jeu.finie) return
        vue.postDelayed({ tourDeMajora() }, 420)
    }

    private fun tourDeMajora() {
        val m = jeu.coupDeMajora()
        vue.invalidate()
        if (m == null) { etat.text = jeu.message; return }
        enchainer(m.r, m.i, m.prise != null)
    }

    private fun enchainer(r: Int, i: Int, apresPrise: Boolean) {
        val suite = jeu.repriseDeMajora(r, i, apresPrise)
        if (suite != null) {
            vue.postDelayed({
                jeu.jouer(suite); vue.invalidate()
                enchainer(suite.r, suite.i, true)
            }, 260)
            return
        }
        jeu.finDuTourDeMajora()
        etat.text = jeu.message
        vue.invalidate()
    }

    override fun onDestroy() { com.rudy.chambre.Ambiance.rendreLaMusique(); son?.liberer(); super.onDestroy() }
}

/**
 * L'affichage : son image de damier, ses pions, et les reperes des coups
 * possibles — pastille jaune pour un deplacement, cercle rouge pour une prise.
 */
class VueDames(ctx: Context, private val jeu: Dames) : View(ctx) {

    var surCoupJoue: (() -> Unit)? = null

    private val images = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    private val pinceau = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    // le damier garde ses proportions : c'est le repere de tous ses pourcentages
    private var cx = 0f; private var cy = 0f; private var cl = 0f; private var ch = 0f

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        Thread {
            for (n in listOf("damier.webp", "lune.webp", "majora.webp", "fond.webp")) charger(n)
            post { invalidate() }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun charger(nom: String): Bitmap? {
        images[nom]?.let { return it }
        return try {
            context.assets.open("dames/$nom").use {
                BitmapFactory.decodeStream(it)?.also { b -> images[nom] = b }
            }
        } catch (_: Throwable) { null }
    }

    /**
     * Son plateau, exactement comme son « #stage » : une boite de proportion
     * 16 sur 9, centree, la plus grande qui tienne a l'ecran, puis agrandie
     * de douze pour cent. Toutes ses positions de pions sont des pourcentages
     * de cette boite, pas de l'ecran.
     */
    private fun cadre() {
        // sa boite 16/9, agrandie pour remplir l'ecran debout : le damier
        // devient nettement plus grand, et les cases gardent leurs proportions
        var l = kotlin.math.min(width.toFloat(), height * 16f / 9f)
        var h = l * 9f / 16f
        val agrandissement = kotlin.math.min(1.62f, width / (l * .68f))
        l *= agrandissement; h *= agrandissement
        cl = l; ch = h
        cx = (width - l) / 2f; cy = (height - h) / 2f
    }

    private fun px(v: Float) = cx + cl * v / 100f
    private fun py(v: Float) = cy + ch * v / 100f

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF05030A.toInt())
        cadre()
        // le decor couvre l'ecran
        charger("fond.webp")?.let {
            val s = kotlin.math.max(width.toFloat() / it.width, height.toFloat() / it.height)
            val l = it.width * s; val h = it.height * s
            c.drawBitmap(it, null, RectF((width - l) / 2f, (height - h) / 2f,
                (width + l) / 2f, (height + h) / 2f), pinceau)
        }
        // le damier est etire sur la boite, puis decoupe : 18 % en haut,
        // 16 % de chaque cote, 10 % en bas — son clip-path
        charger("damier.webp")?.let {
            c.save()
            c.clipRect(cx + cl * .16f, cy + ch * .18f, cx + cl * .84f, cy + ch * .90f)
            c.drawBitmap(it, null, RectF(cx, cy, cx + cl, cy + ch), pinceau)
            c.restore()
        }

        // les reperes des coups possibles
        for (m in jeu.coupsAffiches()) {
            val (x, y) = Plateau.RANGEES[m.r][m.i]
            if (m.prise != null) {
                p.style = Paint.Style.STROKE; p.strokeWidth = cl * .0028f
                p.color = 0xFFFF4B3E.toInt()
                c.drawCircle(px(x), py(y), cl * .019f, p)
                p.style = Paint.Style.FILL
            } else {
                p.color = 0xFFFFE05A.toInt()
                c.drawCircle(px(x), py(y), cl * .006f, p)
            }
        }

        // les pions
        for (r in 0 until 8) for (i in Plateau.RANGEES[r].indices) {
            val piece = jeu.B[r][i] ?: continue
            val (x, y) = Plateau.RANGEES[r][i]
            val im = charger(if (piece.camp == "majora") "majora.webp" else "lune.webp") ?: continue
            // ses tailles de pions : 4,35 % pour Majora, 4,48 % pour les lunes
            // ses tailles d'origine, un peu etoffees : elles restent sous la
            // largeur d'une case, les pions ne debordent donc pas
            val t = cl * (if (piece.camp == "majora") .0475f else .0490f)
            val cxp = px(x); val cyp = py(y)
            val choisie = jeu.choisie?.let { it.first == r && it.second == i } == true
            if (choisie) {
                p.color = 0x66FFE05A
                c.drawCircle(cxp, cyp, t * .8f, p)
            }
            c.drawBitmap(im, null,
                RectF(cxp - t / 2f, cyp - t / 2f, cxp + t / 2f, cyp + t / 2f), pinceau)
            // la couronne d'une dame
            if (piece.dame) {
                p.color = 0xFFFFD35A.toInt()
                p.style = Paint.Style.STROKE; p.strokeWidth = t * .08f
                c.drawCircle(cxp, cyp, t * .34f, p)
                p.style = Paint.Style.FILL
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
        // la case la plus proche du doigt, dans un rayon raisonnable
        var proche: Triple<Int, Int, Float>? = null
        for (r in 0 until 8) for (i in Plateau.RANGEES[r].indices) {
            val (x, y) = Plateau.RANGEES[r][i]
            val d = hypot(e.x - px(x), e.y - py(y))
            val actuel = proche
            if (actuel == null || d < actuel.third) proche = Triple(r, i, d)
        }
        val m = proche ?: return true
        if (m.third > cl * .045f) return true
        if (jeu.toucher(m.first, m.second)) { invalidate(); surCoupJoue?.invoke() }
        return true
    }
}
