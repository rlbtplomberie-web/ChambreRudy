package com.skinpsp.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import com.skinpsp.app.core.CoeurPSP
import java.io.File
import java.util.zip.ZipInputStream
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Emulateur PlayStation Portable.
 *
 * Le demarrage est volontairement prudent : on affiche d'abord un ecran de
 * statut, puis on ouvre le coeur, puis on installe les fichiers systeme sur un
 * fil de fond, et l'interface n'est construite qu'ensuite. Chaque etape est
 * notee sur disque AVANT d'etre tentee : si l'application disparait, la
 * relance suivante dit exactement laquelle n'est jamais revenue.
 */
class MainActivity : ComponentActivity() {

    private lateinit var coeur: CoeurPSP
    private lateinit var vue: SkinView
    private lateinit var gl: GLSurfaceView
    private lateinit var racine: FrameLayout
    private lateinit var barre: HorizontalScrollView
    private lateinit var statut: TextView
    private lateinit var bQualite: Button
    private lateinit var bLissage: Button


    private var son: Son? = null
    private var jeuCourant: File? = null
    private var qualite = 1
    private var romsAffichees: List<Rom> = emptyList()
    private var reprendreLaPartie = true
    private var construite = false

    private val prefs by lazy { getSharedPreferences("skin_psp", MODE_PRIVATE) }

    /** Reglages du coeur qu'on peut essayer si un jeu se comporte mal. */
    private val ESSAIS = listOf(
        Triple("ppsspp_texture_filtering", "Filtrage des textures",
               listOf("Auto", "Linear")),
        Triple("ppsspp_texture_scaling_level", "Agrandissement des textures",
               listOf("1x", "2x")),
        Triple("ppsspp_texture_deposterize", "Adoucir les aplats",
               listOf("enabled", "disabled")),
        Triple("ppsspp_cpu_core", "Recompilateur rapide (peut planter)",
               listOf("IR JIT", "JIT")),
        Triple("ppsspp_fast_memory", "Accès mémoire rapide (peut planter)",
               listOf("disabled", "enabled")),
        Triple("ppsspp_skip_buffer_effects", "Sauter les effets de tampon (plus rapide)",
               listOf("disabled", "enabled")),
        /* Le moteur graphique. « auto » fait choisir Vulkan au coeur sur
           Android, que ce pont ne sait pas servir : le premier choix, donc le
           sur, est OpenGL. */
        Triple("ppsspp_backend", "Moteur graphique",
               listOf("opengl", "auto", "vulkan")),
        /* Premier choix = valeur sûre, c'est elle que le rattrapage remet
           apres une chute. Ici la valeur sûre est le calcul logiciel : c'est
           la seule qui n'exige pas de contexte graphique sur le fil ou le
           coeur demarre son moteur. */
        Triple("ppsspp_software_rendering", "Calcul logiciel (sûr, plus lent)",
               listOf("enabled", "disabled")))

    private val choisirDossier =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                Bibliotheque.poserDossier(this, uri)
                ouvrirCatalogue()
            }
        }

    // ================= journal =================

    private fun noter(ligne: String) {
        try {
            val f = File(coeur.dossierSysteme, "journal_appli.txt")
            f.appendText(ligne + "\n")
        } catch (_: Exception) {}
    }

    // ================= jalons du demarrage =================

    /**
     * Note l'etape en cours, AVANT de la tenter.
     *
     * L'ecriture est synchrone et forcee sur le disque : les preferences
     * ecrivent en differe, et un plantage natif survenant juste apres perdrait
     * l'information.
     */
    /**
     * Referme la barre toute seule.
     *
     * Elle restait ouverte jusqu'a ce qu'on rappuie sur MENU, ce qui masquait
     * le haut de la console pendant le jeu.
     */
    private val replier = Runnable {
        if (::barre.isInitialized) barre.visibility = View.GONE
    }

    private fun basculerBarre() {
        if (!::barre.isInitialized) return
        barre.removeCallbacks(replier)
        if (barre.visibility == View.VISIBLE) {
            barre.visibility = View.GONE
        } else {
            barre.visibility = View.VISIBLE
            barre.postDelayed(replier, 6000L)
        }
    }

    private fun etape(nom: String) {
        try {
            java.io.FileOutputStream(File(filesDir, "etape.txt")).use {
                it.write(nom.toByteArray()); it.flush(); it.fd.sync()
            }
        } catch (_: Throwable) {}
    }

    private fun etapeLue(): String? = try {
        val f = File(filesDir, "etape.txt")
        if (f.exists()) f.readText().trim().ifBlank { null } else null
    } catch (_: Throwable) { null }

    private fun dire(texte: String) {
        etape(texte)
        try { if (::statut.isInitialized) statut.text = texte } catch (_: Throwable) {}
    }

    /** Affiche l'erreur au lieu de disparaitre. */
    private fun ecranDeSecours(e: Throwable) {
        try {
            val d = resources.displayMetrics.density
            val t = TextView(this).apply {
                setPadding((20 * d).toInt(), (50 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
                setBackgroundColor(Color.BLACK)
                setTextColor(Color.WHITE)
                textSize = 13f
                setTextIsSelectable(true)
                text = "Le démarrage a échoué.\n\n" + e + "\n\n" +
                       e.stackTrace.take(12).joinToString("\n") { it.toString() }
            }
            setContentView(ScrollView(this).apply { addView(t) })
        } catch (_: Throwable) {}
    }

    // ================= cycle de vie =================

    override fun onCreate(s: Bundle?) {
        etape("chargement des bibliothèques natives")
        val avant = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { fil, e ->
            try {
                java.io.FileOutputStream(File(filesDir, "chute.txt")).use {
                    it.write((e.toString() + "\n" +
                        e.stackTrace.take(12).joinToString("\n")).toByteArray())
                    it.flush(); it.fd.sync()
                }
            } catch (_: Throwable) {}
            avant?.uncaughtException(fil, e)
        }
        super.onCreate(s)
        val precedente = etapeLue()

        // Un ecran minimal AVANT toute construction : sans lui, une erreur
        // survenant pendant le montage n'a aucune fenetre pour s'afficher.
        try {
            val d = resources.displayMetrics.density
            statut = TextView(this).apply {
                setPadding((20 * d).toInt(), (50 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
                setBackgroundColor(Color.BLACK)
                setTextColor(Color.WHITE)
                textSize = 13f
                setTextIsSelectable(true)
                text = "Démarrage…"
            }
            setContentView(ScrollView(this).apply { addView(statut) })
        } catch (_: Throwable) {}

        if (precedente != null && precedente != "terminé") {
            dire("Le démarrage précédent s'était arrêté à :\n\n" + precedente +
                 "\n\nNouvelle tentative…")
        }

        try {
            dire("ouverture du cœur")
            coeur = CoeurPSP(this)
            noter("")
            noter("--- démarrage " + java.text.SimpleDateFormat(
                "HH:mm:ss", java.util.Locale.FRANCE).format(java.util.Date()) + " ---")
            if (!coeur.pret) noter(coeur.derniereErreur)
            /*
             * Les reglages enregistres d'une version a l'autre.
             *
             * La liste des choix a change — des noms d'options etaient faux,
             * et la valeur sure du calcul logiciel s'est inversee. Un reglage
             * garde de l'ancienne version ecraserait donc le bon. On efface
             * une seule fois, en gardant une marque pour ne pas recommencer.
             */
            if (prefs.getInt("version_reglages", 0) < 2) {
                val e = prefs.edit()
                for (c in prefs.all.keys.toList())
                    if (c.startsWith("opt_ppsspp_")) e.remove(c)
                e.putInt("version_reglages", 2).apply()
                noter("réglages remis à neuf (la liste des options a changé)")
            }
            val rattrape = rattraperApresChute()
            /* Sans ce chargement par Android, PPSSPP ne peut pas rattacher
               ses fils de travail et l'emulation tombe au demarrage du fil
               graphique. La trace le dit tout de suite. */
            noter("cœur chargé par Android : " +
                  (if (CoeurPSP.noyauCharge) "oui" else "NON — " + CoeurPSP.erreurNoyau))

            if (rattrape) Toast.makeText(this,
                "L'application s'était fermée : les réglages risqués sont " +
                "revenus au réglage sûr", Toast.LENGTH_LONG).show()

            dire("installation des fichiers PPSSPP…")
            Thread {
                val bilan = try { installerFichiers() } catch (e: Throwable) { "échec : " + e }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    try {
                        dire("construction de l'interface")
                        construire(bilan, precedente)
                    } catch (e: Throwable) { ecranDeSecours(e) }
                }
            }.start()
        } catch (e: Throwable) {
            ecranDeSecours(e)
        }
    }

    /**
     * Copie les fichiers systeme de PPSSPP.
     *
     * A appeler depuis un fil de FOND : huit megaoctets et demi en vingt-sept
     * fichiers. Sur le fil principal, cette copie bloque l'affichage, et
     * Android ferme une application qu'il croit figee.
     */
    private fun installerFichiers(): String {
        val cible = File(coeur.dossierSysteme, "PPSSPP")
        // Le numero change quand le contenu embarque evolue : les fichiers
        // sont alors reinstalles au lieu d'etre crus deja en place.
        val temoin = File(cible, ".installe_v2")
        if (temoin.exists()) return "fichiers déjà en place"
        cible.mkdirs()
        var n = 0
        fun copier(chemin: String, vers: File) {
            val entrees = assets.list(chemin) ?: return
            if (entrees.isEmpty()) {
                vers.parentFile?.mkdirs()
                assets.open(chemin).use { e -> vers.outputStream().use { e.copyTo(it) } }
                n++
            } else {
                vers.mkdirs()
                for (x in entrees) copier("$chemin/$x", File(vers, x))
            }
        }
        for (x in assets.list("psp/systeme") ?: emptyArray())
            copier("psp/systeme/$x", File(cible, x))
        temoin.writeText("ok")
        return "fichiers installés (" + n + ")"
    }

    // ================= construction de l'interface =================

    /**
     * Plein ecran veritable.
     *
     * Sans cela, la barre d'etat en haut et la barre de navigation en bas
     * restent affichees, et la console n'occupe pas toute la surface. C'est ce
     * qui manquait.
     */
    private fun bordABord() {
        try {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            val c = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat
                .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } catch (_: Throwable) {}
        try {
            // le decoupe de l'ecran ne doit pas rogner la console non plus
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        } catch (_: Throwable) {}
    }

    override fun onWindowFocusChanged(focus: Boolean) {
        super.onWindowFocusChanged(focus)
        if (focus) bordABord()
    }

    private fun construire(bilan: String, precedente: String?) {
        bordABord()
        // Pas de fenetre translucide : la surface OpenGL ne serait plus
        // composee et on verrait le bureau a travers.
        window.setFormat(PixelFormat.OPAQUE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        vue = SkinView(this)
        gl = creerCoucheGl()

        racine = FrameLayout(this)
        // Rien d'opaque au-dessus de la couche OpenGL.
        // La vue de l'habillage ne peint rien la ou l'ecran doit apparaitre :
        // c'est la surface OpenGL, dessous, qui s'y montre.
        racine.setBackgroundColor(Color.TRANSPARENT)
        vue.setBackgroundColor(Color.TRANSPARENT)
        /*
         * La couche OpenGL occupe tout l'ecran.
         *
         * Je l'avais reduite a un pixel, pensant qu'elle ne servait qu'a
         * porter le contexte. Mais le coeur interroge la taille de la surface :
         * il obtenait zero, en concluait qu'il n'y avait rien a afficher, et
         * ne presentait jamais son image — d'ou l'ecran noir alors que
         * l'emulation tournait et que le son sortait.
         *
         * Elle reste invisible : c'est une SurfaceView, donc dessinee DERRIERE
         * la fenetre, et l'habillage la recouvre entierement.
         */
        racine.addView(gl, FrameLayout.LayoutParams(-1, -1))
        racine.addView(vue, FrameLayout.LayoutParams(-1, -1))
        barre = construireBarre()
        racine.addView(barre, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        // La barre est repliee au demarrage : c'est le bouton MENU qui la
        // fait apparaitre. Elle etait visible d'emblee et masquait la console.
        barre.visibility = View.GONE
        setContentView(racine)

        vue.surCommandes = { b, x, y, avance ->
            rendu.boutons = b; rendu.sx = x; rendu.sy = y; rendu.avanceRapide = avance
        }
        vue.surCadre = { l, t, r, b ->
            rendu.cadre = intArrayOf(l.toInt(), t.toInt(), r.toInt(), b.toInt())
        }
        vue.surMenu = { basculerBarre() }
        vue.surJeux = { ouvrirCatalogue() }
        vue.surChoixJeu = { i ->
            if (i in romsAffichees.indices) {
                vue.listeVisible = false
                chargerDepuis(romsAffichees[i].uri, romsAffichees[i].nom)
            }
        }
        vue.surCheat = { ouvrirEssais() }
        vue.surQuit = { finishAffinity() }
        vue.surErreur = { l -> noter(l) }
        vue.lissage = prefs.getBoolean("lissage", true)
        reprendreLaPartie = prefs.getBoolean("reprise", true)

        appliquerQualite(prefs.getInt("qualite", 1))
        appliquerEssais()
        construite = true
        noter(bilan)
        dire("terminé")

        if (!coeur.pret) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Cœur d'émulation absent")
                .setMessage(coeur.derniereErreur)
                .setPositiveButton("OK", null).show()
        } else if (precedente != null && precedente != "terminé") {
            android.app.AlertDialog.Builder(this)
                .setTitle("Le démarrage précédent avait échoué")
                .setMessage("Il s'est arrêté à : " + precedente)
                .setPositiveButton("Compris", null).show()
        }
    }

    /**
     * Couche OpenGL.
     *
     * Elle ne sert qu'a porter le contexte graphique et a faire tourner
     * l'emulation : l'image est relue puis dessinee par le skin. Elle est donc
     * reduite a un pixel, hors du champ de vision.
     */
    /**
     * Le rendu : il fait tourner l'emulation et relit l'image.
     *
     * L'affichage lui-meme est fait par le skin, sur son propre dessin. Cette
     * couche ne sert donc qu'a porter le contexte graphique dont le coeur a
     * besoin, et reste reduite a un pixel, hors du champ de vision.
     */
    private inner class Rendu : GLSurfaceView.Renderer {
        @Volatile var boutons = 0
        @Volatile var sx = 0f
        @Volatile var sy = 0f
        @Volatile var avanceRapide = false
        /** Rectangle de l'ecran dans la vue, en pixels. */
        @Volatile var cadre: IntArray? = null
        @Volatile var vueL = 0
        @Volatile var vueH = 0
        /** Images relues : sert au diagnostic affiche a l'ecran. */
        @Volatile var imagesRelues = 0L

        private var pret = false
        private var dernier = 0L
        private var reste = 0.0
        private var comptees = 0
        private var depuis = 0L

        override fun onSurfaceCreated(g: GL10?, c: EGLConfig?) {
            android.opengl.GLES20.glClearColor(0f, 0f, 0f, 1f)
            /*
             * Tampon cree UNE FOIS, assez grand pour toutes les finesses.
             *
             * Il faisait 480 sur 272 — la definition native. Des la finesse
             * x2 le coeur dessinait plus grand que lui. On le taille donc
             * d'emblee pour x4, et on redescend si la carte refuse.
             */
            var l = 480 * 4; var h = 272 * 4
            while (l >= 480 && !coeur.glInit(l, h)) { l /= 2; h /= 2 }
            pret = l >= 480
            noter("tampon de rendu : " + l + " x " + h)
            noter("contexte OpenGL prêt : " + (if (pret) "oui" else "NON"))
        }

        override fun onSurfaceChanged(g: GL10?, w: Int, h: Int) {
            vueL = w; vueH = h
            noter("surface OpenGL : " + w + " x " + h)
        }

        override fun onDrawFrame(g: GL10?) {
            android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, 0)
            android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT)
            if (!pret || !coeur.romChargee) return

            // Cadence : on suit celle annoncee par le coeur, sans jamais
            // rattraper plus d'une image de retard — un long blocage ne doit
            // pas provoquer une rafale.
            val ns = System.nanoTime()
            if (dernier == 0L) dernier = ns
            var ecoule = (ns - dernier).toDouble()
            dernier = ns
            val visee = if (coeur.imagesParSeconde > 10) coeur.imagesParSeconde else 60.0
            val parImage = 1_000_000_000.0 / visee
            if (ecoule > 250_000_000.0) ecoule = parImage
            reste += ecoule
            val tours = if (avanceRapide) 3 else if (reste >= parImage * 0.9) 1 else 0
            if (tours == 0) return
            reste = (reste - parImage * tours).coerceIn(0.0, parImage)

            repeat(tours) {
                coeur.image(boutons, sx, sy)
                val n = coeur.son()
                if (n > 0 && !avanceRapide) son?.jouer(coeur.echantillons, n)
            }
            comptees += tours
            if (ns - depuis > 1_000_000_000L) {
                vue.cadence = comptees * 1e9 / (ns - depuis)
                comptees = 0; depuis = ns

            }
            // On dessine directement la texture du coeur dans le rectangle de
            // l'ecran, que l'habillage laisse transparent.
            // Une seule voie : l'image est relue puis peinte dans
            // l'habillage. Aucune superposition de couches, donc aucune des
            // difficultes de composition qui nous ont couté tant d'essais.
            if (coeur.lireImage()) {
                vue.poserImage(coeur.pixels, coeur.imageL, coeur.imageH)
                imagesRelues++
            }
        }
    }

    private lateinit var rendu: Rendu

    private fun creerCoucheGl(): GLSurfaceView {
        val v = GLSurfaceView(this)
        // La surface reste derriere les vues ordinaires : l'habillage se
        // dessine par-dessus, et le rectangle qu'il laisse vide montre le jeu.
        v.setZOrderMediaOverlay(false)
        v.holder.setFormat(PixelFormat.RGBA_8888)
        v.setEGLContextClientVersion(3)
        v.setEGLConfigChooser(8, 8, 8, 8, 16, 8)
        v.preserveEGLContextOnPause = true
        rendu = Rendu()
        v.setRenderer(rendu)
        v.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        return v
    }

    // ================= barre de commandes =================

    private fun bouton(texte: String, action: () -> Unit): Button {
        val d = resources.displayMetrics.density
        return Button(this).apply {
            text = texte
            textSize = 12f
            isAllCaps = false
            setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
            setOnClickListener {
                if (::barre.isInitialized) {
                    barre.removeCallbacks(replier)
                    barre.postDelayed(replier, 6000L)
                }
                action()
            }
        }
    }

    private fun construireBarre(): HorizontalScrollView {
        val d = resources.displayMetrics.density
        val ligne = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())
        }
        ligne.addView(bouton("Jeux") { ouvrirCatalogue() })
        ligne.addView(bouton("Dossier") { choisirDossier.launch(null) })
        ligne.addView(bouton("Sauver") { sauver() })
        ligne.addView(bouton("Charger") { charger() })
        bQualite = bouton("Natif") { appliquerQualite(qualite + 1); relancerPourQualite() }
        ligne.addView(bQualite)
        bLissage = bouton("Lissé") {
            vue.lissage = !vue.lissage
            prefs.edit().putBoolean("lissage", vue.lissage).apply()
            bLissage.text = if (vue.lissage) "Lissé" else "Net"
        }
        ligne.addView(bLissage)
        ligne.addView(bouton("Réglages") { ouvrirEssais() })
        ligne.addView(bouton("Reset") {
            gl.queueEvent { coeur.reinitialiser(); coeur.sonVider() }
            son?.vider()
        })
        ligne.addView(bouton("Modifier") {
            vue.modeEdition = !vue.modeEdition
            Toast.makeText(this, if (vue.modeEdition)
                "Déplace les touches et l'écran, puis réappuie" else "Disposition enregistrée",
                Toast.LENGTH_SHORT).show()
        })
        ligne.addView(bouton("Défaut") { vue.reinitialiserDisposition() })
        ligne.addView(bouton("État") {
            android.app.AlertDialog.Builder(this)
                .setTitle("État")
                .setMessage(vue.diagnostic() + "\n" +
                    "définition : " + CoeurPSP.LIBELLES[qualite - 1] + "\n" +
                    "jeu : " + (jeuCourant?.name ?: "aucun"))
                .setPositiveButton("Fermer", null).show()
        })
        ligne.addView(bouton("Journal") { ouvrirJournal() })
        return HorizontalScrollView(this).apply {
            addView(ligne)
            setBackgroundColor(0xCC15151A.toInt())
        }
    }

    // ================= finesse =================

    private fun appliquerQualite(n: Int) {
        val nb = CoeurPSP.RESOLUTIONS.size
        qualite = ((n - 1).mod(nb)) + 1
        prefs.edit().putInt("qualite", qualite).apply()
        gl.queueEvent {
            coeur.qualite(qualite)
            // L'image relue garde une taille constante : c'est la carte qui
            // reduit, et ce moyennage est precisement ce qui lisse.
            coeur.reduction(960)
        }
        if (::bQualite.isInitialized) bQualite.text = CoeurPSP.LIBELLES[qualite - 1]
    }

    /**
     * Relance le jeu apres un changement de finesse.
     *
     * PPSSPP ne lit cette option qu'au chargement. On sauvegarde la partie, on
     * relance, on la restaure : la finesse change sans repartir du debut.
     */
    private fun relancerPourQualite() {
        val f = jeuCourant
        val res = CoeurPSP.LIBELLES[qualite - 1]
        if (f == null || !coeur.romChargee) {
            Toast.makeText(this, "$res — prend effet au chargement du jeu",
                           Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "$res — relance du jeu…", Toast.LENGTH_SHORT).show()
        gl.queueEvent {
            val etat = try { coeur.sauverEtat() } catch (_: Throwable) { null }
            coeur.eteindre()
            coeur.renduLogiciel(prefs.getBoolean("logiciel", true))
            coeur.sonVider()
            coeur.qualite(qualite)
            val ok = coeur.chargerJeu(f)
            if (ok && etat != null && reprendreLaPartie) {
                val repris = try { coeur.restaurerEtat(etat) } catch (_: Throwable) { false }
                if (!repris) coeur.reinitialiser()
            }
            coeur.sonVider()
            noter("finesse " + res + " : " + (if (ok) "appliquée" else "échouée"))
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                son?.vider()
                if (!ok) Toast.makeText(this, coeur.derniereErreur, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ================= reglages =================

    private fun appliquerEssais() {
        gl.queueEvent {
            for ((cle, _, valeurs) in ESSAIS)
                coeur.reglage(cle, prefs.getString("opt_$cle", valeurs[0]) ?: valeurs[0])
        }
    }

    /**
     * Remet les reglages risques a leur valeur sure apres un arret brutal.
     *
     * Trois des reglages proposes sont marques « peut planter » : le
     * recompilateur JIT, qui ecrit du code executable qu'Android refuse de
     * plus en plus ; l'acces memoire rapide, qui supprime les verifications
     * de bornes ; et le rendu direct. Choisis ensemble, ils font tomber
     * l'emulation au bout de quelques images.
     *
     * Le probleme, c'est qu'ils sont gardes d'une fois sur l'autre : une fois
     * l'application tombee, elle retombait au lancement suivant, sans qu'on
     * puisse deviner que la cause etait un reglage. On les ramene donc au
     * premier choix — le sur — des qu'une chute est constatee, et on le dit.
     */
    private fun rattraperApresChute(): Boolean {
        /* Deux sortes de chute : une erreur Java, notee dans chute.txt, et un
           arret natif, que le gestionnaire de signal marque de son cote. La
           seconde est justement celle que provoquent ces reglages. */
        val marques = listOf(File(filesDir, "chute.txt"),
                             File(coeur.dossierSysteme, "chute_native"))
        val trouvee = marques.firstOrNull { it.exists() } ?: return false
        var change = false
        val e = prefs.edit()
        for ((cle, _, valeurs) in ESSAIS) {
            val actuel = prefs.getString("opt_$cle", valeurs[0])
            if (actuel != valeurs[0]) { e.putString("opt_$cle", valeurs[0]); change = true }
        }
        e.apply()
        /* On efface la MARQUE, jamais le journal : c'est lui qui dit ce qui
           s'est passe, et il doit rester lisible depuis le menu. */
        File(coeur.dossierSysteme, "chute_native").delete()
        noter("chute constatée (" + trouvee.name + ")" +
              (if (change) " : réglages risqués remis au réglage sûr" else ""))
        return change
    }

    private fun ouvrirEssais() {
        val d = resources.displayMetrics.density
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
        }
        for ((cle, libelle, valeurs) in ESSAIS) {
            val actuel = prefs.getString("opt_$cle", valeurs[0]) ?: valeurs[0]
            ll.addView(CheckBox(this).apply {
                text = libelle + "  (" + actuel + ")"
                textSize = 13.5f
                isChecked = actuel == valeurs[1]
                setOnCheckedChangeListener { _, v ->
                    val nouveau = if (v) valeurs[1] else valeurs[0]
                    prefs.edit().putString("opt_$cle", nouveau).apply()
                    appliquerEssais()
                    text = libelle + "  (" + nouveau + ")"
                }
            })
        }
        ll.addView(CheckBox(this).apply {
            text = "Rendu matériel (plus rapide, écran parfois noir)"
            textSize = 13.5f
            isChecked = !prefs.getBoolean("logiciel", true)
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("logiciel", !v).apply()
                Toast.makeText(this@MainActivity,
                    "Prend effet au prochain chargement de jeu", Toast.LENGTH_SHORT).show()
            }
        })
        ll.addView(CheckBox(this).apply {
            text = "Reprendre la partie après un changement de finesse"
            textSize = 13.5f
            isChecked = reprendreLaPartie
            setOnCheckedChangeListener { _, v ->
                reprendreLaPartie = v
                prefs.edit().putBoolean("reprise", v).apply()
            }
        })
        ll.addView(TextView(this).apply {
            text = "\nLes réglages du cœur prennent effet au prochain chargement de jeu."
            textSize = 12f
        })
        android.app.AlertDialog.Builder(this)
            .setTitle("Réglages")
            .setView(ScrollView(this).apply { addView(ll) })
            .setPositiveButton("Fermer", null)
            .show()
    }

    // ================= catalogue et chargement =================

    /**
     * Affiche la liste des jeux DANS l'ecran de la console.
     *
     * Une fenetre par-dessus la console cassait l'illusion : le catalogue est
     * desormais dessine sur la dalle elle-meme, et se parcourt au doigt.
     */
    private fun ouvrirCatalogue() {
        if (Bibliotheque.dossier(this) == null) { choisirDossier.launch(null); return }
        romsAffichees = Bibliotheque.lister(this)
        vue.listeJeux = romsAffichees.map { it.nom }
        vue.listeVisible = true
        if (romsAffichees.isEmpty())
            Toast.makeText(this, "Aucun jeu PSP dans ce dossier — bouton Dossier",
                           Toast.LENGTH_LONG).show()
    }

    /**
     * Fichiers gardes ouverts.
     *
     * Tant que le jeu tourne, son descripteur doit rester ouvert : c'est lui
     * qui donne au coeur le droit de lire le fichier sans copie.
     */
    private val descripteurs = ArrayList<android.os.ParcelFileDescriptor>()

    private fun copier(uri: Uri, f: File) {
        try {
            contentResolver.openInputStream(uri)?.use { e ->
                f.outputStream().use { e.copyTo(it, 1 shl 20) }
            }
        } catch (e: Throwable) {
            // un disque plein laisse un fichier tronque : on le retire
            try { f.delete() } catch (_: Throwable) {}
            throw e
        }
    }

    /** Extrait le premier jeu trouve dans une archive. */
    private fun extraireZip(uri: Uri, dossier: File): File? {
        dossier.mkdirs()
        val priorite = listOf("iso", "cso", "pbp", "chd", "elf", "prx")
        contentResolver.openInputStream(uri)?.use { flux ->
            ZipInputStream(flux.buffered()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        val nom = File(e.name).name
                        if (priorite.any { nom.lowercase().endsWith("." + it) }) {
                            val dst = File(dossier, nom)
                            dst.outputStream().use { z.copyTo(it) }
                            return dst
                        }
                    }
                    e = z.nextEntry
                }
            }
        }
        val fichiers = dossier.listFiles() ?: return null
        for (ext in priorite)
            fichiers.firstOrNull { it.extension.lowercase() == ext }?.let { return it }
        return fichiers.filter { it.isFile && it.length() > 1024 * 1024 }.maxByOrNull { it.length() }
    }

    private fun chargerDepuis(uri: Uri, nom: String) {
        Toast.makeText(this, "Préparation de " + nom + "…", Toast.LENGTH_SHORT).show()
        Thread {
            var erreur = ""
            var cible: File? = null
            try {
                val local = File(cacheDir, "jeux/" + nom.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                local.parentFile?.mkdirs()
                if (nom.lowercase().endsWith(".zip")) {
                    cible = extraireZip(uri, local)
                    if (cible == null) erreur = "Aucun jeu PSP dans cette archive"
                } else {
                    val doc = DocumentFile.fromSingleUri(this, uri)
                    val taille = doc?.length() ?: -1L
                    val f = File(local.parentFile, local.name)

                    /*
                     * Les jeux PSP ne sont pas des ROMs de quelques centaines
                     * de kilooctets : une image atteint le gigaoctet et demi.
                     * On verifie donc la place AVANT de copier, sinon la copie
                     * remplit la memoire et le systeme ferme l'application —
                     * ce qui arrivait a chaque lancement.
                     */
                    /*
                     * D'abord, essayer de LIRE LE JEU LA OU IL EST.
                     *
                     * Android expose le fichier ouvert sous /proc/self/fd, un
                     * chemin que le coeur sait ouvrir. Cela evite de recopier
                     * un gigaoctet et demi dans la memoire de l'application.
                     */
                    val direct = try {
                        contentResolver.openFileDescriptor(uri, "r")?.let { fd ->
                            descripteurs.add(fd)
                            File("/proc/self/fd/" + fd.fd)
                        }
                    } catch (_: Throwable) { null }

                    if (direct != null && direct.exists() && direct.length() > 0) {
                        noter("jeu lu directement : " + direct.absolutePath +
                              " (" + (direct.length() / 1048576) + " Mo)")
                        cible = direct
                    } else if (!f.exists() || f.length() != taille) {
                        val libre = cacheDir.usableSpace
                        noter("jeu de " + (taille / 1048576) + " Mo, place libre " +
                              (libre / 1048576) + " Mo")
                        if (taille > 0 && libre < taille + 200L * 1048576) {
                            erreur = "Pas assez de place : le jeu fait " +
                                     (taille / 1048576) + " Mo et il reste " +
                                     (libre / 1048576) + " Mo"
                            noter(erreur)
                            cible = null
                        } else {
                            noter("copie du jeu…")
                            copier(uri, f)
                            noter("copie terminée")
                            cible = f
                        }
                    } else {
                        noter("jeu déjà présent, pas de copie")
                        cible = f
                    }
                }
            } catch (e: Exception) { erreur = "Préparation impossible : " + e.message }

            val f = cible
            if (f == null) {
                runOnUiThread {
                    if (!isFinishing) Toast.makeText(this, erreur, Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            noter("fichier prêt : " + f.absolutePath + " (" + f.length() / 1024 + " Ko)")
            jeuCourant = f
            gl.queueEvent {
                coeur.eteindre()
                coeur.sonVider()
                coeur.renduLogiciel(prefs.getBoolean("logiciel", true))
                coeur.qualite(qualite)
                coeur.reduction(960)
                var ok = coeur.chargerJeu(f)
                if (!ok && f.absolutePath.startsWith("/proc/self/fd/")) {
                    // Le coeur n'a pas voulu du chemin direct : on recopie.
                    noter("chemin direct refusé, copie du jeu")
                    runOnUiThread {
                        if (!isFinishing)
                            Toast.makeText(this, "Copie du jeu…", Toast.LENGTH_SHORT).show()
                    }
                    val local = File(cacheDir, "jeux").apply { mkdirs() }
                    val dst = File(local, nom.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                    try {
                        if (!dst.exists() || dst.length() != f.length()) copier(uri, dst)
                        jeuCourant = dst
                        ok = coeur.chargerJeu(dst)
                    } catch (e: Throwable) {
                        noter("copie impossible : " + e)
                    }
                }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (ok) {
                        son?.liberer()
                        son = Son(coeur.frequence)
                        vue.listeVisible = false
                        vue.ecranVide = false
                        barre.visibility = View.GONE
                        Toast.makeText(this, "Lancement de " + nom, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, coeur.derniereErreur, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    // ================= sauvegardes =================

    private fun fichierEtat(): File =
        File(coeur.dossierSysteme, "etats").apply { mkdirs() }
            .let { File(it, coeur.cle + ".etat") }

    private fun sauver() {
        if (!coeur.romChargee) { Toast.makeText(this, "Aucun jeu", Toast.LENGTH_SHORT).show(); return }
        gl.queueEvent {
            val e = coeur.sauverEtat()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (e == null) Toast.makeText(this, "Sauvegarde impossible", Toast.LENGTH_SHORT).show()
                else {
                    try { fichierEtat().writeBytes(e)
                        Toast.makeText(this, "Partie sauvegardée", Toast.LENGTH_SHORT).show()
                    } catch (ex: Exception) {
                        Toast.makeText(this, "Écriture impossible : " + ex.message,
                                       Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun charger() {
        if (!coeur.romChargee) { Toast.makeText(this, "Aucun jeu", Toast.LENGTH_SHORT).show(); return }
        val f = fichierEtat()
        if (!f.exists()) { Toast.makeText(this, "Aucune sauvegarde", Toast.LENGTH_SHORT).show(); return }
        val octets = try { f.readBytes() } catch (_: Exception) { null } ?: return
        gl.queueEvent {
            val ok = coeur.restaurerEtat(octets)
            coeur.sonVider()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                son?.vider()
                Toast.makeText(this, if (ok) "Partie chargée" else "Chargement impossible",
                               Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ================= journal =================

    private fun ouvrirJournal() {
        val texte = try {
            val parts = ArrayList<String>()
            val fa = File(coeur.dossierSysteme, "journal_appli.txt")
            if (fa.exists()) parts.add("== APPLICATION ==\n" +
                fa.readLines().takeLast(120).joinToString("\n"))
            val fc = File(coeur.dossierSysteme, "journal.txt")
            if (fc.exists()) parts.add("== CŒUR ==\n" +
                fc.readLines().takeLast(80).joinToString("\n"))
            val ch = File(filesDir, "chute.txt")
            if (ch.exists()) parts.add("== DERNIÈRE CHUTE ==\n" + ch.readText())
            if (parts.isEmpty()) "(journal vide)" else parts.joinToString("\n\n")
        } catch (e: Exception) { "Lecture impossible : " + e.message }
        val d = resources.displayMetrics.density
        val v = TextView(this).apply {
            text = texte
            textSize = 10.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Journal")
            .setView(ScrollView(this).apply { addView(v) })
            .setPositiveButton("Fermer", null)
            .setNeutralButton("Copier") { _, _ ->
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("journal", texte))
                Toast.makeText(this, "Journal copié", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Vider") { _, _ ->
                try {
                    File(coeur.dossierSysteme, "journal_appli.txt").writeText("")
                    File(coeur.dossierSysteme, "journal.txt").writeText("")
                    File(filesDir, "chute.txt").delete()
                } catch (_: Exception) {}
            }
            .show()
    }

    override fun onPause() {
        super.onPause()
        if (construite) gl.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (construite) gl.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        for (d in descripteurs) try { d.close() } catch (_: Throwable) {}
        descripteurs.clear()
        son?.liberer()
    }
}
