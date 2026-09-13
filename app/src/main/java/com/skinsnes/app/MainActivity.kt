package com.skinsnes.app

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
import com.skinsnes.app.core.CoeurSnes

class MainActivity : ComponentActivity() {

    private lateinit var vue: SkinView
    private lateinit var barre: HorizontalScrollView
    /** Curseur d'opacite, propre a la troisieme presentation horizontale. */
    private lateinit var reglageOpacite: LinearLayout
    private lateinit var coeur: CoeurSnes
    private var son: Son? = null
    private var romsAffichees: List<Rom> = emptyList()

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
        noter("ROM recue : " + brut.takeLast(60))
        val u = try { android.net.Uri.parse(brut) } catch (_: Throwable) { return }
        try { contentResolver.takePersistableUriPermission(
                u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
        vue.postDelayed({ try { chargerDepuis(u) } catch (_: Throwable) {} }, 400)
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

    private val choisirRom = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) chargerDepuis(uri)
    }

    private val choisirDossier = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        Bibliotheque.retenir(this, uri)
        ouvrirCatalogue()
    }

    /**
     * Sort la ROM d'une archive zip. Beaucoup de collections sont zippees, et
     * le coeur ne lit que la ROM nue : on cherche donc la premiere entree dont
     * l'extension est reconnue, la plus grosse s'il y en a plusieurs.
     */
    private fun deziper(octets: ByteArray): ByteArray? {
        val exts = listOf(".sfc", ".smc", ".fig", ".swc")
        var meilleur: ByteArray? = null
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(octets)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val n = e.name.lowercase()
                if (exts.none { n.endsWith(it) }) continue
                val d = z.readBytes()
                if (meilleur == null || d.size > meilleur!!.size) meilleur = d
            }
        }
        return meilleur
    }

    private fun estZip(o: ByteArray) =
        o.size > 4 && o[0] == 0x50.toByte() && o[1] == 0x4B.toByte()

    private fun chargerDepuis(uri: Uri) {
        vue.fermerListe()
        try {
            var octets = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            if (estZip(octets)) {
                val dedans = deziper(octets)
                if (dedans == null) {
                    Toast.makeText(this, "Archive sans ROM Super Nintendo", Toast.LENGTH_LONG).show()
                    return
                }
                octets = dedans
            }
            if (coeur.chargerRom(octets)) {
                // la frequence audio est celle que le coeur annonce pour cette ROM
                son?.liberer()
                son = Son(coeur.frequence)
                vue.son = son
                Toast.makeText(this, "ROM chargée", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, coeur.derniereErreur.ifEmpty { "ROM refusée" }, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Lecture impossible", Toast.LENGTH_SHORT).show()
        }
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
                    Toast.makeText(this, "Aucune ROM Super Nintendo dans ce dossier", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                romsAffichees = roms
                vue.afficherListe(roms.map { it.nom })
            }
        }.start()
    }

    private fun fichierEtat(): java.io.File? {
        if (!coeur.romChargee || coeur.cle.isEmpty()) return null
        return java.io.File(filesDir, "etat_${coeur.cle}.bin")
    }

    private fun sauverEtat() {
        val f = fichierEtat() ?: run { Toast.makeText(this, "Charge d'abord une ROM", Toast.LENGTH_SHORT).show(); return }
        val o = coeur.sauverEtat() ?: run { Toast.makeText(this, "Rien à sauvegarder", Toast.LENGTH_SHORT).show(); return }
        try { f.writeBytes(o); Toast.makeText(this, "État sauvegardé", Toast.LENGTH_SHORT).show() }
        catch (e: Exception) { Toast.makeText(this, "Écriture impossible", Toast.LENGTH_SHORT).show() }
    }

    private fun chargerEtat() {
        val f = fichierEtat() ?: run { Toast.makeText(this, "Charge d'abord une ROM", Toast.LENGTH_SHORT).show(); return }
        if (!f.exists()) { Toast.makeText(this, "Aucune sauvegarde pour ce jeu", Toast.LENGTH_SHORT).show(); return }
        try {
            val ok = coeur.restaurerEtat(f.readBytes())
            Toast.makeText(this, if (ok) "État chargé" else "Sauvegarde incompatible", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "Lecture impossible", Toast.LENGTH_SHORT).show() }
    }

    /** Le journal que la chambre sait relire. */
    private fun noter(texte: String) {
        try {
            val d = java.io.File(filesDir, "systeme").apply { mkdirs() }
            java.io.File(d, "journal_appli.txt").appendText(texte + "\n")
        } catch (_: Throwable) {}
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        bordABord()

        noter("avant ouverture du coeur Snes9x")
        coeur = CoeurSnes(this)
        noter("coeur ouvert : " + coeur.pret)
        vue = SkinView(this)
        vue.coeur = coeur
        val racine = FrameLayout(this)
        racine.setBackgroundColor(Color.BLACK)
        racine.addView(vue, FrameLayout.LayoutParams(-1, -1))

        barre = construireBarre()
        val d = resources.displayMetrics.density
        racine.addView(barre, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = (12 * d).toInt() })

        reglageOpacite = construireReglageOpacite()
        reglageOpacite.visibility = View.GONE
        racine.addView(reglageOpacite, FrameLayout.LayoutParams(-2, -2,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (18 * d).toInt() })

        setContentView(racine)

        vue.surTouche = { if (barre.visibility == View.VISIBLE && !vue.modeEdition) replier(true) }
        vue.surMenu = { replier(barre.visibility == View.VISIBLE) }
        vue.surManette = {
            val nom = when (vue.variantePaysage) {
                1 -> "Grand écran"
                2 -> "Touches transparentes"
                else -> "Écran normal"
            }
            Toast.makeText(this, nom, Toast.LENGTH_SHORT).show()
            majReglageOpacite()
        }
        vue.surJeux = { ouvrirCatalogue() }
        vue.surReset = { coeur.reinitialiser(); Toast.makeText(this, "Redémarrage", Toast.LENGTH_SHORT).show() }
        vue.surPower = {
            coeur.eteindre()
            Toast.makeText(this, "Jeu fermé", Toast.LENGTH_SHORT).show()
            replier(false)
        }
        vue.surChoixJeu = { i -> romsAffichees.getOrNull(i)?.let { chargerDepuis(it.uri) } }
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
                .setMessage(coeur.derniereErreur + "\n\nLe fichier libsnes9x.so doit être présent dans l'APK. " +
                            "Vérifie l'étape « Récupérer le coeur Snes9x » du workflow.")
                .setPositiveButton("OK", null).show()
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
     * Le curseur ne sert que sur la couche transparente : les deux autres
     * presentations dessinent une console, qui n'a pas a s'effacer.
     */
    private fun majReglageOpacite() {
        val utile = vue.modeEdition && vue.estPaysage && vue.variantePaysage == 2
        reglageOpacite.visibility = if (utile) View.VISIBLE else View.GONE
    }

    /** Des touches a peine visibles jusqu'aux touches bien marquees. */
    private fun construireReglageOpacite(): LinearLayout {
        val d = resources.displayMetrics.density
        val ligne = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xDD121212.toInt()); cornerRadius = 28 * d }
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
        }
        ligne.addView(TextView(this).apply {
            text = "Touches : discrètes ← → bien visibles"
            textSize = 12f
            setTextColor(Color.WHITE)
        })
        ligne.addView(SeekBar(this).apply {
            max = 100
            progress = (((vue.opaciteBoutons - 0.12f) / 0.88f) * 100f).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, parUtilisateur: Boolean) {
                    vue.opaciteBoutons = 0.12f + p / 100f * 0.88f
                    if (parUtilisateur) repousserRepli()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    Dispositions.poserOpacite(this@MainActivity, vue.opaciteBoutons)
                }
            })
        }, LinearLayout.LayoutParams((260 * d).toInt(), -2).apply { topMargin = (4 * d).toInt() })
        return ligne
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
            majReglageOpacite()
        }
        bouton("Enregistrer") {
            vue.enregistrerDisposition()
            Toast.makeText(this, "Disposition enregistrée", Toast.LENGTH_SHORT).show()
        }
        bouton("Défaut") { vue.reinitialiserDisposition() }
        bouton("Jeux") { ouvrirCatalogue() }
        bouton("Dossier") { Bibliotheque.oublier(this); choisirDossier.launch(null) }
        bouton("1 ROM") { choisirRom.launch(arrayOf("*/*")) }
        bouton("Sauver") { sauverEtat() }
        bouton("Charger") { chargerEtat() }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(ll, ViewGroup.LayoutParams(-2, -2))
        }
    }
}
