package com.rudy.chambre.livre

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Le froissement d'une page qu'on tourne. */
class SonPage {

    private val frequence = 22050

    fun tourner() {
        try {
            val duree = .32
            val n = (frequence * duree).toInt()
            val d = ShortArray(n)
            var lisse = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / frequence
                // un souffle qui monte puis retombe, comme le papier qui glisse
                val enveloppe = sin(PI * t / duree)
                lisse = lisse * .80 + (Random.nextDouble() * 2 - 1) * .20
                val modulation = 1.0 + sin(2 * PI * 9 * t) * .35
                d[i] = (lisse * enveloppe * modulation * .32 * 32767).toInt()
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
