package com.rudy.chambre

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
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
 * La chambre. Tout le decor, les jeux de societe et les vitrines sont des pages
 * web embarquees ; cette activite ne fait que les afficher et leur ouvrir les
 * portes du telephone : choisir un dossier de ROMs, lister les jeux, lancer un
 * emulateur.
 */
class MainActivity : ComponentActivity() {

    private lateinit var vue: WebView
    /** Console pour laquelle on est en train de choisir un dossier. */
    private var consoleEnAttente: String? = null

    private val choisirDossier =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val console = consoleEnAttente ?: return@registerForActivityResult
            consoleEnAttente = null
            if (uri == null) { repondre(console, JSONArray()); return@registerForActivityResult }
            Bibliotheque.retenir(this, console, uri)
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
            settings.mediaPlaybackRequiresUserGesture = false   // la musique demarre seule
            settings.allowFileAccess = true
            webViewClient = WebViewClient()
            addJavascriptInterface(Pont(), "Android")
            loadUrl("file:///android_asset/web/index.html")
        }
        setContentView(vue)
    }

    override fun onDestroy() { vue.destroy(); super.onDestroy() }

    /** Renvoie la liste des ROMs a la page. */
    private fun repondre(console: String, jeux: JSONArray) {
        runOnUiThread {
            vue.evaluateJavascript(
                "window.onRoms && window.onRoms(${JSONObject().put("console", console)
                    .put("jeux", jeux)})", null)
        }
    }

    private fun listeJson(console: String): JSONArray {
        val a = JSONArray()
        Bibliotheque.lister(this, console).forEach {
            a.put(JSONObject().put("nom", it.nom).put("uri", it.uri.toString()))
        }
        return a
    }

    /** Ce que la page web peut demander au telephone. */
    inner class Pont {

        /** Ouvre le selecteur de dossier Android pour cette console. */
        @JavascriptInterface
        fun choisirDossier(console: String) {
            consoleEnAttente = console
            runOnUiThread { choisirDossier.launch(null) }
        }

        /** Les ROMs deja connues pour cette console, sans rien redemander. */
        @JavascriptInterface
        fun listerRoms(console: String): String = listeJson(console).toString()

        /** Y a-t-il un dossier retenu pour cette console ? */
        @JavascriptInterface
        fun dossierChoisi(console: String): Boolean =
            Bibliotheque.dossier(this@MainActivity, console) != null

        /** Lance l'emulateur de la console sur cette ROM. */
        @JavascriptInterface
        fun lancer(console: String, uriRom: String) {
            startActivity(ecran(console).putExtra("console", console).putExtra("rom", uriRom))
        }

        /** Ouvre l'emulateur sur son catalogue, sans jeu precis. */
        @JavascriptInterface
        fun ouvrirConsole(console: String) {
            startActivity(ecran(console).putExtra("console", console))
        }

        /** La NES garde son interface d'origine ; les autres passent par l'ecran commun. */
        private fun ecran(console: String): Intent = when (console) {
            "nes" -> Intent(this@MainActivity, com.rudy.chambre.nesui.NesActivity::class.java)
            "md"  -> Intent(this@MainActivity, com.rudy.chambre.mdui.MdActivity::class.java)
            "gb"  -> Intent(this@MainActivity, com.rudy.chambre.gbui.GbActivity::class.java)
            "gba" -> Intent(this@MainActivity, com.rudy.chambre.gbaui.GbaActivity::class.java)
            else  -> Intent(this@MainActivity, EmulateurActivity::class.java)
        }
    }
}
