package com.skinnes.app.nes

/**
 * Tampon d'entiers pour les sauvegardes d'etat.
 *
 * Chaque partie du moteur y depose ses registres dans un ordre fixe et les
 * relit dans le meme ordre. Pas d'etiquettes, pas de format autodescriptif :
 * c'est le numero de version en tete qui protege contre une relecture faussee
 * si l'ordre change un jour.
 */
class Etat {

    private var buf: IntArray
    private var n: Int
    private var pos = 0

    constructor() { buf = IntArray(8192); n = 0 }

    constructor(octets: ByteArray) {
        n = octets.size / 4
        buf = IntArray(maxOf(1, n))
        var k = 0
        for (i in 0 until n) {
            buf[i] = ((octets[k].toInt() and 0xFF) shl 24) or
                     ((octets[k + 1].toInt() and 0xFF) shl 16) or
                     ((octets[k + 2].toInt() and 0xFF) shl 8) or
                      (octets[k + 3].toInt() and 0xFF)
            k += 4
        }
    }

    fun octets(): ByteArray {
        val o = ByteArray(n * 4)
        var k = 0
        for (i in 0 until n) {
            val v = buf[i]
            o[k] = (v ushr 24).toByte()
            o[k + 1] = (v ushr 16).toByte()
            o[k + 2] = (v ushr 8).toByte()
            o[k + 3] = v.toByte()
            k += 4
        }
        return o
    }

    // ---------- ecriture ----------
    fun put(v: Int) {
        if (n == buf.size) buf = buf.copyOf(n * 2)
        buf[n++] = v
    }
    fun put(v: Boolean) { put(if (v) 1 else 0) }
    fun put(v: Long) { put((v ushr 32).toInt()); put(v.toInt()) }
    fun put(a: IntArray) { for (x in a) put(x) }
    fun put(a: BooleanArray) { for (x in a) put(x) }

    // ---------- lecture ----------
    /** Renvoie zero au-dela de la fin plutot que de lever : une sauvegarde
     *  tronquee donne un etat bancal, pas un plantage. */
    fun i(): Int = if (pos < n) buf[pos++] else 0
    fun b(): Boolean = i() != 0
    fun l(): Long = (i().toLong() shl 32) or (i().toLong() and 0xFFFFFFFFL)
    fun t(a: IntArray) { for (k in a.indices) a[k] = i() }
    fun t(a: BooleanArray) { for (k in a.indices) a[k] = b() }
}
