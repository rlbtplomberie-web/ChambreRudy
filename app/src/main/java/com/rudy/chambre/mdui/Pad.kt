package com.rudy.chambre.mdui

/** Bits de la manette, tels que ce coeur les attend. */
object Pad {
    const val B = 1 shl 0        // B de la Mega Drive
    const val MODE = 1 shl 2     // le bouton MODE de la manette six touches
    const val START = 1 shl 3
    const val HAUT = 1 shl 4
    const val BAS = 1 shl 5
    const val GAUCHE = 1 shl 6
    const val DROITE = 1 shl 7
    const val C = 1 shl 8
    const val X = 1 shl 9
    const val A = 1 shl 1        // A de la Mega Drive
    const val Y = 1 shl 10
    const val Z = 1 shl 11
}
