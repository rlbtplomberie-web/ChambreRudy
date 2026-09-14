package com.skin3ds.app

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
import com.skin3ds.app.core.Coeur3DS
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

    private lateinit var coeur: Coeur3DS
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

    private val prefs by lazy { getSharedPreferences("skin_3ds", MODE_PRIVATE) }

    /** Reglages du coeur qu'on peut essayer si un jeu se comporte mal. */
    private val ESSAIS = listOf(
        Triple("citra_texture_filtering", "Filtrage des textures",
               listOf("Auto", "Linear")),
        Triple("citra_texture_scaling_level", "Agrandissement des textures",
               listOf("1x", "2x")),
        Triple("citra_texture_deposterize", "Adoucir les aplats",
               listOf("enabled", "disabled")),
        Triple("citra_cpu_core", "Recompilateur rapide (peut planter)",
               listOf("IR JIT", "JIT")),
        Triple("citra_fast_memory", "Accès mémoire rapide (peut planter)",
               listOf("disabled", "enabled")),
        Triple("citra_rendering_mode", "Rendu direct (plus rapide)",
               listOf("buffered", "skip")))

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
     * le haut de la console pendant le jeu. Elle se replie desormais au bout
     * de quelques secondes, et le compte a rebours repart a chaque usage.
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
            coeur = Coeur3DS(this)
            noter("")
            noter("--- démarrage " + java.text.SimpleDateFormat(
                "HH:mm:ss", java.util.Locale.FRANCE).format(java.util.Date()) + " ---")
            if (!coeur.pret) noter(coeur.derniereErreur)

            dire("installation des fichiers Citra…")
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
     * Copie les fichiers systeme de Citra.
     *
     * A appeler depuis un fil de FOND : huit megaoctets et demi en vingt-sept
     * fichiers. Sur le fil principal, cette copie bloque l'affichage, et
     * Android ferme une application qu'il croit figee.
     */
    /**
     * Installe les donnees systeme de la 3DS.
     *
     * Citra ne demarre pas un jeu sans elles : la police partagee de la
     * console, les cles, et l'arborescence NAND. Elles viennent de
     * l'application de reference.
     *
     * A appeler depuis un fil de FOND : la copie bloque l'affichage, et
     * Android ferme une application qu'il croit figee.
     */
    private fun installerFichiers(): String {
        val racine = coeur.dossierSysteme
        val temoin = File(racine, ".sysdata_v1")
        if (temoin.exists()) return "données système déjà en place"
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
        // « systeme » appartient deja a la PSP dans cette application :
        // ses fichiers a lui sont ranges sous « systeme_3ds ». C'est la seule
        // ligne de son projet qui differe de l'original.
        for (x in assets.list("systeme_3ds") ?: emptyArray())
            copier("systeme_3ds/$x", File(racine, x))
        temoin.writeText("ok")
        return "données système installées (" + n + ")"
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

        vue.surCommandes = { b, gx, gy, dx, dy, avance ->
            rendu.boutons = b; rendu.sx = gx; rendu.sy = gy
            rendu.sdx = dx; rendu.sdy = dy; rendu.avanceRapide = avance
        }
        vue.surStylet = { fx, fy -> gl.queueEvent { coeur.stylet(fx, fy) } }
        vue.surChange = {
            Toast.makeText(this,
                Dispositions.PAYSAGES[vue.rangPaysage].libelle, Toast.LENGTH_SHORT).show()
        }
        vue.surMenu = { basculerBarre() }
        vue.surJeux = { ouvrirCatalogue() }
        vue.surChoixJeu = { i ->
            if (i in romsAffichees.indices) {
                vue.listeVisible = false
                chargerDepuis(romsAffichees[i].uri, romsAffichees[i].nom)
            }
        }

        vue.surErreur = { l -> noter(l) }
        vue.lissage = prefs.getBoolean("lissage", true)
        reprendreLaPartie = prefs.getBoolean("reprise", true)

        appliquerQualite(prefs.getInt("qualite", 1))
        appliquerEssais()
        gl.queueEvent { coeur.convention(prefs.getInt("convention", 0)) }
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
        @Volatile var sdx = 0f
        @Volatile var sdy = 0f
        @Volatile var vueL = 0
        @Volatile var vueH = 0
        @Volatile var imagesRelues = 0L

        private var pret = false
        private var dernier = 0L
        private var reste = 0.0
        private var comptees = 0
        private var depuis = 0L

        override fun onSurfaceCreated(g: GL10?, c: EGLConfig?) {
            android.opengl.GLES20.glClearColor(0f, 0f, 0f, 1f)
            pret = coeur.glInit(480, 272)
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
                // Ligne d'etat : elle dit si le coeur avance, s'il dessine, et
                // ce qui arrive jusqu'a l'ecran.
                vue.diagnostic = "%.0f i/s · %d img · %dx%d · clarté %d · %s".format(
                    vue.cadence, coeur.imagesEmulees, coeur.imageL, coeur.imageH,
                    coeur.clarte, if (coeur.enLogiciel) "logiciel" else "matériel")
            }
            // On dessine directement la texture du coeur dans le rectangle de
            // l'ecran, que l'habillage laisse transparent.
            // Une seule voie : l'image est relue puis peinte dans
            // l'habillage, qui la coupe en deux ecrans.
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
                // chaque usage repousse le repli automatique
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
                    coeur.styletEtat + "\n" +
                    "définition : " + Coeur3DS.LIBELLES[qualite - 1] + "\n" +
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
        val nb = Coeur3DS.RESOLUTIONS.size
        qualite = ((n - 1).mod(nb)) + 1
        prefs.edit().putInt("qualite", qualite).apply()
        gl.queueEvent {
            coeur.qualite(qualite)
            // L'image relue garde une taille constante : c'est la carte qui
            // reduit, et ce moyennage est precisement ce qui lisse.
            coeur.reduction(960)
        }
        if (::bQualite.isInitialized) bQualite.text = Coeur3DS.LIBELLES[qualite - 1]
    }

    /**
     * Relance le jeu apres un changement de finesse.
     *
     * Citra ne lit cette option qu'au chargement. On sauvegarde la partie, on
     * relance, on la restaure : la finesse change sans repartir du debut.
     */
    private fun relancerPourQualite() {
        val f = jeuCourant
        val res = Coeur3DS.LIBELLES[qualite - 1]
        if (f == null || !coeur.romChargee) {
            Toast.makeText(this, "$res — prend effet au chargement du jeu",
                           Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "$res — relance du jeu…", Toast.LENGTH_SHORT).show()
        gl.queueEvent {
            val etat = try { coeur.sauverEtat() } catch (_: Throwable) { null }
            coeur.eteindre()
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
        gl.queueEvent { coeur.convention(prefs.getInt("convention", 0)) }
                    text = libelle + "  (" + nouveau + ")"
                }
            })
        }
        ll.addView(CheckBox(this).apply {
            text = "Stylet : repère sur l'écran du bas"
            textSize = 13.5f
            isChecked = prefs.getInt("convention", 0) == 1
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putInt("convention", if (v) 1 else 0).apply()
                gl.queueEvent { coeur.convention(if (v) 1 else 0) }
                Toast.makeText(this@MainActivity,
                    "Touche l'écran du bas pour essayer", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Aucun jeu 3DS dans ce dossier — bouton Dossier",
                           Toast.LENGTH_LONG).show()
    }

    private fun copier(uri: Uri, f: File) {
        contentResolver.openInputStream(uri)?.use { e -> f.outputStream().use { e.copyTo(it) } }
    }

    /** Extrait le premier jeu trouve dans une archive. */
    private fun extraireZip(uri: Uri, dossier: File): File? {
        dossier.mkdirs()
        val priorite = listOf("3ds", "cci", "cxi", "cia", "3dsx", "app", "elf")
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
                    if (cible == null) erreur = "Aucun jeu Nintendo 3DS dans cette archive"
                } else {
                    val doc = DocumentFile.fromSingleUri(this, uri)
                    val f = File(local.parentFile, local.name)
                    if (!f.exists() || f.length() != (doc?.length() ?: -1L)) copier(uri, f)
                    cible = f
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
                coeur.qualite(qualite)
                coeur.reduction(960)
                val ok = coeur.chargerJeu(f)
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
        son?.liberer()
    }
}
