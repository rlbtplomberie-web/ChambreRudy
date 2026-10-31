package com.rudy.chambre

import android.graphics.Bitmap
import android.webkit.WebChromeClient

/**
 * Le lecteur vidéo des pages affiche, avant la première image de chaque vidéo,
 * un écran gris avec le bouton « lecture » (l'affiche par défaut d'Android).
 * On la remplace par une image vide et transparente : la vidéo démarre directement.
 */
fun affichageVideoVide(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

open class PasDePoster : WebChromeClient() {
    override fun getDefaultVideoPoster(): Bitmap = affichageVideoVide()
}
