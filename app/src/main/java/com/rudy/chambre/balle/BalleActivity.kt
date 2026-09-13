package com.rudy.chambre.balle

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * La balle au prisonnier, en natif.
 *
 * L'affiche d'abord, en paysage ; puis le match ; et a la fin, le choix entre
 * recommencer et retourner au bureau.
 */
class BalleActivity : ComponentActivity() {

    private lateinit var vue: VueBalle
    private lateinit var racine: FrameLayout
    private var affiche: ImageView? = null
    private var son: SonBalle? = null

    override fun onCreate(etat: Bundle?) {
        super.onCreate(etat)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        son = SonBalle(this)
        vue = VueBalle(this)
        vue.jeu.surSon = { quoi -> son?.jouer(quoi) }
        vue.surFin = { titre -> finDePartie(titre) }

        racine = FrameLayout(this)
        racine.setBackgroundColor(Color.BLACK)
        racine.addView(vue, FrameLayout.LayoutParams(-1, -1))
        racine.addView(barreDuHaut())
        racine.addView(boutons())
        setContentView(racine)

        montrerAffiche()
    }

    /** L'affiche du match, avant le coup d'envoi. */
    private fun montrerAffiche() {
        val image = try {
            context.assets.open("balle/affiche.jpg").use { BitmapFactory.decodeStream(it) }
        } catch (_: Throwable) { null }

        val bloc = FrameLayout(this)
        bloc.setBackgroundColor(0xFF07060D.toInt())
        val vueImage = ImageView(this).apply {
            if (image != null) setImageBitmap(image)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        bloc.addView(vueImage, FrameLayout.LayoutParams(-1, -1))

        val commencer = Button(this).apply {
            text = "COMMENCER"
            textSize = 16f
            setTextColor(0xFF2A1C06.toInt())
            setBackgroundColor(0xFFF2C14E.toInt())
            setOnClickListener {
                racine.removeView(bloc)
                affiche = null
                vue.rejouer()
            }
        }
        bloc.addView(commencer, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { bottomMargin = (36 * resources.displayMetrics.density).toInt() })

        racine.addView(bloc, FrameLayout.LayoutParams(-1, -1))
    }

    private val context get() = this

    private fun barreDuHaut(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(Button(this@BalleActivity).apply {
            text = "← Bureau"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC150F24.toInt())
            setOnClickListener { finish() }
        })
        layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            topMargin = 18; leftMargin = 18
        }
    }

    /** Les quatre commandes, a droite comme dans ton jeu. */
    private fun boutons(): LinearLayout {
        fun bouton(titre: String, couleur: Int, action: () -> Unit) = Button(this).apply {
            text = titre; textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(couleur)
            setOnClickListener { action() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bouton("ESQUIVE", 0xCC2B4B7A.toInt()) { vue.actionEsquive() })
            addView(bouton("ATTRAPER", 0xCC2F6B33.toInt()) { vue.actionAttraper() })
            addView(bouton("PASSE", 0xCC6B4A2F.toInt()) { vue.actionPasse() })
            addView(bouton("LANCER", 0xCC8A2F2F.toInt()) { vue.actionLancer(false) }.apply {
                setOnLongClickListener { vue.actionLancer(true); true }
            })
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

    override fun onDestroy() { son?.liberer(); super.onDestroy() }
}
