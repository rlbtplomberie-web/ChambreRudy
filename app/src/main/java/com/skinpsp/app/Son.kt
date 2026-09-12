package com.skinpsp.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Sortie audio.
 *
 * Le coeur produit des echantillons par paquets ; on les remet a la piste sans
 * jamais attendre — un blocage ici ralentirait l'emulation.
 */
class Son(frequence: Int) {

    private val piste: AudioTrack
    private var demarre = false

    init {
        val min = AudioTrack.getMinBufferSize(
            frequence, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val taille = maxOf(min * 2, frequence / 10 * 4)
        piste = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(frequence)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setBufferSizeInBytes(taille)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun jouer(echantillons: ShortArray, longueur: Int) {
        if (longueur <= 0) return
        if (!demarre) { piste.play(); demarre = true }
        piste.write(echantillons, 0, longueur, AudioTrack.WRITE_NON_BLOCKING)
    }

    /** Vide ce qui reste a jouer, sans arreter la piste. */
    fun vider() {
        if (!demarre) return
        try { piste.pause(); piste.flush(); piste.play() } catch (_: Exception) {}
    }

    fun liberer() {
        try { if (demarre) piste.stop() } catch (_: Exception) {}
        try { piste.release() } catch (_: Exception) {}
        demarre = false
    }
}
