package com.rudy.chambre.basket

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ses bruitages : le rebond du ballon, le crissement des semelles au depart du
 * tir, et le filet. Comme dans sa page, ils sont calcules, pas enregistres.
 */
class SonBasket {

    private val frequence = 22050

    private fun jouerDonnees(donnees: ShortArray) {
        try {
            val piste = AudioTrack(AudioManager.STREAM_MUSIC, frequence,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                donnees.size * 2, AudioTrack.MODE_STATIC)
            piste.write(donnees, 0, donnees.size)
            piste.setNotificationMarkerPosition(donnees.size)
            piste.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack?) { try { t?.release() } catch (_: Throwable) {} }
                    override fun onPeriodicNotification(t: AudioTrack?) {}
                })
            piste.play()
        } catch (_: Throwable) {}
    }

    /** Le rebond : un son grave qui descend de 115 a 48, plus un choc sourd. */
    private fun rebond() {
        val n = (frequence * .14).toInt()
        val d = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / frequence
            val f = 115.0 * Math.pow(48.0 / 115.0, t / .10)
            val env = Math.exp(-t * 26.0)
            val bruit = if (t < .055) (Random.nextDouble() * 2 - 1) * (1 - t / .055) * .32 else 0.0
            d[i] = ((sin(2 * PI * f * t) * .75 * env + bruit) * 20000).toInt()
                .coerceIn(-32767, 32767).toShort()
        }
        jouerDonnees(d)
    }

    /** Le crissement de semelle : un bruit filtre, aigu, qui descend. */
    private fun semelle(hauteur: Double, duree: Double, volume: Double) {
        val n = (frequence * duree).toInt()
        val d = ShortArray(n)
        var precedent = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / frequence
            val f = hauteur + (hauteur * .48 - hauteur) * (t / duree)
            val enveloppe = sin(PI * i / n)
            val brut = (Random.nextDouble() * 2 - 1) * enveloppe
            // un filtre etroit autour de la hauteur voulue
            precedent = precedent * .86 + brut * .14
            d[i] = ((precedent * sin(2 * PI * f * t) * volume) * 32767).toInt()
                .coerceIn(-32767, 32767).toShort()
        }
        jouerDonnees(d)
    }

    fun jouer(quoi: String) {
        when (quoi) {
            "rebond" -> rebond()
            "tir" -> { semelle(1900.0, .11, .30); semelle(1450.0, .15, .38) }
            "panier", "filet" -> semelle(2400.0, .18, .22)
            "rate" -> rebond()
        }
    }

    fun liberer() {}
}
