package com.rudy.chambre.balle

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Les bruitages du jeu.
 *
 * Dans la version d'origine ils etaient fabriques a la volee, note par note,
 * plutot que lus depuis des fichiers. On fait pareil : chaque bruit est une
 * courte suite de tons, calculee au moment ou on en a besoin.
 */
class SonBalle(ctx: android.content.Context) {

    private val frequenceEchantillon = 22050

    private fun ton(hauteur: Double, duree: Double, volume: Double, glissando: Double = 0.0) {
        try {
            val n = (frequenceEchantillon * duree).toInt()
            val donnees = ShortArray(n)
            for (i in 0 until n) {
                val t = i.toDouble() / frequenceEchantillon
                val f = hauteur + (glissando - hauteur) * (t / duree)
                val enveloppe = (1.0 - t / duree).coerceIn(0.0, 1.0)
                donnees[i] = (sin(2 * PI * f * t) * volume * enveloppe * 32767).toInt().toShort()
            }
            val piste = AudioTrack(AudioManager.STREAM_MUSIC, frequenceEchantillon,
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

    fun jouer(quoi: String) {
        when (quoi) {
            "tir"        -> ton(420.0, .09, .18, 260.0)
            "tir_charge" -> { ton(300.0, .12, .22, 720.0); ton(900.0, .10, .14, 520.0) }
            "passe"      -> ton(560.0, .07, .13, 700.0)
            "attrape"    -> ton(780.0, .07, .15, 980.0)
            "esquive"    -> ton(1200.0, .06, .10, 700.0)
            "rebond"     -> ton(180.0, .06, .16, 120.0)
            "touche"     -> { ton(220.0, .14, .24, 90.0); ton(110.0, .18, .18) }
            "depart"     -> { ton(660.0, .08, .18, 980.0); ton(1320.0, .18, .14, 1560.0) }
        }
    }

    fun liberer() {}
}
