package com.rudy.chambre.balle

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.hypot

/**
 * La balle au prisonnier de Rudy, en natif.
 *
 * L'affiche, puis le match ; son manche a balai a gauche et ses quatre
 * boutons a droite, comme dans sa page.
 */
class PartieActivity : ComponentActivity() {

    private lateinit var vue: VuePartie
    private lateinit var racine: FrameLayout
    private var son: SonBalle? = null
    private var commandes: Commandes? = null

    override fun onCreate(etat: Bundle?) {
        super.onCreate(etat)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        son = SonBalle()
        vue = VuePartie(this)
        vue.partie.surSon = { quoi, force -> son?.jouer(quoi, force) }
        vue.partie.surTexteTir = { t -> commandes?.texteTir = t }
        vue.surFin = { titre -> finDePartie(titre) }

        racine = FrameLayout(this)
        racine.setBackgroundColor(Color.BLACK)
        racine.addView(vue, FrameLayout.LayoutParams(-1, -1))
        commandes = Commandes(this, vue.partie).apply {
            surEsquive = {
                val me = vue.partie.me()
                if (me.dodge <= 0f && me.cool <= 0f) { me.dodge = .38f; son?.jouer("esquive", 1f) }
            }
            surAttraper = {
                val me = vue.partie.me()
                if (vue.partie.B.held !== me && !me.pendingJail && me.fallA <= 0f) {
                    // sa fenetre de rattrapage : un tiers de seconde
                    me.catchTry = .32f
                    son?.jouer("esquive", .7f)
                }
            }
            surPasse = {
                val partie = vue.partie
                val me = partie.me()
                if (partie.B.held === me) partie.passBall(me)
                else partie.dire("TU N'AS PAS LA BALLE")
            }
            surTirDebut = { vue.partie.charging = true; vue.partie.shotCharge = 0f }
            surTirFin = {
                val partie = vue.partie
                val me = partie.me()
                val cible = partie.ciblesDe(me).minByOrNull {
                    hypot(it.x - me.x, it.y - me.y)
                }
                if (partie.B.held === me) {
                    // s'il est encore en repos, le tir attend son tour
                    if (me.cool > 0f) {
                        partie.tirEnAttente = true
                        partie.chargeEnAttente = partie.shotCharge
                    } else {
                        partie.launch(me, cible, partie.shotCharge)
                    }
                }
                partie.charging = false; partie.shotCharge = 0f
            }
        }
        racine.addView(commandes, FrameLayout.LayoutParams(-1, -1))
        racine.addView(retour())
        setContentView(racine)
        com.rudy.chambre.Ambiance.musiqueDuJeu(this, "balle/musique_balle.webm", 0.85f)

        afficherAffiche()
    }

    /** L'affiche du match, avant le coup d'envoi. */
    private fun afficherAffiche() {
        val bloc = FrameLayout(this)
        bloc.setBackgroundColor(0xFF07060D.toInt())
        val image = try {
            assets.open("balle/affiche.jpg").use { BitmapFactory.decodeStream(it) }
        } catch (_: Throwable) { null }
        bloc.addView(ImageView(this).apply {
            if (image != null) setImageBitmap(image)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, FrameLayout.LayoutParams(-1, -1))

        bloc.addView(Button(this).apply {
            text = "COMMENCER"; textSize = 16f
            setTextColor(0xFF2A1C06.toInt())
            setBackgroundColor(0xFFF2C14E.toInt())
            setOnClickListener { racine.removeView(bloc); vue.rejouer() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (36 * resources.displayMetrics.density).toInt()
        })

        racine.addView(bloc, FrameLayout.LayoutParams(-1, -1))
    }

    private fun retour() = Button(this).apply {
        text = "← Bureau"; textSize = 12f
        setTextColor(Color.WHITE)
        setBackgroundColor(0xCC150F24.toInt())
        setOnClickListener { finish() }
        layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            topMargin = 46; leftMargin = 18
        }
    }

    private fun finDePartie(titre: String) {
        runOnUiThread {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(titre)
                .setPositiveButton("Recommencer") { _, _ -> vue.rejouer() }
                .setNegativeButton("Retour au bureau") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    override fun onDestroy() {
        com.rudy.chambre.Ambiance.rendreLaMusique()
        super.onDestroy()
    }
}
