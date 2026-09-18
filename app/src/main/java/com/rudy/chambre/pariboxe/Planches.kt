package com.rudy.chambre.pariboxe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Une planche de combattant, recadree sur le personnage pour economiser la
 * memoire. On garde la taille d'origine de l'image (largeur x hauteur) et la
 * place du recadrage (dx, dy) : l'affichage reste ainsi exactement celui du
 * HTML, ou chaque image est posee « contain » en bas au centre de sa boite.
 */
class Planche(val bmp: Bitmap, val largeur: Int, val hauteur: Int, val dx: Int, val dy: Int)

/** Toutes les planches d'un combattant (Rudy, Theo, Valor ou Mody). */
class Combattant(val nom: String, val comptes: Map<String, Int>) {
    val images = ConcurrentHashMap<String, Planche>()
    @Volatile var abandonne = false
    fun image(mode: String, i: Int): Planche? = images["${mode}_$i"]
    fun compte(mode: String): Int = comptes[mode] ?: 0
}

/**
 * Les images du combat viennent du fichier HTML de Rudy lui-meme
 * (assets/pariboxe/index.html.000 + .001) : rien n'est redessine ni
 * recompresse. Au premier lancement, chaque image et la musique sont
 * decodees une seule fois dans le cache ; les lancements suivants les
 * relisent directement.
 */
object Planches {
    private const val VERSION = "pariboxe-natif-v1"
    private const val GUIL: Byte = 34     // "
    private const val VIRG: Byte = 44     // ,
    private const val CROCH_O: Byte = 91  // [
    private const val CROCH_F: Byte = 93  // ]
    private const val ACC_F: Byte = 125   // }

    fun dossier(ctx: Context) = File(ctx.cacheDir, VERSION)

    fun extraire(ctx: Context) {
        val dir = dossier(ctx)
        if (File(dir, "ok").isFile) return
        // les anciennes copies de la WebView ne servent plus
        File(ctx.cacheDir, "pariboxe-html-original-v4.html").delete()
        File(ctx.cacheDir, "pariboxe-html-original-v4.tmp").delete()
        dir.deleteRecursively()
        dir.mkdirs()

        val s = lireHtml(ctx)

        val index = StringBuilder()
        val jeux = listOf("rudy" to "const F=", "theo" to "const E=",
                          "valor" to "const V=", "mody" to "const MO=")
        for ((nom, marque) in jeux) {
            val p = cherche(s, marque, 0)
            require(p >= 0) { "planches $nom introuvables" }
            val obj = lireObjet(s, p + marque.length)
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

    /** Recolle les deux morceaux du HTML en un seul tableau d'octets. */
    private fun lireHtml(ctx: Context): ByteArray {
        val a = ctx.assets.open("pariboxe/index.html.000").use { it.readBytes() }
        val b = ctx.assets.open("pariboxe/index.html.001").use { it.readBytes() }
        val s = ByteArray(a.size + b.size)
        System.arraycopy(a, 0, s, 0, a.size)
        System.arraycopy(b, 0, s, a.size, b.size)
        return s
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
        if (x1 < 0) { b.prepareToDraw(); return Planche(b, w, h, 0, 0) }
        val c = if (x0 == 0 && y0 == 0 && x1 == w - 1 && y1 == h - 1) b
                else Bitmap.createBitmap(b, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
        if (c !== b) b.recycle()
        c.prepareToDraw()
        return Planche(c, w, h, x0, y0)
    }

    // --- lecture du HTML, octet par octet -----------------------------------

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
