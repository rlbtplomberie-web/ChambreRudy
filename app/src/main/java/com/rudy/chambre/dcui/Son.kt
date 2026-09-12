package com.rudy.chambre.dcui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Sortie audio.
 *
 * L'ecriture ne bloque jamais. J'avais essaye l'inverse — laisser l'audio
 * cadencer l'emulation — et la mesure a montre que l'attente durait cinquante
 * millisecondes a chaque cycle : c'etait devenu le frein. Le tampon est donc
 * assez large pour absorber les irregularites sans jamais faire patienter, et
 * c'est l'horloge qui cadence.
 *
 * Si le tampon est plein, le surplus est abandonne : mieux vaut perdre
 * quelques millisecondes de son qu'introduire un retard qui s'accumule.
 */
class Son(frequence: Int = 44100) {

    /** Six images de son : de quoi encaisser un a-coup sans jamais bloquer. */
    private val parImage = frequence / 60 * 2 * 2
    private val taille = maxOf(
        AudioTrack.getMinBufferSize(frequence, AudioFormat.CHANNEL_OUT_STEREO,
                                    AudioFormat.ENCODING_PCM_16BIT),
        parImage * 6)

    private val piste = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setAudioFormat(AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(frequence)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
        .setBufferSizeInBytes(taille)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    private var demarre = false

    fun jouer(echantillons: ShortArray, longueur: Int) {
        if (longueur <= 0) return
        if (!demarre) { piste.play(); demarre = true }
        piste.write(echantillons, 0, longueur, AudioTrack.WRITE_NON_BLOCKING)
    }

    fun pause() { if (demarre) piste.pause() }
    fun reprendre() { if (demarre) piste.play() }
    fun liberer() {
        try { piste.pause(); piste.flush() } catch (_: Exception) {}
        piste.release()
    }
}
