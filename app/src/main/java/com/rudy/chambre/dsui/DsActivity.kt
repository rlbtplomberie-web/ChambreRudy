package com.rudy.chambre.dsui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import com.rudy.chambre.dsui.core.CoeurDS
import java.io.File

class DsActivity : ComponentActivity() {

    private lateinit var vue: SkinView
    private lateinit var gl: VueGL
    private lateinit var barre: HorizontalScrollView
    private lateinit var coeur: CoeurDS
    private var son: Son? = null
    private var romsAffichees: List<Rom> = emptyList()
    private lateinit var bQualite: Button
    private lateinit var bCadence: Button
    private lateinit var bLissage: Button
    /**
     * Reglages a essayer quand un jeu ne demarre pas. Chacun est un couple
     * option / valeurs possibles, avec les noms exacts que le coeur reclame.
     */
    private val ESSAIS = listOf(
        // Premiere valeur : celle appliquee par defaut.
        Triple("melonds_touch_mode", "Stylet en mode pointeur", listOf("Touch", "Pointer")),
        Triple("melonds_show_cursor", "Curseur dessiné par le cœur", listOf("disabled", "enabled")),
        Triple("melonds_render_mode", "Rendu logiciel", listOf("opengl", "software")),
        Triple("melonds_screen_layout1", "Disposition interne des écrans",
               listOf("Top/Bottom", "Bottom/Top")),
        Triple("melonds_boot_mode", "Démarrage par le menu", listOf("direct", "native")))

    private val prefs by lazy { getSharedPreferences("skin_n64", MODE_PRIVATE) }

    /**
     * Journal de l'application, a cote de celui du coeur. Le coeur ne voit pas
     * ce qui se passe avant lui — installation du catalogue, copie du jeu — et
     * c'est justement la que les choses peuvent echouer.
     */
    /**
     * Capte les exceptions fatales et les ecrit dans le journal avant que
     * l'application ne se ferme. Sans cela, une erreur Java fait disparaitre
     * l'application sans laisser la moindre trace lisible depuis le telephone.
     */
    private fun installerCapteur() {
        val precedent = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { fil, e ->
            try {
                val trace = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(trace))
                File(coeur.dossierSysteme, "journal_appli.txt").appendText(
                    "\n!!! ARRET BRUTAL sur le fil " + fil.name + "\n" + trace.toString() + "\n")
            } catch (_: Throwable) {}
            precedent?.uncaughtException(fil, e)
        }
    }

    private fun noter(ligne: String) {
        try {
            File(coeur.dossierSysteme, "journal_appli.txt").appendText(
                ligne.trimEnd() + "\n")
        } catch (_: Exception) {}
    }
    private val dossierJeux by lazy { File(filesDir, "jeux").apply { mkdirs() } }
    private var nomJeu = ""
    private var imagesVues = 0
    private var tracesBoutons = 0
    /** Fichier du jeu en cours : la resolution ne changeant qu'au chargement,
     *  il faut pouvoir le relancer. */
    private var jeuCourant: java.io.File? = null
    /** Reprendre la partie apres un changement de finesse. */
    private var reprendreLaPartie = true

    // ================= barre =================
    private val replieur = Runnable { if (!vue.modeEdition) replier(true) }

    private fun replier(oui: Boolean) {
        barre.visibility = if (oui) View.GONE else View.VISIBLE
        barre.removeCallbacks(replieur)
        if (!oui) barre.postDelayed(replieur, 4000)
    }

    private fun repousserRepli() {
        barre.removeCallbacks(replieur)
        if (barre.visibility == View.VISIBLE) barre.postDelayed(replieur, 4000)
    }

    // ================= fichiers =================
    private val choisirJeu = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) chargerDepuis(it)
    }
    private val choisirDossier = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        if (it == null) return@registerForActivityResult
        Bibliotheque.retenir(this, it); ouvrirCatalogue()
    }
    // ================= jeux =================
    /**
     * Mupen64Plus-Next lit le jeu par son chemin. Android n'en donne pas sur un fichier
     * choisi : on copie donc le jeu dans le stockage de l'application. Un .gdi
     * entraine tous ses fichiers de pistes, qui sont nombreux.
     */
    private fun chargerDepuis(uri: Uri) {
        noter("chargerDepuis : " + uri)
        vue.fermerListe()
        val doc = DocumentFile.fromSingleUri(this, uri)
        if (doc == null) { noter("ECHEC : fichier inaccessible"); return }
        val nom = doc.name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "jeu"
        noter("nom : " + nom + ", taille annoncee " + doc.length())
        val local = File(dossierJeux, nom.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._ -]"), "_"))
        Toast.makeText(this, "Préparation de $nom…", Toast.LENGTH_SHORT).show()
        Thread {
            var cible: File? = null
            var erreur = ""
            noter("preparation de : " + nom)
            try {
                local.mkdirs()
                val ext = nom.substringAfterLast('.', "").lowercase()
                    if (ext == "zip") {
                    cible = extraireZip(uri, local)
                    if (cible == null)
                        erreur = "Aucune ROM Nintendo DS dans cette archive (.nds attendu)"
                } else {
                    val f = File(local, nom)
                    if (!f.exists() || f.length() != doc.length()) copier(uri, f)
                    cible = f
                    if (ext == "gdi" || ext == "cue" || ext == "m3u") copierPistes(f, uri)
                }
            } catch (e: Exception) { erreur = "Copie impossible : ${e.message}" }
            val f = cible
            if (f == null) {
                noter("ECHEC de la preparation : " + erreur)
                runOnUiThread { Toast.makeText(this, erreur, Toast.LENGTH_LONG).show() }
                return@Thread
            }
            noter("fichier pret : " + f.absolutePath + " (" + f.length() / 1024 + " Ko)")
            noter("contenu du dossier : " + (f.parentFile?.listFiles()
                    ?.joinToString(", ") { it.name + " " + it.length() / 1024 + "Ko" } ?: "?"))
            // Le chargement se fait sur le fil OpenGL : Mupen64Plus-Next y cree ses
            // ressources graphiques, et le faire ailleurs fait tomber l'appli.
            jeuCourant = f
            gl.surFilGl {
                noter("appel du coeur sur le fil OpenGL")
                val ok = coeur.chargerJeu(f)
                noter(if (ok) "coeur : jeu accepte" else "coeur : refus — " + coeur.derniereErreur)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (ok) {
                        vue.trouEcran = coeur.rendulMateriel
                        noter("affichage : " + (if (vue.trouEcran)
                            "OpenGL direct, résolution libre" else "relecture, résolution native"))
                        son?.liberer(); son = Son(coeur.frequence)
                        gl.surSon = { ech, n -> son?.jouer(ech, n) }
                        nomJeu = f.name
                        vue.ecranVide = false
                        // les cartes memoire virtuelles sont creees par le coeur
                        // dans le dossier de sauvegarde : on note ce qu'il y a
                        noter("dossier de sauvegarde : " + (coeur.dossierSysteme.listFiles()
                                ?.joinToString(", ") { it.name + " " + it.length() / 1024 + "Ko" }
                                ?.ifBlank { "vide" } ?: "illisible"))
                        Toast.makeText(this, "Jeu chargé", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, coeur.derniereErreur, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun copier(uri: Uri, f: File) {
        contentResolver.openInputStream(uri)!!.use { e -> f.outputStream().use { e.copyTo(it, 1 shl 20) } }
    }

    /** Copie tous les fichiers du meme dossier que le .gdi : ses pistes. */
    private fun copierPistes(index: File, uri: Uri) {
        val racine = Bibliotheque.dossier(this) ?: return
        val arbre = DocumentFile.fromTreeUri(this, racine) ?: return
        val parent = chercherParent(arbre, index.name, 0) ?: return
        for (e in parent.listFiles()) {
            val n = e.name ?: continue
            if (n == index.name) continue
            if (!n.lowercase().matches(Regex(""".*\.(bin|raw|iso|img|track\d*)$"""))) continue
            val dst = File(index.parentFile, n)
            if (!dst.exists() || dst.length() != e.length()) copier(e.uri, dst)
        }
    }

    private fun chercherParent(d: DocumentFile, nom: String, prof: Int): DocumentFile? {
        if (prof > 3) return null
        val enfants = d.listFiles()
        if (enfants.any { it.name == nom }) return d
        for (e in enfants) if (e.isDirectory) chercherParent(e, nom, prof + 1)?.let { return it }
        return null
    }

    private fun extraireZip(uri: Uri, dossier: File): File? {
        // Extensions Nintendo 64. Cette liste etait restee celle de la Dreamcast
        // — gdi, chd, cdi — si bien qu'aucune archive n'etait reconnue.
        val priorite = listOf("nds", "dsi", "ids", "srl")
        contentResolver.openInputStream(uri)!!.use { e ->
            java.util.zip.ZipInputStream(e).use { z ->
                while (true) {
                    val en = z.nextEntry ?: break
                    if (en.isDirectory) continue
                    val f = File(dossier, File(en.name).name)
                    if (!f.exists() || f.length() != en.size) f.outputStream().use { z.copyTo(it, 1 shl 20) }
                }
            }
        }
        val fichiers = dossier.listFiles() ?: return null
        noter("archive : " + fichiers.joinToString(", ") { it.name + " " + it.length() / 1024 + "Ko" })
        for (ext in priorite) fichiers.firstOrNull { it.extension.lowercase() == ext }?.let { return it }
        // Repli : le plus gros fichier de l'archive. Beaucoup de ROMs circulent
        // sans extension, ou avec une extension inattendue ; le coeur, lui,
        // reconnait le format a son contenu.
        val gros = fichiers.filter { it.isFile && it.length() > 1024 * 1024 }.maxByOrNull { it.length() }
        if (gros != null) noter("aucune extension connue : on tente " + gros.name)
        return gros
    }

    /**
     * Bouton CHANGE : fait defiler les quatre presentations horizontales,
     * dans l'ordre demande — deux ecrans egaux, deux ecrans inegaux, l'ecran
     * du haut seul, l'ecran du bas seul, puis retour au debut.
     */
    private fun changerPresentation() {
        if (resources.configuration.orientation !=
            android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            Toast.makeText(this, "Tourne le téléphone : les présentations sont horizontales",
                           Toast.LENGTH_SHORT).show()
            return
        }
        val n = vue.rangPaysage + 1
        vue.rangPaysage = n
        prefs.edit().putInt("rang_paysage", vue.rangPaysage).apply()
        val h = com.rudy.chambre.dsui.Dispositions.habillage(true, vue.rangPaysage)
        noter("présentation : " + h.libelle)
        Toast.makeText(this, h.libelle, Toast.LENGTH_SHORT).show()
    }

    private fun ouvrirCatalogue() {
        noter("bouton JEUX")
        if (vue.listeOuverte) { vue.fermerListe(); return }
        if (Bibliotheque.dossier(this) == null) {
            noter("aucun dossier retenu : ouverture du selecteur")
            choisirDossier.launch(null); return
        }
        Toast.makeText(this, "Lecture du dossier…", Toast.LENGTH_SHORT).show()
        Thread {
            val roms = Bibliotheque.lister(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (roms.isEmpty()) {
                    Toast.makeText(this, "Aucun jeu Nintendo DS dans ce dossier", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                noter("catalogue : " + roms.size + " jeux trouves")
                romsAffichees = roms
                vue.afficherListe(roms.map { it.nom })
            }
        }.start()
    }






    // ================= etats =================
    private fun fichierEtat(): File? =
        if (coeur.romChargee && coeur.cle.isNotEmpty()) File(filesDir, "etat_${coeur.cle}.bin") else null

    private fun sauverEtat() {
        val f = fichierEtat() ?: run { Toast.makeText(this, "Charge d'abord un jeu", Toast.LENGTH_SHORT).show(); return }
        gl.surFilGl {
            val o = coeur.sauverEtat()
            val msg = if (o == null) "Rien à sauvegarder" else try {
                f.writeBytes(o); "État sauvegardé"
            } catch (e: Exception) { "Écriture impossible" }
            runOnUiThread { if (!isFinishing) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun chargerEtat() {
        val f = fichierEtat() ?: run { Toast.makeText(this, "Charge d'abord un jeu", Toast.LENGTH_SHORT).show(); return }
        if (!f.exists()) { Toast.makeText(this, "Aucune sauvegarde pour ce jeu", Toast.LENGTH_SHORT).show(); return }
        val o = try { f.readBytes() } catch (e: Exception) { null }
        if (o == null) { Toast.makeText(this, "Lecture impossible", Toast.LENGTH_SHORT).show(); return }
        gl.surFilGl {
            val ok = coeur.restaurerEtat(o)
            runOnUiThread {
                if (!isFinishing)
                    Toast.makeText(this, if (ok) "État chargé" else "Sauvegarde incompatible",
                                   Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ================= reglages =================
    private var qualite = 1

    /** true quand le moteur graphique logiciel est actif. */

    private fun appliquerQualite(n: Int) {
        val nb = com.rudy.chambre.dsui.core.CoeurDS.RESOLUTIONS.size
        qualite = ((n - 1).mod(nb)) + 1
        prefs.edit().putInt("qualite", qualite).apply()
        gl.surFilGl { coeur.qualite(qualite) }
        gl.qualite = qualite
        noter("résolution interne demandée : " +
              com.rudy.chambre.dsui.core.CoeurDS.LIBELLES[qualite - 1])
        bQualite.text = com.rudy.chambre.dsui.core.CoeurDS.LIBELLES[qualite - 1]
    }

    /**
     * Explique pourquoi la resolution ne bouge pas, plutot que de laisser
     * croire a un bouton en panne.
     */
    /**
     * Relance le jeu apres un changement de resolution.
     *
     * Le coeur ne lit cette option qu'au chargement — elle est marquee
     * « restart » dans sa liste. La changer en cours de partie ne pouvait
     * donc rien faire, et c'est pourquoi le bouton semblait sans effet.
     */
    /**
     * Relance le jeu apres un changement de finesse.
     *
     * melonDS ne lit cette option qu'au chargement. On sauvegarde donc la
     * partie, on relance, on la restaure : la finesse change sans qu'on
     * reparte du debut.
     */
    private fun relancerPourQualite() {
        val f = jeuCourant
        val res = com.rudy.chambre.dsui.core.CoeurDS.LIBELLES[qualite - 1]
        if (f == null || !coeur.romChargee) {
            Toast.makeText(this, "$res — prend effet au chargement du jeu",
                           Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "$res — relance du jeu…", Toast.LENGTH_SHORT).show()
        gl.surFilGl {
            val etat = try { coeur.sauverEtat() } catch (e: Exception) { null }
            coeur.eteindre()
            coeur.sonVider()
            coeur.qualite(qualite)
            // Le tampon est redimensionne maintenant, aucun jeu n'etant
            // charge : le coeur n'a donc rien a refaire. Le faire apres
            // l'obligeait a reconstruire ses ressources en pleine partie.
            gl.ajusterTampon()
            val ok = coeur.chargerJeu(f)
            if (ok && etat != null && reprendreLaPartie) {
                val repris = try { coeur.restaurerEtat(etat) } catch (e: Exception) { false }
                noter("partie " + (if (repris) "reprise là où elle en était"
                                   else "non reprise : redémarrage au début"))
                if (!repris) coeur.reinitialiser()
            }
            coeur.sonVider()
            noter("finesse " + res + " : " + (if (ok) "appliquée" else "échouée"))
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                son?.vider()
                if (ok) vue.ecranVide = false
                else Toast.makeText(this, coeur.derniereErreur, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Ce que fait le bouton Natif, en clair. */
    private fun expliquerQualite() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Finesse de l'image")
            .setMessage(
                "Chaque écran de la console est dessiné en 256 × 192 points. " +
                "Ce réglage multiplie cette définition : à ×4, chaque écran est " +
                "calculé en 1024 × 768.\n\n" +
                "L'image affichée garde exactement la même taille — seule la " +
                "pixelisation diminue. La taille des écrans, elle, se règle dans " +
                "« Modifier », en déplaçant leurs rectangles.\n\n" +
                "Le changement relance le jeu, melonDS ne lisant ce réglage qu'au " +
                "chargement. La partie en cours est conservée.")
            .setPositiveButton("Compris", null)
            .show()
    }


    /** Zéro : la cadence de la console. Les autres la remplacent. */
    private val CADENCES = listOf(0, 30, 60, 120)

    private fun appliquerCadence(v: Int) {
        gl.cadenceCible = v
        prefs.edit().putInt("cadence", v).apply()
        bCadence.text = if (v == 0) "Cadence native" else "Cadence $v"
    }

    private fun cadenceSuivante() {
        val i = CADENCES.indexOf(gl.cadenceCible).let { if (it < 0) 0 else it }
        val v = CADENCES[(i + 1) % CADENCES.size]
        appliquerCadence(v)
        Toast.makeText(this, when (v) {
            0 -> "Cadence de la console : %.2f images/s".format(coeur.imagesParSeconde)
            30 -> "30 images/s — le jeu tourne à la moitié de sa vitesse"
            120 -> "120 images/s — le jeu tourne au double de sa vitesse"
            else -> "60 images/s"
        }, Toast.LENGTH_LONG).show()
    }

    private fun expliquerCadence() {
        android.app.AlertDialog.Builder(this)
            .setTitle("À propos de la cadence")
            .setMessage("Une console a une cadence fixe : la Nintendo DS produit " +
                "%.2f images par seconde, et sa musique comme sa vitesse de jeu en dépendent.\n\n"
                    .format(coeur.imagesParSeconde) +
                "Changer ce réglage ne rend donc pas le jeu plus fluide : il le fait tourner " +
                "plus lentement ou plus vite. À 30, tout se déroule à la moitié de la vitesse ; " +
                "à 120, au double.\n\n" +
                "Garde « native » pour jouer normalement. Les autres valeurs servent à ralentir " +
                "un passage difficile, ou à mesurer ce dont l'appareil est capable.")
            .setPositiveButton("Compris", null)
            .show()
    }


    private fun appliquerEssais() {
        gl.surFilGl {
            for ((cle, _, valeurs) in ESSAIS)
                coeur.option(cle, prefs.getString("opt_$cle", valeurs[0]) ?: valeurs[0])
        }
    }

    /**
     * Fenetre d'essais : les reglages du coeur qu'on peut changer quand un jeu
     * se comporte mal. Un seul a la fois, puis on relance le jeu.
     */
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
                    reprendreLaPartie = prefs.getBoolean("reprise", true)
        appliquerEssais()
                    text = libelle + "  (" + nouveau + ")"
                }
            })
        }
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
            text = "\nLes réglages du cœur prennent effet au prochain chargement de jeu. " +
                   "Change-en un seul à la fois, puis relance un jeu."
            textSize = 12f
        })
        android.app.AlertDialog.Builder(this)
            .setTitle("Réglages à essayer")
            .setView(ScrollView(this).apply { addView(ll) })
            .setPositiveButton("Fermer", null)
            .show()
    }

    /** Arrete le jeu en cours : le coeur est vide, l'ecran redevient noir. */
    private fun arreterJeu() {
        if (!coeur.romChargee) {
            Toast.makeText(this, "Aucun jeu en cours", Toast.LENGTH_SHORT).show(); return
        }
        noter("arret du jeu demande")
        gl.surFilGl {
            coeur.eteindre()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                son?.liberer(); son = null
                gl.surSon = null
                vue.ecranVide = true
                vue.trouEcran = false
                jeuCourant = null
                nomJeu = ""
                Toast.makeText(this, "Jeu arrêté", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Affiche le journal. C'est la seule facon de savoir pourquoi un jeu
     * refuse de demarrer, un logcat n'etant pas lisible depuis le telephone.
     */
    private fun ouvrirJournal() {
        val texte = try {
            val parts = ArrayList<String>()
            val fa = File(coeur.dossierSysteme, "journal_appli.txt")
            if (fa.exists()) parts.add("== APPLICATION ==\n" +
                fa.readLines().takeLast(150).joinToString("\n").trim())
            val fc = File(coeur.dossierSysteme, "journal.txt")
            if (fc.exists()) parts.add("== COEUR ==\n" + fc.readLines().takeLast(80).joinToString("\n"))
            if (parts.isEmpty()) "(journal vide)" else parts.joinToString("\n\n")
        } catch (e: Exception) { "Lecture impossible : ${e.message}" }
        val vueTexte = TextView(this).apply {
            text = texte; textSize = 10.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            val d = resources.displayMetrics.density
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Journal")
            .setView(ScrollView(this).apply { addView(vueTexte) })
            .setPositiveButton("Fermer", null)
            .setNegativeButton("Vider") { _, _ ->
                try {
                    File(coeur.dossierSysteme, "journal_appli.txt").writeText("")
                    File(coeur.dossierSysteme, "journal.txt").writeText("")
                } catch (_: Exception) {}
                Toast.makeText(this, "Journal vidé", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Copier") { _, _ ->
                val cb = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("journal", texte))
                Toast.makeText(this, "Journal copié", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * melonDS n'a besoin d'aucun fichier prealable : il embarque son propre
     * micrologiciel. Rien a installer, contrairement aux consoles precedentes.
     */
    private fun installerReglages(): String = "melonDS : aucun fichier requis"


    // ================= cycle de vie =================
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        bordABord()

        coeur = CoeurDS(this)
        installerCapteur()
        // On n'efface plus le journal au demarrage : l'application plante,
        // on la rouvre pour lire la trace... et l'ouverture l'effacait.
        // On se contente de le raccourcir quand il devient long.
        try {
            val j = File(coeur.dossierSysteme, "journal_appli.txt")
            if (j.length() > 60_000) j.writeText(j.readLines().takeLast(200).joinToString("\n") + "\n")
        } catch (_: Exception) {}
        noter("")
        noter("--- demarrage de l'application " + java.text.SimpleDateFormat(
            "HH:mm:ss", java.util.Locale.FRANCE).format(java.util.Date()) + " ---")
        noter("Android " + Build.VERSION.SDK_INT + ", application visant l'API "
              + applicationInfo.targetSdkVersion)
        val bilanBios = installerReglages()


        gl = VueGL(this, coeur)
        vue = SkinView(this)

        val racine = FrameLayout(this)
        // Pas de fond ici : une surface OpenGL est posee derriere la fenetre,
        // et un fond opaque la masquerait entierement. C'est le nettoyage en
        // noir de la couche GL qui remplit l'ecran.
        // La surface GL ne sert plus qu'a porter le contexte et le fil du coeur ;
        // elle n'a pas a etre vue, le skin la recouvre entierement.
        racine.addView(gl, FrameLayout.LayoutParams(-1, -1))
        racine.addView(vue, FrameLayout.LayoutParams(-1, -1))         // le skin par-dessus

        barre = construireBarre()
        val d = resources.displayMetrics.density
        racine.addView(barre, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = (12 * d).toInt() })
        setContentView(racine)

        // le skin transmet ses commandes et la position du cadre a la couche GL
        vue.surCommandes = { b, sx, sy, _, _, ff ->
            if (b != 0 && tracesBoutons < 5) {
                tracesBoutons++
                noter("touche enfoncée : masque %04X".format(b))
            }
            gl.boutons = b; gl.stickX = sx; gl.stickY = sy
            gl.avanceRapide = ff
        }
        gl.surJournal = { ligne -> noter(ligne) }
        vue.surCadreHaut = { l, t, r, b -> gl.cadreHaut = floatArrayOf(l, t, r, b) }
        vue.surCadreBas = { l, t, r, b -> gl.cadreBas = floatArrayOf(l, t, r, b) }
        vue.surTactile = { fx, fy -> gl.surFilGl { coeur.stylet(fx, fy) } }
        vue.surErreur = { ligne -> noter(ligne) }
        // la couche OpenGL relit l'image, le skin la dessine
        gl.surImage = {
            if (!vue.trouEcran && gl.rendulMateriel) vue.trouEcran = true
            if (imagesVues < 200) {
                imagesVues++
            }
            vue.cadence = gl.cadenceMesuree
            vue.poserImage(coeur.pixels, coeur.imageL, coeur.imageH)
        }

        vue.lissage = prefs.getBoolean("lissage", true)
        gl.lissage = vue.lissage
        bLissage.text = if (vue.lissage) "Lissé" else "Net"
        appliquerEssais()
        appliquerCadence(prefs.getInt("cadence", 0))
        appliquerQualite(prefs.getInt("qualite", 1))
        // en paysage, l'image remplit le cadre prevu pour elle

        vue.surTouche = { if (barre.visibility == View.VISIBLE && !vue.modeEdition) replier(true) }
        vue.surMenu = { replier(barre.visibility == View.VISIBLE) }
        vue.surJeux = { ouvrirCatalogue() }
        vue.surChange = { changerPresentation() }
        vue.surQuit = { finishAffinity() }
        vue.surChoixJeu = { i ->
            val r = romsAffichees.getOrNull(i)
            noter("choix n" + i + " : " + (r?.nom ?: "hors liste"))
            if (r != null) chargerDepuis(r.uri)
        }
        onBackPressedDispatcher.addCallback(this) {
            when {
                vue.listeOuverte -> vue.fermerListe()
                barre.visibility != View.VISIBLE -> replier(false)
                else -> finish()
            }
        }
        barre.postDelayed(replieur, 4000)

        if (!coeur.pret) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Coeur d'émulation absent")
                .setMessage(coeur.derniereErreur + "\n\nLe fichier libmupen.so doit être dans l'APK.")
                .setPositiveButton("OK", null).show()
        }
        noter(bilanBios)
    }

    private fun bordABord() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.setBackgroundDrawable(null)
        /* Sans cela la surface OpenGL reste invisible : posee derriere la
           fenetre, elle n'y perce un trou que si le format de la fenetre
           autorise la transparence. Un fond transparent ne suffit pas. */
        window.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        /*
         * Sans cela, la surface OpenGL reste invisible.
         *
         * Une surface posee derriere la fenetre ne se voit que si elle peut y
         * percer un trou, et ce trou n'est possible que si la fenetre accepte
         * la transparence. Un fond de fenetre transparent ne suffit pas : il
         * faut que le format meme de la fenetre l'autorise.
         */

    }

    override fun onPause() { super.onPause(); gl.onPause(); son?.pause() }
    override fun onResume() { super.onResume(); gl.onResume(); son?.reprendre() }
    override fun onDestroy() { super.onDestroy(); son?.liberer() }
    override fun onWindowFocusChanged(f: Boolean) { super.onWindowFocusChanged(f); if (f) bordABord() }

    private fun construireBarre(): HorizontalScrollView {
        val d = resources.displayMetrics.density
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(0xDD121212.toInt()); cornerRadius = 999f }
            setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
        }
        fun bouton(txt: String, action: () -> Unit) = Button(this).apply {
            text = txt; isAllCaps = false; textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(0xFF333333.toInt()); cornerRadius = 999f }
            minWidth = 0; minimumWidth = 0
            setPadding((12 * d).toInt(), (7 * d).toInt(), (12 * d).toInt(), (7 * d).toInt())
            setOnClickListener { action(); repousserRepli() }
            ll.addView(this, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (5 * d).toInt() })
        }

        lateinit var bModif: Button
        bModif = bouton("Modifier") {
            vue.modeEdition = !vue.modeEdition
            bModif.text = if (vue.modeEdition) "Terminer" else "Modifier"
        }
        bouton("Enregistrer") {
            vue.enregistrerDisposition()
            Toast.makeText(this, "Disposition enregistrée", Toast.LENGTH_SHORT).show()
        }
        bouton("Défaut") { vue.reinitialiserDisposition() }
        bouton("Jeux") { ouvrirCatalogue() }
        bouton("Dossier") { Bibliotheque.oublier(this); choisirDossier.launch(null) }
        bouton("1 jeu") { choisirJeu.launch(arrayOf("*/*")) }
        bouton("Sauver") { sauverEtat() }
        bouton("Charger") { chargerEtat() }
        bQualite = bouton("Natif") { appliquerQualite(qualite + 1); relancerPourQualite() }
        bQualite.setOnLongClickListener { expliquerQualite(); true }
        bLissage = bouton("Lissé") {
            vue.lissage = !vue.lissage
            gl.lissage = vue.lissage
            prefs.edit().putBoolean("lissage", vue.lissage).apply()
            bLissage.text = if (vue.lissage) "Lissé" else "Net"
        }
        bouton("Reset") {
            // Sans vidage, les echantillons de la partie precedente restent en
            // attente et se jouent d'un coup au redemarrage.
            gl.surFilGl { coeur.reinitialiser(); coeur.sonVider() }
            son?.vider()
        }
        bouton("Arrêt") { arreterJeu() }
        bCadence = bouton("Cadence native") { cadenceSuivante() }
        bCadence.setOnLongClickListener { expliquerCadence(); true }
        bouton("Journal") { ouvrirJournal() }
        bouton("Essais") { ouvrirEssais() }
        bouton("État") {
            android.app.AlertDialog.Builder(this)
                .setTitle("État de l'application")
                .setMessage(vue.diagnostic() +
                            "\n\ncoeur : " + (if (coeur.pret) "chargé" else "absent") +

                            "\nrésolution interne : " + (640 * qualite) + "×" + (480 * qualite) +
                            "\naffichage : " + (if (vue.trouEcran) "OpenGL direct" else "relecture") +
                            "\ncadence mesurée : %.1f images/s".format(gl.cadenceMesuree) +
                            "\ncadence attendue : %.2f".format(
                                if (gl.cadenceCible > 0) gl.cadenceCible.toDouble()
                                else coeur.imagesParSeconde) +
                            "\ncatalogue des ROMs : " +
                            (java.io.File(coeur.dossierSysteme, "mupen64plus.ini").length() / 1024) + " Ko")
                .setPositiveButton("OK", null).show()
        }.setOnLongClickListener {
            vue.afficherDiagnostic = !vue.afficherDiagnostic; true
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(ll, ViewGroup.LayoutParams(-2, -2))
        }
    }
}
