package com.skinps1.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Sortie audio stereo, a la frequence que le coeur annonce (32 040 Hz sur PlayStation). */
class Son(private val frequence: Int = 44100) {
    private val taille = AudioTrack.getMinBufferSize(
        frequence, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(16384)

    private val piste = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setAudioFormat(AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(frequence)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
        .setBufferSizeInBytes(taille * 2)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    private var demarre = false

    fun jouer(echantillons: ShortArray) {
        if (echantillons.isEmpty()) return
        if (!demarre) { piste.play(); demarre = true }
        piste.write(echantillons, 0, echantillons.size, AudioTrack.WRITE_NON_BLOCKING)
    }

    fun pause() { if (demarre) piste.pause() }
    fun reprendre() { if (demarre) piste.play() }
    fun liberer() { piste.release() }
}
