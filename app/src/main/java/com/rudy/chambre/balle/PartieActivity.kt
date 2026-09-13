package com.rudy.chambre.balle

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
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
    private var boutonTir: Button? = null

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
        vue.partie.surTexteTir = { t -> boutonTir?.text = t }
        vue.surFin = { titre -> finDePartie(titre) }

        racine = FrameLayout(this)
        racine.setBackgroundColor(Color.BLACK)
        racine.addView(vue, FrameLayout.LayoutParams(-1, -1))
        racine.addView(mancheABalai())
        racine.addView(boutons())
        racine.addView(retour())
        setContentView(racine)

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

    /** Le manche a balai : il apparait la ou le doigt se pose, comme chez lui. */
    private fun mancheABalai(): View {
        val zone = object : View(this) {
            private var cx = 0f; private var cy = 0f
            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { cx = e.x; cy = e.y }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.x - cx; val dy = e.y - cy
                        val l = hypot(dx, dy)
                        val rayon = 90f * resources.displayMetrics.density * .5f
                        val f = if (l > rayon) rayon / l else 1f
                        vue.partie.jx = dx * f / rayon
                        vue.partie.jy = dy * f / rayon
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        vue.partie.jx = 0f; vue.partie.jy = 0f
                    }
                }
                return true
            }
        }
        zone.layoutParams = FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * .45f).toInt(), -1, Gravity.START)
        return zone
    }

    /** Ses quatre boutons : esquive, attraper, passe, tir. */
    private fun boutons(): LinearLayout {
        fun bouton(titre: String, couleur: Int) = Button(this).apply {
            text = titre; textSize = 12f
            setTextColor(Color.WHITE); setBackgroundColor(couleur)
        }
        val esquive = bouton("ESQUIVE", 0xCC2B4B7A.toInt()).apply {
            setOnClickListener {
                val me = vue.partie.me()
                if (me.dodge <= 0f && me.cool <= 0f) { me.dodge = .38f; son?.jouer("esquive", 1f) }
            }
        }
        val attraper = bouton("ATTRAPER", 0xCC2F6B33.toInt()).apply {
            setOnClickListener { vue.partie.me().catchTry = .32f }
        }
        val passe = bouton("PASSE", 0xCC6B4A2F.toInt()).apply {
            setOnClickListener { vue.partie.passBall(vue.partie.me()) }
        }
        // le tir se charge tant qu'on garde le doigt appuye
        val tir = bouton("TIR", 0xCC8A2F2F.toInt()).apply {
            setOnTouchListener { _, e ->
                val partie = vue.partie
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { partie.charging = true; partie.shotCharge = 0f }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val me = partie.me()
                        val cibles = partie.ciblesDe(me).minByOrNull { hypot(it.x - me.x, it.y - me.y) }
                        partie.launch(me, cibles, partie.shotCharge)
                        partie.charging = false; partie.shotCharge = 0f
                    }
                }
                true
            }
        }
        boutonTir = tir
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(esquive); addView(attraper); addView(passe); addView(tir)
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = 22; bottomMargin = 22
            }
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
}
