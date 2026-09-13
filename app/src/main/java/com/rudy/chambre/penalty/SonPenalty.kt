package com.rudy.chambre.penalty

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ses bruitages de tir au but : les pas sur l'herbe, la frappe, le filet, le
 * gant du gardien, et la foule.
 */
class SonPenalty(private val ctx: android.content.Context? = null) {

    private val frequence = 22050
    private var lecteur: android.media.MediaPlayer? = null

    /** Son vrai enregistrement d'applaudissements, celui de sa page. */
    private fun applaudissementsReels(): Boolean {
        val c = ctx ?: return false
        return try {
            lecteur?.release()
            val f = c.assets.openFd("penalty/applaudissements.mp3")
            lecteur = android.media.MediaPlayer().apply {
                setDataSource(f.fileDescriptor, f.startOffset, f.length)
                prepare(); start()
            }
            f.close()
            true
        } catch (_: Throwable) { false }
    }

    private fun jouer(donnees: ShortArray) {
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

    /** Un bruit sourd et court : le pas, la frappe, le gant. */
    private fun choc(duree: Double, hauteur: Double, volume: Double, declin: Double) {
        val n = (frequence * duree).toInt()
        val d = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / frequence
            val env = exp(-t * declin)
            val bruit = (Random.nextDouble() * 2 - 1) * .5
            val corps = sin(2 * PI * hauteur * t)
            d[i] = ((corps * .6 + bruit * .4) * env * volume * 32767).toInt()
                .coerceIn(-32767, 32767).toShort()
        }
        jouer(d)
    }

    /** La foule : un souffle large qui monte puis retombe. */
    private fun foule(duree: Double, volume: Double, montant: Boolean) {
        val n = (frequence * duree).toInt()
        val d = ShortArray(n)
        var lisse = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / frequence
            val q = t / duree
            val env = if (montant) sin(PI * q).coerceAtLeast(0.0)
                      else (1 - q) * (1 - q)
            lisse = lisse * .93 + (Random.nextDouble() * 2 - 1) * .07
            d[i] = (lisse * env * volume * 32767).toInt().coerceIn(-32767, 32767).toShort()
        }
        jouer(d)
    }

    fun liberer() { try { lecteur?.release() } catch (_: Throwable) {}; lecteur = null }

    fun jouer(quoi: String) {
        when (quoi) {
            "pas" -> choc(.09, 150.0, .22, 34.0)
            "frappe" -> choc(.16, 90.0, .55, 22.0)
            "filet" -> choc(.22, 260.0, .30, 12.0)
            "gant" -> choc(.13, 190.0, .38, 26.0)
            "applaudissements" -> if (!applaudissementsReels()) foule(1.8, .45, true)
            "deception" -> foule(1.4, .28, false)
        }
    }
}
