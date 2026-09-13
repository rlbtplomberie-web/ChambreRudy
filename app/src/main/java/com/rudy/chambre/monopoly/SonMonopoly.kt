package com.rudy.chambre.monopoly

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ses bruitages : le pas du jeton, les des, l'achat, la perte, la prison et
 * la faillite — calcules comme dans sa page.
 */
class SonMonopoly {

    private val frequence = 22050

    private fun jouerDonnees(d: ShortArray) {
        try {
            val piste = AudioTrack(AudioManager.STREAM_MUSIC, frequence,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                d.size * 2, AudioTrack.MODE_STATIC)
            piste.write(d, 0, d.size)
            piste.setNotificationMarkerPosition(d.size)
            piste.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack?) { try { t?.release() } catch (_: Throwable) {} }
                    override fun onPeriodicNotification(t: AudioTrack?) {}
                })
            piste.play()
        } catch (_: Throwable) {}
    }

    private fun note(depart: Double, arrivee: Double, duree: Double, volume: Double) {
        val n = (frequence * duree).toInt()
        val d = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / frequence
            val f = depart * Math.pow(arrivee / depart, t / duree)
            d[i] = (sin(2 * PI * f * t) * volume * exp(-t * 9.0) * 32767).toInt()
                .coerceIn(-32767, 32767).toShort()
        }
        jouerDonnees(d)
    }

    /** Les des : de petits chocs secs, comme du bois qui roule. */
    private fun des() {
        val n = (frequence * .45).toInt()
        val d = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / frequence
            val choc = ((t * 22).toInt() % 3 == 0)
            val env = if (choc) exp(-((t * 22) % 1.0) * 30.0) else 0.0
            d[i] = ((Random.nextDouble() * 2 - 1) * env * .5 * 32767).toInt()
                .coerceIn(-32767, 32767).toShort()
        }
        jouerDonnees(d)
    }

    fun jouer(quoi: String) {
        when (quoi) {
            "pas" -> note(420.0, 380.0, .045, .18)
            "des" -> des()
            "achat" -> { note(520.0, 780.0, .10, .35); note(780.0, 1040.0, .12, .25) }
            "perte" -> note(300.0, 170.0, .16, .35)
            "prison" -> { note(200.0, 120.0, .22, .45); note(120.0, 80.0, .30, .35) }
            "faillite" -> { note(260.0, 90.0, .45, .45); note(180.0, 60.0, .55, .35) }
        }
    }

    fun liberer() {}
}
