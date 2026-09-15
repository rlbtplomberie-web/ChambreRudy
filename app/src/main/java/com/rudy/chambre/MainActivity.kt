package com.rudy.chambre

import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
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

        /*
         * Les fichiers de la chambre sont servis comme un petit site local.
         *
         * Une page ouverte en file:// n'a pas le droit d'aller chercher ses
         * propres fichiers : les musiques et les videos resteraient muettes.
         * Servis sous une adresse, ils se chargent normalement.
         */
        val serveur = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        vue = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            settings.setGeolocationEnabled(false)
            // dessin par la puce graphique, sur une couche a part
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            // pas de barres ni de rebond : autant d'images en moins a calculer
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            setBackgroundColor(android.graphics.Color.BLACK)
            // garde une image prete hors de l'ecran : moins de saccades au defilement
            try { androidx.webkit.WebSettingsCompat.setOffscreenPreRaster(settings, true) } catch (_: Throwable) {}
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                    serveur.shouldInterceptRequest(r.url)
            }
            addJavascriptInterface(Pont(), "Android")
            loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
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
            // l'application voisine n'est pas installee : on prefere le dire
            // plutot que d'ouvrir une version abandonnee qui se fermerait
            android.widget.Toast.makeText(this,
                fiche.nom + " : installe ton application " + paquet +
                ", la chambre l'ouvrira.", android.widget.Toast.LENGTH_LONG).show()
            return null
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

        /** Le dernier plantage note, ou une chaine vide. */
        @JavascriptInterface
        fun dernierPlantage(): String =
            try {
                val f = java.io.File(filesDir, "dernier_plantage.txt")
                if (f.exists()) f.readText() else ""
            } catch (_: Throwable) { "" }

        @JavascriptInterface
        fun effacerPlantage() {
            try { java.io.File(filesDir, "dernier_plantage.txt").delete() } catch (_: Throwable) {}
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
                // l'ecran de cet emulateur existe-t-il vraiment dans cet APK ?
                val present = try { Class.forName(c.activite); true } catch (_: Throwable) { false }
                o.put("ecran", present)
                if (!present) o.put("ok", false)
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
            runOnUiThread {
                try {
                    val fiche = Consoles.parId(console)
                    if (fiche == null) {
                        android.widget.Toast.makeText(this@MainActivity,
                            "console inconnue : " + console, android.widget.Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    val i = ecran(console)
                    if (i == null) {
                        android.widget.Toast.makeText(this@MainActivity,
                            fiche.nom + " : ecran introuvable (" + fiche.activite + ")",
                            android.widget.Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    if (uriRom.isBlank()) {
                        android.widget.Toast.makeText(this@MainActivity,
                            fiche.nom + " : aucun jeu designe", android.widget.Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    // Les trois moteurs plus lourds ne comprennent pas le
                    // simple extra "rom" des emulateurs integres.
                    if (console == "n64") {
                        val direct = N64.intentionParCatalogue(this@MainActivity, uriRom)
                        if (direct != null) {
                            startActivity(direct)
                            return@runOnUiThread
                        }
                    }
                    if (fiche.activite.startsWith("org.dolphinemu")) {
                        val cheminJeu = Dolphin.preparerJeu(this@MainActivity, uriRom)
                        if (cheminJeu == null) {
                            android.widget.Toast.makeText(this@MainActivity,
                                fiche.nom + " : jeu impossible a preparer pour Dolphin",
                                android.widget.Toast.LENGTH_LONG).show()
                            return@runOnUiThread
                        }
                        i.putExtra("SelectedGames", arrayOf(cheminJeu))
                        i.putExtra("SelectedTitle", fiche.nom)
                        i.putExtra("riivolution", false)
                        i.putExtra("systemMenu", false)
                        i.putExtra("platform", if (console == "wii") 1 else 0)
                    } else {
                        i.putExtra("rom", uriRom)
                        // SkinGC sert aux deux consoles : il choisit son
                        // habillage d'apres cette information.
                        if (fiche.activite == "com.skingc.app.MainActivity") {
                            i.putExtra("console", console)
                        }
                    }
                    startActivity(i)
                } catch (e: Throwable) {
                    android.widget.Toast.makeText(this@MainActivity,
                        "lancement impossible : " + e, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
