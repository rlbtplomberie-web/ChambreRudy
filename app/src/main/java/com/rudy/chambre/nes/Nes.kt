package com.rudy.chambre.nes

/** Bits de la manette, dans l'ordre de sortie du registre a decalage. */
object Pad {
    const val A = 1
    const val B = 2
    const val SELECT = 4
    const val START = 8
    const val HAUT = 16
    const val BAS = 32
    const val GAUCHE = 64
    const val DROITE = 128
}

class Manette {
    @JvmField var etat = 0
    private var verrou = 0
    private var strobe = false

    fun ecrire(v: Int) {
        strobe = v and 1 != 0
        if (strobe) verrou = etat
    }

    fun ecrire(e: Etat) { e.put(etat); e.put(verrou); e.put(strobe) }
    fun lire(e: Etat) { etat = e.i(); verrou = e.i(); strobe = e.b() }

    fun lire(): Int {
        if (strobe) verrou = etat
        val bit = verrou and 1
        verrou = (verrou shr 1) or 0x80      // les bits sortants valent 1
        return bit
    }
}

/**
 * La console assemblee : plan memoire, manettes, transfert OAM,
 * et la synchronisation trois cycles PPU pour un cycle processeur.
 */
class Nes(@JvmField val cart: Cartouche) : BusCpu {

    @JvmField val ram = IntArray(2048)
    @JvmField val ppu = Ppu(cart)
    @JvmField val apu = Apu({ a -> lire(a) })
    @JvmField val manettes = arrayOf(Manette(), Manette())
    @JvmField val cpu = Cpu(this)
    private var cyclesDma = 0

    init { cpu.reset() }

    override fun lire(a0: Int): Int {
        val a = a0 and 0xFFFF
        return when {
            a < 0x2000 -> ram[a and 0x07FF]              // 2 Ko repetes quatre fois
            a < 0x4000 -> ppu.lireRegistre(a and 7)      // huit registres repetes
            a == 0x4015 -> apu.lireStatus()
            a == 0x4016 -> manettes[0].lire() or 0x40
            a == 0x4017 -> manettes[1].lire() or 0x40
            a < 0x4020 -> 0
            else -> cart.lirePrg(a)
        }
    }

    override fun ecrire(a0: Int, v0: Int) {
        val a = a0 and 0xFFFF
        val v = v0 and 0xFF
        when {
            a < 0x2000 -> ram[a and 0x07FF] = v
            a < 0x4000 -> ppu.ecrireRegistre(a and 7, v)
            a == 0x4014 -> dmaOam(v)
            a == 0x4016 -> { manettes[0].ecrire(v); manettes[1].ecrire(v) }
            a < 0x4020 -> apu.ecrire(a, v)
            else -> cart.ecrirePrg(a, v)
        }
    }

    /** Copie 256 octets vers l'OAM. Le processeur est gele pendant ce temps. */
    private fun dmaOam(page: Int) {
        val base = page shl 8
        for (i in 0 until 256) ppu.oam[(ppu.oamAddr + i) and 0xFF] = lire(base + i)
        cyclesDma += 513
    }

    private fun pas(): Int {
        if (ppu.nmiEnAttente) { ppu.nmiEnAttente = false; cpu.nmiDemande = true }
        if (cart.irqEnAttente || apu.irqEnAttente || apu.dmc.irqEnAttente) cpu.irqDemande = true

        val n: Int
        if (cyclesDma > 0) { n = minOf(cyclesDma, 8); cyclesDma -= n }
        else n = cpu.pas()

        for (i in 0 until n) apu.tick()
        for (i in 0 until n * 3) {
            val avant = ppu.ligne
            ppu.tick()
            // le compteur du mapper 4 se decremente en fin de partie visible
            if (ppu.ligne != avant && avant < 240 && ppu.renduActif()) cart.horlogeLigne()
        }
        return n
    }

    /** Fait tourner la console jusqu'a la fin de l'image en cours. */
    fun imageSuivante() {
        val depart = ppu.image
        var garde = 0
        while (ppu.image == depart) {
            pas()
            if (++garde > 400000) return       // securite : ne jamais bloquer l'interface
        }
    }

    fun son(): ShortArray = apu.vider()

    fun appuyer(joueur: Int, masque: Int, enfonce: Boolean) {
        val m = manettes[joueur]
        m.etat = if (enfonce) m.etat or masque else m.etat and masque.inv()
    }

    /** Etat complet des huit boutons du joueur 1, en une fois. */
    fun boutons(joueur: Int, masque: Int) { manettes[joueur].etat = masque and 0xFF }

    /**
     * Sauvegarde d'etat : tout ce qui bouge, dans un ordre fixe.
     * La ROM elle-meme n'y est pas ; c'est a l'appelant de verifier
     * qu'on recharge bien l'etat du bon jeu.
     */
    fun sauverEtat(): ByteArray {
        val e = Etat()
        e.put(VERSION_ETAT)
        e.put(ram); e.put(cyclesDma)
        cpu.ecrire(e); ppu.ecrire(e); apu.ecrire(e); cart.ecrire(e)
        manettes[0].ecrire(e); manettes[1].ecrire(e)
        return e.octets()
    }

    /** false si la sauvegarde vient d'une version incompatible. */
    fun restaurerEtat(octets: ByteArray): Boolean {
        val e = Etat(octets)
        if (e.i() != VERSION_ETAT) return false
        e.t(ram); cyclesDma = e.i()
        cpu.lire(e); ppu.lire(e); apu.lire(e); cart.lire(e)
        manettes[0].lire(e); manettes[1].lire(e)
        return true
    }

    /** Recopie l'image courante en couleurs ARGB, pretes a etre affichees. */
    fun imageArgb(sortie: IntArray) {
        val e = ppu.ecran
        val c = Ppu.COULEURS
        for (i in e.indices) sortie[i] = 0xFF000000.toInt() or c[e[i] and 0x3F]
    }

    companion object { const val VERSION_ETAT = 1 }
}
