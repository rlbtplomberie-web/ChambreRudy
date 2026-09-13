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

    /**
     * La musique baisse a un quart pendant le jeu et revient en sortant,
     * comme le faisaient ses pages web.
     */
    fun adoucirLaMusique() = SonPartage.volume(0.25f)

    fun rendreLaMusique() = SonPartage.volume(1.0f)

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
