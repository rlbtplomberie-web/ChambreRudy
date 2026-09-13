package com.rudy.chambre.basket

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.hypot
import kotlin.math.min

/**
 * L'ecran du jeu de paniers : le terrain, la jauge d'appui, le bouton
 * POSITION / TIR et le compteur, disposes comme dans sa page.
 */
class BasketActivity : ComponentActivity() {

    private lateinit var vue: VueBasket
    private lateinit var tableau: Tableau
    private val regles = Regles()
    private var son: SonBasket? = null

    override fun onCreate(etat: Bundle?) {
        super.onCreate(etat)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        son = SonBasket()
        vue = VueBasket(this)
        vue.surTir = { son?.jouer("tir") }
        vue.surPanier = {
            if (dernierResultat == "vert" || dernierResultat == "orange") son?.jouer("filet")
        }

        tableau = Tableau(this)
        val racine = FrameLayout(this)
        racine.setBackgroundColor(Color.BLACK)
        racine.addView(vue, FrameLayout.LayoutParams(-1, -1))
        racine.addView(tableau, FrameLayout.LayoutParams(-1, -1))
        setContentView(racine)
        com.rudy.chambre.Ambiance.adoucirLaMusique()
        // son affiche d'avant-match : le jeu ne demarre qu'au bouton
        com.rudy.chambre.Ambiance.affiche(this, racine, "basket/affiche.jpg") {}
    }

    private var dernierResultat = ""
    private var message = ""
    private var messageT = 0f

    /** Le tableau de bord : jauge, bouton, compteur et messages. */
    inner class Tableau(ctx: android.content.Context) : View(ctx) {

        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val texte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val finesse get() = resources.displayMetrics.density
        private var dernier = 0L

        // le cadre 16/9, comme dans la vue
        private var cx = 0f; private var cy = 0f; private var cl = 0f; private var ch = 0f
        private fun cadre() {
            cl = min(width.toFloat(), height * 16f / 9f)
            ch = min(height.toFloat(), width * 9f / 16f)
            cx = (width - cl) / 2f; cy = (height - ch) / 2f
        }
        private fun px(v: Float) = cx + cl * v / 100f
        private fun py(v: Float) = cy + ch * v / 100f

        // le bouton de tir : 104 points, a 4 % et 6 % du coin bas droit
        private fun boutonCX() = px(100f) - 4f * cl / 100f - 52f * finesse
        private fun boutonCY() = py(100f) - 6f * ch / 100f - 52f * finesse
        private fun boutonR() = 52f * finesse

        override fun onDraw(c: Canvas) {
            val maintenant = System.nanoTime()
            val dt = if (dernier == 0L) 0f else ((maintenant - dernier) / 1_000_000_000.0).toFloat()
            dernier = maintenant
            cadre()
            regles.avancer(min(dt, .05f))
            if (messageT > 0f) messageT -= dt

            compteur(c)
            if (regles.vise) jauge(c)
            bouton(c)
            if (messageT > 0f) messageEcran(c)
            invalidate()
        }

        /** Son compteur : essais et paniers, en haut a gauche. */
        private fun compteur(c: Canvas) {
            texte.textSize = 15f * finesse
            texte.textAlign = Paint.Align.LEFT
            val libelle = "LANCERS ${regles.essais}/10     PANIERS ${regles.paniers}/7"
            val l = texte.measureText(libelle)
            p.color = 0xCC0A0F18.toInt()
            c.drawRoundRect(RectF(px(3f) - 10f, py(4f) - 22f * finesse,
                px(3f) + l + 10f, py(4f) + 8f * finesse), 10f, 10f, p)
            texte.color = Color.WHITE
            c.drawText(libelle, px(3f), py(4f), texte)
            texte.textAlign = Paint.Align.CENTER
        }

        /**
         * Sa jauge : rouge fonce, rouge clair, orange, vert au centre, puis en
         * miroir — et l'aiguille qui la parcourt.
         */
        private fun jauge(c: Canvas) {
            val q = regles.jauge()
            val l = cl * .52f
            val h = 25f * finesse
            val x0 = cx + (cl - l) / 2f
            val y0 = py(88f)

            fun bande(de: Float, a: Float, couleur: Int) {
                if (a <= de) return
                p.color = couleur
                c.drawRect(x0 + l * de / 100f, y0, x0 + l * a / 100f, y0 + h, p)
            }
            val a = (q.g - q.gs / 2f).coerceAtLeast(0f)
            val z = (q.g + q.gs / 2f).coerceAtMost(100f)
            val o1 = (a - q.o).coerceAtLeast(0f); val o2 = (z + q.o).coerceAtMost(100f)
            val r1 = (o1 - q.lr).coerceAtLeast(0f); val r2 = (o2 + q.lr).coerceAtMost(100f)

            bande(0f, r1, 0xFF760000.toInt())
            bande(r1, o1, 0xFFE13B2D.toInt())
            bande(o1, a, 0xFFEF8A16.toInt())
            bande(a, z, 0xFF27B84A.toInt())
            bande(z, o2, 0xFFEF8A16.toInt())
            bande(o2, r2, 0xFFE13B2D.toInt())
            bande(r2, 100f, 0xFF760000.toInt())

            p.style = Paint.Style.STROKE; p.strokeWidth = 3f * finesse
            p.color = 0xEBFFFFFF.toInt()
            c.drawRoundRect(RectF(x0, y0, x0 + l, y0 + h), 14f, 14f, p)
            p.style = Paint.Style.FILL

            // l'aiguille
            val ax = x0 + l * regles.position / 100f
            p.color = Color.WHITE
            c.drawRect(ax - 2f * finesse, y0 - 5f * finesse, ax + 2f * finesse, y0 + h + 5f * finesse, p)

            texte.textSize = 13f * finesse; texte.color = 0xFFFFE9A8.toInt()
            c.drawText("APPUIE SUR TIR", cx + cl / 2f, y0 - 12f * finesse, texte)
        }

        /** Son bouton rond : POSITION, puis TIR pendant la visee. */
        private fun bouton(c: Canvas) {
            val r = boutonR()
            p.color = 0xFFB51F28.toInt()
            c.drawCircle(boutonCX(), boutonCY(), r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 4f * finesse; p.color = 0xCCFFFFFF.toInt()
            c.drawCircle(boutonCX(), boutonCY(), r, p)
            p.style = Paint.Style.FILL
            texte.textSize = 18f * finesse; texte.color = Color.WHITE
            c.drawText(if (regles.vise) "TIR" else "POSITION",
                boutonCX(), boutonCY() + 6f * finesse, texte)
        }

        private fun messageEcran(c: Canvas) {
            texte.textSize = 30f * finesse
            texte.style = Paint.Style.STROKE; texte.strokeWidth = 6f; texte.color = 0xCC000000.toInt()
            c.drawText(message, cx + cl / 2f, py(24f), texte)
            texte.style = Paint.Style.FILL; texte.color = 0xFFFFE52B.toInt()
            c.drawText(message, cx + cl / 2f, py(24f), texte)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
            if (hypot(e.x - boutonCX(), e.y - boutonCY()) > boutonR() + 8f) return true
            if (regles.finie) return true

            if (!regles.vise) {
                if (vue.enDribble()) regles.commencerAViser()
            } else {
                val resultat = regles.tirer()
                dernierResultat = resultat
                vue.tirer()
                postDelayed({
                    message = regles.message(resultat); messageT = .95f
                    son?.jouer(if (resultat == "vert" || resultat == "orange") "panier" else "rate")
                    if (regles.finie) postDelayed({ finDePartie() }, 650)
                }, 950)
            }
            return true
        }
    }

    private fun finDePartie() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(regles.titreFin())
            .setMessage(regles.texteFin())
            .setPositiveButton("Rejouer") { _, _ -> regles.rejouer() }
            .setNegativeButton("Retour au bureau") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    override fun onDestroy() { com.rudy.chambre.Ambiance.rendreLaMusique(); son?.liberer(); super.onDestroy() }
}
