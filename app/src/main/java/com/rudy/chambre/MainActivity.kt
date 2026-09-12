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

    /**
     * L'ecran de l'emulateur de cette console.
     *
     * La GameCube fait exception : son emulateur n'est pas un coeur qu'on
     * charge, c'est Dolphin recompile avec les pads de Rudy. Il reste donc une
     * application a part. Si elle est installee sur le telephone, la chambre
     * l'ouvre ; sinon on se rabat sur l'ecran interne, qui expliquera pourquoi
     * il ne peut rien faire.
     */
    private fun ecran(console: String): Intent? {
        val fiche = Consoles.parId(console) ?: return null
        fiche.paquetVoisin?.let { paquet ->
            packageManager.getLaunchIntentForPackage(paquet)?.let { return it }
        }
        return try { Intent(this, Class.forName(fiche.activite)) } catch (_: Throwable) { null }
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

        /**
         * Etat de chaque console : son coeur est-il bien dans l'application, et
         * un dossier de jeux a-t-il ete choisi ? C'est la reponse en une seconde
         * a la question « pourquoi celle-la ne demarre pas ».
         */
        @JavascriptInterface
        fun diagnostic(): String {
            val dossierLib = java.io.File(applicationInfo.nativeLibraryDir)
            val a = JSONArray()
            for (c in Consoles.TOUTES) {
                val o = JSONObject().put("id", c.id).put("nom", c.nom)
                if (c.coeur == null) {
                    o.put("coeur", "aucun nécessaire").put("ok", true)
                } else {
                    val f = java.io.File(dossierLib, c.coeur)
                    o.put("coeur", if (f.exists()) c.coeur + " · " + (f.length() / 1048576) + " Mo"
                                   else c.coeur + " ABSENT")
                    o.put("ok", f.exists())
                }
                o.put("dossier", Dossiers.dossier(this@MainActivity, c.id) != null)
                o.put("jeux", Dossiers.lister(this@MainActivity, c.id).size)
                a.put(o)
            }
            return a.toString()
        }

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
