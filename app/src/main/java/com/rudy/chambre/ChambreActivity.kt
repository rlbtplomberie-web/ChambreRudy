package com.rudy.chambre

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * La chambre.
 *
 * Le decor et ses objets sont dessines par [VueChambre], en natif. Les jeux,
 * les vitrines et l'atelier restent des pages : ils s'ouvrent par-dessus,
 * dans [PageActivity], au moment ou on les demande.
 */
class ChambreActivity : ComponentActivity() {

    private lateinit var vue: VueChambre
    private lateinit var son: SonChambre
    private lateinit var etiquette: TextView

    override fun onCreate(etat: Bundle?) {
        super.onCreate(etat)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        son = SonChambre(this)
        vue = VueChambre(this)
        vue.surObjet = { quoi -> toucheObjet(quoi) }

        etiquette = TextView(this).apply {
            setTextColor(0xFFFFEEC2.toInt())
            textSize = 13f
            setPadding(28, 28, 28, 28)
            alpha = 0f
        }

        vue.surEtapeIntro = { quoi ->
            when (quoi) {
                "ouverture" -> son.bruit("carton.mp3")
                "chambre" -> {
                    son.bruit("pose.mp3")
                    son.radio()
                    vue.radioAllumee = true       // la radio s'allume en arrivant
                }
            }
        }

        val racine = FrameLayout(this)
        racine.addView(vue, FrameLayout.LayoutParams(-1, -1))
        racine.addView(etiquette, FrameLayout.LayoutParams(-2, -2))
        setContentView(racine)

        // l'ouverture du carton, une seule fois par lancement
        if (etat == null) vue.post { vue.jouerIntro() }
    }

    /** Un menu sombre, lisible par-dessus le decor. */
    private fun menu(): AlertDialog.Builder =
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun dire(texte: String) {
        etiquette.text = texte
        etiquette.animate().alpha(1f).setDuration(160).withEndAction {
            etiquette.animate().alpha(0f).setStartDelay(1600).setDuration(400).start()
        }.start()
    }

    // ================= ce qu'on touche =================

    private fun toucheObjet(quoi: String) {
        when (quoi) {
            "carton" -> {
                son.bruit("carton.mp3")
                vue.consoleSuivante { console ->
                    son.bruit("pose.mp3")
                    dire(console.nom)
                }
            }
            "radio" -> {
                val morceau = son.radio()
                vue.radioAllumee = morceau.isNotEmpty()
                vue.invalidate()
                dire(morceau.ifEmpty { "radio éteinte" })
            }
            "tiroir" -> {
                son.bruit("tiroir.mp3")
                val ouvrir = vue.tiroir < 0.5f
                vue.ouvrirTiroir(ouvrir) { if (ouvrir) proposerTiroir() }
            }
            "porteG" -> {
                val ouvrir = vue.porteG < 0.5f
                son.bruit(if (ouvrir) "porte_ouvre.mp3" else "porte_ferme.mp3")
                vue.bougerPorte(true, ouvrir) { if (ouvrir) menuJeuxDeSociete() }
            }
            "porteD" -> {
                val ouvrir = vue.porteD < 0.5f
                son.bruit(if (ouvrir) "porte_ouvre.mp3" else "porte_ferme.mp3")
                vue.bougerPorte(false, ouvrir) { if (ouvrir) ouvrirVitrine() }
            }
            "tele" -> vue.consolePosee()?.let { dire(it.nom) }
            "serrure" -> menuDehors()
            "livre" -> page("jeux/livre_vide.html", "Livre")
        }
    }

    // ================= les menus =================

    private fun proposerTiroir() {
        menu()
            .setTitle("Le tiroir est ouvert")
            .setItems(arrayOf("Dessiner", "Jouer aux cartes", "Refermer")) { _, i ->
                when (i) {
                    0 -> page("jeux/atelier.html", "Atelier de dessin")
                    1 -> menuCartes()
                    else -> { son.bruit("tiroir.mp3"); vue.ouvrirTiroir(false) }
                }
            }.show()
    }

    private fun menuCartes() {
        menu()
            .setTitle("Jeu de cartes")
            .setItems(arrayOf("Poker", "Blackjack")) { _, i ->
                page(if (i == 0) "jeux/poker.html" else "jeux/blackjack.html",
                     if (i == 0) "Poker" else "Blackjack", paysage = true)
            }.show()
    }

    private fun menuJeuxDeSociete() {
        menu()
            .setTitle("Jeux de société")
            .setItems(arrayOf("Échecs", "Dames", "Monopoly")) { _, i ->
                page(listOf("jeux/echecs.html", "jeux/dames.html", "jeux/monopoly.html")[i],
                     listOf("Échecs", "Dames", "Monopoly")[i])
            }
            .setOnDismissListener { if (vue.porteG > 0.5f) fermerPortes() }
            .show()
    }

    private fun menuDehors() {
        menu()
            .setTitle("Voulez-vous sortir ?")
            .setItems(arrayOf("Tir au but", "Faire des paniers")) { _, i ->
                page(if (i == 0) "jeux/penalty_leger.html" else "jeux/basket_leger.html",
                     if (i == 0) "Tir au but" else "Basket", paysage = true)
            }
            .setNegativeButton("Rester", null)
            .show()
    }

    /** La vitrine de la console posee sur le bureau. */
    private fun ouvrirVitrine() {
        val console = vue.consolePosee()
        if (console == null) {
            dire("sors d'abord une console du carton")
            fermerPortes()
            return
        }
        val fichiers = mapOf(
            "nes" to "nes", "gb" to "gameboy", "snes" to "snes", "md" to "megadrive",
            "gba" to "gba", "ds" to "ds", "ps1" to "ps1", "n64" to "n64", "dc" to "dreamcast",
            "psp" to "psp", "gc" to "gamecube", "wii" to "wii", "3ds" to "3ds"
        )
        val f = fichiers[console.id] ?: return
        page("jeux/vitrine-$f.html", "Vitrine " + console.nom)
    }

    private fun fermerPortes() {
        if (vue.porteG > 0.5f) { son.bruit("porte_ferme.mp3"); vue.bougerPorte(true, false) }
        if (vue.porteD > 0.5f) { son.bruit("porte_ferme.mp3"); vue.bougerPorte(false, false) }
    }

    /** Ouvre une page par-dessus la chambre, et coupe la musique le temps du jeu. */
    private fun page(chemin: String, titre: String, paysage: Boolean = false) {
        son.enPause(true)
        startActivity(Intent(this, PageActivity::class.java)
            .putExtra("page", chemin)
            .putExtra("titre", titre)
            .putExtra("paysage", paysage))
    }

    override fun onResume() {
        super.onResume()
        son.enPause(false)
    }

    override fun onPause() {
        super.onPause()
        son.enPause(true)
    }

    override fun onDestroy() {
        son.liberer()
        super.onDestroy()
    }
}
