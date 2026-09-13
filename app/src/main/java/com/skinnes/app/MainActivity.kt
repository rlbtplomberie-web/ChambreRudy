package com.skinnes.app

import android.app.Activity
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
import com.skinnes.app.core.CoeurNes
import com.skinnes.app.nes.AutoTest

class MainActivity : ComponentActivity() {

    private lateinit var vue: SkinView
    private lateinit var barre: HorizontalScrollView
    /** Reglage de l'opacite des touches, propre a la troisieme presentation. */
    private lateinit var reglageOpacite: LinearLayout
    private var romsAffichees: List<Rom> = emptyList()
    private var son: Son? = null

    /**
     * La barre se retire d'elle-meme ; on la rappelle par le bouton MENU du skin.
     * Le compte a rebours est relance a chaque fois qu'elle s'ouvre, sinon elle
     * restait affichee par-dessus le jeu jusqu'a ce qu'on la ferme a la main.
     */
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

    /** Relance le compte a rebours sans changer l'etat courant. */
    private fun repousserRepli() {
        barre.removeCallbacks(replieur)
        if (barre.visibility == View.VISIBLE) barre.postDelayed(replieur, 4000)
    }

    private val choisirRom = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        chargerDepuis(uri)
    }

    private val choisirDossier = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        Bibliotheque.retenir(this, uri)
        ouvrirCatalogue()
    }

    private fun chargerDepuis(uri: Uri) {
        vue.fermerListe()
        try {
            val octets = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            if (vue.coeur.chargerRom(octets)) {
                Toast.makeText(this, "ROM chargée", Toast.LENGTH_SHORT).show()
            } else {
                val motif = (vue.coeur as? CoeurNes)?.derniereErreur ?: "ROM refusée"
                Toast.makeText(this, motif, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Lecture impossible", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Ouvre le catalogue directement dans l'ecran de jeu.
     * Sans dossier retenu, on demande d'abord lequel.
     */
    private fun ouvrirCatalogue() {
        if (vue.listeOuverte) { vue.fermerListe(); return }
        if (Bibliotheque.dossier(this) == null) { choisirDossier.launch(null); return }
        Toast.makeText(this, "Lecture du dossier…", Toast.LENGTH_SHORT).show()
        Thread {
            val roms = Bibliotheque.lister(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (roms.isEmpty()) {
                    Toast.makeText(this, "Aucun .nes dans ce dossier", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                romsAffichees = roms
                vue.afficherListe(roms.map { it.nom })
            }
        }.start()
    }

    private fun fichierEtat(): java.io.File? {
        val c = vue.coeur as? CoeurNes ?: return null
        if (!c.romChargee || c.cle.isEmpty()) return null
        return java.io.File(filesDir, "etat_${c.cle}.bin")
    }

    private fun sauverEtat() {
        val c = vue.coeur as? CoeurNes
        val f = fichierEtat()
        if (c == null || f == null) {
            Toast.makeText(this, "Charge d'abord une ROM", Toast.LENGTH_SHORT).show(); return
        }
        val o = c.sauverEtat()
        if (o == null) {
            Toast.makeText(this, "Rien à sauvegarder", Toast.LENGTH_SHORT).show(); return
        }
        try {
            f.writeBytes(o)
            Toast.makeText(this, "État sauvegardé", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Écriture impossible", Toast.LENGTH_SHORT).show()
        }
    }

    private fun chargerEtat() {
        val c = vue.coeur as? CoeurNes
        val f = fichierEtat()
        if (c == null || f == null) {
            Toast.makeText(this, "Charge d'abord une ROM", Toast.LENGTH_SHORT).show(); return
        }
        if (!f.exists()) {
            Toast.makeText(this, "Aucune sauvegarde pour ce jeu", Toast.LENGTH_SHORT).show(); return
        }
        try {
            val ok = c.restaurerEtat(f.readBytes())
            Toast.makeText(this,
                if (ok) "État chargé" else "Sauvegarde d'une autre version",
                Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Lecture impossible", Toast.LENGTH_SHORT).show()
        }
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

        son = Son()
        vue = SkinView(this)
        vue.son = son
        val racine = FrameLayout(this)
        racine.setBackgroundColor(Color.BLACK)
        racine.addView(vue, FrameLayout.LayoutParams(-1, -1))

        barre = construireBarre()
        val d = resources.displayMetrics.density
        val haut = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = (12 * d).toInt() }
        racine.addView(barre, haut)

        reglageOpacite = construireReglageOpacite()
        reglageOpacite.visibility = View.GONE
        racine.addView(reglageOpacite, FrameLayout.LayoutParams(-2, -2,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (18 * d).toInt()
            })

        setContentView(racine)
        // toucher le jeu ou la manette referme la barre : on joue, on ne regle plus
        vue.surTouche = { if (barre.visibility == View.VISIBLE && !vue.modeEdition) replier(true) }
        // le bouton MENU du skin remplace l'ancienne poignee : il ouvre et referme la barre
        vue.surMenu = { replier(barre.visibility == View.VISIBLE) }
        vue.surReset = {
            vue.coeur.reinitialiser()
            Toast.makeText(this, "Redémarrage", Toast.LENGTH_SHORT).show()
        }
        vue.surJeux = { ouvrirCatalogue() }
        vue.surQuit = { finishAffinity() }
        vue.surManette = {
            val nom = when (vue.variantePaysage) {
                1 -> "Manette NES"
                2 -> "Touches transparentes"
                else -> "Console NES"
            }
            Toast.makeText(this, nom, Toast.LENGTH_SHORT).show()
            majReglageOpacite()
        }
        vue.surChoixJeu = { i ->
            romsAffichees.getOrNull(i)?.let { vue.fermerListe(); chargerDepuis(it.uri) }
        }
        vue.surPower = {
            (vue.coeur as? CoeurNes)?.eteindre()
            Toast.makeText(this, "Jeu fermé", Toast.LENGTH_SHORT).show()
            replier(false)
        }
        // filet de securite si le bouton MENU se retrouve mal place :
        // retour ouvre la barre, et la referme en quittant
        onBackPressedDispatcher.addCallback(this) {
            when {
                vue.listeOuverte -> vue.fermerListe()
                barre.visibility != View.VISIBLE -> replier(false)
                else -> finish()
            }
        }
        barre.postDelayed(replieur, 4000)

        // L'auto-test ne se manifeste que s'il trouve quelque chose.
        // Silence au lancement = portage fidele.
        // Sur un fil separe : l'auto-test execute plus d'un demi-million de cycles,
        // ce qui gelerait l'interface une a deux secondes au lancement.
        Thread {
            val rapport = AutoTest.lancer()
            if (rapport != null) runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                android.app.AlertDialog.Builder(this)
                    .setTitle("Le moteur ne se comporte pas comme prevu")
                    .setMessage(rapport)
                    .setPositiveButton("Continuer quand meme", null)
                    .show()
            }
        }.start()

        jeuDemandeParLaChambre()
    }

    /**
     * Plein ecran reel : on dessine sous les barres systeme ET sous la decoupe
     * de la camera. C'est le reglage qui manque a un navigateur.
     */
    private fun bordABord() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.setBackgroundDrawable(null)
    }

    override fun onPause() { super.onPause(); son?.pause() }
    override fun onResume() { super.onResume(); son?.reprendre() }
    override fun onDestroy() { super.onDestroy(); son?.liberer() }

    override fun onWindowFocusChanged(f: Boolean) {
        super.onWindowFocusChanged(f)
        if (f) bordABord()
    }

    /**
     * Le curseur d'opacite n'apparait que la ou il sert : en mode Modifier,
     * sur la troisieme presentation horizontale. Les deux autres dessinent une
     * console, qui n'a pas a s'effacer.
     */
    private fun majReglageOpacite() {
        val utile = vue.modeEdition && vue.estPaysage && vue.variantePaysage == 2
        reglageOpacite.visibility = if (utile) View.VISIBLE else View.GONE
    }

    /** Du plus discret au plus visible, en gardant toujours les touches reperables. */
    private fun construireReglageOpacite(): LinearLayout {
        val d = resources.displayMetrics.density
        val ligne = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xDD121212.toInt()); cornerRadius = 28 * d
            }
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
        }
        val titre = TextView(this).apply {
            text = "Touches : discrètes ← → bien visibles"
            textSize = 12f
            setTextColor(Color.WHITE)
        }
        ligne.addView(titre)

        val curseur = SeekBar(this).apply {
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
        }
        ligne.addView(curseur, LinearLayout.LayoutParams((260 * d).toInt(), -2).apply {
            topMargin = (4 * d).toInt()
        })
        return ligne
    }

    private fun construireBarre(): HorizontalScrollView {
        val d = resources.displayMetrics.density
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(0xDD121212.toInt()); cornerRadius = 999f
            }
            setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
        }
        fun bouton(txt: String, action: () -> Unit) = Button(this).apply {
            text = txt
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0xFF333333.toInt()); cornerRadius = 999f
            }
            minWidth = 0; minimumWidth = 0
            setPadding((12 * d).toInt(), (7 * d).toInt(), (12 * d).toInt(), (7 * d).toInt())
            setOnClickListener { action(); repousserRepli() }
            ll.addView(this, LinearLayout.LayoutParams(-2, -2).apply {
                marginEnd = (5 * d).toInt()
            })
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
        bouton("Test") {
            Toast.makeText(this, "Test en cours…", Toast.LENGTH_SHORT).show()
            Thread {
                val r = AutoTest.lancer()
                val msg = r ?: "Moteur conforme, ${AutoTest.resume()}"
                runOnUiThread {
                    if (!isFinishing && !isDestroyed)
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }.start()
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(ll, ViewGroup.LayoutParams(-2, -2))
        }
    }

}
