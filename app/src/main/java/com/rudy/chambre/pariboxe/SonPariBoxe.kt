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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

private const val SR = 44100

/**
 * Les sons de PariBoxe : la musique du combat (celle du HTML, volume 0.22,
 * en boucle) et les bruitages que le HTML fabriquait en direct avec WebAudio
 * (impact, souffle du coup, K.O.), recalcules ici avec les memes frequences,
 * filtres et enveloppes.
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
            idSouffles = IntArray(3) { pool.load(wav(File(sons, "souffle$it.wav"), bruitSouffle()).path, 1) }
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

    /** Une barre de vie descend : impact(perte>=11 ? 1.25 : 1), ou K.O. a zero. */
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

    /** exponentialRampToValueAtTime */
    private fun rampe(v0: Double, v1: Double, t0: Double, t1: Double, t: Double) =
        v0 * (v1 / v0).pow(((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0))

    /** enveloppe(gain,t0,pic,dur) du HTML */
    private fun env(t: Double, pic: Double, dur: Double): Double = when {
        t < 0 -> 0.0
        t <= 0.008 -> rampe(0.0001, pic, 0.0, 0.008, t)
        t <= dur -> rampe(pic, 0.0001, 0.008, dur, t)
        else -> 0.0001
    }

    /** BiquadFilter « bandpass » de WebAudio. */
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
        // boum grave : sinus 190*f -> 48 Hz en 0.16 s, enveloppe .55 / .20, arret a .24
        var ph = 0.0
        val nOsc = min((0.24 * SR).toInt(), buf.size)
        for (i in 0 until nOsc) {
            val t = i.toDouble() / SR
            val fr = if (t < 0.16) rampe(190 * f, 48.0, 0.0, 0.16, t) else 48.0
            ph += 2 * PI * fr / SR
            buf[i] += sin(ph) * env(t, 0.55, 0.20)
        }
        // claque : bruit passe-bande 1500*f Hz, Q .8, enveloppe .34 / .11, arret a .14
        val bp = PasseBande()
        val nB = min((0.14 * SR).toInt(), buf.size)
        for (i in 0 until nB) {
            val t = i.toDouble() / SR
            buf[i] += bp.pas(Random.nextDouble() * 2 - 1, 1500 * f, 0.8) * env(t, 0.34, 0.11)
        }
    }

    private fun impact(f: Double): DoubleArray {
        val buf = DoubleArray((0.25 * SR).toInt())
        ajouterImpact(buf, f)
        return buf
    }

    private fun ko(): DoubleArray {
        val buf = DoubleArray((0.96 * SR).toInt())
        ajouterImpact(buf, 1.35)
        // resonance : triangle 120 -> 40 Hz, depart +0.10 s, .0001 -> .4 (0.03) -> .0001 (0.8), arret 0.85
        var ph = 0.0
        val d = (0.10 * SR).toInt()
        for (i in 0 until (0.85 * SR).toInt()) {
            val j = d + i
            if (j >= buf.size) break
            val t = i.toDouble() / SR
            val fr = if (t < 0.7) rampe(120.0, 40.0, 0.0, 0.7, t) else 40.0
            ph += fr / SR
            val x = ph - kotlin.math.floor(ph)
            val tri = if (x < 0.25) 4 * x else if (x < 0.75) 2 - 4 * x else 4 * x - 4
            val g = when {
                t <= 0.03 -> rampe(0.0001, 0.4, 0.0, 0.03, t)
                t <= 0.8 -> rampe(0.4, 0.0001, 0.03, 0.8, t)
                else -> 0.0001
            }
            buf[j] += tri * g
        }
        return buf
    }

    /** Sifflement : bruit passe-bande Q 1.4, 420 -> 2100 Hz (0.16 s), gain .0001 -> .12 (0.05) -> .0001 (0.2). */
    private fun bruitSouffle(): DoubleArray {
        val n = (0.22 * SR).toInt()
        val buf = DoubleArray(n)
        val bp = PasseBande()
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val fr = if (t < 0.16) rampe(420.0, 2100.0, 0.0, 0.16, t) else 2100.0
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
        val bb = ByteBuffer.allocate(44 + s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + s.size * 2); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(SR); bb.putInt(SR * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(s.size * 2)
        for (v in s) bb.putShort((max(-1.0, min(1.0, v)) * 32767).toInt().toShort())
        FileOutputStream(f).use { it.write(bb.array()) }
        return f
    }
}
