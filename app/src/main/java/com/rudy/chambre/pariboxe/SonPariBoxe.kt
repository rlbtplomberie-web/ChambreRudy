package com.rudy.chambre.pariboxe

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

private const val SR = 44100

/**
 * Les sons de PariBoxe : la musique du combat (celle du HTML, volume 0.22,
 * en boucle) et les bruitages que le HTML fabriquait en direct avec
 * WebAudio — impact, souffle du coup, K.O. — recalcules ici avec les memes
 * frequences, filtres et enveloppes.
 */
class SonPariBoxe {
    private val pool = SoundPool.Builder().setMaxStreams(10).setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build()
    @Volatile private var idImpact = 0
    @Volatile private var idImpactFort = 0
    @Volatile private var idKo = 0
    @Volatile private var idSouffles = IntArray(0)
    private var tour = 0

    private var musique: MediaPlayer? = null
    private var voulue = false
    private var enPause = false
    private var ferme = false

    /** Appele depuis le fil de chargement. */
    fun preparer(dossier: File) {
        try {
            val sons = File(dossier, "sons")
            sons.mkdirs()
            idImpact = pool.load(wav(File(sons, "impact.wav"), impact(1.0)).path, 1)
            idImpactFort = pool.load(wav(File(sons, "impact_fort.wav"), impact(1.25)).path, 1)
            idKo = pool.load(wav(File(sons, "ko.wav"), ko()).path, 1)
            idSouffles = IntArray(3) { pool.load(wav(File(sons, "souffle$it.wav"), fabriquerSouffle()).path, 1) }
        } catch (_: Throwable) { }
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setDataSource(File(dossier, "musique.mp3").path)
            mp.isLooping = true
            mp.setVolume(0.22f, 0.22f)
            mp.prepare()
            synchronized(this) {
                if (ferme) mp.release()
                else {
                    musique = mp
                    if (voulue && !enPause) mp.start()
                }
            }
        } catch (_: Throwable) { }
    }

    fun demarrerMusique() {
        synchronized(this) {
            voulue = true
            val m = musique ?: return
            try { m.seekTo(0); if (!enPause) m.start() } catch (_: Throwable) { }
        }
    }

    fun arreterMusique() {
        synchronized(this) {
            voulue = false
            val m = musique ?: return
            try { if (m.isPlaying) m.pause(); m.seekTo(0) } catch (_: Throwable) { }
        }
    }

    fun pause() {
        synchronized(this) {
            enPause = true
            val m = musique ?: return
            try { if (m.isPlaying) m.pause() } catch (_: Throwable) { }
        }
    }

    fun reprise() {
        synchronized(this) {
            enPause = false
            val m = musique ?: return
            if (voulue) try { m.start() } catch (_: Throwable) { }
        }
    }

    fun liberer() {
        synchronized(this) {
            ferme = true
            try { musique?.release() } catch (_: Throwable) { }
            musique = null
        }
        try { pool.release() } catch (_: Throwable) { }
    }

    /** Un coup qui touche : « impact », ou le K.O. si la barre tombe a zero. */
    fun coup(reste: Float, perte: Float) {
        if (reste <= 0f) jouer(idKo)
        else jouer(if (perte >= 11f) idImpactFort else idImpact)
    }

    fun souffle() {
        val s = idSouffles
        if (s.isEmpty()) return
        tour = (tour + 1) % s.size
        jouer(s[tour])
    }

    private fun jouer(id: Int) {
        if (id != 0) try { pool.play(id, 1f, 1f, 1, 0, 1f) } catch (_: Throwable) { }
    }

    // --- fabrication des sons, comme le graphe WebAudio du HTML ------------

    private fun rampe(v0: Double, v1: Double, t0: Double, t1: Double, t: Double) =
        v0 * (v1 / v0).pow((t - t0) / (t1 - t0))

    /** enveloppe(gain,t0,pic,dur) du HTML */
    private fun env(t: Double, pic: Double, dur: Double): Double = when {
        t <= 0.008 -> rampe(0.0001, pic, 0.0, 0.008, t)
        t <= dur -> rampe(pic, 0.0001, 0.008, dur, t)
        else -> 0.0001
    }

    /** Filtre passe-bande de WebAudio (gain de crete 0 dB). */
    private class PasseBande {
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        fun pas(x: Double, f0: Double, q: Double): Double {
            val w0 = 2 * PI * f0 / SR
            val a = sin(w0) / (2 * q)
            val a0 = 1 + a
            val y = (a / a0) * x + (-a / a0) * x2 - (-2 * cos(w0) / a0) * y1 - ((1 - a) / a0) * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    private fun ajouterImpact(buf: DoubleArray, f: Double) {
        var ph = 0.0
        val nOsc = min((0.24 * SR).toInt(), buf.size)
        for (i in 0 until nOsc) {
            val t = i.toDouble() / SR
            val fr = if (t < 0.16) 190 * f * (48 / (190 * f)).pow(t / 0.16) else 48.0
            ph += 2 * PI * fr / SR
            buf[i] += sin(ph) * env(t, 0.55, 0.20)
        }
        val bp = PasseBande()
        val nBruit = min((0.14 * SR).toInt(), buf.size)
        for (i in 0 until nBruit) {
            val t = i.toDouble() / SR
            buf[i] += bp.pas(Random.nextDouble() * 2 - 1, 1500 * f, 0.8) * env(t, 0.34, 0.11)
        }
    }

    private fun impact(f: Double): DoubleArray {
        val buf = DoubleArray((0.24 * SR).toInt())
        ajouterImpact(buf, f)
        return buf
    }

    private fun ko(): DoubleArray {
        val n = ((0.10 + 0.85) * SR).toInt()
        val buf = DoubleArray(n)
        ajouterImpact(buf, 1.35)
        var ph = 0.0
        for (i in (0.10 * SR).toInt() until n) {
            val t = i.toDouble() / SR - 0.10
            val fr = if (t < 0.7) 120 * (40.0 / 120).pow(t / 0.7) else 40.0
            ph += fr / SR
            val p = ph - floor(ph)
            val tri = when { p < 0.25 -> 4 * p; p < 0.75 -> 2 - 4 * p; else -> 4 * p - 4 }
            val g = when {
                t <= 0.03 -> rampe(0.0001, 0.4, 0.0, 0.03, t)
                t <= 0.8 -> rampe(0.4, 0.0001, 0.03, 0.8, t)
                else -> 0.0001
            }
            buf[i] += tri * g
        }
        return buf
    }

    private fun fabriquerSouffle(): DoubleArray {
        val n = (0.22 * SR).toInt()
        val buf = DoubleArray(n)
        val bp = PasseBande()
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val fr = if (t < 0.16) 420 * (2100.0 / 420).pow(t / 0.16) else 2100.0
            val g = when {
                t <= 0.05 -> rampe(0.0001, 0.12, 0.0, 0.05, t)
                t <= 0.2 -> rampe(0.12, 0.0001, 0.05, 0.2, t)
                else -> 0.0001
            }
            buf[i] = bp.pas(Random.nextDouble() * 2 - 1, fr, 1.4) * g
        }
        return buf
    }

    private fun wav(f: File, s: DoubleArray): File {
        val n = s.size
        val bb = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + n * 2); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16)
        bb.putShort(1.toShort()); bb.putShort(1.toShort())
        bb.putInt(SR); bb.putInt(SR * 2)
        bb.putShort(2.toShort()); bb.putShort(16.toShort())
        bb.put("data".toByteArray()); bb.putInt(n * 2)
        for (v in s) bb.putShort((max(-1.0, min(1.0, v)) * 32767).toInt().toShort())
        FileOutputStream(f).use { it.write(bb.array()) }
        return f
    }
}
