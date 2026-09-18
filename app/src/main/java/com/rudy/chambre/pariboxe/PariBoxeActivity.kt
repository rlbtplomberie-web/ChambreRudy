package com.rudy.chambre.pariboxe

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rudy.chambre.ChambreActivity
import java.io.File
import java.io.FileOutputStream

/**
 * PariBoxe affiche strictement le fichier HTML de Rudy.
 * Aucune feuille de style, aucun script et aucun zoom ne sont ajoutés ici.
 */
class PariBoxeActivity : ComponentActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            overScrollMode = WebView.OVER_SCROLL_NEVER
            setBackgroundColor(Color.BLACK)
            webViewClient = WebViewClient()
            loadUrl(preparerHtml().toURI().toString())
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(web, FrameLayout.LayoutParams(-1, -1))
        })
        com.rudy.chambre.SonPartage.volume(0f)
    }

    private fun preparerHtml(): File {
        val html = File(cacheDir, "pariboxe-html-original-v4.html")
        if (html.isFile && html.length() > 30_000_000L) return html
        val temporaire = File(cacheDir, "pariboxe-html-original-v4.tmp")
        FileOutputStream(temporaire).use { sortie ->
            assets.open("pariboxe/index.html.000").use { it.copyTo(sortie) }
            assets.open("pariboxe/index.html.001").use { it.copyTo(sortie) }
        }
        if (html.exists()) html.delete()
        if (!temporaire.renameTo(html)) {
            temporaire.copyTo(html, overwrite = true)
            temporaire.delete()
        }
        return html
    }

    private fun retourBureau() {
        startActivity(Intent(this, ChambreActivity::class.java)
            .putExtra("retour_bureau", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    @Deprecated("Le retour Android revient au bureau de la chambre.")
    override fun onBackPressed() = retourBureau()

    override fun onDestroy() {
        try { web.destroy() } catch (_: Throwable) { }
        com.rudy.chambre.Ambiance.rendreLaMusique()
        super.onDestroy()
    }
}
