package com.rudy.chambre.nesui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Sortie audio : un flux mono 44,1 kHz alimente image par image. */
class Son {
    private val taille = AudioTrack.getMinBufferSize(
        44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(8192)

    private val piste = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(taille * 2)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    private var demarre = false

    fun jouer(echantillons: ShortArray) {
        if (echantillons.isEmpty()) return
        if (!demarre) { piste.play(); demarre = true }
        // WRITE_NON_BLOCKING : mieux vaut sauter du son que retarder l'image
        piste.write(echantillons, 0, echantillons.size, AudioTrack.WRITE_NON_BLOCKING)
    }

    fun pause() { if (demarre) piste.pause() }
    fun reprendre() { if (demarre) piste.play() }
    fun liberer() { piste.release() }
}
