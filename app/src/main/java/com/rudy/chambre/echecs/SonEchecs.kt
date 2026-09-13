package com.rudy.chambre.echecs

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Ses deux bruitages : le deplacement, et la capture en deux notes graves. */
class SonEchecs {

    private val frequence = 22050

    private fun ton(depart: Double, arrivee: Double, duree: Double,
                    volume: Double, carre: Boolean, retard: Double = 0.0) {
        try {
            val n = (frequence * (duree + retard)).toInt()
            val d = ShortArray(n)
            val debut = (frequence * retard).toInt()
            for (i in debut until n) {
                val t = (i - debut).toDouble() / frequence
                val f = depart * Math.pow(arrivee / depart, t / duree)
                val phase = 2 * PI * f * t
                val onde = if (carre) (if (sin(phase) >= 0) 1.0 else -1.0)
                           else 2.0 / PI * kotlin.math.asin(sin(phase))
                d[i] = (onde * volume * exp(-t * 18.0) * 32767).toInt()
                    .coerceIn(-32767, 32767).toShort()
            }
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

    fun jouer(quoi: String) {
        when (quoi) {
            "deplacement" -> ton(260.0, 185.0, .09, .55, false)
            "capture" -> {
                ton(150.0, 93.0, .14, .50, true)
                ton(105.0, 65.0, .14, .45, true, .045)
            }
        }
    }

    fun liberer() {}
}
