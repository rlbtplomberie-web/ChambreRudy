package com.rudy.chambre.pariboxe

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rudy.chambre.ChambreActivity
import java.io.File

/**
 * PariBoxe en natif : plus de WebView.
 * Les images, les sprites et la musique sont extraits tels quels du fichier HTML
 * (assets pariboxe/index.html.000 + .001) puis dessines par VuePariBoxe
 * avec les memes tailles, positions et cadences que la page.
 */
class PariBoxeActivity : ComponentActivity() {
    private lateinit var son: SonPariBoxe
    private lateinit var vue: VuePariBoxe

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        pleinEcran()

        // ancien cache de la version WebView (40 Mo) devenu inutile
        try {
            File(cacheDir, "pariboxe-html-original-v4.html").delete()
            File(cacheDir, "pariboxe-html-original-v4.tmp").delete()
        } catch (_: Throwable) { }

        son = SonPariBoxe()
        vue = VuePariBoxe(this, son)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(vue, FrameLayout.LayoutParams(-1, -1))
        })
        com.rudy.chambre.SonPartage.volume(0f)
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
        // PariBoxe ne se joue qu'a l'horizontale (et la vue pivote d'elle-meme si l'ecran reste vertical)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        try { son.reprise() } catch (_: Throwable) { }
    }

    override fun onPause() {
        try { son.pause() } catch (_: Throwable) { }
        super.onPause()
    }

    private fun retourBureau() {
        // lancée depuis un terrain de la rue de Shinato : on revient simplement dans la rue
        if (intent.getBooleanExtra("depuis_shinato", false)) { finish(); return }
        startActivity(Intent(this, ChambreActivity::class.java)
            .putExtra("retour_bureau", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    @Deprecated("Le retour Android revient au bureau de la chambre.")
    override fun onBackPressed() = retourBureau()

    override fun onDestroy() {
        try { vue.liberer() } catch (_: Throwable) { }
        try { son.liberer() } catch (_: Throwable) { }
        com.rudy.chambre.Ambiance.rendreLaMusique()
        super.onDestroy()
    }
}
