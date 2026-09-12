package com.rudy.chambre.gbui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Sortie audio.
 *
 * Le coeur produit des echantillons a son rythme ; on les ecrit sans jamais
 * bloquer le fil qui les fournit. Un son en retard vaut mieux qu'une image
 * qui saccade.
 */
class Son(frequence: Int) {

    private val piste: AudioTrack

    init {
        val minimum = AudioTrack.getMinBufferSize(
            frequence, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val taille = maxOf(minimum * 4, frequence / 4 * 4)
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
        piste.play()
    }

    fun ecrire(donnees: ShortArray, n: Int) {
        if (n <= 0) return
        // WRITE_NON_BLOCKING : si la file est pleine, on jette plutot que
        // d'attendre. L'emulation ne doit jamais patienter apres le son.
        piste.write(donnees, 0, n, AudioTrack.WRITE_NON_BLOCKING)
    }

    fun pause() { try { piste.pause() } catch (_: Throwable) {} }
    fun reprendre() { try { piste.play() } catch (_: Throwable) {} }

    fun liberer() {
        try { piste.stop() } catch (_: Throwable) {}
        try { piste.release() } catch (_: Throwable) {}
    }
}
