package com.rudy.chambre.cartes

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.*
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Le poker de Rudy : sa table, ses trois adversaires cartes face cachee, le
 * tapis de cinq cartes, le pot et ses jetons.
 */
class PokerActivity : ComponentActivity() {

    private val jeu = Poker()
    private lateinit var vue: VuePoker
    private var son: SonCartes? = null

    override fun onCreate(e: Bundle?) {
        super.onCreate(e)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        son = SonCartes()
        vue = VuePoker(this, jeu)

        val barre = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(bouton("DISTRIBUER") { jeu.distribuer(); son?.bip(620.0, .08); vue.invalidate() })
            addView(bouton("SUIVRE") { jeu.suivre(); son?.bip(440.0, .06); vue.invalidate() })
            addView(bouton("SE COUCHER") { jeu.seCoucher(); son?.bip(250.0, .06); vue.invalidate() })
            addView(bouton("+100 JETONS") { jeu.ajouterDesJetons(); vue.invalidate() })
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = 22 }
        }

        val racine = FrameLayout(this)
        racine.setBackgroundColor(Color.BLACK)
        racine.addView(vue, FrameLayout.LayoutParams(-1, -1))
        racine.addView(barre)
        racine.addView(bouton("← Bureau") { finish() }.apply {
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
                .apply { topMargin = 18; leftMargin = 18 }
        })
        setContentView(racine)
    }

    private fun bouton(titre: String, action: () -> Unit) = Button(this).apply {
        text = titre; textSize = 12f
        setTextColor(Color.WHITE); setBackgroundColor(0xCC2A1B3F.toInt())
        setOnClickListener { action() }
    }

    override fun onDestroy() { son?.liberer(); super.onDestroy() }
}

/** Sa table : Kev, Yoel et Arthur autour, Rudy en bas, le tapis au centre. */
class VuePoker(ctx: Context, private val jeu: Poker) : View(ctx) {

    private val images = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    private val pinceau = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val texte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        Thread {
            charger("tapis.webp"); charger("dos.webp")
            for (c in Paquet.couleurs) for (h in Paquet.hauteurs) charger("${c}_${h.lowercase()}.webp")
            post { invalidate() }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun charger(nom: String): Bitmap? {
        images[nom]?.let { return it }
        return try {
            context.assets.open("cartes/$nom").use {
                BitmapFactory.decodeStream(it)?.also { b -> images[nom] = b }
            }
        } catch (_: Throwable) { null }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF0A1410.toInt())
        charger("tapis.webp")?.let {
            c.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), pinceau)
        }

        val hauteur = height * .20f
        val largeur = hauteur * .68f

        // les trois adversaires, cartes face cachee
        val noms = listOf("Kev", "Yoel", "Arthur")
        val places = listOf(width * .16f to height * .24f,
                            width * .50f to height * .16f,
                            width * .84f to height * .24f)
        noms.forEachIndexed { i, nom ->
            val (cx, cy) = places[i]
            if (jeu.phase > 0) {
                charger("dos.webp")?.let { dos ->
                    c.drawBitmap(dos, null,
                        RectF(cx - largeur * .95f, cy - hauteur / 2f, cx + largeur * .05f, cy + hauteur / 2f), pinceau)
                    c.drawBitmap(dos, null,
                        RectF(cx - largeur * .05f, cy - hauteur / 2f, cx + largeur * .95f, cy + hauteur / 2f), pinceau)
                }
            }
            texte.textSize = height * .032f; texte.color = 0xFFCFC7B7.toInt()
            c.drawText(nom, cx, cy + hauteur * .72f, texte)
        }

        // le tapis : cinq emplacements au centre
        val ecart = largeur * 1.12f
        val depart = width / 2f - ecart * 2f
        for (k in 0 until 5) {
            val x = depart + k * ecart
            val cy = height * .48f
            val carte = jeu.tapis.getOrNull(k)
            if (carte != null) {
                charger(carte.lowercase() + ".webp")?.let {
                    c.drawBitmap(it, null,
                        RectF(x - largeur / 2f, cy - hauteur / 2f, x + largeur / 2f, cy + hauteur / 2f), pinceau)
                }
            } else {
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0x66FFFFFF
                c.drawRoundRect(RectF(x - largeur / 2f, cy - hauteur / 2f,
                    x + largeur / 2f, cy + hauteur / 2f), 8f, 8f, p)
                p.style = Paint.Style.FILL
            }
        }

        // ta main, en bas
        jeu.maMain.forEachIndexed { i, carte ->
            val x = width / 2f + (i - .5f) * largeur * 1.05f
            charger(carte.lowercase() + ".webp")?.let {
                c.drawBitmap(it, null,
                    RectF(x - largeur / 2f, height * .78f - hauteur / 2f,
                          x + largeur / 2f, height * .78f + hauteur / 2f), pinceau)
            }
        }

        texte.textSize = height * .036f; texte.color = 0xFFFFD35A.toInt()
        c.drawText("Pot : ${jeu.pot} ♦", width * .16f, height * .60f, texte)
        c.drawText("${jeu.jetons} ♦", width * .84f, height * .60f, texte)
        texte.textSize = height * .030f; texte.color = Color.WHITE
        c.drawText(jeu.message, width / 2f, height * .94f, texte)
    }
}
