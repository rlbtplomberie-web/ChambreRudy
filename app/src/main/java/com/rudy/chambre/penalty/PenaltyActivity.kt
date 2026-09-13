package com.rudy.chambre.penalty

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * L'ecran du tir au but : les quatre directions, le bouton de frappe, le
 * compteur de buts et d'arrets, et le choix de difficulte — comme chez lui.
 */
class PenaltyActivity : ComponentActivity() {

    private lateinit var vue: VuePenalty
    private lateinit var compteur: TextView
    private lateinit var message: TextView
    private var son: SonPenalty? = null

    private var buts = 0
    private var arrets = 0
    private var finie = false
    private var directionChoisie: String? = null
    private var enCours = false
    /** Ses trois niveaux : 25 %, 48 % ou 72 % de chances d'arret. */
    private var chanceArret = .48f

    override fun onCreate(etat: Bundle?) {
        super.onCreate(etat)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        son = SonPenalty(this)
        vue = VuePenalty(this)
        vue.surSon = { quoi -> son?.jouer(quoi) }
        vue.surFinDeFrappe = { arrete -> finDeFrappe(arrete) }

        val racine = FrameLayout(this)
        racine.setBackgroundColor(Color.BLACK)
        racine.addView(vue, FrameLayout.LayoutParams(-1, -1))
        racine.addView(tableauDeBord())
        racine.addView(directions())
        racine.addView(boutonFrappe())
        message = TextView(this).apply {
            textSize = 26f; setTextColor(0xFFFFE52B.toInt())
            setShadowLayer(8f, 0f, 3f, Color.BLACK)
            visibility = android.view.View.GONE
        }
        racine.addView(message, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(racine)
    }

    private fun tableauDeBord(): LinearLayout {
        compteur = TextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE)
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        majCompteur()
        val niveaux = Spinner(this).apply {
            adapter = ArrayAdapter(this@PenaltyActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Facile", "Normal", "Difficile"))
            setSelection(1)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, i: Int, id: Long) {
                    chanceArret = when (i) { 0 -> .25f; 2 -> .72f; else -> .48f }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(26, 22, 26, 10)
            addView(compteur)
            addView(niveaux, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 30 })
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
        }
    }

    private fun majCompteur() {
        compteur.text = "⚽ BUTS $buts/5     🧤 ARRÊTS $arrets/3"
    }

    /** Ses quatre directions : ↖ ↗ ↙ ↘ */
    private fun directions(): GridLayout {
        val grille = GridLayout(this).apply { rowCount = 2; columnCount = 2 }
        val flèches = listOf("↖" to "ul", "↗" to "ur", "↙" to "ll", "↘" to "lr")
        for ((signe, code) in flèches) {
            grille.addView(Button(this).apply {
                text = signe; textSize = 22f
                setTextColor(Color.WHITE)
                setBackgroundColor(0xAA1B3350.toInt())
                setOnClickListener {
                    if (!enCours && !finie) {
                        directionChoisie = code
                        // la direction choisie se distingue des autres
                        for (i in 0 until grille.childCount) {
                            val b = grille.getChildAt(i) as Button
                            b.setBackgroundColor(if (b === this) 0xFF2E7D32.toInt() else 0xAA1B3350.toInt())
                        }
                    }
                }
            })
        }
        grille.layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START)
            .apply { leftMargin = 26; bottomMargin = 26 }
        return grille
    }

    private fun boutonFrappe() = Button(this).apply {
        text = "TIRER"; textSize = 18f
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFFB51F28.toInt())
        setOnClickListener { frapper() }
        layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = 30; bottomMargin = 34
        }
    }

    private fun frapper() {
        if (enCours || finie) return
        val direction = directionChoisie
        if (direction == null) { dire("Choisis d'abord une direction ↖ ↗ ↙ ↘", 900); return }
        enCours = true
        val arrete = Math.random() < chanceArret
        // son « cpuDirection » : il plonge au bon endroit s'il arrete, ailleurs sinon
        val plongeon = if (arrete) direction
                       else listOf("ul", "ur", "ll", "lr").filter { it != direction }.random()
        vue.frapper(direction, arrete, plongeon)
    }

    private fun finDeFrappe(arrete: Boolean) {
        if (arrete) { arrets++; dire("ARRÊT DU GARDIEN !", 1000) }
        else { buts++; dire("BUT ! ⚽", 1000) }
        majCompteur()

        if (buts >= 5) {
            finie = true; dire("🏆 RUDY GAGNE : 5 BUTS !", 0)
            son?.jouer("applaudissements")
            vue.postDelayed({ finDePartie("🏆 RUDY GAGNE", "Cinq buts marqués.") }, 900)
        } else if (arrets >= 3) {
            finie = true; dire("🧤 LE GARDIEN GAGNE : 3 ARRÊTS !", 0)
            son?.jouer("deception")
            vue.postDelayed({ finDePartie("🧤 LE GARDIEN GAGNE", "Trois arrêts encaissés.") }, 900)
        } else {
            vue.postDelayed({
                vue.remettre(); directionChoisie = null; enCours = false
            }, 1050)
        }
    }

    override fun onDestroy() { son?.liberer(); super.onDestroy() }

    private fun dire(t: String, ms: Long) {
        message.text = t
        message.visibility = android.view.View.VISIBLE
        if (ms > 0) message.postDelayed({
            if (message.text == t) message.visibility = android.view.View.GONE
        }, ms)
    }

    private fun finDePartie(titre: String, texte: String) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(titre).setMessage(texte)
            .setPositiveButton("Rejouer") { _, _ ->
                buts = 0; arrets = 0; finie = false; enCours = false
                directionChoisie = null; majCompteur()
                message.visibility = android.view.View.GONE
                vue.remettre()
            }
            .setNegativeButton("Retour au bureau") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }
}
