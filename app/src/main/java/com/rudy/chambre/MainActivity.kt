package com.rudy.chambre

import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * La chambre : le decor, les jeux de societe et les vitrines, en pages web.
 * Cette activite les affiche et leur ouvre les portes du telephone — choisir un
 * dossier de ROMs, lister les jeux, lancer un emulateur.
 */
class MainActivity : ComponentActivity() {

    private lateinit var vue: WebView
    private var consoleEnAttente: String? = null

    private val choisirDossier =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val console = consoleEnAttente ?: return@registerForActivityResult
            consoleEnAttente = null
            if (uri == null) { repondre(console, JSONArray()); return@registerForActivityResult }
            Dossiers.retenir(this, console, uri)
            repondre(console, listeJson(console))
        }

    override fun onCreate(etat: Bundle?) {
        super.onCreate(etat)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        vue = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            webViewClient = WebViewClient()
            addJavascriptInterface(Pont(), "Android")
            loadUrl("file:///android_asset/web/index.html")
        }
        setContentView(vue)
    }

    override fun onDestroy() { vue.destroy(); super.onDestroy() }

    private fun repondre(console: String, jeux: JSONArray) {
        val message = JSONObject().put("console", console).put("jeux", jeux)
        runOnUiThread { vue.evaluateJavascript("window.onRoms && window.onRoms($message)", null) }
    }

    private fun listeJson(console: String): JSONArray {
        val a = JSONArray()
        Dossiers.lister(this, console).forEach {
            a.put(JSONObject().put("nom", it.nom).put("uri", it.uri.toString()))
        }
        return a
    }

    /** L'ecran de l'emulateur de cette console, tel qu'il existe dans son paquet. */
    private fun ecran(console: String): Intent? {
        val nom = Consoles.parId(console)?.activite ?: return null
        return try {
            Intent(this, Class.forName(nom))
        } catch (_: Throwable) { null }
    }

    /** Ce que la page web peut demander au telephone. */
    inner class Pont {

        @JavascriptInterface
        fun choisirDossier(console: String) {
            consoleEnAttente = console
            runOnUiThread { choisirDossier.launch(null) }
        }

        @JavascriptInterface
        fun listerRoms(console: String): String = listeJson(console).toString()

        @JavascriptInterface
        fun dossierChoisi(console: String): Boolean = Dossiers.dossier(this@MainActivity, console) != null

        @JavascriptInterface
        fun ouvrirConsole(console: String) {
            val i = ecran(console) ?: return
            runOnUiThread { startActivity(i) }
        }

        @JavascriptInterface
        fun lancer(console: String, uriRom: String) {
            val i = ecran(console)?.putExtra("rom", uriRom) ?: return
            runOnUiThread { startActivity(i) }
        }
    }
}
