package com.rudy.chambre.atelier

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Le bruit du crayon sur le papier, fabrique comme dans sa page : un souffle
 * continu, filtre autour de la frequence propre a chaque crayon, dont le
 * volume suit la vitesse du trait.
 */
class SonCrayon {

    var actif = true

    private val frequence = 22050
    private var piste: AudioTrack? = null
    private var fil: Thread? = null
    private var enMarche = false

    @Volatile private var niveau = 0f
    @Volatile private var centre = 1800f
    @Volatile private var largeur = .9f

    private fun demarrer() {
        if (enMarche) return
        enMarche = true
        val taille = AudioTrack.getMinBufferSize(frequence,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
        piste = AudioTrack(AudioManager.STREAM_MUSIC, frequence,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            taille, AudioTrack.MODE_STREAM)
        piste?.play()
        fil = Thread {
            val bloc = ShortArray(512)
            var precedent = 0.0
            var phase = 0.0
            var volumeLisse = 0.0
            while (enMarche) {
                val cible = niveau.toDouble()
                for (i in bloc.indices) {
                    volumeLisse += (cible - volumeLisse) * .002
                    // un souffle, adouci comme le sien
                    val brut = Random.nextDouble() * 2 - 1
                    precedent = (precedent + brut * .7) / 1.6
                    // et module autour de la frequence du crayon
                    phase += 2 * PI * centre / frequence
                    val filtre = precedent * (1.0 + sin(phase) * largeur) * .5
                    bloc[i] = (filtre * volumeLisse * 32767).toInt()
                        .coerceIn(-32767, 32767).toShort()
                }
                try { piste?.write(bloc, 0, bloc.size) } catch (_: Throwable) { break }
            }
        }
        fil?.priority = Thread.MIN_PRIORITY
        fil?.start()
    }

    /**
     * Son « frotte » : le volume monte avec la vitesse, et la frequence aussi
     * un peu, comme une mine qui accroche plus fort quand on va vite.
     */
    fun frotter(vitesse: Float, outil: Outil, gomme: Boolean) {
        if (!actif) { niveau = 0f; return }
        demarrer()
        val f = if (gomme) SON_GOMME.first else outil.sonF
        val q = if (gomme) SON_GOMME.second else outil.sonQ
        val v = if (gomme) SON_GOMME.third else outil.sonV
        niveau = min(1f, .12f + vitesse / 28f) * v * .5f
        centre = f * (1f + min(vitesse, 40f) / 90f)
        largeur = q
    }

    fun silence() { niveau = 0f }

    fun liberer() {
        enMarche = false
        try { fil?.join(200) } catch (_: Throwable) {}
        try { piste?.stop(); piste?.release() } catch (_: Throwable) {}
        piste = null
    }
}
