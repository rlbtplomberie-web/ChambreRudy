package com.rudy.chambre.shinato

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader

/**
 * « Sortir à Shinato » : le jeu Rudy Style (la rue, la plage, la moto, le Pixel Bar,
 * le Dragon d'Or, la ville délabrée, Vaviel et leurs mini-jeux).
 *
 * Chaque lieu et chaque mini-jeu est un fichier à part dans assets/shinato :
 *   index.html        la rue de Shinato (point de départ)
 *   lieux/*.html      les autres cartes, chargées seulement quand on y va
 *   jeux/*.html       les mini-jeux, chargés seulement quand on les lance
 * Le téléphone ne lit donc jamais tout d'un coup.
 */
class ShinatoActivity : ComponentActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        WindowCompat.setDecorFitsSystemWindows(window, false)
        pleinEcran()

        val serveur = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false   // la musique de la ville démarre sans attendre
            settings.allowFileAccess = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            // pas de mode sombre forcé : il blanchissait les personnages
            try { settings.javaClass.getMethod("setAlgorithmicDarkeningAllowed", Boolean::class.java).invoke(settings, false) } catch (_: Throwable) { }
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isHapticFeedbackEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                    serveur.shouldInterceptRequest(r.url)
            }
            webChromeClient = WebChromeClient()
            setOnLongClickListener { true }                     // pas de menu d'appui long
            isLongClickable = false
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(web, FrameLayout.LayoutParams(-1, -1))
        })
        // la musique de la chambre laisse la place à celle de Shinato
        try { com.rudy.chambre.SonPartage.volume(0f) } catch (_: Throwable) { }
        web.loadUrl("https://appassets.androidplatform.net/assets/shinato/index.html")
    }

    private fun pleinEcran() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) pleinEcran()
    }

    override fun onResume() {
        super.onResume()
        web.onResume(); web.resumeTimers()
    }

    override fun onPause() {
        web.onPause(); web.pauseTimers()                      // plus de son ni de calcul quand l'appli passe derrière
        super.onPause()
    }

    /** Le retour Android ramène dans la chambre, devant la baie vitrée. */
    @Deprecated("Le retour Android revient dans la chambre.")
    override fun onBackPressed() { finish() }

    override fun onDestroy() {
        try { web.stopLoading(); web.loadUrl("about:blank"); web.destroy() } catch (_: Throwable) { }
        try { com.rudy.chambre.Ambiance.rendreLaMusique() } catch (_: Throwable) { }
        super.onDestroy()
    }
}
