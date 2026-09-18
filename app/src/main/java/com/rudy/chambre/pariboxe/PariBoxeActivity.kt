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

/**
 * PariBoxe en natif (plus de WebView) : la scene est dessinee par
 * [VuePariBoxe] avec les planches de sprites du fichier HTML de Rudy,
 * aux memes tailles, positions et vitesses d'animation.
 */
class PariBoxeActivity : ComponentActivity() {
    private lateinit var vue: VuePariBoxe
    private val son = SonPariBoxe()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        vue = VuePariBoxe(this).apply { son = this@PariBoxeActivity.son }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(vue, FrameLayout.LayoutParams(-1, -1))
        })
        com.rudy.chambre.SonPartage.volume(0f)
        vue.lancer()
    }

    override fun onResume() {
        super.onResume()
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        son.reprise()
        vue.enMarche = true
    }

    override fun onPause() {
        vue.enMarche = false
        son.pause()
        super.onPause()
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
        try { vue.fermer() } catch (_: Throwable) { }
        try { son.liberer() } catch (_: Throwable) { }
        com.rudy.chambre.Ambiance.rendreLaMusique()
        super.onDestroy()
    }
}
