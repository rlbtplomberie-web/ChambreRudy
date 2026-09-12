package com.skinnes.app.nes

/**
 * Auto-test embarque.
 *
 * Le moteur a ete valide en Python par 212 verifications, mais le portage en
 * Kotlin ne peut etre verifie qu'a l'execution. Ce fichier rejoue les controles
 * les plus discriminants directement dans l'application : si un seul echoue,
 * c'est le portage qui a derape, pas la logique.
 */
object AutoTest {

    private val echecs = ArrayList<String>()
    private var faits = 0

    private fun verif(nom: String, obtenu: Any?, attendu: Any?) {
        faits++
        if (obtenu != attendu) echecs.add("$nom : $obtenu au lieu de $attendu")
    }

    /** Bus de test : 64 Ko plats, sans cartouche. */
    private class BusPlat : BusCpu {
        val m = IntArray(65536)
        override fun lire(a: Int) = m[a and 0xFFFF]
        override fun ecrire(a: Int, v: Int) { m[a and 0xFFFF] = v and 0xFF }
    }

    private fun prog(vararg octets: Int): Cpu {
        val b = BusPlat()
        for (i in octets.indices) b.m[0x8000 + i] = octets[i] and 0xFF
        b.m[0xFFFC] = 0x00; b.m[0xFFFD] = 0x80
        val c = Cpu(b)
        c.reset()
        return c
    }

    private fun bus(c: Cpu): IntArray = (c.bus as BusPlat).m

    // ---------------- processeur ----------------
    private fun testCpu() {
        // modes d'adressage
        var c = prog(0xA5, 0x10); bus(c)[0x10] = 0x42
        c.pas(); verif("LDA zp", c.a, 0x42)

        c = prog(0xB5, 0xFF); c.x = 2; bus(c)[0x01] = 0x37
        c.pas(); verif("LDA zp,X boucle dans la page", c.a, 0x37)

        c = prog(0xBD, 0xF0, 0x12); c.x = 0x20; bus(c)[0x1310] = 0x22
        var cy = c.pas()
        verif("LDA abs,X franchit la page", c.a, 0x22)
        verif("cycle supplementaire au franchissement", cy, 5)

        c = prog(0xB1, 0x10); c.y = 0x10
        bus(c)[0x10] = 0xF8; bus(c)[0x11] = 0x30; bus(c)[0x3108] = 0x7E
        c.pas(); verif("LDA (zp),Y", c.a, 0x7E)

        // bug materiel du JMP indirect
        c = prog(0x6C, 0xFF, 0x02)
        bus(c)[0x02FF] = 0x00; bus(c)[0x0300] = 0x40; bus(c)[0x0200] = 0x80
        c.pas(); verif("JMP (ind) bug de page", c.pc, 0x8000)

        // arithmetique signee
        c = prog(0xA9, 0x50, 0x69, 0x50); c.pas(); c.pas()
        verif("ADC resultat", c.a, 0xA0)
        verif("ADC debordement signe", c.p and F_V != 0, true)
        verif("ADC pas de retenue", c.p and F_C != 0, false)

        c = prog(0x38, 0xA9, 0x50, 0xE9, 0xB0)
        c.pas(); c.pas(); c.pas()
        verif("SBC debordement", c.p and F_V != 0, true)
        verif("SBC emprunt", c.p and F_C != 0, false)

        // comparaison
        c = prog(0xC9, 0x10); c.a = 0x08; c.pas()
        verif("CMP inferieur : pas de retenue", c.p and F_C != 0, false)
        verif("CMP inferieur : negatif", c.p and F_N != 0, true)

        // decalages
        c = prog(0x38, 0x2A); c.a = 0x80; c.pas(); c.pas()
        verif("ROL entrante", c.a, 0x01)
        verif("ROL sortante", c.p and F_C != 0, true)

        // pile et sous-programmes
        c = prog(0x20, 0x00, 0x90)
        c.pas()
        verif("JSR destination", c.pc, 0x9000)
        verif("JSR adresse empilee", bus(c)[0x1FC] or (bus(c)[0x1FD] shl 8), 0x8002)
        bus(c)[0x9000] = 0x60
        c.pas(); verif("RTS retour", c.pc, 0x8003)

        c = prog(0x08, 0x68); c.p = 0x24; c.pas(); c.pas()
        verif("PHP force B et U", c.a and 0x30, 0x30)

        // interruptions
        c = prog(0xEA); bus(c)[0xFFFA] = 0x00; bus(c)[0xFFFB] = 0x90
        c.nmiDemande = true
        cy = c.pas()
        verif("NMI vecteur", c.pc, 0x9000)
        verif("NMI cycles", cy, 7)
        verif("NMI n'empile pas B", bus(c)[0x1FB] and F_B, 0)

        c = prog(0xEA); c.p = c.p or F_I; c.irqDemande = true
        c.pas(); verif("IRQ masquee par le drapeau I", c.pc, 0x8001)

        // branchements
        c = prog(0xA2, 0x03, 0xCA, 0xD0, 0xFD)
        for (i in 0 until 7) c.pas()
        verif("boucle DEX/BNE", c.x, 0)

        // opcodes non documentes
        c = prog(0xA7, 0x10); bus(c)[0x10] = 0x5C; c.pas()
        verif("LAX charge A et X", c.a == 0x5C && c.x == 0x5C, true)

        c = prog(0x07, 0x10); c.a = 0x01; bus(c)[0x10] = 0x40; c.pas()
        verif("SLO decale puis ou-logique", c.a, 0x81)

        // comptabilite des cycles sur une longue boucle
        c = prog(0xE8, 0x4C, 0x00, 0x80)
        var total = 0L
        for (i in 0 until 100000) total += c.pas()
        verif("cycles sur 100 000 instructions", total, 250000L)
    }

    // ---------------- affichage ----------------
    private fun cartTest(): Cartouche {
        val prg = ByteArray(32768)
        val chr = ByteArray(8192)
        // tuile 1 : moitie gauche pleine
        for (y in 0 until 8) { chr[16 + y] = 0xF0.toByte(); chr[16 + 8 + y] = 0 }
        return Cartouche(prg, chr, MIR_HORIZONTAL)
    }

    private fun testPpu() {
        var p = Ppu(cartTest())

        p.status = 0x80
        verif("lecture de $2002 renvoie vblank", p.lireRegistre(2) and 0x80, 0x80)
        verif("lecture de $2002 efface vblank", p.status and 0x80, 0)

        // miroir horizontal
        p = Ppu(cartTest())
        p.ecrireVram(0x2000, 0x11)
        verif("miroir horizontal", p.lireVram(0x2400), 0x11)
        p.ecrireVram(0x3F10, 0x0C)
        verif("miroir de palette 3F10 vers 3F00", p.lireVram(0x3F00), 0x0C)

        // registres de defilement, sequence documentee
        p = Ppu(cartTest())
        p.ecrireRegistre(0, 0x00)
        p.ecrireRegistre(5, 0x7D)
        p.ecrireRegistre(5, 0x5E)
        p.ecrireRegistre(6, 0x3D)
        p.ecrireRegistre(6, 0xF0)
        verif("registre v apres la sequence", p.v, 0x3DF0)

        // duree d'une image
        p = Ppu(cartTest())
        var n = 0
        while (p.image == 0) { p.tick(); n++ }
        verif("cycles par image", n, 341 * 262)

        // vblank et NMI
        p = Ppu(cartTest())
        p.ctrl = 0x80
        while (!(p.ligne == 241 && p.cycle == 1)) p.tick()
        p.tick()
        verif("vblank leve en ligne 241", p.status and 0x80, 0x80)
        verif("NMI demandee", p.nmiEnAttente, true)

        // rendu reel du fond
        p = Ppu(cartTest())
        p.ecrireVram(0x3F00, 0x0F)
        p.ecrireVram(0x3F01, 0x21)
        for (i in 0 until 960) p.ecrireVram(0x2000 + i, 1)
        p.mask = 0x08 or 0x02
        while (p.ligne < 240) p.tick()
        val L = 100 * 256
        verif("fond : quatre pixels colores", p.ecran[L], 0x21)
        verif("fond : puis quatre vides", p.ecran[L + 4], 0x0F)
        verif("fond : motif repete", p.ecran[L + 8], 0x21)
    }

    // ---------------- son ----------------
    private fun testApu() {
        var a = Apu({ 0 })
        a.ecrire(0x4015, 0x01)
        a.ecrire(0x4002, 0xAB)
        a.ecrire(0x4003, 0x02)
        verif("periode sur 11 bits", a.p1.periode, 0x2AB)
        verif("duree chargee", a.p1.duree, 10)

        // periode du registre a decalage du bruit
        a = Apu({ 0 })
        a.ecrire(0x4015, 0x08)
        a.ecrire(0x400E, 0x00)
        a.bruit.registre = 1
        // horlogeTimer() contient le diviseur : avec la periode 4, le registre
        // ne se decale qu'un appel sur cinq. La periode du registre est bien de
        // 32767 decalages, ce qui fait 1 + 5 * 32766 = 163831 appels.
        var periode = 0
        for (i in 0 until 200000) {
            a.bruit.horlogeTimer()
            if (a.bruit.registre == 1) { periode = i + 1; break }
        }
        verif("bruit mode long : periode", periode, 163831)

        a = Apu({ 0 })
        a.ecrire(0x4015, 0x08)
        a.ecrire(0x400E, 0x80)
        a.bruit.registre = 1
        // 93 decalages, meme diviseur : 1 + 5 * 92 = 461 appels.
        periode = 0
        for (i in 0 until 2000) {
            a.bruit.horlogeTimer()
            if (a.bruit.registre == 1) { periode = i + 1; break }
        }
        verif("bruit mode court : periode", periode, 461)

        // sequenceur
        a = Apu({ 0 })
        a.ecrire(0x4017, 0x00)
        for (i in 0 until 29830) a.tick()
        verif("interruption en mode quatre pas", a.irqEnAttente, true)

        a = Apu({ 0 })
        a.ecrire(0x4017, 0x80)
        for (i in 0 until 40000) a.tick()
        verif("aucune interruption en mode cinq pas", a.irqEnAttente, false)

        // volume d'un carre seul
        a = Apu({ 0 })
        a.ecrire(0x4015, 0x01)
        a.ecrire(0x4000, 0x10 or 0x0F)
        a.ecrire(0x4002, 0x40); a.ecrire(0x4003, 0x00)
        a.p1.pas = 1
        val s = a.melange()
        verif("melange d'un carre a plein volume", (s * 1000).toInt(), 149)
    }

    // ---------------- console complete ----------------
    private fun testSysteme() {
        val n = Nes(cartTest())
        n.ecrire(0x0000, 0x42)
        verif("RAM repetee en $0800", n.lire(0x0800), 0x42)
        verif("RAM repetee en $1800", n.lire(0x1800), 0x42)

        for (i in 0 until 256) n.ram[0x0300 + i] = (i * 3) and 0xFF
        n.ecrire(0x4014, 0x03)
        verif("transfert OAM", n.ppu.oam[100], (100 * 3) and 0xFF)

        n.appuyer(0, Pad.A, true)
        n.appuyer(0, Pad.START, true)
        n.ecrire(0x4016, 1); n.ecrire(0x4016, 0)
        val lus = IntArray(8) { n.lire(0x4016) and 1 }
        verif("ordre des boutons",
            lus.joinToString(""), "10010000")
    }

    /** Lance tout. Renvoie null si tout passe, sinon le rapport d'echec. */
    fun lancer(): String? {
        echecs.clear(); faits = 0
        try {
            testCpu(); testPpu(); testApu(); testSysteme()
        } catch (e: Throwable) {
            return "Auto-test interrompu : ${e::class.java.simpleName} ${e.message}"
        }
        if (echecs.isEmpty()) return null
        val n = minOf(echecs.size, 6)
        return "Auto-test : ${echecs.size} echec(s) sur $faits\n" +
                echecs.take(n).joinToString("\n")
    }

    fun resume(): String = "$faits verifications"
}
