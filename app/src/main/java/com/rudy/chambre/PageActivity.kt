package com.rudy.chambre

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
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

    private fun repondre(console: String) {
        val jeux = JSONArray()
        Dossiers.lister(this, console).forEach {
            jeux.put(JSONObject().put("nom", it.nom).put("uri", it.uri.toString()))
        }
        val message = JSONObject().put("console", console).put("jeux", jeux)
        runOnUiThread {
            vue.evaluateJavascript(
                "window.dispatchEvent(new MessageEvent('message',{data:" +
                JSONObject().put("type", "roms").put("console", console).put("jeux", jeux) + "}))", null)
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
                } catch (e: Throwable) {
                    android.widget.Toast.makeText(this@PageActivity,
                        "lancement impossible : " + e, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }

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
