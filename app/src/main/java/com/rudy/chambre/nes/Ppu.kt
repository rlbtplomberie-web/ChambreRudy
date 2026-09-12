package com.rudy.chambre.nes

/**
 * PPU de la NES, rendu au cycle pres avec les registres de defilement
 * dits "loopy" (v, t, x, w). Seule facon d'avoir un defilement correct
 * et les decoupes d'ecran en milieu d'image, dont beaucoup de jeux dependent.
 *
 * 341 cycles par ligne, 262 lignes. 0-239 visibles, 241 declenche la NMI,
 * 261 est la ligne de pre-rendu.
 */
class Ppu(private val cart: Cartouche) {

    @JvmField val vram = IntArray(2048)
    @JvmField val palette = IntArray(32)
    @JvmField val oam = IntArray(256)

    @JvmField var ctrl = 0
    @JvmField var mask = 0
    @JvmField var status = 0
    @JvmField var oamAddr = 0

    @JvmField var v = 0          // adresse courante, accessible pour l'auto-test
    private var t = 0
    private var xFin = 0
    private var w = 0
    private var tampon = 0

    @JvmField var cycle = 0
    @JvmField var ligne = 0
    @JvmField var image = 0
    @JvmField var nmiEnAttente = false

    private var nt = 0
    private var at = 0
    private var motifBas = 0
    private var motifHaut = 0
    private var decMotifBas = 0
    private var decMotifHaut = 0
    private var decAttrBas = 0
    private var decAttrHaut = 0

    // sprites de la ligne : x, motif bas, motif haut, attributs, est le sprite zero
    private val spX = IntArray(8)
    private val spBas = IntArray(8)
    private val spHaut = IntArray(8)
    private val spAttr = IntArray(8)
    private val spZero = BooleanArray(8)
    private var nSprites = 0

    /** Indices de palette, 256x240. */
    @JvmField val ecran = IntArray(256 * 240)

    // ================= acces memoire =================
    private fun adrNametable(a0: Int): Int {
        val a = a0 and 0x0FFF
        var table = a shr 10
        when (cart.miroir) {
            MIR_HORIZONTAL -> table = if (table < 2) 0 else 1
            MIR_VERTICAL -> table = table and 1
            MIR_UNIQUE_BASSE -> table = 0
            MIR_UNIQUE_HAUTE -> table = 1
            else -> return a % 2048
        }
        return table * 1024 + (a and 0x3FF)
    }

    private fun adrPalette(a0: Int): Int {
        var a = a0 and 0x1F
        // les entrees de fond sont partagees avec celles des sprites
        if (a == 0x10 || a == 0x14 || a == 0x18 || a == 0x1C) a -= 0x10
        return a
    }

    fun lireVram(a0: Int): Int {
        val a = a0 and 0x3FFF
        return when {
            a < 0x2000 -> cart.lireChr(a)
            a < 0x3F00 -> vram[adrNametable(a)]
            else -> palette[adrPalette(a)]
        }
    }

    fun ecrireVram(a0: Int, v0: Int) {
        val a = a0 and 0x3FFF
        val v = v0 and 0xFF
        when {
            a < 0x2000 -> cart.ecrireChr(a, v)
            a < 0x3F00 -> vram[adrNametable(a)] = v
            else -> palette[adrPalette(a)] = v and 0x3F
        }
    }

    // ================= registres $2000-$2007 =================
    fun lireRegistre(r: Int): Int {
        when (r and 7) {
            2 -> {
                val res = (status and 0xE0) or (tampon and 0x1F)
                status = status and 0x80.inv()   // la lecture efface le drapeau vblank
                w = 0                            // et remet la bascule a zero
                return res
            }
            4 -> return oam[oamAddr]
            7 -> {
                val a = v and 0x3FFF
                val res: Int
                if (a >= 0x3F00) {
                    res = palette[adrPalette(a)]
                    tampon = vram[adrNametable(a)]
                } else {
                    res = tampon
                    tampon = lireVram(a)
                }
                v = (v + (if (ctrl and 0x04 != 0) 32 else 1)) and 0x7FFF
                return res
            }
        }
        return tampon
    }

    fun ecrireRegistre(r: Int, v0: Int) {
        val valeur = v0 and 0xFF
        tampon = valeur
        when (r and 7) {
            0 -> {
                val avant = ctrl
                ctrl = valeur
                t = (t and 0xF3FF) or ((valeur and 0x03) shl 10)
                // activer la NMI pendant le vblank la declenche immediatement
                if (avant and 0x80 == 0 && valeur and 0x80 != 0 && status and 0x80 != 0)
                    nmiEnAttente = true
            }
            1 -> mask = valeur
            3 -> oamAddr = valeur
            4 -> { oam[oamAddr] = valeur; oamAddr = (oamAddr + 1) and 0xFF }
            5 -> if (w == 0) {
                t = (t and 0xFFE0) or (valeur shr 3); xFin = valeur and 7; w = 1
            } else {
                t = (t and 0x8FFF) or ((valeur and 0x07) shl 12)
                t = (t and 0xFC1F) or ((valeur and 0xF8) shl 2)
                w = 0
            }
            6 -> if (w == 0) {
                t = (t and 0x00FF) or ((valeur and 0x3F) shl 8); w = 1
            } else {
                t = (t and 0xFF00) or valeur; v = t; w = 0
            }
            7 -> {
                ecrireVram(v, valeur)
                v = (v + (if (ctrl and 0x04 != 0) 32 else 1)) and 0x7FFF
            }
        }
    }

    // ================= deplacements de v =================
    private fun incX() {
        if ((v and 0x001F) == 31) { v = v and 0x001F.inv(); v = v xor 0x0400 }
        else v++
    }

    private fun incY() {
        if ((v and 0x7000) != 0x7000) v += 0x1000
        else {
            v = v and 0x7000.inv()
            var y = (v and 0x03E0) shr 5
            when (y) {
                29 -> { y = 0; v = v xor 0x0800 }   // 30 rangees, pas 32
                31 -> y = 0
                else -> y++
            }
            v = (v and 0x03E0.inv()) or (y shl 5)
        }
    }

    private fun copierX() { v = (v and 0x041F.inv()) or (t and 0x041F) }
    private fun copierY() { v = (v and 0x7BE0.inv()) or (t and 0x7BE0) }

    private fun chargerDecaleurs() {
        decMotifBas = (decMotifBas and 0xFF00) or motifBas
        decMotifHaut = (decMotifHaut and 0xFF00) or motifHaut
        val bits = at and 3
        decAttrBas = (decAttrBas and 0xFF00) or (if (bits and 1 != 0) 0xFF else 0)
        decAttrHaut = (decAttrHaut and 0xFF00) or (if (bits and 2 != 0) 0xFF else 0)
    }

    private fun majDecaleurs() {
        decMotifBas = (decMotifBas shl 1) and 0xFFFF
        decMotifHaut = (decMotifHaut shl 1) and 0xFFFF
        decAttrBas = (decAttrBas shl 1) and 0xFFFF
        decAttrHaut = (decAttrHaut shl 1) and 0xFFFF
    }

    // ================= sprites =================
    private fun inverserOctet(b: Int): Int {
        var x = b
        x = ((x and 0xF0) shr 4) or ((x and 0x0F) shl 4)
        x = ((x and 0xCC) shr 2) or ((x and 0x33) shl 2)
        x = ((x and 0xAA) shr 1) or ((x and 0x55) shl 1)
        return x and 0xFF
    }

    /**
     * Prepare les sprites de la ligne SUIVANTE. Le materiel compare la ligne
     * courante a la coordonnee Y : un sprite pose en Y apparait donc en Y+1.
     */
    private fun evaluerSprites(ligneComparee: Int) {
        nSprites = 0
        val hauteur = if (ctrl and 0x20 != 0) 16 else 8
        var depassement = false
        for (i in 0 until 64) {
            val yy = oam[i * 4]
            var dy = ligneComparee - yy
            if (dy in 0 until hauteur) {
                if (nSprites == 8) { depassement = true; break }
                var tuile = oam[i * 4 + 1]
                val attr = oam[i * 4 + 2]
                if (attr and 0x80 != 0) dy = hauteur - 1 - dy      // retournement vertical
                val base: Int
                if (hauteur == 16) {
                    base = (tuile and 1) * 0x1000
                    tuile = tuile and 0xFE
                    if (dy >= 8) { tuile += 1; dy -= 8 }
                } else base = if (ctrl and 0x08 != 0) 0x1000 else 0
                var bas = lireVram(base + tuile * 16 + dy)
                var haut = lireVram(base + tuile * 16 + dy + 8)
                if (attr and 0x40 != 0) {                          // retournement horizontal
                    bas = inverserOctet(bas); haut = inverserOctet(haut)
                }
                spX[nSprites] = oam[i * 4 + 3]
                spBas[nSprites] = bas
                spHaut[nSprites] = haut
                spAttr[nSprites] = attr
                spZero[nSprites] = (i == 0)
                nSprites++
            }
        }
        if (depassement) status = status or 0x20
    }

    // ================= production d'un pixel =================
    private fun pixel(x: Int): Int {
        var fond = 0
        var palFond = 0
        if (mask and 0x08 != 0 && !(x < 8 && mask and 0x02 == 0)) {
            val d = 15 - xFin
            fond = ((decMotifBas shr d) and 1) or (((decMotifHaut shr d) and 1) shl 1)
            if (fond != 0)
                palFond = ((decAttrBas shr d) and 1) or (((decAttrHaut shr d) and 1) shl 1)
        }

        var sp = 0
        var palSp = 0
        var devant = false
        var zero = false
        if (mask and 0x10 != 0 && !(x < 8 && mask and 0x04 == 0)) {
            for (i in 0 until nSprites) {
                val dx = x - spX[i]
                if (dx in 0..7) {
                    val d = 7 - dx
                    val vv = ((spBas[i] shr d) and 1) or (((spHaut[i] shr d) and 1) shl 1)
                    if (vv != 0) {
                        sp = vv
                        palSp = spAttr[i] and 3
                        devant = spAttr[i] and 0x20 == 0
                        zero = spZero[i]
                        break                       // priorite au sprite le plus bas en OAM
                    }
                }
            }
        }

        if (fond != 0 && sp != 0 && zero && x != 255) status = status or 0x40

        if (sp != 0 && (devant || fond == 0)) return palette[adrPalette(0x10 + palSp * 4 + sp)]
        if (fond != 0) return palette[adrPalette(palFond * 4 + fond)]
        return palette[0]
    }

    fun renduActif() = (mask and 0x18) != 0

    // ================= sauvegarde d'etat =================
    // L'ecran n'est pas sauvegarde : il se redessine a l'image suivante.
    fun ecrire(e: Etat) {
        e.put(vram); e.put(palette); e.put(oam)
        e.put(ctrl); e.put(mask); e.put(status); e.put(oamAddr)
        e.put(v); e.put(t); e.put(xFin); e.put(w); e.put(tampon)
        e.put(cycle); e.put(ligne); e.put(image); e.put(nmiEnAttente)
        e.put(nt); e.put(at); e.put(motifBas); e.put(motifHaut)
        e.put(decMotifBas); e.put(decMotifHaut); e.put(decAttrBas); e.put(decAttrHaut)
        e.put(spX); e.put(spBas); e.put(spHaut); e.put(spAttr); e.put(spZero); e.put(nSprites)
    }

    fun lire(e: Etat) {
        e.t(vram); e.t(palette); e.t(oam)
        ctrl = e.i(); mask = e.i(); status = e.i(); oamAddr = e.i()
        v = e.i(); t = e.i(); xFin = e.i(); w = e.i(); tampon = e.i()
        cycle = e.i(); ligne = e.i(); image = e.i(); nmiEnAttente = e.b()
        nt = e.i(); at = e.i(); motifBas = e.i(); motifHaut = e.i()
        decMotifBas = e.i(); decMotifHaut = e.i(); decAttrBas = e.i(); decAttrHaut = e.i()
        e.t(spX); e.t(spBas); e.t(spHaut); e.t(spAttr); e.t(spZero); nSprites = e.i()
    }

    // ================= horloge =================
    fun tick() {
        val visible = ligne < 240
        val prerendu = ligne == 261

        if (prerendu && cycle == 1) status = status and 0xE0.inv()

        if ((visible || prerendu) && renduActif()) {
            // Les tuiles sont lues deux d'avance : celles recuperees ici sortent
            // a l'ecran seize pixels plus tard. D'ou les phases decalees.
            if ((cycle in 2..257) || (cycle in 321..337)) {
                majDecaleurs()
                when ((cycle - 1) and 7) {
                    0 -> { chargerDecaleurs(); nt = lireVram(0x2000 or (v and 0x0FFF)) }
                    2 -> {
                        val a = 0x23C0 or (v and 0x0C00) or ((v shr 4) and 0x38) or ((v shr 2) and 0x07)
                        at = (lireVram(a) shr (((v shr 4) and 4) or (v and 2))) and 3
                    }
                    4 -> {
                        val base = if (ctrl and 0x10 != 0) 0x1000 else 0
                        motifBas = lireVram(base + nt * 16 + ((v shr 12) and 7))
                    }
                    6 -> {
                        val base = if (ctrl and 0x10 != 0) 0x1000 else 0
                        motifHaut = lireVram(base + nt * 16 + ((v shr 12) and 7) + 8)
                    }
                    7 -> incX()
                }
            }
            if (cycle == 256) incY()
            if (cycle == 257) {
                chargerDecaleurs(); copierX()
                evaluerSprites(if (visible) ligne else -1)
            }
            if (prerendu && cycle in 280..304) copierY()
        }

        if (visible && cycle in 1..256) ecran[ligne * 256 + (cycle - 1)] = pixel(cycle - 1)

        if (ligne == 241 && cycle == 1) {
            status = status or 0x80
            if (ctrl and 0x80 != 0) nmiEnAttente = true
        }

        cycle++
        if (cycle > 340) {
            cycle = 0
            ligne++
            if (ligne > 261) { ligne = 0; image++ }
        }
    }

    companion object {
        /** Palette NES en 0xRRGGBB. */
        @JvmField val COULEURS = intArrayOf(
            0x666666, 0x002A88, 0x1412A7, 0x3B00A4, 0x5C007E, 0x6E0040, 0x6C0600, 0x561D00,
            0x333500, 0x0B4800, 0x005200, 0x004F08, 0x00404D, 0x000000, 0x000000, 0x000000,
            0xADADAD, 0x155FD9, 0x4240FF, 0x7527FE, 0xA01ACC, 0xB71E7B, 0xB53120, 0x994E00,
            0x6B6D00, 0x388700, 0x0C9300, 0x008F32, 0x007C8D, 0x000000, 0x000000, 0x000000,
            0xFFFEFF, 0x64B0FF, 0x9290FF, 0xC676FF, 0xF36AFF, 0xFE6ECC, 0xFE8170, 0xEA9E22,
            0xBCBE00, 0x88D800, 0x5CE430, 0x45E082, 0x48CDDE, 0x4F4F4F, 0x000000, 0x000000,
            0xFFFEFF, 0xC0DFFF, 0xD3D2FF, 0xE8C8FF, 0xFBC2FF, 0xFEC4EA, 0xFECCC5, 0xF7D8A5,
            0xE4E594, 0xCFEF96, 0xBDF4AB, 0xB3F3CC, 0xB5EBF2, 0xB8B8B8, 0x000000, 0x000000
        )
    }
}
