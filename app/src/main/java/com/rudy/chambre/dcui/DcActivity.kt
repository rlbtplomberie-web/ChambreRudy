package com.rudy.chambre.dcui

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
import com.rudy.chambre.dcui.core.CoeurDc
import java.io.File

class DcActivity : ComponentActivity() {

    private lateinit var vue: SkinView
    private lateinit var gl: VueGL
    private lateinit var barre: HorizontalScrollView
    private lateinit var coeur: CoeurDc
    private var son: Son? = null
    private var romsAffichees: List<Rom> = emptyList()
    private lateinit var bQualite: Button
    private lateinit var bBios: Button
    private lateinit var bEcran: Button
    private lateinit var bCadence: Button
    /**
     * Reglages a essayer quand un jeu ne demarre pas. Chacun est un couple
     * option / valeurs possibles, avec les noms exacts que le coeur reclame.
     */
    private val ESSAIS = listOf(
        Triple("reicast_hle_bios", "BIOS émulé (HLE)", listOf("disabled", "enabled")),
        Triple("reicast_threaded_rendering", "Rendu en fil séparé", listOf("enabled", "disabled")),
        Triple("reicast_emulate_framebuffer", "Émuler le tampon d'image", listOf("disabled", "enabled")),
        Triple("reicast_enable_dsp", "Processeur de son", listOf("enabled", "disabled")),
        Triple("reicast_gdrom_fast_loading", "Lecture disque rapide", listOf("disabled", "enabled")))

    private val prefs by lazy { getSharedPreferences("skin_dc", MODE_PRIVATE) }

    /**
     * Journal de l'application, a cote de celui du coeur. Le coeur ne voit pas
     * ce qui se passe avant lui — installation du BIOS, copie du jeu — et
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
    private var jeuxTriche: List<JeuTriche> = emptyList()
    private var codes: List<CodeTriche> = emptyList()
    private var nomJeu = ""

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
    private val choisirBios = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        Thread {
            var n = 0
            for (u in uris) {
                val nom = (DocumentFile.fromSingleUri(this, u)?.name ?: "").lowercase()
                val cible = CoeurDc.NOMS_BIOS.firstOrNull { it == nom } ?: continue
                try {
                    val octets = contentResolver.openInputStream(u)!!.use { it.readBytes() }
                    File(coeur.dossierBios, cible).writeBytes(octets)
                    File(coeur.dossierSysteme, cible).writeBytes(octets)
                    n++
                } catch (_: Exception) {}
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                Toast.makeText(this, if (n > 0) "$n fichier(s) BIOS installé(s)"
                    else "Aucun fichier reconnu (dc_boot.bin, dc_flash.bin)", Toast.LENGTH_LONG).show()
                majBios()
            }
        }.start()
    }
    // ================= jeux =================
    /**
     * Flycast lit le jeu par son chemin. Android n'en donne pas sur un fichier
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
                    if (cible == null) erreur = "Rien de lisible dans cette archive"
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
            // Le chargement se fait sur le fil OpenGL : Flycast y cree ses
            // ressources graphiques, et le faire ailleurs fait tomber l'appli.
            gl.surFilGl {
                noter("appel du coeur sur le fil OpenGL")
                val ok = coeur.chargerJeu(f)
                noter(if (ok) "coeur : jeu accepte" else "coeur : refus — " + coeur.derniereErreur)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (ok) {
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
        val priorite = listOf("gdi", "chd", "cdi", "cue", "m3u")
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
        for (ext in priorite) fichiers.firstOrNull { it.extension.lowercase() == ext }?.let { return it }
        return null
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
                    Toast.makeText(this, "Aucun jeu Dreamcast dans ce dossier", Toast.LENGTH_LONG).show()
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
                .setMessage("La base couvre ${Triches.nombreJeux(this)} jeux Dreamcast, mais " +
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
                    Triches.enregistrerActifs(this@DcActivity, coeur.cle,
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

    private fun appliquerQualite(n: Int) {
        qualite = ((n - 1).mod(8)) + 1
        prefs.edit().putInt("qualite", qualite).apply()
        gl.surFilGl { coeur.qualite(qualite) }
        gl.qualite = qualite
        bQualite.text = if (qualite == 1) "Native" else "Native ×$qualite"
    }

    private val NOMS_ECRAN = arrayOf("4:3", "Remplit", "Rogné")

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
            .setMessage("Une console a une cadence fixe : la Dreamcast produit " +
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

    private fun appliquerEcran(mode: Int, zoom: Float) {
        val m = ((mode % 3) + 3) % 3
        vue.modeEcran = m
        vue.zoomEcran = zoom.coerceIn(0.5f, 2.0f)
        prefs.edit().putInt("ecran_mode", m).putFloat("ecran_zoom", vue.zoomEcran).apply()
        bEcran.text = "Écran " + NOMS_ECRAN[m] +
            (if (kotlin.math.abs(vue.zoomEcran - 1f) > 0.01f) " ×%.2f".format(vue.zoomEcran) else "")
    }

    private fun majBios() {
        val manque = coeur.biosManquants()
        bBios.text = if (manque.isEmpty()) "BIOS ✓" else "BIOS"
    }

    /**
     * Installe le BIOS embarque dans l'APK. Il est ecrit aux deux endroits ou
     * Flycast le cherche selon sa version : le sous-dossier "dc" du dossier
     * systeme, et le dossier systeme lui-meme.
     */
    private fun installerBiosEmbarque(): String {
        val bilan = StringBuilder()
        try {
            for (nom in assets.list("skins/dc/bios") ?: emptyArray()) {
                if (!nom.lowercase().endsWith(".bin")) continue
                for (dossier in listOf(coeur.dossierBios, coeur.dossierSysteme)) {
                    val cible = File(dossier, nom.lowercase())
                    if (cible.length() > 1000) continue
                    assets.open("skins/dc/bios/$nom").use { e -> cible.outputStream().use { e.copyTo(it) } }
                    bilan.append("installé ").append(cible.absolutePath)
                         .append(" (").append(cible.length() / 1024).append(" Ko)\n")
                }
            }
        } catch (e: Exception) {
            bilan.append("ÉCHEC de l'installation : ").append(e.message).append('\n')
        }
        return bilan.toString()
    }

    /**
     * Affiche le journal du coeur. C'est la seule facon de savoir pourquoi un
     * jeu refuse de demarrer, un logcat n'etant pas lisible depuis le telephone.
     */
    private fun appliquerEssais() {
        gl.surFilGl {
            for ((cle, _, valeurs) in ESSAIS)
                coeur.option(cle, prefs.getString("opt_$cle", valeurs[0]) ?: valeurs[0])
        }
    }

    /**
     * Fenetre d'essais. Le cœur plante a l'initialisation sur cet appareil, et
     * je ne sais pas encore lequel de ces reglages y remedie : ils sont donc
     * exposes pour pouvoir chercher.
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
                    text = libelle + "  (" + nouveau + ")"
                }
            })
        }
        ll.addView(TextView(this).apply {
            text = "\nCes réglages prennent effet au prochain chargement de jeu. " +
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
                codes = emptyList(); jeuxTriche = emptyList(); nomJeu = ""
                Toast.makeText(this, "Jeu arrêté", Toast.LENGTH_SHORT).show()
            }
        }
    }

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
        val vue = TextView(this).apply {
            text = texte; textSize = 10.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            val d = resources.displayMetrics.density
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Journal du cœur")
            .setView(ScrollView(this).apply { addView(vue) })
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

    // ================= cycle de vie =================
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        bordABord()

        coeur = CoeurDc(this)
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
        noter("assets/bios : " + (assets.list("skins/dc/bios")?.joinToString(", ") ?: "(illisible)"))
        val bilanBios = installerBiosEmbarque()
        noter(bilanBios.ifBlank { "installation BIOS : rien a faire" })
        noter(coeur.etatBios())
        noter("BIOS present : " + coeur.biosPresent())

        gl = VueGL(this, coeur)
        vue = SkinView(this)

        val racine = FrameLayout(this)
        // Pas de fond ici : une surface OpenGL est posee derriere la fenetre,
        // et un fond opaque la masquerait entierement. C'est le nettoyage en
        // noir de la couche GL qui remplit l'ecran.
        // La surface GL ne sert plus qu'a porter le contexte et le fil du coeur ;
        // elle n'a pas a etre vue, le skin la recouvre entierement.
        racine.setBackgroundColor(Color.BLACK)
        racine.addView(gl, FrameLayout.LayoutParams(-1, -1))
        racine.addView(vue, FrameLayout.LayoutParams(-1, -1))         // le skin par-dessus

        barre = construireBarre()
        val d = resources.displayMetrics.density
        racine.addView(barre, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = (12 * d).toInt() })
        setContentView(racine)

        // le skin transmet ses commandes et la position du cadre a la couche GL
        vue.surCommandes = { b, sx, sy, gL, gR, ff ->
            gl.boutons = b; gl.stickX = sx; gl.stickY = sy
            gl.gachetteL = gL; gl.gachetteR = gR; gl.avanceRapide = ff
        }
        gl.surJournal = { ligne -> noter(ligne) }
        // la couche OpenGL relit l'image, le skin la dessine
        gl.surImage = {
            vue.cadence = gl.cadenceMesuree
            vue.poserImage(coeur.pixels, coeur.imageL, coeur.imageH)
        }

        appliquerEssais()
        appliquerCadence(prefs.getInt("cadence", 0))
        appliquerQualite(prefs.getInt("qualite", 1))
        // en paysage, l'image remplit le cadre prevu pour elle
        appliquerEcran(prefs.getInt("ecran_mode", 1), prefs.getFloat("ecran_zoom", 1f))
        majBios()

        vue.surTouche = { if (barre.visibility == View.VISIBLE && !vue.modeEdition) replier(true) }
        vue.surMenu = { replier(barre.visibility == View.VISIBLE) }
        vue.surJeux = { ouvrirCatalogue() }
        vue.surCheat = { ouvrirTriches() }
        vue.surQuit = { finishAffinity() }
        vue.surManette = {
            val nom = when (vue.variantePaysage) {
                1 -> "Grand écran"
                2 -> "Touches transparentes"
                else -> "Écran normal"
            }
            Toast.makeText(this, nom, Toast.LENGTH_SHORT).show()
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
                .setMessage(coeur.derniereErreur + "\n\nLe fichier libflycast.so doit être dans l'APK.")
                .setPositiveButton("OK", null).show()
        } else if (!coeur.biosPresent()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("BIOS Dreamcast manquant")
                .setMessage("Manquants : " + coeur.biosManquants().joinToString(", ") +
                            "\n\n" + coeur.etatBios() +
                            "\nInstallation depuis l'APK :\n" +
                            bilanBios.ifBlank { "(rien à installer)" })
                .setPositiveButton("OK", null).show()
        }
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

    /**
     * Regle l'opacite des touches de la couche transparente.
     *
     * Elle ne concerne qu'elle : sur les deux presentations qui dessinent une
     * console, il n'y a rien a effacer, et on le dit plutot que d'ouvrir une
     * fenetre sans effet.
     */
    private fun reglerOpacite() {
        if (!vue.surCoucheTransparente) {
            Toast.makeText(this, "Réglage réservé aux touches transparentes (bouton manette)",
                           Toast.LENGTH_SHORT).show()
            return
        }
        val d = resources.displayMetrics.density
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * d).toInt(), (18 * d).toInt(), (22 * d).toInt(), (10 * d).toInt())
        }
        ll.addView(TextView(this).apply { text = "Discrètes ← → bien visibles" })
        ll.addView(SeekBar(this).apply {
            max = 100
            progress = (((vue.opaciteBoutons - 0.12f) / 0.88f) * 100f).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, parUtilisateur: Boolean) {
                    vue.opaciteBoutons = 0.12f + p / 100f * 0.88f
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    Dispositions.poserOpacite(this@DcActivity, vue.opaciteBoutons)
                }
            })
        })
        android.app.AlertDialog.Builder(this)
            .setTitle("Opacité des touches")
            .setView(ll)
            .setPositiveButton("OK") { _, _ ->
                Dispositions.poserOpacite(this, vue.opaciteBoutons)
            }
            .show()
    }

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
        bouton("Écran plein") {
            vue.ecranPleinePage()
            Toast.makeText(this, "L'écran couvre toute la vue", Toast.LENGTH_SHORT).show()
        }
        bouton("Écran d'origine") {
            vue.ecranOrigine()
            Toast.makeText(this, "Écran rendu à sa dalle", Toast.LENGTH_SHORT).show()
        }
        bouton("Transparence") { reglerOpacite() }
        bouton("Jeux") { ouvrirCatalogue() }
        bouton("Dossier") { Bibliotheque.oublier(this); choisirDossier.launch(null) }
        bouton("1 jeu") { choisirJeu.launch(arrayOf("*/*")) }
        bouton("Sauver") { sauverEtat() }
        bouton("Charger") { chargerEtat() }
        bQualite = bouton("Native") { appliquerQualite(qualite + 1) }
        bEcran = bouton("Écran Remplit") { appliquerEcran(vue.modeEcran + 1, vue.zoomEcran) }
        bouton("Triche") { ouvrirTriches() }
        bBios = bouton("BIOS") { choisirBios.launch(arrayOf("*/*")) }
        bouton("Reset") { gl.surFilGl { coeur.reinitialiser() } }
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
                            "\nBIOS : " + (if (coeur.biosPresent()) "présent" else "manquant") +
                            "\n" + coeur.etatBios() +
                            "\nrésolution interne : " + (640 * qualite) + "×" + (480 * qualite) +
                            "\ncadence mesurée : %.1f images/s".format(gl.cadenceMesuree) +
                            "\ncadence attendue : %.2f".format(
                                if (gl.cadenceCible > 0) gl.cadenceCible.toDouble()
                                else coeur.imagesParSeconde) +
                            "\ncodes de triche : " + codes.count { it.actif } + " actifs sur " + codes.size +
                            "\nmémoire exposée : " + coeur.memoireTaille / 1024 + " Ko")
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
