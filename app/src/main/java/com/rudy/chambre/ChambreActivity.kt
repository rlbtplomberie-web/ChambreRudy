package com.rudy.chambre

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.TextureView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
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
        try {
            demarrer(etat)
        } catch (e: Throwable) {
            // Plutot que de se fermer sans rien dire, la chambre affiche ce qui
            // a echoue : la ligne exacte est alors lisible a l'ecran.
            afficherLeSouci(e)
        }
    }

    /** L'ecran de secours : le detail de l'erreur, et de quoi le recopier. */
    private fun afficherLeSouci(e: Throwable) {
        val texte = StringBuilder("La chambre n'a pas pu démarrer.\n\n")
        texte.append(e.toString()).append('\n')
        for (ligne in e.stackTrace.take(12))
            if (ligne.className.startsWith("com.rudy")) texte.append("  ").append(ligne).append('\n')
        try {
            java.io.File(filesDir, "dernier_plantage.txt").writeText(texte.toString())
        } catch (_: Throwable) {}
        val vueTexte = TextView(this).apply {
            setTextColor(0xFFFFD9D2.toInt())
            setBackgroundColor(0xFF1A0E0E.toInt())
            textSize = 12f
            setPadding(36, 90, 36, 36)
            setTextIsSelectable(true)
            text = texte.toString()
        }
        setContentView(android.widget.ScrollView(this).apply { addView(vueTexte) })
    }

    /** Le vrai demarrage, appele a l'abri. */
    private fun demarrer(etat: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        son = SonChambre(this)
        SonPartage.radio = son

        vue = VueChambre(this)
        vue.surObjet = { quoi -> toucheObjet(quoi) }
        vue.surEcranTele = { r -> placerEcranTele(r) }
        // le bouton de la radio regle vraiment le volume de la musique
        vue.surVolume = { v -> son.majVolume(v) }

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

        // « Bureau » depuis un emulateur doit revenir immediatement a la
        // chambre utile (TV, tiroirs et consoles), jamais a l'introduction.
        val retourBureau = intent.getBooleanExtra("retour_bureau", false)
        if (retourBureau) {
            vue.post { vue.cadrerSurLaTele() }
        } else if (etat == null) {
            // l'ouverture du carton, une seule fois par lancement
            vue.post { vue.jouerIntro() }
        }
        // la premiere console est prete avant meme qu'on touche le carton
        vue.postDelayed({ preparerVideo(Decor.CONSOLES[0]) }, 1200)
    }

    /**
     * Le choix d'une video, avec la telecommande.
     *
     * On ouvre le selecteur du telephone ; ce qu'il rend est joue sur l'ecran
     * de la tele, a la place des sequences de consoles.
     */
    private val choisirUneVideo =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {}
            jouerSurLaTele(uri)
        }

    /** Joue une video du telephone sur l'ecran de la tele. */
    private fun jouerSurLaTele(uri: android.net.Uri) {
        teleAllumee = true
        ecranTele.alpha = 1f
        placerEcranTele(vue.rectangleTele())
        val surface = ecranTele.surfaceTexture ?: return
        try { lecteur?.release() } catch (_: Throwable) {}
        lecteur = null
        try {
            lecteur = android.media.MediaPlayer().apply {
                setDataSource(this@ChambreActivity, uri)
                setSurface(android.view.Surface(surface))
                setVolume(0.9f, 0.9f)
                setOnCompletionListener {
                    teleAllumee = false
                    ecranTele.animate().alpha(0f).setDuration(600).start()
                    son.enPause(false)
                }
                prepare()
                start()
            }
            son.enPause(true)        // la radio se tait pendant la video
            dire("lecture sur la télé")
        } catch (_: Throwable) {
            teleAllumee = false
            ecranTele.alpha = 0f
            dire("cette vidéo ne peut pas être lue")
        }
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
                // la console met plus d'une seconde a sortir : on prepare sa
                // video pendant ce temps, elle demarre alors a l'instant meme
                preparerVideo(vue.consoleAVenir())
                vue.consoleSuivante { console ->
                    if (console == null) {
                        // la 3DS vient de rentrer : le carton est vide, la
                        // tele s'eteint. Un nouvel appui fera ressortir la NES.
                        eteindreLaTele()
                        dire("tout est rangé dans le carton")
                        vue.postDelayed({ preparerVideo(Decor.CONSOLES[0]) }, 400)
                        return@consoleSuivante
                    }
                    son.bruit("pose.mp3")
                    dire(console.nom)
                    allumerLaTele(console)
                    // et on prepare deja celle d'apres
                    val i = Decor.CONSOLES.indexOf(console) + 1
                    if (i < Decor.CONSOLES.size) {
                        vue.postDelayed({ preparerVideo(Decor.CONSOLES[i]) }, 900)
                    }
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
            // le second tiroir, en bas a droite : meme bruit, meme geste
            "tiroir2" -> {
                son.bruit("tiroir.mp3")
                val ouvrir = vue.tiroir2 < 0.5f
                vue.ouvrirTiroir2(ouvrir)
            }
            /*
             * La telecommande.
             *
             * Elle ne sert que si le bureau est libre : tant qu'une console
             * est posee, la tele lui appartient.
             */
            // la loupe : rien pour l'instant, Rudy dira ce qu'elle fait
            "loupe" -> dire("une loupe")
            "telecommande" -> {
                if (vue.consolePosee() != null) {
                    dire("veuillez d'abord ranger la console")
                } else {
                    son.bruit("pose.mp3")
                    try {
                        choisirUneVideo.launch(arrayOf("video/*"))
                    } catch (_: Throwable) {
                        dire("aucune application pour choisir une vidéo")
                    }
                }
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
            "livre" -> startActivity(Intent(this,
                com.rudy.chambre.livre.LivreActivity::class.java))
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
    /**
     * Preparer une video d'avance, sur un fil a part.
     *
     * La preparation lit le fichier et decode ses premieres images : faite sur
     * le fil de l'affichage, elle figeait la chambre le temps d'un battement.
     * Elle se fait donc a cote, et la video est prete avant meme l'appui.
     */
    private fun preparerVideo(console: Decor.ConsolePosee) {
        if (videoPrete == console.video || videoEnPreparation == console.video) return
        videoEnPreparation = console.video
        Thread {
            var pret: android.media.MediaPlayer? = null
            try {
                val d = assets.openFd("chambre/" + console.video)
                pret = android.media.MediaPlayer().apply {
                    setDataSource(d.fileDescriptor, d.startOffset, d.length)
                    setVolume(0.85f, 0.85f)
                    prepare()
                }
                d.close()
            } catch (_: Throwable) {
                try { pret?.release() } catch (_: Throwable) {}
                pret = null
            }
            runOnUiThread {
                videoEnPreparation = null
                if (pret == null) return@runOnUiThread
                try { lecteurPret?.release() } catch (_: Throwable) {}
                lecteurPret = pret
                videoPrete = console.video
            }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private var videoEnPreparation: String? = null

    /** La tele s'allume et joue la sequence de demarrage de cette console. */
    /** La tele s'eteint : la video s'arrete et l'ecran s'efface en douceur. */
    private fun eteindreLaTele() {
        teleAllumee = false
        try { lecteur?.stop() } catch (_: Throwable) {}
        try { lecteur?.release() } catch (_: Throwable) {}
        lecteur = null
        ecranTele.animate().alpha(0f).setDuration(500).start()
        son.enPause(false)          // la radio reprend
    }

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
            if (pret == null) {
                // pas encore prete : on la prepare a cote et on rappelle
                // cette meme fonction des qu'elle l'est
                preparerVideo(console)
                vue.postDelayed({ if (teleAllumee) allumerLaTele(console) }, 120)
                teleAllumee = true
                return
            }
            lecteur = pret.apply {
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

        /*
         * Ce qu'a fait la console posee sur la table.
         *
         * Un arret dans le coeur natif ne remonte pas jusqu'a Java : aucun
         * plantage n'est alors enregistre, et l'ecran se ferme sans rien dire.
         * Le journal, lui, garde la trace de chaque etape franchie. On y
         * cherche la derniere fois que CETTE console est partie, et on montre
         * tout ce qui a suivi : c'est son compte rendu a elle.
         */
        /*
         * Ce qu'Android a note pour notre propre application.
         *
         * Mupen64Plus tourne dans le meme processus que la Chambre : ses
         * erreurs sont donc dans le journal d'Android, sous notre numero. Une
         * application a le droit de lire le sien. C'est le seul moyen de voir
         * pourquoi un ecran se referme sans rien dire — ni plantage Java, ni
         * message a l'ecran.
         */
        val androidJournal = try {
            val p = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", "400", "--pid=" + android.os.Process.myPid()))
            val lignes = p.inputStream.bufferedReader().readLines()
            /*
             * Ce qui nous interesse vraiment : ce que dit SON emulateur.
             *
             * Je gardais aussi tous les avertissements du systeme, et ils
             * noyaient ses propres messages — c'est pour cela qu'on ne voyait
             * jamais la raison du refus. On prend d'abord les siens.
             */
            val siennes = lignes.filter { l ->
                l.contains("mupen", true) || l.contains("paulscode", true) ||
                l.contains("CoreService", true) || l.contains("CoreFragment", true) ||
                l.contains("GameActivity", true) || l.contains("dolphin", true) ||
                l.contains("citra", true) || l.contains("AndroidRuntime")
            }
            val utiles = if (siennes.isNotEmpty()) siennes
                         else lignes.filter { it.contains(" E ") }
            if (utiles.isEmpty()) ""
            else "\n\nCe qu'Android a note :\n" +
                 utiles.takeLast(16).joinToString("\n") { it.take(150) }
        } catch (_: Throwable) { "" }

        val posee = vue.consolePosee()
        val journal = try {
            val f = java.io.File(java.io.File(filesDir, "systeme"), "journal_appli.txt")
            if (!f.exists()) "\n\n(aucune console n'a encore laissé de trace)"
            else {
                val lignes = f.readLines()
                if (posee == null) {
                    // aucune console sur la table : on montre quand meme les
                    // dernieres traces, c'est souvent la que se cache la panne
                    val suite = lignes.filter { it.isNotBlank() }.takeLast(22)
                    if (suite.isEmpty()) "\n\n(rien n'a encore ete tente)"
                    else "\n\nDernieres traces :\n" + suite.joinToString("\n")
                } else {
                    val marque = "=== CONSOLE " + posee.nom + " "
                    val depart = lignes.indexOfLast { it.startsWith(marque) }
                    if (depart < 0) {
                        "\n\n" + posee.nom + " : aucun lancement enregistré."
                    } else {
                        val suite = lignes.drop(depart)
                            .filter { it.isNotBlank() }
                            .takeLast(22)
                        "\n\nCompte rendu de " + posee.nom + " :\n" + suite.joinToString("\n") +
                        "\n\n(si ça s'arrête après « avant ouverture du cœur », c'est le" +
                        " moteur qui refuse ; après « ROM reçue », c'est le jeu.)"
                    }
                }
            }
        } catch (_: Throwable) { "" }

        menu().setTitle(if (posee != null) posee.nom + " — compte rendu" else "Ce que contient cet APK")
            .setMessage(texte + plantage + journal + androidJournal)
            .setPositiveButton("Fermer", null)
            .setNegativeButton("Tout effacer") { _, _ ->
                try { java.io.File(filesDir, "dernier_plantage.txt").delete() } catch (_: Throwable) {}
                try { java.io.File(java.io.File(filesDir, "systeme"), "journal_appli.txt").delete() } catch (_: Throwable) {}
            }
            .setNeutralButton("Copier") { _, _ ->
                try {
                    val cb = getSystemService(android.content.ClipboardManager::class.java)
                    cb.setPrimaryClip(android.content.ClipData.newPlainText(
                        "bilan", texte + plantage + journal + androidJournal))
                    dire("copié")
                } catch (_: Throwable) {}
            }
            .show()
    }

    // ================= les menus =================

    private fun proposerTiroir() {
        menu()
            .setTitle("Le tiroir est ouvert")
            .setItems(arrayOf("Dessiner", "Jouer aux cartes", "Refermer")) { _, i ->
                when (i) {
                    0 -> startActivity(Intent(this,
                        com.rudy.chambre.atelier.AtelierActivity::class.java))
                    1 -> menuCartes()
                    else -> { son.bruit("tiroir.mp3"); vue.ouvrirTiroir(false) }
                }
            }.show()
    }

    private fun menuCartes() {
        menu()
            .setTitle("Jeu de cartes")
            .setItems(arrayOf("Poker", "Blackjack")) { _, i ->
                startActivity(Intent(this, if (i == 0)
                    com.rudy.chambre.cartes.PokerActivity::class.java
                else com.rudy.chambre.cartes.BlackjackActivity::class.java))
            }.show()
    }

    /**
     * L'armoire de gauche, comme dans sa page : un panneau en bas au centre,
     * a neuf pour cent du bord, violet borde d'or, avec ses trois jeux cote a
     * cote et le bouton pour refermer.
     */
    private fun menuJeuxDeSociete() {
        panneau("À quoi veux-tu jouer ?",
                listOf("Monopoly", "Échecs", "Dames"),
                1,                                  // les echecs sont mis en avant
                0xF5261A42.toInt(), 0x59FFDC96,
                "← Refermer l'armoire") { i ->
            when (i) {
                0 -> startActivity(Intent(this, com.rudy.chambre.monopoly.MonopolyActivity::class.java))
                1 -> startActivity(Intent(this, com.rudy.chambre.echecs.EchecsActivity::class.java))
                2 -> startActivity(Intent(this, com.rudy.chambre.dames.DamesActivity::class.java))
            }
        }
    }

    private var panneauOuvert: View? = null

    /**
     * Un panneau de choix, pose en bas de l'ecran comme les siens.
     * [enAvant] designe le bouton mis en valeur, [fermer] le texte du retour.
     */
    private fun panneau(titre: String, choix: List<String>, enAvant: Int,
                        fond: Int, bordure: Int, fermer: String,
                        surChoix: (Int) -> Unit) {
        panneauOuvert?.let { (it.parent as? FrameLayout)?.removeView(it) }

        val dens = resources.displayMetrics.density
        fun dp(v: Float) = (v * dens).toInt()

        val bloc = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                colors = intArrayOf(fond, 0xF5120B24.toInt())
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke(dp(2f), bordure)
            }
        }
        bloc.addView(TextView(this).apply {
            text = titre; textSize = 14f
            setTextColor(0xFFFFEEC2.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10f))
        })

        val rangee = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        choix.forEachIndexed { i, nom ->
            rangee.addView(Button(this).apply {
                text = nom; textSize = 11f
                setTextColor(if (i == enAvant) 0xFF2A1C06.toInt() else Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setColor(if (i == enAvant) 0xFFE8C36A.toInt() else 0x33FFFFFF)
                    setStroke(dp(1f), 0x66FFDC96)
                }
                setOnClickListener { fermerLePanneau(); surChoix(i) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (i > 0) leftMargin = dp(8f)
            })
        }
        bloc.addView(rangee)

        bloc.addView(Button(this).apply {
            text = fermer; textSize = 12f
            setTextColor(0xFFCFC7B7.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { fermerLePanneau(); if (vue.porteG > 0.5f) fermerPortes() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })

        val place = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        place.bottomMargin = (resources.displayMetrics.heightPixels * .03f).toInt()
        place.width = (resources.displayMetrics.widthPixels * .74f).toInt()
        (findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout)
            .addView(bloc, place)
        panneauOuvert = bloc
        bloc.alpha = 0f
        bloc.translationY = dp(14f).toFloat()
        bloc.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    private fun fermerLePanneau() {
        val p = panneauOuvert ?: return
        panneauOuvert = null
        p.animate().alpha(0f).translationY(20f).setDuration(180)
            .withEndAction { (p.parent as? FrameLayout)?.removeView(p) }.start()
    }

    private fun menuDehors() {
        menu()
            .setTitle("Voulez-vous sortir ?")
            .setItems(arrayOf("Tir au but", "Faire des paniers", "Balle au prisonnier", "PariBoxe")) { _, i ->
                // Les jeux de plein air et PariBoxe sont integres a RetroRom.
                val ecrans = listOf(
                    com.rudy.chambre.penalty.PenaltyActivity::class.java,
                    com.rudy.chambre.basket.BasketActivity::class.java,
                    com.rudy.chambre.balle.PartieActivity::class.java,
                    com.rudy.chambre.pariboxe.PariBoxeActivity::class.java
                )
                startActivity(Intent(this, ecrans[i]))
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
        // si une console vient de partir, la chambre ne fait que passer :
        // sa musique reste muette, celle du jeu prend le relais
        if (!SonPartage.consoleVientDePartir()) {
            SonPartage.volume(1f)          // rendu apres le passage d'un emulateur
            son.enPause(false)
        }
    }

    override fun onPause() {
        super.onPause()
        // On ouvre un jeu ou une vitrine : la chambre passe derriere, mais
        // elle reste a l'ecran de l'application. La radio continue donc de
        // jouer, comme quand on change de piece.
        if (isFinishing) son.enPause(true)
    }

    /**
     * L'application passe vraiment en arriere-plan (bouton accueil, autre
     * application) : la, on met la radio en pause.
     */
    override fun onStop() {
        super.onStop()
        val enAvant = (application as? Chambre)?.enAvantPlan ?: false
        if (!enAvant) son.enPause(true)
    }

    override fun onDestroy() {
        try { lecteur?.release() } catch (_: Throwable) {}
        try { lecteurPret?.release() } catch (_: Throwable) {}
        son.liberer()
        super.onDestroy()
    }
}
