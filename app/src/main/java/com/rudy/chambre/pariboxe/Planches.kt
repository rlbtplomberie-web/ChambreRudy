package com.rudy.chambre.pariboxe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Une planche recadree sur le personnage (economie de memoire). On garde la
 * taille d'origine de l'image (largeur x hauteur) et la place du recadrage
 * (dx, dy) : l'affichage reste celui du HTML, ou chaque image est posee
 * « contain » en bas au centre de sa boite.
 */
class Planche(val bmp: Bitmap, val largeur: Int, val hauteur: Int, val dx: Int, val dy: Int)

/**
 * Les images et la musique du combat sont celles du fichier HTML de Rudy
 * (assets/pariboxe/index.html.000 + .001) : rien n'est redessine ni
 * recompresse. Au premier lancement chaque image est decodee une fois dans
 * le cache ; les lancements suivants la relisent directement.
 */
object Planches {
    private const val VERSION = "pariboxe-natif-v2"
    private val GUIL = '"'.code.toByte()
    private val VIRG = ','.code.toByte()
    private val CROCH_O = '['.code.toByte()
    private val CROCH_F = ']'.code.toByte()
    private val ACC_O = '{'.code.toByte()
    private val ACC_F = '}'.code.toByte()

    fun dossier(ctx: Context) = File(ctx.cacheDir, VERSION)

    @Synchronized
    fun extraire(ctx: Context) {
        val dir = dossier(ctx)
        if (File(dir, "ok").isFile) return
        // l'ancienne copie de la WebView ne sert plus
        File(ctx.cacheDir, "pariboxe-html-original-v4.html").delete()
        File(ctx.cacheDir, "pariboxe-html-original-v4.tmp").delete()
        dir.deleteRecursively()
        dir.mkdirs()

        val parties = listOf("pariboxe/index.html.000", "pariboxe/index.html.001")
        var total = 0
        for (nom in parties) ctx.assets.open(nom).use { total += compter(it) }
        val s = ByteArray(total)
        var pos = 0
        for (nom in parties) ctx.assets.open(nom).use { e ->
            while (true) {
                val n = e.read(s, pos, total - pos)
                if (n <= 0) break
                pos += n
            }
        }

        val index = StringBuilder()
        val jeux = listOf("rudy" to "const F=", "theo" to "const E=", "valor" to "const V=", "mody" to "const MO=")
        for ((nom, marque) in jeux) {
            val p = cherche(s, marque, 0)
            require(p >= 0) { "planches $nom introuvables" }
            val obj = lireObjet(s, cherche(s, "{", p))
            for ((mode, liste) in obj) {
                liste.forEachIndexed { i, r -> ecrire(File(dir, "$nom/${mode}_$i.img"), s, r[0], r[1]) }
                index.append(nom).append(' ').append(mode).append(' ').append(liste.size).append('\n')
            }
        }

        fun dataApres(marque: String, fichier: String) {
            val p = cherche(s, marque, 0)
            require(p >= 0) { "$marque introuvable" }
            val deb = cherche(s, "base64,", p) + 7
            ecrire(File(dir, fichier), s, deb, jusqua(s, GUIL, deb))
        }
        dataApres("#game{position:fixed", "fond.img")
        dataApres("#pariboxeStart{", "depart.img")
        dataApres("#selStage{", "choix.img")
        dataApres("var ICONE_VALOR=", "icone_valor.img")
        dataApres("var ICONE_MODY=", "icone_mody.img")
        dataApres("new Audio(", "musique.mp3")

        fun brut(marque: String, fichier: String) {
            val p = cherche(s, marque, 0)
            require(p >= 0) { "$marque introuvable" }
            val deb = p + marque.length
            ecrire(File(dir, fichier), s, deb, jusqua(s, GUIL, deb))
        }
        brut("rudy_theo:\"", "fin_rudy_theo.img")
        brut("rudy_valor:\"", "fin_rudy_valor.img")
        brut("rudy_mody:\"", "fin_rudy_mody.img")
        brut("  theo:\"", "fin_theo.img")
        brut("  valor:\"", "fin_valor.img")
        brut("  mody:\"", "fin_mody.img")

        File(dir, "planches.txt").writeText(index.toString())
        File(dir, "ok").writeText("1")
    }

    /** nom -> (mode -> nombre d'images), dans l'ordre du HTML. */
    fun lireComptes(dir: File): Map<String, Map<String, Int>> {
        val res = LinkedHashMap<String, LinkedHashMap<String, Int>>()
        File(dir, "planches.txt").readLines().forEach { ligne ->
            val m = ligne.trim().split(' ')
            if (m.size == 3) res.getOrPut(m[0]) { LinkedHashMap() }[m[1]] = m[2].toIntOrNull() ?: 0
        }
        return res
    }

    fun image(f: File): Bitmap? = try {
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    } catch (_: Throwable) { null }

    /** Decode une planche puis la recadre sur ses pixels visibles. */
    fun planche(f: File, tampon: IntArray): Planche? {
        val b = image(f) ?: return null
        val w = b.width
        val h = b.height
        val px = if (tampon.size >= w * h) tampon else IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                if ((px[base + x] ushr 24) != 0) {
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
            }
        }
        if (x1 < 0) return Planche(b, w, h, 0, 0)
        val c = if (x0 == 0 && y0 == 0 && x1 == w - 1 && y1 == h - 1) b
                else Bitmap.createBitmap(b, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
        if (c !== b) b.recycle()
        return Planche(c, w, h, x0, y0)
    }

    // --- lecture du HTML, octet par octet -----------------------------------

    private fun compter(e: InputStream): Int {
        val buf = ByteArray(1 shl 16)
        var n = 0
        while (true) {
            val k = e.read(buf)
            if (k < 0) break
            n += k
        }
        return n
    }

    private fun cherche(s: ByteArray, motif: String, depuis: Int): Int {
        val m = motif.toByteArray(Charsets.UTF_8)
        val premier = m[0]
        val fin = s.size - m.size
        var i = depuis
        while (i <= fin) {
            if (s[i] == premier) {
                var j = 1
                while (j < m.size && s[i + j] == m[j]) j++
                if (j == m.size) return i
            }
            i++
        }
        return -1
    }

    private fun jusqua(s: ByteArray, octet: Byte, depuis: Int): Int {
        var i = depuis
        while (i < s.size && s[i] != octet) i++
        return i
    }

    /** Lit {"mode":["data:...;base64,XXX", ...], ...} -> positions du base64. */
    private fun lireObjet(s: ByteArray, accolade: Int): LinkedHashMap<String, MutableList<IntArray>> {
        val res = LinkedHashMap<String, MutableList<IntArray>>()
        require(accolade >= 0 && s[accolade] == ACC_O)
        var i = accolade + 1
        while (i < s.size) {
            val c = s[i]
            if (c == ACC_F) break
            if (c == GUIL) {
                val finCle = jusqua(s, GUIL, i + 1)
                val cle = String(s, i + 1, finCle - i - 1, Charsets.UTF_8)
                i = jusqua(s, CROCH_O, finCle) + 1
                val liste = mutableListOf<IntArray>()
                while (i < s.size && s[i] != CROCH_F) {
                    if (s[i] == GUIL) {
                        val finVal = jusqua(s, GUIL, i + 1)
                        val virgule = jusqua(s, VIRG, i + 1)
                        liste.add(intArrayOf(virgule + 1, finVal))
                        i = finVal + 1
                    } else i++
                }
                i++
                res[cle] = liste
            } else i++
        }
        return res
    }

    private fun ecrire(f: File, s: ByteArray, deb: Int, fin: Int) {
        f.parentFile?.mkdirs()
        val buf = java.util.Base64.getMimeDecoder().decode(ByteBuffer.wrap(s, deb, fin - deb))
        FileOutputStream(f).use { it.write(buf.array(), buf.arrayOffset() + buf.position(), buf.remaining()) }
    }
}
