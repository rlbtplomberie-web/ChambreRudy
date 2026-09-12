package com.skinps1.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import com.skinps1.app.core.CoeurPs1
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var vue: SkinView
    private lateinit var barre: HorizontalScrollView
    private lateinit var coeur: CoeurPs1
    private var son: Son? = null
    private var romsAffichees: List<Rom> = emptyList()
    private lateinit var bQualite: Button
    private lateinit var bStickCroix: Button
    private lateinit var bBios: Button
    private lateinit var bEcran: Button

    private val prefs by lazy { getSharedPreferences("skin_ps1", MODE_PRIVATE) }
    private val dossierJeux by lazy { File(filesDir, "jeux").apply { mkdirs() } }

    // ================= barre =================

    private val replieur = Runnable { if (!vue.modeEdition) replier(true) }

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
        vue.postDelayed({ try { chargerDepuis(u, null) } catch (_: Throwable) {} }, 400)
    }

    private fun replier(oui: Boolean) {
        barre.visibility = if (oui) View.GONE else View.VISIBLE
        barre.removeCallbacks(replieur)
        if (!oui) barre.postDelayed(replieur, 4000)
    }

    private fun repousserRepli() {
        barre.removeCallbacks(replieur)
        if (barre.visibility == View.VISIBLE) barre.postDelayed(replieur, 4000)
    }

    // ================= choix de fichiers =================

    private val choisirJeu = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) chargerDepuis(uri, null)
    }

    private val choisirDossier = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        Bibliotheque.retenir(this, uri)
        ouvrirCatalogue()
    }

    private val choisirBios = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        Thread {
            val ok = try {
                val nom = (DocumentFile.fromSingleUri(this, uri)?.name ?: "scph5502.bin").lowercase()
                val cible = File(coeur.dossierSysteme,
                    if (nom in CoeurPs1.NOMS_BIOS) nom else "scph5502.bin")
                contentResolver.openInputStream(uri)!!.use { e -> cible.outputStream().use { e.copyTo(it) } }
                cible.length() >= 512 * 1024
            } catch (e: Exception) { false }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                Toast.makeText(this, if (ok) "BIOS installé" else "Fichier BIOS invalide", Toast.LENGTH_LONG).show()
                majBios()
            }
        }.start()
    }

    // ================= jeux =================

    /**
     * Le coeur lit le jeu par son chemin, et Android ne donne pas de chemin sur
     * un fichier choisi : on le copie une fois dans le stockage de l'appli.
     * Un .cue entraine ses pistes .bin ; un .zip est extrait tel quel.
     */
    private fun chargerDepuis(uri: Uri, dossierParent: DocumentFile?) {
        vue.fermerListe()
        val doc = DocumentFile.fromSingleUri(this, uri) ?: run {
            Toast.makeText(this, "Fichier inaccessible", Toast.LENGTH_SHORT).show(); return
        }
        val nom = doc.name ?: "jeu"
        val base = nom.substringBeforeLast('.')
        val local = File(dossierJeux, base.replace(Regex("[^A-Za-z0-9._ -]"), "_"))
        Toast.makeText(this, "Préparation de $nom…", Toast.LENGTH_SHORT).show()
        Thread {
            var cible: File? = null
            var erreur = ""
            try {
                local.mkdirs()
                val ext = nom.substringAfterLast('.', "").lowercase()
                if (ext == "zip") {
                    cible = extraireZip(uri, local)
                } else {
                    val f = File(local, nom)
                    if (!f.exists() || f.length() != doc.length()) copier(uri, f)
                    cible = f
                    if (ext == "cue" || ext == "m3u") copierPistes(f, dossierParent ?: parentDe(uri))
                }
                if (cible == null) erreur = "Rien de lisible dans cette archive"
            } catch (e: Exception) {
                erreur = "Copie impossible : ${e.message}"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val f = cible
                if (f == null) { Toast.makeText(this, erreur, Toast.LENGTH_LONG).show(); return@runOnUiThread }
                if (coeur.chargerJeu(f)) {
                    son?.liberer(); son = Son(coeur.frequence); vue.son = son
                    Toast.makeText(this, "Jeu chargé", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, coeur.derniereErreur, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun copier(uri: Uri, f: File) {
        contentResolver.openInputStream(uri)!!.use { e -> f.outputStream().use { e.copyTo(it, 1 shl 20) } }
    }

    private fun parentDe(uri: Uri): DocumentFile? {
        // avec un dossier retenu, on retrouve le parent par la bibliotheque
        val racine = Bibliotheque.dossier(this) ?: return null
        val arbre = DocumentFile.fromTreeUri(this, racine) ?: return null
        val nom = DocumentFile.fromSingleUri(this, uri)?.name ?: return null
        return chercherParent(arbre, nom, 0)
    }

    private fun chercherParent(d: DocumentFile, nom: String, prof: Int): DocumentFile? {
        if (prof > 3) return null
        val enfants = d.listFiles()
        if (enfants.any { it.name == nom }) return d
        for (e in enfants) if (e.isDirectory) chercherParent(e, nom, prof + 1)?.let { return it }
        return null
    }

    /** Copie les fichiers cites par un .cue ou un .m3u depuis le dossier d'origine. */
    private fun copierPistes(cue: File, parent: DocumentFile?) {
        if (parent == null) return
        val guillemet = '"'
        val motif = Regex("FILE\\s+" + guillemet + "([^" + guillemet + "]+)" + guillemet,
                          RegexOption.IGNORE_CASE)
        val cites = cue.readLines().mapNotNull { l ->
            motif.find(l)?.groupValues?.get(1)
                ?: if (cue.extension.lowercase() == "m3u" && l.isNotBlank() && !l.startsWith("#"))
                       l.trim() else null
        }
        for (nom in cites) {
            val src = parent.findFile(nom) ?: continue
            val dst = File(cue.parentFile, nom)
            if (!dst.exists() || dst.length() != src.length()) copier(src.uri, dst)
        }
    }

    /** Extrait une archive sur le disque et renvoie le fichier a lancer. */
    private fun extraireZip(uri: Uri, dossier: File): File? {
        val priorite = listOf("cue", "m3u", "chd", "pbp", "img", "iso", "bin")
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
        if (vue.listeOuverte) { vue.fermerListe(); return }
        if (Bibliotheque.dossier(this) == null) { choisirDossier.launch(null); return }
        Toast.makeText(this, "Lecture du dossier…", Toast.LENGTH_SHORT).show()
        Thread {
            val roms = Bibliotheque.lister(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (roms.isEmpty()) {
                    Toast.makeText(this, "Aucun jeu PlayStation dans ce dossier", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                romsAffichees = roms
                vue.afficherListe(roms.map { it.nom })
            }
        }.start()
    }

    // ================= etats =================

    private fun fichierEtat(): File? {
        if (!coeur.romChargee || coeur.cle.isEmpty()) return null
        return File(filesDir, "etat_${coeur.cle}.bin")
    }

    private fun sauverEtat() {
        val f = fichierEtat() ?: run { Toast.makeText(this, "Charge d'abord un jeu", Toast.LENGTH_SHORT).show(); return }
        val o = coeur.sauverEtat() ?: run { Toast.makeText(this, "Rien à sauvegarder", Toast.LENGTH_SHORT).show(); return }
        try { f.writeBytes(o); Toast.makeText(this, "État sauvegardé", Toast.LENGTH_SHORT).show() }
        catch (e: Exception) { Toast.makeText(this, "Écriture impossible", Toast.LENGTH_SHORT).show() }
    }

    private fun chargerEtat() {
        val f = fichierEtat() ?: run { Toast.makeText(this, "Charge d'abord un jeu", Toast.LENGTH_SHORT).show(); return }
        if (!f.exists()) { Toast.makeText(this, "Aucune sauvegarde pour ce jeu", Toast.LENGTH_SHORT).show(); return }
        try {
            val ok = coeur.restaurerEtat(f.readBytes())
            Toast.makeText(this, if (ok) "État chargé" else "Sauvegarde incompatible", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "Lecture impossible", Toast.LENGTH_SHORT).show() }
    }

    // ================= qualite et BIOS =================

    /**
     * 0 = pixels francs, 1 = lissage bilineaire, 2 = lissage + amelioration interne
     * du coeur quand l'appareil la permet.
     */
    private var qualite = 1
    private val NOMS_QUALITE = arrayOf("Net", "Lissé", "Lissé+")

    private fun appliquerQualite(n: Int) {
        val max = if (coeur.ameliorationPossible) 3 else 2
        qualite = ((n % max) + max) % max
        prefs.edit().putInt("qualite", qualite).apply()
        vue.lissage = qualite >= 1
        coeur.ameliorationInterne(qualite >= 2)
        bQualite.text = "Image " + NOMS_QUALITE[qualite]
    }

    private val NOMS_ECRAN = arrayOf("4:3", "Étiré", "Rogné", "Pixel")

    private fun appliquerEcran(mode: Int, zoom: Float) {
        vue.modeEcran = ((mode % 4) + 4) % 4
        vue.zoomEcran = zoom
        prefs.edit().putInt("ecran_mode", vue.modeEcran).putFloat("ecran_zoom", vue.zoomEcran).apply()
        bEcran.text = "Écran " + NOMS_ECRAN[vue.modeEcran] +
            (if (kotlin.math.abs(vue.zoomEcran - 1f) > 0.01f) " ×%.2f".format(vue.zoomEcran) else "")
    }

    /** Appui long sur Écran : reglage fin de la taille. */
    private fun reglerEcran() {
        val d = resources.displayMetrics.density
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (8 * d).toInt())
        }
        val etiquette = android.widget.TextView(this).apply {
            text = "Taille : ×%.2f".format(vue.zoomEcran); textSize = 15f
        }
        val curseur = android.widget.SeekBar(this).apply {
            max = 150                                    // 0,50 a 2,00 par pas de 0,01
            progress = ((vue.zoomEcran - 0.5f) * 100).toInt()
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, u: Boolean) {
                    val z = 0.5f + p / 100f
                    etiquette.text = "Taille : ×%.2f".format(z)
                    appliquerEcran(vue.modeEcran, z)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        ll.addView(etiquette); ll.addView(curseur)
        android.app.AlertDialog.Builder(this)
            .setTitle("Taille de l'image")
            .setView(ll)
            .setPositiveButton("OK", null)
            .setNeutralButton("Réinitialiser") { _, _ -> appliquerEcran(vue.modeEcran, 1f) }
            .show()
    }

    private fun majBios() {
        bBios.text = coeur.biosPresent()?.let { "BIOS ✓" } ?: "BIOS"
    }

    /** Un BIOS embarque dans l'APK (dossier assets/bios) est installe au premier lancement. */
    private fun installerBiosEmbarque() {
        try {
            for (nom in assets.list("ps1/bios") ?: emptyArray()) {
                val cible = File(coeur.dossierSysteme, nom.lowercase())
                if (cible.exists()) continue
                assets.open("ps1/bios/$nom").use { e -> cible.outputStream().use { e.copyTo(it) } }
            }
        } catch (_: Exception) {}
    }

    // ================= cycle de vie =================

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        bordABord()

        coeur = CoeurPs1(this)
        installerBiosEmbarque()
        vue = SkinView(this)
        vue.coeur = coeur
        val racine = FrameLayout(this)
        racine.setBackgroundColor(Color.BLACK)
        racine.addView(vue, FrameLayout.LayoutParams(-1, -1))

        barre = construireBarre()
        val d = resources.displayMetrics.density
        racine.addView(barre, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = (12 * d).toInt() })
        setContentView(racine)

        appliquerQualite(prefs.getInt("qualite", 1))
        appliquerEcran(prefs.getInt("ecran_mode", 0), prefs.getFloat("ecran_zoom", 1f))
        majBios()

        vue.surTouche = { if (barre.visibility == View.VISIBLE && !vue.modeEdition) replier(true) }
        vue.surMenu = { replier(barre.visibility == View.VISIBLE) }
        vue.surJeux = { ouvrirCatalogue() }
        vue.surQuit = { finishAffinity() }
        vue.surManette = {
            val nom = when (vue.variantePaysage) {
                1 -> "Grand écran"
                2 -> "Touches transparentes"
                else -> "Écran normal"
            }
            Toast.makeText(this, nom, Toast.LENGTH_SHORT).show()
        }
        vue.surChoixJeu = { i -> romsAffichees.getOrNull(i)?.let { chargerDepuis(it.uri, null) } }
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
                .setMessage(coeur.derniereErreur + "\n\nLe fichier libpcsx.so doit être présent dans l'APK. " +
                            "Vérifie l'étape « Récupérer le coeur » du workflow.")
                .setPositiveButton("OK", null).show()
        } else if (coeur.biosPresent() == null) {
            Toast.makeText(this, "Sans BIOS : BIOS émulé, compatibilité réduite. Bouton BIOS pour en installer un.",
                           Toast.LENGTH_LONG).show()
        }

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
    }

    override fun onPause() { super.onPause(); son?.pause() }
    override fun onResume() { super.onResume(); son?.reprendre() }
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
                    Dispositions.poserOpacite(this@MainActivity, vue.opaciteBoutons)
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
        bQualite = bouton("Image Lissé") { appliquerQualite(qualite + 1) }
        bBios = bouton("BIOS") { choisirBios.launch(arrayOf("*/*")) }
        bEcran = bouton("Écran 4:3") { appliquerEcran(vue.modeEcran + 1, vue.zoomEcran) }
        bEcran.setOnLongClickListener { reglerEcran(); true }
        vue.stickCommandeCroix = prefs.getBoolean("stick_croix", true)
        bStickCroix = bouton(
            if (vue.stickCommandeCroix) "Stick → croix ✓" else "Stick → croix ✗") {
            vue.stickCommandeCroix = !vue.stickCommandeCroix
            prefs.edit().putBoolean("stick_croix", vue.stickCommandeCroix).apply()
            bStickCroix.text = if (vue.stickCommandeCroix) "Stick → croix ✓" else "Stick → croix ✗"
            Toast.makeText(this, if (vue.stickCommandeCroix)
                "Le stick commande aussi la croix : utile pour les jeux qui l'ignorent"
                else "Le stick ne commande plus que lui-même", Toast.LENGTH_LONG).show()
        }
        bouton("Reset") { coeur.reinitialiser() }
        bouton("État") {
            // appui long : bandeau permanent, pour voir le masque pendant qu'on joue
            android.app.AlertDialog.Builder(this)
                .setTitle("État de l'application")
                .setMessage(vue.diagnostic() + "\n\ncoeur : " + (if (coeur.pret) "chargé" else "absent") +
                            "\nBIOS : " + (coeur.biosPresent() ?: "aucun, BIOS émulé") +
                            "\nimage : " + NOMS_QUALITE[qualite] +
                            "\namélioration interne : " +
                            (if (coeur.ameliorationPossible) "possible" else "indisponible (64 bits)"))
                .setPositiveButton("OK", null).show()
        }.setOnLongClickListener {
            vue.afficherDiagnostic = !vue.afficherDiagnostic
            Toast.makeText(this, if (vue.afficherDiagnostic) "Bandeau de contrôle affiché"
                                 else "Bandeau masqué", Toast.LENGTH_SHORT).show()
            true
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(ll, ViewGroup.LayoutParams(-2, -2))
        }
    }
}
