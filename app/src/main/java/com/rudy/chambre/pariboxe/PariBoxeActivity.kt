package com.rudy.chambre.pariboxe

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rudy.chambre.ChambreActivity
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * PariBoxe reste strictement le combat cree par Rudy : ses images, animations,
 * IA et commandes vivent dans l'asset du jeu. Cette activite Android le place
 * simplement dans RetroRom et gere ses sorties vers la chambre.
 */
class PariBoxeActivity : ComponentActivity() {
    private lateinit var racine: FrameLayout
    private lateinit var web: WebView
    private var resultatAffiche = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(etat: Bundle?) {
        super.onCreate(etat)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        racine = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            // Le fichier de Rudy est deja calibre en vw/vh pour son ecran.
            // WebView ne doit ni l'agrandir, ni le reduire, ni appliquer un zoom d'aperçu.
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false
            settings.textZoom = 100
            setInitialScale(100)
            overScrollMode = WebView.OVER_SCROLL_NEVER
            setBackgroundColor(Color.BLACK)
            addJavascriptInterface(PontAndroid(), "AndroidPariBoxe")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(vue: WebView, url: String) {
                    resultatAffiche = false
                    installerDetectionDeFin()
                }
            }
            loadUrl(Uri.fromFile(preparerJeu()).toString())
        }
        racine.addView(web, FrameLayout.LayoutParams(-1, -1))
        setContentView(racine)
        com.rudy.chambre.Ambiance.adoucirLaMusique()
    }

    /**
     * Les animations de PariBoxe sont volumineuses. Elles restent completes,
     * mais sont stockees compressees dans l'APK afin que la mise a jour puisse
     * etre deposee depuis le telephone. Android les decompresse une seule fois
     * dans son cache avant de lancer le combat.
     */
    private fun preparerJeu(): File {
        val cible = File(cacheDir, "pariboxe-v1.html")
        if (cible.isFile && cible.length() > 30_000_000L) return cible
        val temporaire = File(cacheDir, "pariboxe-v1.tmp")
        val compresse = File(cacheDir, "pariboxe-v1.xz")
        try {
            // Les deux morceaux sont des assets normaux : ils sont reunis ici
            // avant la decompression et n'exigent ni internet ni permission.
            FileOutputStream(compresse).use { destination ->
                assets.open("pariboxe/index.html.xz.000").use { it.copyTo(destination) }
                assets.open("pariboxe/index.html.xz.001").use { it.copyTo(destination) }
            }
            FileInputStream(compresse).use { brut ->
                XZInputStream(brut).use { source ->
                    FileOutputStream(temporaire).use { destination -> source.copyTo(destination) }
                }
            }
            if (cible.exists()) cible.delete()
            if (!temporaire.renameTo(cible)) {
                temporaire.copyTo(cible, overwrite = true)
                temporaire.delete()
            }
        } catch (e: Throwable) {
            temporaire.delete()
            compresse.delete()
            throw IllegalStateException("PariBoxe ne peut pas etre prepare", e)
        }
        compresse.delete()
        return cible
    }

    /** Le jeu avertit l'activite seulement lorsqu'une barre de vie atteint 0. */
    private inner class PontAndroid {
        @JavascriptInterface fun combatFini(gagnant: String) {
            runOnUiThread { afficherFin(gagnant) }
        }
    }

    private fun installerDetectionDeFin() {
        web.evaluateJavascript(
            """
            (function () {
              if (window.__pariBoxeWatcher) return;
              window.__pariBoxeWatcher = true;
              function vie(id) {
                var e = document.getElementById(id);
                if (!e) return 100;
                var n = parseFloat(e.style.width);
                return isFinite(n) ? n : 100;
              }
              setInterval(function () {
                if (!window.__combatReady || window.__pariBoxeTermine) return;
                var rudy = vie('rudyHp'), chauve = vie('cpuHp');
                if (rudy > 0.01 && chauve > 0.01) return;
                window.__pariBoxeTermine = true;
                window.__combatReady = false;
                if (window.AndroidPariBoxe) {
                  AndroidPariBoxe.combatFini(chauve <= 0.01 ? 'RUDY' : 'LE CHAUVE');
                }
              }, 120);
            })();
            """.trimIndent(), null
        )
    }

    private fun afficherFin(gagnant: String) {
        if (resultatAffiche || isFinishing) return
        resultatAffiche = true

        val densite = resources.displayMetrics.density
        val voile = FrameLayout(this).apply { setBackgroundColor(0xE8000000.toInt()) }
        val colonne = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding((28 * densite).toInt(), (22 * densite).toInt(),
                (28 * densite).toInt(), (24 * densite).toInt())
            setBackgroundColor(0xEE17100B.toInt())
        }
        colonne.addView(TextView(this).apply {
            text = "$gagnant GAGNE !"
            textSize = 30f
            setTextColor(0xFFFFD54F.toInt())
            gravity = android.view.Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        colonne.addView(TextView(this).apply {
            text = "Le combat est termine."
            textSize = 16f
            setTextColor(0xFFF5E8D4.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, (8 * densite).toInt(), 0, (18 * densite).toInt())
        })
        val boutons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        boutons.addView(Button(this).apply {
            text = "RECOMMENCER"
            setTextColor(0xFF251700.toInt())
            setBackgroundColor(0xFFF2C14E.toInt())
            setOnClickListener {
                racine.removeView(voile)
                // Retour au premier ecran du jeu : le choix des combattants.
                web.reload()
            }
        })
        boutons.addView(Button(this).apply {
            text = "← BUREAU"
            setTextColor(Color.WHITE)
            setBackgroundColor(0x33555555)
            setOnClickListener { retourAuBureau() }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = (12 * densite).toInt() })
        colonne.addView(boutons)
        voile.addView(colonne, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER))
        racine.addView(voile, FrameLayout.LayoutParams(-1, -1))
    }

    private fun retourAuBureau() {
        startActivity(Intent(this, ChambreActivity::class.java)
            .putExtra("retour_bureau", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    @Deprecated("Gestion explicite : ce jeu doit revenir au bureau.")
    override fun onBackPressed() = retourAuBureau()

    override fun onDestroy() {
        try { web.destroy() } catch (_: Throwable) {}
        com.rudy.chambre.Ambiance.rendreLaMusique()
        super.onDestroy()
    }
}
