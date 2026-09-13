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

    /**
     * Un pas sur du gazon synthetique : un bruissement de brins, un choc mat
     * du talon, puis un frottement du pied qui glisse. Trois couches, comme
     * un vrai appui.
     */
    private fun pas(force: Double = 1.0) {
        val duree = .155
        val n = (frequence * duree).toInt()
        val d = ShortArray(n)
        var x1 = 0.0; var x2 = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / frequence

            // 1. le froissement des brins d'herbe : un souffle large qui
            //    s'eteint vite
            val bruit = Math.random() * 2 - 1
            // un filtre passe-bande maison, autour de 2 kHz
            val coupe = 0.34
            x1 += coupe * (bruit - x1)
            x2 += coupe * (x1 - x2)
            val herbe = (x1 - x2) * exp(-t * 30.0) * .55

            // 2. le choc sourd du talon : une frequence qui plonge
            val hauteur = 132.0 * exp(-t * 26.0) + 58.0
            val talon = sin(2 * Math.PI * hauteur * t) * exp(-t * 38.0) * .42

            // 3. le pied qui glisse un peu apres l'appui
            val glisse = if (t > .045) {
                val u = t - .045
                (Math.random() * 2 - 1) * exp(-u * 46.0) * .18
            } else 0.0

            val v = (herbe + talon + glisse) * force
            d[i] = (v.coerceIn(-1.0, 1.0) * 26000).toInt().toShort()
        }
        jouer(d)
    }

    /**
     * La frappe dans le ballon : le claquement sec du cuir, le corps creux
     * qui resonne un instant, et le souffle du pied qui fend l'air.
     */
    private fun frappe() {
        val duree = .30
        val n = (frequence * duree).toInt()
        val d = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / frequence

            // 1. le claquement du cuir : tres bref, tres large
            val claque = (Math.random() * 2 - 1) * exp(-t * 220.0) * .95

            // 2. le corps du ballon : une note grave qui plonge et resonne
            val hauteur = 165.0 * exp(-t * 20.0) + 62.0
            val corps = (sin(2 * Math.PI * hauteur * t) * .62 +
                         sin(4 * Math.PI * hauteur * t) * .20) * exp(-t * 15.0)

            // 3. l'air deplace par le pied, juste avant l'impact
            val souffle = if (t < .02) (Math.random() * 2 - 1) * (1 - t / .02) * .30 else 0.0

            val v = claque + corps + souffle
            d[i] = (v.coerceIn(-1.0, 1.0) * 30000).toInt().toShort()
        }
        jouer(d)
    }

    fun jouer(quoi: String) {
        when (quoi) {
            "pas" -> pas()
            "pasCourse" -> pas(1.25)          // l'appui de la course, plus franc
            "frappe" -> frappe()
            "filet" -> choc(.22, 260.0, .30, 12.0)
            "gant" -> choc(.13, 190.0, .38, 26.0)
            "applaudissements" -> if (!applaudissementsReels()) foule(1.8, .45, true)
            "hue" -> foule(1.3, .30, false)     // le souffle de deception
            "deception" -> foule(1.4, .28, false)
        }
    }
}
