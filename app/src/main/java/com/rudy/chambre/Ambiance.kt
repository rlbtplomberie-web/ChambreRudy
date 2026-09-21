package com.rudy.chambre

import android.app.Activity
import android.graphics.BitmapFactory
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Ce qui entoure un jeu : l'affiche d'avant-match, et la musique de la chambre
 * qui continue pendant la partie, simplement adoucie.
 */
object Ambiance {

    private var lecteur: android.media.MediaPlayer? = null

    /**
     * Sa musique de jeu : elle remplace celle de la radio le temps de la
     * partie, exactement comme son « jeuMusique » en tournant en boucle.
     * La radio se tait, puis revient a la sortie.
     */
    fun musiqueDuJeu(activite: Activity, fichier: String, volume: Float = 1f) {
        SonPartage.volume(0f)                   // la radio se tait
        arreterLaMusique()
        try {
            val f = activite.assets.openFd(fichier)
            lecteur = android.media.MediaPlayer().apply {
                setDataSource(f.fileDescriptor, f.startOffset, f.length)
                isLooping = true
                setVolume(volume, volume)
                prepare()
                start()
            }
            f.close()
        } catch (_: Throwable) {
            // pas de musique propre au jeu : on garde la radio, adoucie
            SonPartage.volume(0.25f)
        }
    }

    private fun arreterLaMusique() {
        try { lecteur?.stop() } catch (_: Throwable) {}
        try { lecteur?.release() } catch (_: Throwable) {}
        lecteur = null
    }

    /** La musique baisse pendant le jeu, faute de piste propre. */
    fun adoucirLaMusique() = SonPartage.volume(0.25f)

    /** En sortant du jeu : sa musique s'arrete, la radio revient. */
    fun rendreLaMusique() {
        arreterLaMusique()
        // de retour dans la rue de Shinato : c'est sa musique qui joue, pas la radio de la chambre
        SonPartage.volume(if (SonPartage.dansShinato) 0f else 1.0f)
    }

    /**
     * Son ecran de fin : l'image de victoire ou de defaite, avec le choix de
     * recommencer ou de retourner au bureau.
     */
    fun ecranDeFin(activite: Activity, racine: FrameLayout, fichier: String,
                   titre: String, detail: String,
                   recommencer: () -> Unit) {
        val image = try {
            activite.assets.open(fichier).use { BitmapFactory.decodeStream(it) }
        } catch (_: Throwable) { null }

        val bloc = FrameLayout(activite)
        bloc.setBackgroundColor(0xEE07060D.toInt())
        if (image != null) {
            bloc.addView(ImageView(activite).apply {
                setImageBitmap(image)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }

        val dens = activite.resources.displayMetrics.density
        val colonne = android.widget.LinearLayout(activite).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        colonne.addView(android.widget.TextView(activite).apply {
            text = titre; textSize = 30f
            setTextColor(0xFFFFE9A8.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        colonne.addView(android.widget.TextView(activite).apply {
            text = detail; textSize = 15f
            setTextColor(0xFFE8E2D4.toInt())
            gravity = Gravity.CENTER
            setPadding(0, (6 * dens).toInt(), 0, (14 * dens).toInt())
        })
        val rangee = android.widget.LinearLayout(activite).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        rangee.addView(Button(activite).apply {
            text = "RECOMMENCER"; textSize = 14f
            setTextColor(0xFF2A1C06.toInt())
            setBackgroundColor(0xFFF2C14E.toInt())
            setOnClickListener { racine.removeView(bloc); recommencer() }
        })
        rangee.addView(Button(activite).apply {
            text = "← PARTIR"; textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x33FFFFFF)
            setOnClickListener { activite.finish() }
        }, android.widget.LinearLayout.LayoutParams(-2, -2).apply {
            leftMargin = (10 * dens).toInt()
        })
        colonne.addView(rangee)

        bloc.addView(colonne, FrameLayout.LayoutParams(-2, -2,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (40 * dens).toInt()
        })

        racine.addView(bloc, FrameLayout.LayoutParams(-1, -1))
    }

    /**
     * L'affiche d'avant-match : elle occupe tout l'ecran, avec un bouton pour
     * commencer. Le jeu ne demarre qu'a cet appui.
     */
    fun affiche(activite: Activity, racine: FrameLayout, fichier: String, commencer: () -> Unit) {
        val image = try {
            activite.assets.open(fichier).use { BitmapFactory.decodeStream(it) }
        } catch (_: Throwable) { null }
        if (image == null) { commencer(); return }

        val bloc = FrameLayout(activite)
        bloc.setBackgroundColor(0xFF07060D.toInt())
        bloc.addView(ImageView(activite).apply {
            setImageBitmap(image)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, FrameLayout.LayoutParams(-1, -1))

        bloc.addView(Button(activite).apply {
            text = "COMMENCER"
            textSize = 16f
            setTextColor(0xFF2A1C06.toInt())
            setBackgroundColor(0xFFF2C14E.toInt())
            setOnClickListener { racine.removeView(bloc); commencer() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (36 * activite.resources.displayMetrics.density).toInt()
        })

        racine.addView(bloc, FrameLayout.LayoutParams(-1, -1))
    }
}
