package com.rudy.chambre.nes

/**
 * Cartouches et mappers. Decoupage commun : quatre fenetres PRG de 8 Ko et
 * huit fenetres CHR de 1 Ko, ce qui evite un code de pagination par mapper.
 */

const val MIR_HORIZONTAL = 0
const val MIR_VERTICAL = 1
const val MIR_UNIQUE_BASSE = 2
const val MIR_UNIQUE_HAUTE = 3
const val MIR_QUATRE = 4

open class Cartouche(prgIn: ByteArray, chrIn: ByteArray, @JvmField var miroir: Int) {

    @JvmField val prg = IntArray(prgIn.size) { prgIn[it].toInt() and 0xFF }
    @JvmField val chrEstRam = chrIn.isEmpty()
    @JvmField val chr = if (chrEstRam) IntArray(8192)
                        else IntArray(chrIn.size) { chrIn[it].toInt() and 0xFF }
    @JvmField val ram = IntArray(8192)
    @JvmField val nPrg8 = maxOf(1, prg.size / 8192)
    @JvmField val nChr1 = maxOf(1, chr.size / 1024)
    @JvmField val banquesPrg = intArrayOf(0, 1 % nPrg8, (nPrg8 - 2).coerceAtLeast(0), nPrg8 - 1)
    @JvmField val banquesChr = IntArray(8) { it }
    @JvmField var irqEnAttente = false
    @JvmField var numero = 0

    fun lirePrg(a: Int): Int {
        if (a in 0x6000..0x7FFF) return ram[(a - 0x6000) % ram.size]
        if (a < 0x8000) return 0
        val b = Math.floorMod(banquesPrg[(a - 0x8000) shr 13], nPrg8)
        return prg[b * 8192 + (a and 0x1FFF)]
    }

    fun ecrirePrg(a: Int, v: Int) {
        when {
            a in 0x6000..0x7FFF -> ram[(a - 0x6000) % ram.size] = v and 0xFF
            a >= 0x8000 -> registre(a, v and 0xFF)
        }
    }

    open fun registre(a: Int, v: Int) {}

    fun lireChr(a: Int): Int {
        val q = a and 0x1FFF
        val b = Math.floorMod(banquesChr[q shr 10], nChr1)
        return chr[b * 1024 + (q and 0x3FF)]
    }

    fun ecrireChr(a: Int, v: Int) {
        if (!chrEstRam) return
        val q = a and 0x1FFF
        val b = Math.floorMod(banquesChr[q shr 10], nChr1)
        chr[b * 1024 + (q and 0x3FF)] = v and 0xFF
    }

    /** Appelee une fois par ligne visible. Seul le mapper 4 s'en sert. */
    open fun horlogeLigne() {}

    // ================= sauvegarde d'etat =================
    // La ROM de programme ne bouge jamais : seuls la pagination, la RAM de
    // sauvegarde et, si elle est inscriptible, la memoire graphique sont gardees.
    fun ecrire(e: Etat) {
        e.put(miroir); e.put(ram); e.put(banquesPrg); e.put(banquesChr); e.put(irqEnAttente)
        if (chrEstRam) e.put(chr)
        ecrireMapper(e)
    }

    fun lire(e: Etat) {
        miroir = e.i(); e.t(ram); e.t(banquesPrg); e.t(banquesChr); irqEnAttente = e.b()
        if (chrEstRam) e.t(chr)
        lireMapper(e)
    }

    open fun ecrireMapper(e: Etat) {}
    open fun lireMapper(e: Etat) {}
}

/** Mapper 2 : fenetre basse commutable, fenetre haute figee sur la derniere. */
class UxROM(p: ByteArray, c: ByteArray, m: Int) : Cartouche(p, c, m) {
    override fun registre(a: Int, v: Int) {
        val b = (v and 0x0F) * 2
        banquesPrg[0] = b; banquesPrg[1] = b + 1
    }
}

/** Mapper 3 : seuls les motifs graphiques sont commutes. */
class CNROM(p: ByteArray, c: ByteArray, m: Int) : Cartouche(p, c, m) {
    override fun registre(a: Int, v: Int) {
        val b = (v and 0x03) * 8
        for (i in 0 until 8) banquesChr[i] = b + i
    }
}

/**
 * Mapper 1. Les ecritures arrivent bit par bit dans un registre a decalage :
 * cinq ecritures sont necessaires pour valider une valeur.
 */
class MMC1(p: ByteArray, c: ByteArray, m: Int) : Cartouche(p, c, m) {
    private var decalage = 0x10
    private var controle = 0x0C
    private var chr0 = 0
    private var chr1 = 0
    private var banquePrg = 0

    init { appliquer() }

    override fun registre(a: Int, v: Int) {
        if (v and 0x80 != 0) {
            decalage = 0x10; controle = controle or 0x0C; appliquer(); return
        }
        val dernierTour = (decalage and 1) == 1
        decalage = (decalage shr 1) or ((v and 1) shl 4)
        if (dernierTour) {
            val valeur = decalage and 0x1F
            decalage = 0x10
            when ((a shr 13) and 3) {
                0 -> controle = valeur
                1 -> chr0 = valeur
                2 -> chr1 = valeur
                else -> banquePrg = valeur and 0x0F
            }
            appliquer()
        }
    }

    private fun appliquer() {
        miroir = when (controle and 3) {
            0 -> MIR_UNIQUE_BASSE; 1 -> MIR_UNIQUE_HAUTE; 2 -> MIR_VERTICAL; else -> MIR_HORIZONTAL
        }
        val derniere16 = (nPrg8 / 2) - 1
        when ((controle shr 2) and 3) {
            0, 1 -> {
                val b = (banquePrg and 0x0E) * 2
                banquesPrg[0] = b; banquesPrg[1] = b + 1
                banquesPrg[2] = b + 2; banquesPrg[3] = b + 3
            }
            2 -> {
                val b = banquePrg * 2
                banquesPrg[0] = 0; banquesPrg[1] = 1
                banquesPrg[2] = b; banquesPrg[3] = b + 1
            }
            else -> {
                val b = banquePrg * 2
                banquesPrg[0] = b; banquesPrg[1] = b + 1
                banquesPrg[2] = derniere16 * 2; banquesPrg[3] = derniere16 * 2 + 1
            }
        }
        if (controle and 0x10 != 0) {
            val x = (chr0 and 0x1F) * 4
            val y = (chr1 and 0x1F) * 4
            for (i in 0 until 4) { banquesChr[i] = x + i; banquesChr[4 + i] = y + i }
        } else {
            val x = (chr0 and 0x1E) * 4
            for (i in 0 until 8) banquesChr[i] = x + i
        }
    }

    override fun ecrireMapper(e: Etat) {
        e.put(decalage); e.put(controle); e.put(chr0); e.put(chr1); e.put(banquePrg)
    }
    override fun lireMapper(e: Etat) {
        decalage = e.i(); controle = e.i(); chr0 = e.i(); chr1 = e.i(); banquePrg = e.i()
    }
}

/**
 * Mapper 4. Huit registres internes, plus un compteur decremente a chaque
 * ligne : c'est lui qui permet les decoupes d'ecran en milieu d'image.
 */
class MMC3(p: ByteArray, c: ByteArray, m: Int) : Cartouche(p, c, m) {
    private val r = intArrayOf(0, 2, 4, 5, 6, 7, 0, 1)
    private var selection = 0
    private var irqValeur = 0
    private var irqCompteur = 0
    private var irqActive = false
    private var irqRecharger = false

    init { appliquer() }

    override fun registre(a: Int, v: Int) {
        val pair = (a and 1) == 0
        when ((a shr 13) and 3) {
            0 -> { if (pair) selection = v else r[selection and 7] = v; appliquer() }
            1 -> if (pair) miroir = if (v and 1 != 0) MIR_HORIZONTAL else MIR_VERTICAL
            2 -> if (pair) irqValeur = v else { irqCompteur = 0; irqRecharger = true }
            else -> if (pair) { irqActive = false; irqEnAttente = false } else irqActive = true
        }
    }

    private fun appliquer() {
        val dernier = nPrg8 - 1
        val r6 = Math.floorMod(r[6], nPrg8)
        val r7 = Math.floorMod(r[7], nPrg8)
        if (selection and 0x40 != 0) {
            banquesPrg[0] = dernier - 1; banquesPrg[1] = r7
            banquesPrg[2] = r6; banquesPrg[3] = dernier
        } else {
            banquesPrg[0] = r6; banquesPrg[1] = r7
            banquesPrg[2] = dernier - 1; banquesPrg[3] = dernier
        }
        val x = r[0] and 0xFE
        val y = r[1] and 0xFE
        val grandes = intArrayOf(x, x + 1, y, y + 1)
        val petites = intArrayOf(r[2], r[3], r[4], r[5])
        val ordre = if (selection and 0x80 != 0) petites + grandes else grandes + petites
        for (i in 0 until 8) banquesChr[i] = ordre[i]
    }

    override fun horlogeLigne() {
        if (irqCompteur == 0 || irqRecharger) {
            irqCompteur = irqValeur; irqRecharger = false
        } else irqCompteur--
        if (irqCompteur == 0 && irqActive) irqEnAttente = true
    }

    override fun ecrireMapper(e: Etat) {
        e.put(r); e.put(selection); e.put(irqValeur); e.put(irqCompteur)
        e.put(irqActive); e.put(irqRecharger)
    }
    override fun lireMapper(e: Etat) {
        e.t(r); selection = e.i(); irqValeur = e.i(); irqCompteur = e.i()
        irqActive = e.b(); irqRecharger = e.b()
    }
}

object Ines {
    val MAPPERS_CONNUS = intArrayOf(0, 1, 2, 3, 4)

    /** Renvoie null si le fichier n'est pas exploitable, avec le motif dans [erreur]. */
    var erreur: String = ""

    fun charger(o: ByteArray): Cartouche? {
        erreur = ""
        if (o.size < 16 || o[0].toInt() != 0x4E || o[1].toInt() != 0x45 ||
            o[2].toInt() != 0x53 || o[3].toInt() != 0x1A) {
            erreur = "Ce fichier n'est pas une ROM NES."
            return null
        }
        val nPrg = o[4].toInt() and 0xFF
        val nChr = o[5].toInt() and 0xFF
        val f6 = o[6].toInt() and 0xFF
        val f7 = o[7].toInt() and 0xFF
        val numero = (f7 and 0xF0) or (f6 shr 4)
        val miroir = when {
            f6 and 0x08 != 0 -> MIR_QUATRE
            f6 and 1 != 0 -> MIR_VERTICAL
            else -> MIR_HORIZONTAL
        }
        val debut = 16 + (if (f6 and 0x04 != 0) 512 else 0)
        val tPrg = nPrg * 16384
        val tChr = nChr * 8192
        if (o.size < debut + tPrg) {
            erreur = "ROM incomplete : donnees de programme tronquees."
            return null
        }
        val prg = o.copyOfRange(debut, debut + tPrg)
        val finChr = minOf(o.size, debut + tPrg + tChr)
        val chr = o.copyOfRange(debut + tPrg, finChr)
        val c = when (numero) {
            0 -> Cartouche(prg, chr, miroir)
            1 -> MMC1(prg, chr, miroir)
            2 -> UxROM(prg, chr, miroir)
            3 -> CNROM(prg, chr, miroir)
            4 -> MMC3(prg, chr, miroir)
            else -> { erreur = "Mapper $numero non pris en charge."; return null }
        }
        c.numero = numero
        return c
    }
}
