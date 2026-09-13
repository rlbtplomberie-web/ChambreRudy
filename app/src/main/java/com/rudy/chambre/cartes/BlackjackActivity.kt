package com.rudy.chambre.cartes

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.*
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.min

/**
 * Le blackjack de Rudy : le croupier en haut, ses quatre places en bas, et
 * ses deux boutons — CARTE et RESTER.
 */
class BlackjackActivity : ComponentActivity() {

    private val jeu = Blackjack()
    private lateinit var vue: VueBlackjack
    private var son: SonCartes? = null
    private lateinit var carte: Button
    private lateinit var rester: Button
    private lateinit var distribuer: Button

    override fun onCreate(e: Bundle?) {
        super.onCreate(e)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        son = SonCartes()
        vue = VueBlackjack(this, jeu)

        carte = bouton("CARTE") {
            if (jeu.enCours && jeu.tour == 0) {
                val fini = jeu.carte()
                son?.bip(440.0, .05)
                vue.invalidate()
                if (fini) vue.postDelayed({ joueurSuivant() }, 350)
            }
        }
        rester = bouton("RESTER") {
            if (jeu.enCours && jeu.tour == 0) {
                jeu.rester(); son?.bip(250.0, .05); vue.invalidate(); joueurSuivant()
            }
        }
        distribuer = bouton("DISTRIBUER") {
            jeu.distribuer(); son?.bip(620.0, .08); vue.invalidate()
            majBoutons()
            if (jeu.joueurs[0].etat == "BLACKJACK !") vue.postDelayed({ joueurSuivant() }, 500)
        }

        val barre = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(carte); addView(rester); addView(distribuer)
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = 24 }
        }

        val racine = FrameLayout(this)
        racine.setBackgroundColor(Color.BLACK)
        racine.addView(vue, FrameLayout.LayoutParams(-1, -1))
        racine.addView(barre)
        racine.addView(bouton("← Bureau") { finish() }.apply {
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
                .apply { topMargin = 18; leftMargin = 18 }
        })
        setContentView(racine)
        majBoutons()
    }

    private fun bouton(titre: String, action: () -> Unit) = Button(this).apply {
        text = titre; textSize = 13f
        setTextColor(Color.WHITE); setBackgroundColor(0xCC1E3F2B.toInt())
        setOnClickListener { action() }
    }

    private fun majBoutons() {
        val aToi = jeu.enCours && jeu.tour == 0
        carte.isEnabled = aToi
        rester.isEnabled = aToi
        distribuer.isEnabled = !jeu.enCours
    }

    /** Son « nextPlayer » : les trois adversaires, puis le croupier. */
    private fun joueurSuivant() {
        jeu.tour++
        majBoutons()
        vue.invalidate()
        if (jeu.tour > 3) { vue.postDelayed({ tourDuCroupier() }, 380); return }
        vue.postDelayed({ coupDeLOrdinateur() }, 450)
    }

    private fun coupDeLOrdinateur() {
        val quoi = jeu.coupDeLOrdinateur()
        son?.bip(400.0, .05)
        vue.invalidate()
        if (quoi == "reste") { vue.postDelayed({ joueurSuivant() }, 450); return }
        vue.postDelayed({
            if (jeu.apresLeCoupDeLOrdinateur()) { vue.invalidate(); vue.postDelayed({ joueurSuivant() }, 450) }
            else coupDeLOrdinateur()
        }, 450)
    }

    private fun tourDuCroupier() {
        jeu.croupierDecouvert = true
        jeu.message = "Le croupier joue…"
        vue.invalidate()
        fun pas() {
            if (jeu.croupierDoitTirer()) {
                jeu.croupierTire(); son?.bip(330.0, .06); vue.invalidate()
                vue.postDelayed({ pas() }, 380)
            } else {
                jeu.conclure()
                son?.bip(if (jeu.valeur(jeu.croupier) > 21) 720.0 else 500.0, .14)
                vue.invalidate(); majBoutons()
            }
        }
        vue.postDelayed({ pas() }, 380)
    }

    override fun onDestroy() { son?.liberer(); super.onDestroy() }
}

/** Le tapis, les mains, les scores et l'etat de chacun. */
class VueBlackjack(ctx: Context, private val jeu: Blackjack) : View(ctx) {

    private val images = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    private val pinceau = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val texte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        Thread {
            charger("tapis_blackjack.webp"); charger("dos.webp")
            for (c in Paquet.couleurs) for (h in Paquet.hauteurs) charger("${c}_${h.lowercase()}.webp")
            post { invalidate() }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun charger(nom: String): Bitmap? {
        images[nom]?.let { return it }
        return try {
            context.assets.open("cartes/$nom").use {
                BitmapFactory.decodeStream(it)?.also { b -> images[nom] = b }
            }
        } catch (_: Throwable) { null }
    }

    private fun imageCarte(carte: String) =
        charger(carte.lowercase() + ".webp") ?: charger("dos.webp")

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF07130C.toInt())
        charger("tapis_blackjack.webp")?.let {
            c.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), pinceau)
        }
        val hauteurCarte = height * .22f

        // le croupier, en haut
        main(c, jeu.croupier.cartes, width / 2f, height * .22f, hauteurCarte,
             cacherLaSeconde = !jeu.croupierDecouvert && jeu.enCours)
        texte.textSize = height * .035f; texte.color = 0xFFF3E2B8.toInt()
        val scoreCroupier = if (jeu.croupierDecouvert || !jeu.enCours)
            "(${jeu.valeur(jeu.croupier)})"
        else if (jeu.croupier.cartes.isNotEmpty()) "(?)" else ""
        c.drawText("CROUPIER $scoreCroupier", width / 2f, height * .07f, texte)

        // les quatre places, en bas
        for (i in 0 until 4) {
            val cx = width * (.14f + i * .24f)
            main(c, jeu.joueurs[i].cartes, cx, height * .66f, hauteurCarte * .86f)
            texte.textSize = height * .030f
            texte.color = if (jeu.enCours && jeu.tour == i) 0xFFFFE16E.toInt() else 0xFFCFC7B7.toInt()
            val nom = if (i == 0) "TOI" else "JOUEUR ${i + 1}"
            val score = if (jeu.joueurs[i].cartes.isNotEmpty()) " (${jeu.valeur(jeu.joueurs[i])})" else ""
            c.drawText(nom + score, cx, height * .855f, texte)
            texte.textSize = height * .026f; texte.color = 0xFFFFD35A.toInt()
            c.drawText(jeu.joueurs[i].etat, cx, height * .895f, texte)
        }

        texte.textSize = height * .032f; texte.color = Color.WHITE
        c.drawText(jeu.message, width / 2f, height * .45f, texte)
    }

    /** Une main : les cartes se chevauchent legerement, comme chez lui. */
    private fun main(c: Canvas, cartes: List<String>, cx: Float, cy: Float,
                     hauteur: Float, cacherLaSeconde: Boolean = false) {
        if (cartes.isEmpty()) return
        val largeur = hauteur * .68f
        val ecart = largeur * .62f
        val total = ecart * (cartes.size - 1) + largeur
        var x = cx - total / 2f
        cartes.forEachIndexed { i, carte ->
            val im = if (cacherLaSeconde && i == 1) charger("dos.webp") else imageCarte(carte)
            if (im != null) c.drawBitmap(im, null,
                RectF(x, cy - hauteur / 2f, x + largeur, cy + hauteur / 2f), pinceau)
            x += ecart
        }
    }
}
