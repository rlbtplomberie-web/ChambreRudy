package com.rudy.chambre.livre

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rudy.chambre.Livres
import kotlin.math.abs
import kotlin.math.min

/**
 * La bibliotheque de Rudy, en natif.
 *
 * Son etagere en volume — le livre de face au centre, les voisins tournes et
 * recules — puis le livre ouvert, les pages qui tournent et leur bruit.
 */
class LivreActivity : ComponentActivity() {

    private lateinit var vue: VueLivre
    private lateinit var titre: TextView
    private var son: SonPage? = null

    private val choisirDossier =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            try { contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
            getSharedPreferences("chambre_rudy", MODE_PRIVATE).edit()
                .putString("dossier_livres", uri.toString()).apply()
            chargerLaBibliotheque()
        }

    private val choisirUnLivre =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try { contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
            val nom = androidx.documentfile.provider.DocumentFile
                .fromSingleUri(this, uri)?.name ?: "livre.pdf"
            titre.text = "Préparation…"
            Thread {
                Livres.ajouter(this, uri, nom)
                runOnUiThread { chargerLaBibliotheque() }
            }.start()
        }

    override fun onCreate(e: Bundle?) {
        super.onCreate(e)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        son = SonPage()
        vue = VueLivre(this)
        vue.surPageTournee = { son?.tourner() }
        vue.surTitre = { t -> titre.text = t }
        vue.surOuverture = { livre -> ouvrir(livre) }

        titre = TextView(this).apply {
            textSize = 15f; setTextColor(0xFFF3D6A0.toInt())
            setShadowLayer(6f, 0f, 3f, Color.BLACK)
            text = "Bibliothèque"
        }

        fun bouton(t: String, action: () -> Unit) = Button(this).apply {
            text = t; textSize = 12f
            setTextColor(Color.WHITE); setBackgroundColor(0xCC1A1226.toInt())
            setOnClickListener { action() }
        }

        val racine = FrameLayout(this)
        racine.setBackgroundColor(0xFF2A1B10.toInt())
        racine.addView(vue, FrameLayout.LayoutParams(-1, -1))
        racine.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(bouton("← Bureau") { finish() })
            addView(bouton("＋ Un livre") { choisirUnLivre.launch(arrayOf("application/pdf")) })
            addView(bouton("📁 Dossier") { choisirDossier.launch(null) })
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
                .apply { topMargin = 16; leftMargin = 16 }
        })
        racine.addView(titre, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { bottomMargin = 26 })
        setContentView(racine)

        chargerLaBibliotheque()
    }

    /** La liste des livres, avec leur couverture demandee au fil du defilement. */
    private fun chargerLaBibliotheque() {
        Thread {
            val liste = Livres.lister(this)
            val livres = (0 until liste.length()).map {
                val o = liste.getJSONObject(it)
                VueLivre.Livre(o.getString("titre"), o.getString("id"))
            }
            runOnUiThread {
                vue.poser(livres)
                titre.text = if (livres.isEmpty())
                    "Aucun livre — touche « Dossier »" else livres[0].titre
            }
        }.start()
    }

    /** Ouvrir un livre : le telephone prepare ses pages, puis on lit. */
    private fun ouvrir(livre: VueLivre.Livre) {
        titre.text = livre.titre + " — ouverture…"
        Thread {
            val pages = Livres.pages(this, livre.id)
            val chemins = (0 until pages.length()).map { pages.getString(it) }
            runOnUiThread {
                if (chemins.isEmpty()) { titre.text = livre.titre + " — illisible"; return@runOnUiThread }
                vue.lire(livre, chemins)
                titre.text = livre.titre
            }
        }.start()
    }

    override fun onBackPressed() {
        if (vue.enLecture()) vue.fermer() else super.onBackPressed()
    }

    override fun onDestroy() { son?.liberer(); super.onDestroy() }
}
