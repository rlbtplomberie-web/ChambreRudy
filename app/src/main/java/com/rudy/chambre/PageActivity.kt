package com.rudy.chambre

import android.content.Intent
import android.net.Uri
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject

/**
 * Une page ouverte par-dessus la chambre : un jeu, une vitrine, l'atelier.
 *
 * Ces ecrans ne bougent qu'a la demande, ils n'ont donc rien a gagner a etre
 * redessines soixante fois par seconde. Ils restent des pages, et gardent le
 * pont vers le telephone : choisir un dossier de ROMs, lancer un jeu.
 */
class PageActivity : ComponentActivity() {

    private lateinit var vue: WebView
    private var consoleEnAttente: String? = null
    /** Ce que la page attend quand elle demande un fichier. */
    private var attenteFichier: ValueCallback<Array<Uri>>? = null

    private val choisirDossierLivres =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try { contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
                getSharedPreferences("chambre_rudy", MODE_PRIVATE).edit()
                    .putString("dossier_livres", uri.toString()).apply()
            }
            runOnUiThread { vue.evaluateJavascript("window.livresChanges && window.livresChanges()", null) }
        }

    /** Un seul livre, choisi a la main. */
    private val choisirUnLivre =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try { contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
            val nom = androidx.documentfile.provider.DocumentFile
                .fromSingleUri(this, uri)?.name ?: "livre.pdf"
            Thread {
                Livres.ajouter(this, uri, nom)
                runOnUiThread {
                    vue.evaluateJavascript("window.livresChanges && window.livresChanges()", null)
                }
            }.start()
        }

    private val choisirFichiers =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { liste ->
            attenteFichier?.onReceiveValue(liste.toTypedArray())
            attenteFichier = null
        }

    private val choisirDossier =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val console = consoleEnAttente ?: return@registerForActivityResult
            consoleEnAttente = null
            if (uri != null) Dossiers.retenir(this, console, uri)
            repondre(console)
        }

    override fun onCreate(etat: Bundle?) {
        super.onCreate(etat)
        val page = intent.getStringExtra("page") ?: "jeux/atelier.html"
        val titre = intent.getStringExtra("titre") ?: ""
        if (intent.getBooleanExtra("paysage", false))
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val serveur = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            // les pages de livres, fabriquees a la demande dans l'espace de l'application
            .addPathHandler("/livres/", WebViewAssetLoader.InternalStoragePathHandler(
                this, java.io.File(filesDir, "livres")))
            .build()

        vue = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.BLACK)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                    serveur.shouldInterceptRequest(r.url)
            }
            /*
             * Une page qui demande un fichier — « Ajouter un livre », une
             * jaquette — doit ouvrir le selecteur du telephone. Sans ce relais,
             * le bouton ne fait rien du tout.
             */
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    v: WebView?,
                    retour: ValueCallback<Array<Uri>>?,
                    parametres: FileChooserParams?
                ): Boolean {
                    attenteFichier?.onReceiveValue(null)
                    attenteFichier = retour
                    val types = parametres?.acceptTypes
                        ?.filter { it.isNotBlank() }
                        ?.toTypedArray()
                        ?: arrayOf("*/*")
                    return try {
                        choisirFichiers.launch(if (types.isEmpty()) arrayOf("*/*") else types)
                        true
                    } catch (_: Throwable) {
                        attenteFichier = null
                        false
                    }
                }
            }
            addJavascriptInterface(Pont(), "Android")
            loadUrl("https://appassets.androidplatform.net/assets/web/$page")
        }

        val retour = Button(this).apply {
            text = "← " + (if (titre.isNotEmpty()) titre else "Chambre")
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC1A1226.toInt())
            textSize = 13f
            setOnClickListener { finish() }
        }

        val racine = FrameLayout(this)
        racine.addView(vue, FrameLayout.LayoutParams(-1, -1))
        racine.addView(retour, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            topMargin = (28 * resources.displayMetrics.density).toInt()
            leftMargin = (12 * resources.displayMetrics.density).toInt()
        })
        setContentView(racine)
    }

    /** Renvoie la liste a la page, dans la forme qu'elle attend. */
    private fun repondre(console: String) {
        val jeux = JSONArray()
        Dossiers.lister(this, console).forEach {
            jeux.put(JSONObject().put("nom", it.nom).put("uri", it.uri.toString()))
        }
        val donnees = JSONObject().put("type", "roms").put("console", console).put("jeux", jeux)
        runOnUiThread {
            vue.evaluateJavascript("window.recevoirRoms && window.recevoirRoms($jeux)", null)
            vue.evaluateJavascript(
                "window.dispatchEvent(new MessageEvent('message',{data:$donnees}))", null)
        }
    }

    override fun onDestroy() { vue.destroy(); super.onDestroy() }

    /** Ce que la page peut demander au telephone. */
    inner class Pont {

        @JavascriptInterface
        fun choisirDossier(console: String) {
            consoleEnAttente = console
            runOnUiThread { choisirDossier.launch(null) }
        }

        @JavascriptInterface
        fun listerRoms(console: String): String {
            val a = JSONArray()
            Dossiers.lister(this@PageActivity, console).forEach {
                a.put(JSONObject().put("nom", it.nom).put("uri", it.uri.toString()))
            }
            return a.toString()
        }

        @JavascriptInterface
        fun lancer(console: String, uriRom: String) {
            runOnUiThread {
                try {
                    val fiche = Consoles.parId(console) ?: return@runOnUiThread
                    val i = Intent(this@PageActivity, Class.forName(fiche.activite))
                        .putExtra("rom", uriRom)
                    startActivity(i)
                    finish()                       // la vitrine s'efface derriere le jeu
                } catch (e: ClassNotFoundException) {
                    android.widget.Toast.makeText(this@PageActivity,
                        (Consoles.parId(console)?.nom ?: console) +
                        " : cet emulateur n'est pas encore dans cet APK.",
                        android.widget.Toast.LENGTH_LONG).show()
                } catch (e: Throwable) {
                    android.widget.Toast.makeText(this@PageActivity,
                        "lancement impossible : " + e, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }

        /** Ajoute un seul livre, choisi a la main. */
        @JavascriptInterface
        fun ajouterUnLivre() {
            runOnUiThread { choisirUnLivre.launch(arrayOf("application/pdf")) }
        }

        /** Ouvre le selecteur pour choisir le dossier des livres. */
        @JavascriptInterface
        fun choisirDossierLivres() {
            runOnUiThread { choisirDossierLivres.launch(null) }
        }

        /** La liste des livres du dossier : titre et identifiant. */
        @JavascriptInterface
        fun listerLivres(): String = Livres.lister(this@PageActivity).toString()

        /**
         * Fabrique les pages d'un livre et renvoie leurs adresses.
         *
         * Android sait ouvrir un PDF : on dessine chaque page en image, une
         * fois pour toutes, et la page web n'a plus qu'a les afficher.
         */
        @JavascriptInterface
        fun pagesDuLivre(id: String): String = Livres.pages(this@PageActivity, id).toString()

        /** La couverture d'un livre, pour l'etagere. */
        @JavascriptInterface
        fun couverture(id: String): String = Livres.couverture(this@PageActivity, id)

        /** Les pages preparees depuis : le livre s'allonge pendant la lecture. */
        @JavascriptInterface
        fun pagesPretes(id: String): String = Livres.pagesPretes(this@PageActivity, id).toString()

        /** Le jeu adoucit la musique de la chambre, sans la couper. */
        @JavascriptInterface
        fun volumeRadio(v: Float) {
            runOnUiThread { SonPartage.volume(v.coerceIn(0f, 1f)) }
        }

        /** Le jeu demande a revenir au bureau. */
        @JavascriptInterface
        fun retourChambre() { runOnUiThread { finish() } }

        @JavascriptInterface
        fun ouvrirConsole(console: String) {
            runOnUiThread {
                try {
                    val fiche = Consoles.parId(console) ?: return@runOnUiThread
                    startActivity(Intent(this@PageActivity, Class.forName(fiche.activite)))
                } catch (_: Throwable) {}
            }
        }
    }
}
