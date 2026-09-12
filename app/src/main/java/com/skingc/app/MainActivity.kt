package com.skingc.app

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
import com.skingc.app.core.CoeurGC
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var vue: SkinView
    private lateinit var gl: VueGL
    private lateinit var barre: HorizontalScrollView
    private lateinit var coeur: CoeurGC
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
        Triple("dolphin_efb_scale", "Finesse de l'image",
               listOf("x1 (640 x 528)", "x2 (1280 x 1056)", "x3 (1920 x 1584)",
                      "x4 (2560 x 2112)")),
        Triple("dolphin_widescreen", "Image large 16/9",
               listOf("disabled", "enabled")),
        Triple("dolphin_cheats_enabled", "Codes de triche internes",
               listOf("enabled", "disabled")),
        Triple("dolphin_shader_compilation_mode", "Compilation des nuanceurs",
               listOf("Synchronous (Ubershaders)", "Asynchronous (Ubershaders)",
                      "Synchronous", "Asynchronous (Skip Drawing)")),
        Triple("dolphin_dsp_hle", "Son rapide",
               listOf("enabled", "disabled")))



    private val prefs by lazy { getSharedPreferences("skin_gc", MODE_PRIVATE) }

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
    /**
     * Un jeu designe par la chambre.
     *
     * Elle passe l'adresse du fichier dans l'intention qui ouvre cet ecran :
     * on le charge alors directement, sans passer par le catalogue.
     */
    private fun nomDe(u: android.net.Uri): String =
        try { androidx.documentfile.provider.DocumentFile.fromSingleUri(this, u)?.name
              ?: u.lastPathSegment ?: "jeu" } catch (_: Throwable) { "jeu" }

    private fun jeuDemandeParLaChambre() {
        val brut = intent?.getStringExtra("rom") ?: return
        val u = try { android.net.Uri.parse(brut) } catch (_: Throwable) { return }
        try { contentResolver.takePersistableUriPermission(
                u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
        vue.postDelayed({ try { chargerDepuis(u) } catch (_: Throwable) {} }, 400)
    }

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
    private var jeuxTriche: List<JeuTriche> = emptyList()
    private var codes: List<CodeTriche> = emptyList()
    private var nomJeu = ""
    private var imagesVues = 0
    private var reprendreLaPartie = true
    /** Surveillance : redescend d'un cran si l'image reste noire. */
    private var surveille = false
    /** Fichier du jeu en cours : la resolution ne changeant qu'au chargement,
     *  il faut pouvoir le relancer. */
    private var jeuCourant: java.io.File? = null

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
     * Dolphin lit le jeu par son chemin. Android n'en donne pas sur un fichier
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
                        erreur = "Aucune ROM GameCube dans cette archive " +
                                 "(.iso, .gcm, .rvz ou .gcz attendus)"
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
            // Le chargement se fait sur le fil OpenGL : Dolphin y cree ses
            // ressources graphiques, et le faire ailleurs fait tomber l'appli.
            if (!rendulogiciel())
                prefs.edit().putBoolean("tentative_materiel", true).apply()
            jeuCourant = f
            gl.surFilGl {
                noter("appel du coeur sur le fil OpenGL")
                val ok = coeur.chargerJeu(f)
                noter(if (ok) "coeur : jeu accepte" else "coeur : refus — " + coeur.derniereErreur)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (ok) {
                        noter("moteur " + (if (coeur.rendulMateriel) "matériel" else "logiciel") +
                              ", affichage par relecture")
                        son?.liberer(); son = Son(coeur.frequence)
                        gl.surSon = { ech, n -> son?.jouer(ech, n) }
                        nomJeu = f.name
                        preparerTriches()
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
        // Extensions GameCube. Cette liste etait restee celle de la Dreamcast
        // — gdi, chd, cdi — si bien qu'aucune archive n'etait reconnue.
        val priorite = listOf("iso", "gcm", "rvz", "gcz", "ciso", "wbfs")
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
                    Toast.makeText(this, "Aucun jeu GameCube dans ce dossier", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                noter("catalogue : " + roms.size + " jeux trouves")
                romsAffichees = roms
                vue.afficherListe(roms.map { it.nom })
            }
        }.start()
    }

    // ================= triche =================

    /** Retrouve les codes du jeu courant et reapplique ceux qui etaient actifs. */
    private fun preparerTriches() {
        jeuxTriche = Triches.pourJeu(this, nomJeu)
        codes = jeuxTriche.firstOrNull()?.codes ?: emptyList()
        val actifs = Triches.chargerActifs(this, coeur.cle)
        codes.forEach { it.actif = it.nom in actifs }
        appliquerTriches()
    }

    private fun appliquerTriches() {
        // la liste est lue par le fil OpenGL a chaque image : on l'y modifie
        gl.surFilGl { if (coeur.tricheDisponible) coeur.tricheAppliquer(codes) }
    }

    /**
     * Fenetre des codes. Quand un jeu existe en plusieurs regions, on demande
     * d'abord laquelle : les adresses different d'une region a l'autre, et un
     * code applique a la mauvaise version corrompt la memoire du jeu.
     */
    private fun ouvrirTriches() {
        if (!coeur.romChargee) {
            Toast.makeText(this, "Charge d'abord un jeu", Toast.LENGTH_SHORT).show(); return
        }
        if (!coeur.tricheDisponible) {
            Toast.makeText(this, "Ce cœur n'expose pas la mémoire : codes indisponibles",
                           Toast.LENGTH_LONG).show(); return
        }
        if (jeuxTriche.isEmpty()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Aucun code pour ce jeu")
                .setMessage("La base couvre ${Triches.nombreJeux(this)} jeux GameCube, mais " +
                            "aucun ne correspond à « $nomJeu ».\n\n" +
                            "Le nom du fichier sert à la recherche : renomme-le comme le titre " +
                            "du jeu et recharge-le.")
                .setPositiveButton("OK", null).show()
            return
        }
        if (jeuxTriche.size > 1 && codes === jeuxTriche.first().codes) {
            val noms = jeuxTriche.map { it.nom }.toTypedArray()
            android.app.AlertDialog.Builder(this)
                .setTitle("Quelle version ?")
                .setItems(noms) { _, i ->
                    codes = jeuxTriche[i].codes
                    val actifs = Triches.chargerActifs(this, coeur.cle)
                    codes.forEach { it.actif = it.nom in actifs }
                    appliquerTriches(); listeTriches()
                }
                .setNegativeButton("Annuler", null).show()
            return
        }
        listeTriches()
    }

    private fun listeTriches() {
        val d = resources.displayMetrics.density
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
        }
        for (c in codes) {
            ll.addView(CheckBox(this).apply {
                text = c.nom
                textSize = 13.5f
                isChecked = c.actif
                setOnCheckedChangeListener { _, v ->
                    c.actif = v
                    Triches.enregistrerActifs(this@MainActivity, coeur.cle,
                        codes.filter { it.actif }.map { it.nom }.toSet())
                    appliquerTriches()
                }
            })
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Codes — ${codes.size} disponibles")
            .setView(ScrollView(this).apply { addView(ll) })
            .setPositiveButton("Fermer", null)
            .setNeutralButton("Tout désactiver") { _, _ ->
                codes.forEach { it.actif = false }
                Triches.enregistrerActifs(this, coeur.cle, emptySet())
                appliquerTriches()
            }
            .setNegativeButton("Changer de version") { _, _ ->
                codes = emptyList(); ouvrirTriches()
            }
            .show()
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
    /**
     * Filet de securite pour le rendu materiel.
     *
     * Il fait tomber l'application au chargement d'un jeu, et le reglage etant
     * memorise, on se retrouve pris dans une boucle de plantees. On pose donc
     * un temoin avant de charger, qu'on efface une fois l'emulation lancee :
     * s'il est encore la au demarrage suivant, c'est que ca n'a pas tenu, et
     * on revient d'office au rendu logiciel.
     */
    private fun verifierTentative() {
        if (!prefs.getBoolean("tentative_materiel", false)) return
        prefs.edit()
            .putBoolean("tentative_materiel", false)
            .putString("opt_dolphin_shader_compilation_mode", "Synchronous")
            .apply()
        noter("le rendu materiel n'a pas tenu : retour au rendu logiciel")
        android.app.AlertDialog.Builder(this)
            .setTitle("Retour au rendu logiciel")
            .setMessage("Le rendu matériel a fait tomber l'application au chargement du jeu. " +
                        "Je suis revenu à la compilation synchrone, qui fonctionne.\n\n" +
                        "Attention : ce moteur dessine toujours à la résolution d'origine, " +
                        "320 × 237. Le réglage de résolution restera sans effet tant qu'il " +
                        "est actif — appuie sur le bouton de résolution pour retenter le " +
                        "rendu matériel.")
            .setPositiveButton("OK", null).show()
    }

    private fun rendulogiciel() =
        (prefs.getString("opt_dolphin_shader_compilation_mode",
            "Synchronous (Ubershaders)") ?: "") == "Synchronous"

    private fun appliquerQualite(n: Int) {
        val nb = com.skingc.app.core.CoeurGC.RESOLUTIONS.size
        qualite = ((n - 1).mod(nb)) + 1
        prefs.edit().putInt("qualite", qualite).apply()
        gl.qualite = qualite
        gl.surFilGl {
            coeur.qualite(qualite)
            // L'image relue garde une taille constante : c'est la carte qui
            // reduit, et ce moyennage est precisement ce qui lisse.
            coeur.reduction(960)
        }
        noter("résolution interne demandée : " +
              com.skingc.app.core.CoeurGC.LIBELLES[qualite - 1])
        val res = com.skingc.app.core.CoeurGC.LIBELLES[qualite - 1]
        bQualite.text = if (rendulogiciel()) "Natif (logiciel)" else res
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
    private fun relancerPourQualite() {
        val f = jeuCourant
        val res = com.skingc.app.core.CoeurGC.LIBELLES[qualite - 1]

        // Le moteur logiciel dessine toujours a la resolution d'origine : lui
        // demander mieux ne peut rien donner. Des qu'on veut plus que le
        // natif, on bascule donc sur le moteur materiel, seul capable de
        // monter en definition. Le filet de securite reste actif.
        if (qualite > 1 && rendulogiciel()) {
            prefs.edit().putString("opt_dolphin_shader_compilation_mode",
                "Synchronous (Ubershaders)").apply()
            appliquerEssais()
            noter("passage au rendu materiel pour atteindre " + res)
            Toast.makeText(this, "Passage au rendu matériel pour atteindre $res",
                           Toast.LENGTH_LONG).show()
        } else if (qualite == 1 && !rendulogiciel()) {
            Toast.makeText(this, "Résolution native", Toast.LENGTH_SHORT).show()
        }

        if (f == null || !coeur.romChargee) {
            Toast.makeText(this, "Résolution $res — prend effet au chargement du jeu",
                           Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "$res — relance du jeu…", Toast.LENGTH_SHORT).show()
        surveille = qualite > 1
        gl.surFilGl {
            // On garde la partie en cours : le coeur ne lit cette option qu'au
            // chargement, mais rien n'oblige a perdre sa progression. On
            // sauvegarde l'etat, on relance, on le restaure.
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
            noter("relance en " + res + " : " + (if (ok) "réussie" else "échouée"))
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                son?.vider()
                if (ok) {
                    vue.ecranVide = false
                } else {
                    Toast.makeText(this, coeur.derniereErreur, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun expliquerQualite() {
        if (!rendulogiciel()) {
            Toast.makeText(this, "Résolution interne : ×$qualite, effet au prochain jeu chargé",
                           Toast.LENGTH_LONG).show()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Pourquoi la finesse ne change pas")
            .setMessage(
                "Les nuanceurs sont compilés de façon synchrone. C'est plus sûr " +
                "la puce d'affichage de la console, ce qui le rend parfaitement fidèle — " +
                "et l'empêche de dessiner ailleurs qu'à la résolution d'origine. " +
                "Monter la résolution lui est impossible par construction.\n\n" +
                "Je l'ai choisi parce que le moteur matériel faisait tomber l'application " +
                "hier soir. Maintenant que le reste fonctionne, tu peux le tenter : " +
                "bouton Essais, première ligne, « Rendu matériel ». Le réglage de " +
                "résolution reprendra alors tout son sens.\n\n" +
                "Si l'application replante, reviens à la compilation synchrone par le même chemin.")
            .setPositiveButton("Compris", null)
            .setNeutralButton("Ouvrir les essais") { _, _ -> ouvrirEssais() }
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
            .setMessage("Une console a une cadence fixe : la GameCube produit " +
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
                    appliquerEssais()
                    if (cle == "dolphin_efb_scale") appliquerQualite(qualite)
                    text = libelle + "  (" + nouveau + ")"
                }
            })
        }
        // un reglage de l'application, et non du coeur
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
                   "Change-en un seul à la fois, puis relance un jeu.\n\n" +
                   "Si le jeu se comporte mal après un changement de finesse, décoche la " +
                   "reprise de partie : elle repartira du début, ce qui est toujours propre."
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
                jeuCourant = null
                codes = emptyList(); jeuxTriche = emptyList(); nomJeu = ""
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
     * Installe les fichiers systeme de Dolphin, AVANT d'ouvrir le coeur.
     *
     * Le coeur lit son dossier Sys des son ouverture : il y garde sa base de
     * reglages par jeu, sans laquelle beaucoup de titres presentent des
     * defauts graves. On ne peut donc pas passer par lui pour connaitre le
     * dossier — il n'existe pas encore.
     */
    private fun installerSystemeAvantCoeur(): String {
        val racine = File(filesDir, "systeme").apply { mkdirs() }
        val temoin = File(racine, ".systeme_v1")
        if (temoin.exists()) return "fichiers système déjà en place"
        var n = 0

        fun copier(chemin: String, vers: File) {
            val entrees = try { assets.list(chemin) } catch (_: Throwable) { null } ?: return
            if (entrees.isEmpty()) {
                vers.parentFile?.mkdirs()
                assets.open(chemin).use { e -> vers.outputStream().use { e.copyTo(it) } }
                n++
            } else {
                vers.mkdirs()
                for (x in entrees) copier("$chemin/$x", File(vers, x))
            }
        }

        val racines = try { assets.list("gc/systeme") } catch (_: Throwable) { null }
        if (racines.isNullOrEmpty()) return "aucun fichier système embarqué"
        for (x in racines) copier("gc/systeme/$x", File(racine, x))
        temoin.writeText("ok")
        return "fichiers système installés (" + n + ")"
    }

    // ================= cycle de vie =================
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        bordABord()

        /*
         * Les fichiers systeme AVANT le coeur.
         *
         * Dolphin lit son dossier Sys des son ouverture. Je le construisais
         * apres, si bien qu'il demarrait sans, et l'application se fermait au
         * lancement. L'ordre compte ici plus qu'ailleurs.
         */
        installerCapteur()
        val bilanSysteme = installerSystemeAvantCoeur()
        coeur = CoeurGC(this)
        noter(bilanSysteme)
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
        noter("codes de triche : " + Triches.nombreJeux(this) + " jeux couverts")
        val bilanBios = bilanSysteme


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
        vue.surCommandes = { b, sx, sy, cx, cy, ff ->
            gl.boutons = b; gl.stickX = sx; gl.stickY = sy
            // les quatre boutons C voyagent comme second stick
            gl.stickCX = cx; gl.stickCY = cy; gl.avanceRapide = ff
        }
        gl.surJournal = { ligne -> noter(ligne) }
        vue.surCadre = { l, t, r, b -> gl.cadre = floatArrayOf(l, t, r, b) }
        vue.surErreur = { ligne -> noter(ligne) }
        // la couche OpenGL relit l'image, le skin la dessine
        // Le temoin s'efface des que l'emulation a tenu deux cents images, quel
        // que soit le mode de rendu. Il etait accroche a la relecture, qui ne
        // sert qu'au rendu logiciel : en materiel il ne s'effacait jamais, et
        // le filet de securite croyait a une plantee a chaque lancement.
        gl.surImageEmise = {
            // Rattrapage automatique. Si l'image reste entierement noire
            // pendant deux secondes apres un changement de finesse, c'est que
            // l'appareil ne suit pas : on redescend d'un cran et on le dit,
            // plutot que de laisser un ecran noir.
            if (surveille && coeur.imagesNoires > 120) {
                surveille = false
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (qualite > 1) {
                        noter("image noire à " + com.skingc.app.core.CoeurGC.LIBELLES[qualite - 1] +
                              " : retour au cran inférieur")
                        Toast.makeText(this,
                            "Cette finesse ne passe pas sur cet appareil — retour au cran inférieur",
                            Toast.LENGTH_LONG).show()
                        appliquerQualite(qualite - 1)
                        relancerPourQualite()
                    }
                }
            }
            if (imagesVues < 200) {
                imagesVues++
                if (imagesVues == 200 && prefs.getBoolean("tentative_materiel", false)) {
                    prefs.edit().putBoolean("tentative_materiel", false).apply()
                    noter("le rendu matériel tient : témoin effacé")
                }
            }
        }
        gl.surImage = {
            vue.cadence = gl.cadenceMesuree
            vue.poserImage(coeur.pixels, coeur.imageL, coeur.imageH)
        }

        reprendreLaPartie = prefs.getBoolean("reprise", true)
        verifierTentative()
        vue.lissage = prefs.getBoolean("lissage", true)
        gl.lissage = vue.lissage
        bLissage.text = if (vue.lissage) "Lissé" else "Net"
        appliquerEssais()
        appliquerCadence(prefs.getInt("cadence", 0))
        appliquerQualite(prefs.getInt("qualite", 1))
        // en paysage, l'image remplit le cadre prevu pour elle
        // Le cadrage ne bouge pas : l'image remplit le cadre du skin, comme
        // avant. Monter la finesse ne doit rien changer a la taille affichee.

        vue.surTouche = { if (barre.visibility == View.VISIBLE && !vue.modeEdition) replier(true) }
        vue.surMenu = { replier(barre.visibility == View.VISIBLE) }
        vue.surJeux = { ouvrirCatalogue() }
        vue.surCheat = { ouvrirTriches() }
        vue.surQuit = { finishAffinity() }
        vue.surManette = {
            Toast.makeText(this, if (vue.varianteLarge) "Grand écran" else "Écran normal",
                           Toast.LENGTH_SHORT).show()
        }
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
                .setMessage(coeur.derniereErreur + "\n\nLe fichier libdolphin.so doit être dans l'APK.")
                .setPositiveButton("OK", null).show()
        }
        noter(bilanBios)

        jeuDemandeParLaChambre()
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
        bouton("Triche") { ouvrirTriches() }
        bouton("Reset") {
            // Sans vidage, les echantillons de la partie precedente restent en
            // attente et se jouent d'un coup : c'est le grondement entendu au
            // redemarrage.
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
                            "\ncompilation des nuanceurs : " +
                                (if (rendulogiciel()) "synchrone, plus lente mais sûre"
                                 else "ubershaders, plus fluide") +
                            "\ncadence mesurée : %.1f images/s".format(gl.cadenceMesuree) +
                            "\ncadence attendue : %.2f".format(
                                if (gl.cadenceCible > 0) gl.cadenceCible.toDouble()
                                else coeur.imagesParSeconde) +
                            "\ncodes de triche : " + codes.count { it.actif } +
                                " actifs sur " + codes.size +
                            "\nfichiers système : " +
                                (if (File(coeur.dossierSysteme, ".systeme_v1").exists())
                                 "installés" else "ABSENTS"))
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
