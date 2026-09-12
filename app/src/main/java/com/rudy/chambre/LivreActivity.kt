package com.rudy.chambre

import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * La bibliotheque et le lecteur, en natif.
 *
 * Android sait ouvrir un PDF tout seul : inutile de passer par une page web et
 * une bibliotheque a telecharger. On choisit un dossier une fois, tous les
 * livres qu'il contient apparaissent, et les pages se tournent au doigt.
 */
class LivreActivity : ComponentActivity() {

    private data class Livre(val titre: String, val uri: Uri)

    private val livres = ArrayList<Livre>()
    private var lecteur: PdfRenderer? = null
    private var fichier: ParcelFileDescriptor? = null
    private var page = 0
    private var titreCourant = ""

    private lateinit var vue: VuePage
    private lateinit var barre: LinearLayout
    private lateinit var etat: TextView

    private val choisirDossier =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {}
            getSharedPreferences("chambre_rudy", MODE_PRIVATE).edit()
                .putString("dossier_livres", uri.toString()).apply()
            lireLeDossier()
        }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        vue = VuePage(this)
        etat = TextView(this).apply { setTextColor(0xFFEFE3CC.toInt()); textSize = 12f }

        barre = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC140D22.toInt())
            setPadding(16, 16, 16, 16)
            addView(bouton("← Chambre") { finish() })
            addView(bouton("Bibliothèque") { choisirLivre() })
            addView(bouton("Dossier") { choisirDossier.launch(null) })
            addView(etat, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 18 })
        }

        val racine = FrameLayout(this)
        racine.setBackgroundColor(Color.BLACK)
        racine.addView(vue, FrameLayout.LayoutParams(-1, -1))
        racine.addView(barre, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(racine)

        lireLeDossier()
        if (livres.isEmpty()) {
            etat.text = "Choisis le dossier de tes livres"
            choisirDossier.launch(null)
        }
    }

    private fun bouton(texte: String, action: () -> Unit) = Button(this).apply {
        text = texte; textSize = 12f
        setTextColor(Color.WHITE)
        setBackgroundColor(0x33FFFFFF)
        setOnClickListener { action() }
    }

    /** Parcourt le dossier retenu et retient tous les PDF. */
    private fun lireLeDossier() {
        livres.clear()
        val brut = getSharedPreferences("chambre_rudy", MODE_PRIVATE)
            .getString("dossier_livres", null) ?: return
        val racine = try { DocumentFile.fromTreeUri(this, Uri.parse(brut)) } catch (_: Throwable) { null }
            ?: return
        fun parcourir(d: DocumentFile, profondeur: Int) {
            if (profondeur > 2) return
            for (f in try { d.listFiles() } catch (_: Throwable) { return }) {
                if (f.isDirectory) parcourir(f, profondeur + 1)
                else {
                    val n = f.name ?: continue
                    if (n.lowercase().endsWith(".pdf"))
                        livres.add(Livre(n.removeSuffix(".pdf").removeSuffix(".PDF"), f.uri))
                }
            }
        }
        parcourir(racine, 0)
        livres.sortBy { it.titre.lowercase() }
        etat.text = livres.size.toString() + " livre(s)"
        if (livres.isNotEmpty() && lecteur == null) ouvrir(0)
    }

    private fun choisirLivre() {
        if (livres.isEmpty()) { choisirDossier.launch(null); return }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Bibliothèque")
            .setItems(livres.map { it.titre }.toTypedArray()) { _, i -> ouvrir(i) }
            .show()
    }

    private fun ouvrir(i: Int) {
        fermer()
        val livre = livres.getOrNull(i) ?: return
        try {
            fichier = contentResolver.openFileDescriptor(livre.uri, "r")
            lecteur = PdfRenderer(fichier!!)
            page = 0
            titreCourant = livre.titre
            afficher()
        } catch (e: Throwable) {
            etat.text = "illisible : " + livre.titre
        }
    }

    private fun fermer() {
        try { lecteur?.close() } catch (_: Throwable) {}
        try { fichier?.close() } catch (_: Throwable) {}
        lecteur = null; fichier = null
    }

    private fun afficher() {
        val l = lecteur ?: return
        if (page < 0) page = 0
        if (page >= l.pageCount) page = l.pageCount - 1
        try {
            val p = l.openPage(page)
            val largeur = resources.displayMetrics.widthPixels
            val hauteur = (largeur.toFloat() * p.height / p.width).toInt()
            val image = Bitmap.createBitmap(largeur, hauteur, Bitmap.Config.ARGB_8888)
            image.eraseColor(Color.WHITE)
            p.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            p.close()
            vue.poser(image)
            etat.text = titreCourant + " · " + (page + 1) + " / " + l.pageCount
        } catch (_: Throwable) {}
    }

    override fun onDestroy() { fermer(); super.onDestroy() }

    /** L'affichage d'une page, qu'on tourne au doigt. */
    inner class VuePage(ctx: android.content.Context) : View(ctx) {
        private var image: Bitmap? = null
        private val peinture = Paint(Paint.FILTER_BITMAP_FLAG)
        private var xDepart = 0f

        fun poser(b: Bitmap) { image?.recycle(); image = b; invalidate() }

        override fun onDraw(c: Canvas) {
            val b = image ?: return
            val ech = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
            val l = b.width * ech; val h = b.height * ech
            c.drawBitmap(b, null,
                RectF((width - l) / 2f, (height - h) / 2f, (width + l) / 2f, (height + h) / 2f),
                peinture)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { xDepart = e.x; return true }
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - xDepart
                    if (dx < -60) { page++; afficher() }
                    else if (dx > 60) { page--; afficher() }
                    else if (e.x > width * .65f) { page++; afficher() }
                    else if (e.x < width * .35f) { page--; afficher() }
                    return true
                }
            }
            return true
        }
    }
}
