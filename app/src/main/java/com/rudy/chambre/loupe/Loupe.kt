package com.rudy.chambre.loupe

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * La loupe du second tiroir.
 *
 * Elle montre de pres la console posee sur le bureau : une page HTML par
 * console, rangee dans assets/loupe/. Le bouton retour la referme et on
 * retrouve la chambre telle qu'on l'avait laissee.
 */
object Loupe {

    /** Console posee (id de Decor.CONSOLES) -> page a montrer. */
    private val PAGES = mapOf(
        "nes" to "nes.html",
        "gb" to "gameboy.html",
        "snes" to "supernintendo.html",
        "md" to "megadrive.html",
        "gba" to "gameboyadvance.html",
        "ds" to "nintendods.html",
        "ps1" to "playstation.html",
        "n64" to "nintendo64.html",
        "dc" to "dreamcast.html",
        "psp" to "psp.html",
        "gc" to "gamecube.html",
        "wii" to "wii.html",
        "3ds" to "nintendo3ds.html"
    )

    fun aUnePage(idConsole: String) = PAGES.containsKey(idConsole)

    @SuppressLint("SetJavaScriptEnabled")
    fun ouvrir(activite: Activity, idConsole: String) {
        val page = PAGES[idConsole] ?: return
        val dialogue = Dialog(activite, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val vue = WebView(activite).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            // three.js vient d'internet : garde en cache, la loupe marche
            // ensuite meme sans connexion
            settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
        }
        /*
         * Si Android arrete l'affichage des pages web par manque de memoire,
         * une WebView sans cette reponse fait fermer TOUTE l'appli.
         * La loupe se referme simplement, et l'on retrouve la chambre.
         */
        vue.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(v: WebView, d: android.webkit.RenderProcessGoneDetail): Boolean {
                try { (v.parent as? android.view.ViewGroup)?.removeView(v) } catch (_: Throwable) { }
                try { v.destroy() } catch (_: Throwable) { }
                try { dialogue.setOnDismissListener(null); dialogue.dismiss() } catch (_: Throwable) { }
                return true
            }
        }
        vue.loadUrl("file:///android_asset/loupe/$page")
        dialogue.setContentView(vue)
        dialogue.setOnDismissListener {
            try { vue.stopLoading(); vue.loadUrl("about:blank"); vue.destroy() } catch (_: Throwable) { }
        }
        dialogue.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            WindowInsetsControllerCompat(w, w.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        dialogue.show()
    }
}
