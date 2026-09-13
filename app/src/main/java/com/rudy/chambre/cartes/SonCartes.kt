package com.rudy.chambre.cartes

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Son « beep » : une note courte, comme dans ses deux pages de cartes. */
class SonCartes {

    private val frequence = 22050

    fun bip(hauteur: Double, duree: Double, volume: Double = .35) {
        try {
            val n = (frequence * duree).toInt()
            val d = ShortArray(n)
            for (i in 0 until n) {
                val t = i.toDouble() / frequence
                d[i] = (sin(2 * PI * hauteur * t) * volume * exp(-t * 18.0) * 32767).toInt()
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

    fun liberer() {}
}
