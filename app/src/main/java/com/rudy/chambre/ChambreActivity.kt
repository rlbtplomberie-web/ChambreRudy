package com.rudy.chambre

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.TextureView
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
    private lateinit var ecranTele: TextureView
    private var lecteur: android.media.MediaPlayer? = null

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
        vue.surEcranTele = { r -> placerEcranTele(r) }

        etiquette = TextView(this).apply {
            setTextColor(0xFFFFEEC2.toInt())
            textSize = 13f
            setPadding(28, 28, 28, 28)
            alpha = 0f
        }

        vue.surEtapeIntro = { quoi ->
            when (quoi) {
                "debut" -> {                      // la radio joue des la premiere image
                    son.radio()
                    vue.radioAllumee = son.allumee()
                }
                "ouverture" -> son.bruit("carton.mp3")
                "chambre" -> {
                    son.bruit("pose.mp3")
                    vue.cadrerSurLaTele()
                }
            }
        }

        // l'ecran de la tele : une surface posee sur le decor, a sa place exacte
        ecranTele = TextureView(this).apply { alpha = 0f; isOpaque = false }

        val racine = FrameLayout(this)
        racine.addView(vue, FrameLayout.LayoutParams(-1, -1))
        racine.addView(ecranTele, FrameLayout.LayoutParams(1, 1))
        racine.addView(etiquette, FrameLayout.LayoutParams(-2, -2))
        setContentView(racine)

        // l'ouverture du carton, une seule fois par lancement
        if (etat == null) vue.post { vue.jouerIntro() }
        // la premiere console est prete avant meme qu'on touche le carton
        vue.postDelayed({ preparerVideo(Decor.CONSOLES[0]) }, 1200)
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
                    allumerLaTele(console)
                    // et on prepare deja celle d'apres
                    val suivante = Decor.CONSOLES[(Decor.CONSOLES.indexOf(console) + 1) % Decor.CONSOLES.size]
                    vue.postDelayed({ preparerVideo(suivante) }, 900)
                }
            }
            "radio" -> {
                val morceau = son.radio()
                vue.radioAllumee = son.allumee()
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
            "teleLong" -> montrerAssemblage()
            "serrure" -> menuDehors()
            "livre" -> page("jeux/livre_vide.html", "Bibliothèque")
        }
    }

    /**
     * L'ecran de la tele suit le decor.
     *
     * La video est une surface posee sur la vue : elle ne se deplace pas toute
     * seule quand la camera tourne. On la replace donc a chaque image, et on
     * l'efface des qu'on regarde un autre mur.
     */
    private fun placerEcranTele(r: android.graphics.RectF?) {
        if (!::ecranTele.isInitialized) return
        if (r == null || !teleAllumee) { ecranTele.visibility = android.view.View.INVISIBLE; return }
        val lp = ecranTele.layoutParams as? FrameLayout.LayoutParams ?: return
        val l = r.width().toInt(); val h = r.height().toInt()
        if (l <= 0 || h <= 0) { ecranTele.visibility = android.view.View.INVISIBLE; return }
        if (lp.width != l || lp.height != h || lp.leftMargin != r.left.toInt()
            || lp.topMargin != r.top.toInt()) {
            lp.width = l; lp.height = h
            lp.leftMargin = r.left.toInt(); lp.topMargin = r.top.toInt()
            ecranTele.layoutParams = lp
        }
        ecranTele.visibility = android.view.View.VISIBLE
    }

    private var teleAllumee = false
    /** La video de la prochaine console, deja prete a partir. */
    private var lecteurPret: android.media.MediaPlayer? = null
    private var videoPrete: String? = null

    /** Prepare a l'avance la sequence de la console suivante, pour qu'elle parte net. */
    private fun preparerVideo(console: Decor.ConsolePosee) {
        if (videoPrete == console.video) return
        try { lecteurPret?.release() } catch (_: Throwable) {}
        lecteurPret = null; videoPrete = null
        try {
            val d = assets.openFd("chambre/" + console.video)
            lecteurPret = android.media.MediaPlayer().apply {
                setDataSource(d.fileDescriptor, d.startOffset, d.length)
                setVolume(0.85f, 0.85f)
                prepare()                       // tout le travail se fait ici, avant l'appui
            }
            d.close()
            videoPrete = console.video
        } catch (_: Throwable) { lecteurPret = null; videoPrete = null }
    }

    /** La tele s'allume et joue la sequence de demarrage de cette console. */
    private fun allumerLaTele(console: Decor.ConsolePosee) {
        teleAllumee = true
        ecranTele.alpha = 1f
        placerEcranTele(vue.rectangleTele())

        try { lecteur?.release() } catch (_: Throwable) {}
        lecteur = null

        val surface = ecranTele.surfaceTexture ?: return
        try {
            // si elle a ete preparee d'avance, elle part a l'instant meme
            val pret = if (videoPrete == console.video) lecteurPret else null
            lecteurPret = null; videoPrete = null
            lecteur = (pret ?: android.media.MediaPlayer().apply {
                val d = assets.openFd("chambre/" + console.video)
                setDataSource(d.fileDescriptor, d.startOffset, d.length)
                setVolume(0.85f, 0.85f)
                prepare()
                d.close()
            }).apply {
                setSurface(android.view.Surface(surface))
                setOnCompletionListener {
                    teleAllumee = false
                    ecranTele.animate().alpha(0f).setDuration(600).start()
                }
                start()
            }
            son.enPause(true)                       // la radio se tait pendant la sequence
            ecranTele.postDelayed({ son.enPause(false) }, 6000)
        } catch (_: Throwable) { teleAllumee = false; ecranTele.alpha = 0f }
    }

    /**
     * Ce que l'APK contient reellement : les moteurs presents, et les
     * emulateurs assembles depuis leur propre projet. Le bilan est ecrit a la
     * compilation, donc il ne ment pas.
     */
    private fun montrerAssemblage() {
        val texte = try {
            assets.open("chambre/assemblage.txt").bufferedReader().use { it.readText() }
        } catch (_: Throwable) { "aucun bilan : cet APK date d'avant cette mesure." }
        val plantage = try {
            val f = java.io.File(filesDir, "dernier_plantage.txt")
            if (f.exists()) "\n\nDernier plantage :\n" + f.readText() else ""
        } catch (_: Throwable) { "" }
        menu().setTitle("Ce que contient cet APK")
            .setMessage(texte + plantage)
            .setPositiveButton("Fermer", null)
            .setNegativeButton("Effacer le plantage") { _, _ ->
                try { java.io.File(filesDir, "dernier_plantage.txt").delete() } catch (_: Throwable) {}
            }
            .show()
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
            .setItems(arrayOf("Tir au but", "Faire des paniers", "Balle au prisonnier")) { _, i ->
                when (i) {
                    // celui-la est desormais en natif
                    2 -> startActivity(Intent(this, com.rudy.chambre.balle.BalleActivity::class.java))
                    else -> page(
                        if (i == 0) "jeux/penalty_leger.html" else "jeux/basket_leger.html",
                        if (i == 0) "Tir au but" else "Basket", paysage = true)
                }
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
        try { lecteur?.release() } catch (_: Throwable) {}
        try { lecteurPret?.release() } catch (_: Throwable) {}
        son.liberer()
        super.onDestroy()
    }
}
