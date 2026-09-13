package com.rudy.chambre.balle

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Ses bruitages, fabriques note par note comme dans sa page : il ne les avait
 * pas en fichiers, ils etaient calcules a la volee.
 */
class SonBalle {

    private val frequence = 22050

    private fun ton(hauteur: Double, duree: Double, volume: Double, glissando: Double = 0.0) {
        try {
            val n = (frequence * duree).toInt()
            val donnees = ShortArray(n)
            for (i in 0 until n) {
                val t = i.toDouble() / frequence
                val f = hauteur + (glissando - hauteur) * (t / duree)
                val enveloppe = (1.0 - t / duree).coerceIn(0.0, 1.0)
                donnees[i] = (sin(2 * PI * f * t) * volume * enveloppe * 32767).toInt().toShort()
            }
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

    fun jouer(quoi: String, force: Float = 1f) {
        when (quoi) {
            "touche"  -> { ton(220.0, .14, .24, 90.0); ton(110.0, .18, .18) }
            "attrape" -> ton(780.0, .07, .15, 980.0)
            "rebond"  -> ton(180.0, .06, (.05 + force / 900.0).coerceAtMost(.22), 120.0)
            "marche"  -> ton(150.0, .05, .07, 110.0)
            "course"  -> ton(190.0, .05, .10, 130.0)
            "zap"     -> ton(1400.0, .04, .09, 2100.0)
            "pret"    -> { ton(880.0, .10, .16, 1320.0); ton(1320.0, .14, .12, 1760.0) }
            "esquive" -> ton(1200.0, .06, .10, 700.0)
            "bip"     -> ton(660.0, .08, .18, 980.0)
            "go"      -> { ton(660.0, .08, .18, 980.0); ton(1320.0, .18, .14, 1560.0) }
        }
    }
}
