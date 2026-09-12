package com.rudy.chambre

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool

/**
 * Le son de la chambre.
 *
 * Les bruitages passent par un lecteur court, toujours pret : le tiroir, les
 * portes, le carton, la console qu'on pose. La musique, elle, passe par un
 * lecteur ordinaire, qui enchaine les sept morceaux.
 */
class SonChambre(private val ctx: Context) {

    private val bruits = SoundPool.Builder().setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()

    private val identifiants = HashMap<String, Int>()
    private var musique: MediaPlayer? = null
    private var morceau = -1
    var volume = 0.55f

    init {
        for (n in listOf("tiroir.mp3", "carton.mp3", "porte_ouvre.mp3",
                         "porte_ferme.mp3", "pose.mp3", "rire.mp3")) {
            try {
                ctx.assets.openFd("chambre/$n").use { identifiants[n] = bruits.load(it, 1) }
            } catch (_: Throwable) {}
        }
    }

    fun bruit(nom: String) {
        identifiants[nom]?.let { bruits.play(it, 1f, 1f, 1, 0, 1f) }
    }

    /** Allume la radio, passe au morceau suivant, ou l'eteint. */
    fun radio(): String {
        if (musique != null) { jouer(morceau + 1); return Decor.MUSIQUES.getOrNull(morceau)?.second ?: "" }
        jouer(if (morceau < 0) 0 else morceau)
        return Decor.MUSIQUES.getOrNull(morceau)?.second ?: ""
    }

    private fun jouer(i: Int) {
        arreter()
        if (i >= Decor.MUSIQUES.size) { morceau = -1; return }
        morceau = i
        try {
            val d = ctx.assets.openFd("chambre/" + Decor.MUSIQUES[i].first)
            musique = MediaPlayer().apply {
                setDataSource(d.fileDescriptor, d.startOffset, d.length)
                setVolume(volume, volume)
                setOnCompletionListener { jouer(morceau + 1) }
                prepare(); start()
            }
            d.close()
        } catch (_: Throwable) { musique = null }
    }

    fun arreter() {
        try { musique?.stop(); musique?.release() } catch (_: Throwable) {}
        musique = null
    }

    fun majVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        try { musique?.setVolume(volume, volume) } catch (_: Throwable) {}
    }

    fun enPause(oui: Boolean) {
        try { if (oui) musique?.pause() else musique?.start() } catch (_: Throwable) {}
    }

    fun liberer() { arreter(); bruits.release() }
}
