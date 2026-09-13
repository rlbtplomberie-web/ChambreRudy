package com.rudy.chambre.dames

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Ses deux bruitages, calcules comme dans sa page : un deplacement discret et
 * une prise plus grave, en trois notes qui descendent.
 */
class SonDames {

    private val frequence = 22050

    private fun ton(hauteur: Double, duree: Double, volume: Double, forme: String = "sinus") {
        try {
            val n = (frequence * duree).toInt()
            val d = ShortArray(n)
            for (i in 0 until n) {
                val t = i.toDouble() / frequence
                val phase = 2 * PI * hauteur * t
                val onde = when (forme) {
                    "carre" -> if (sin(phase) >= 0) 1.0 else -1.0
                    "triangle" -> 2.0 / PI * kotlin.math.asin(sin(phase))
                    "dent" -> 2.0 * ((hauteur * t) % 1.0) - 1.0
                    else -> sin(phase)
                }
                d[i] = (onde * volume * exp(-t * 12.0) * 32767).toInt()
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
            "deplacement" -> { ton(230.0, .055, .45, "triangle"); ton(165.0, .065, .35, "triangle") }
            "prise" -> {
                ton(125.0, .09, .75, "carre")
                ton(82.0, .15, .55, "dent")
                ton(48.0, .20, .50, "triangle")
            }
        }
    }

    fun liberer() {}
}
