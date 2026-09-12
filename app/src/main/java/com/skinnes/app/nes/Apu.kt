package com.skinnes.app.nes

/**
 * APU : deux carres, un triangle, un bruit, la lecture d'echantillons.
 * Le sequenceur cadence enveloppes, balayages et durees a environ 240 Hz.
 * Le melange final est non lineaire, comme sur le materiel : une somme
 * simple ferait sonner les volumes relatifs faux.
 */

const val CPU_HZ = 1789773.0

private val DUREES = intArrayOf(
    10, 254, 20, 2, 40, 4, 80, 6, 160, 8, 60, 10, 14, 12, 26, 14,
    12, 16, 24, 18, 48, 20, 96, 22, 192, 24, 72, 26, 16, 28, 32, 30)

private val RAPPORTS = arrayOf(
    intArrayOf(0, 1, 0, 0, 0, 0, 0, 0),
    intArrayOf(0, 1, 1, 0, 0, 0, 0, 0),
    intArrayOf(0, 1, 1, 1, 1, 0, 0, 0),
    intArrayOf(1, 0, 0, 1, 1, 1, 1, 1))

private val SUITE_TRIANGLE = intArrayOf(
    15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)

private val PERIODES_BRUIT = intArrayOf(
    4, 8, 16, 32, 64, 96, 128, 160, 202, 254, 380, 508, 762, 1016, 2034, 4068)

private val PERIODES_DMC = intArrayOf(
    428, 380, 340, 320, 286, 254, 226, 214, 190, 160, 142, 128, 106, 84, 72, 54)

class Enveloppe {
    @JvmField var demarrer = false
    @JvmField var boucle = false
    @JvmField var constant = false
    @JvmField var volume = 0
    private var diviseur = 0
    private var decroissance = 0

    fun horloge() {
        if (demarrer) { demarrer = false; decroissance = 15; diviseur = volume }
        else if (diviseur > 0) diviseur--
        else {
            diviseur = volume
            if (decroissance > 0) decroissance-- else if (boucle) decroissance = 15
        }
    }

    fun sortie() = if (constant) volume else decroissance

    fun ecrire(e: Etat) {
        e.put(demarrer); e.put(boucle); e.put(constant); e.put(volume)
        e.put(diviseur); e.put(decroissance)
    }
    fun lire(e: Etat) {
        demarrer = e.b(); boucle = e.b(); constant = e.b(); volume = e.i()
        diviseur = e.i(); decroissance = e.i()
    }
}

class Carre(private val canalDeux: Boolean) {
    @JvmField var active = false
    @JvmField var duree = 0
    @JvmField var periode = 0
    @JvmField var pas = 0
    @JvmField val env = Enveloppe()
    private var rapport = 0
    private var compteur = 0
    private var halte = false
    private var balActif = false
    private var balPeriode = 0
    private var balNegatif = false
    private var balDecalage = 0
    private var balRecharger = false
    private var balCompteur = 0

    fun ecrire(r: Int, v: Int) {
        when (r) {
            0 -> {
                rapport = v shr 6
                halte = v and 0x20 != 0
                env.boucle = halte
                env.constant = v and 0x10 != 0
                env.volume = v and 0x0F
            }
            1 -> {
                balActif = v and 0x80 != 0
                balPeriode = (v shr 4) and 7
                balNegatif = v and 0x08 != 0
                balDecalage = v and 7
                balRecharger = true
            }
            2 -> periode = (periode and 0x700) or v
            else -> {
                periode = (periode and 0x0FF) or ((v and 7) shl 8)
                if (active) duree = DUREES[v shr 3]
                pas = 0
                env.demarrer = true
            }
        }
    }

    /** Le canal 1 soustrait un de plus : difference materielle reelle. */
    fun cible(): Int {
        val d = periode shr balDecalage
        return if (balNegatif) periode - d - (if (canalDeux) 0 else 1) else periode + d
    }

    fun muet() = periode < 8 || cible() > 0x7FF

    fun horlogeBalayage() {
        if (balCompteur == 0 && balActif && balDecalage != 0 && !muet())
            periode = maxOf(0, cible())
        if (balCompteur == 0 || balRecharger) { balCompteur = balPeriode; balRecharger = false }
        else balCompteur--
    }

    fun horlogeDuree() { if (!halte && duree > 0) duree-- }

    fun horlogeTimer() {
        if (compteur == 0) { compteur = periode; pas = (pas + 1) and 7 } else compteur--
    }

    fun sortie(): Int {
        if (!active || duree == 0 || muet()) return 0
        return env.sortie() * RAPPORTS[rapport][pas]
    }

    fun ecrire(e: Etat) {
        e.put(active); e.put(duree); e.put(periode); e.put(pas); env.ecrire(e)
        e.put(rapport); e.put(compteur); e.put(halte)
        e.put(balActif); e.put(balPeriode); e.put(balNegatif)
        e.put(balDecalage); e.put(balRecharger); e.put(balCompteur)
    }
    fun lire(e: Etat) {
        active = e.b(); duree = e.i(); periode = e.i(); pas = e.i(); env.lire(e)
        rapport = e.i(); compteur = e.i(); halte = e.b()
        balActif = e.b(); balPeriode = e.i(); balNegatif = e.b()
        balDecalage = e.i(); balRecharger = e.b(); balCompteur = e.i()
    }
}

class Triangle {
    @JvmField var active = false
    @JvmField var duree = 0
    @JvmField var periode = 0
    private var compteur = 0
    private var pas = 0
    private var halte = false
    private var linRecharge = 0
    private var linCompteur = 0
    private var linRecharger = false

    fun ecrire(r: Int, v: Int) {
        when (r) {
            0 -> { halte = v and 0x80 != 0; linRecharge = v and 0x7F }
            2 -> periode = (periode and 0x700) or v
            3 -> {
                periode = (periode and 0x0FF) or ((v and 7) shl 8)
                if (active) duree = DUREES[v shr 3]
                linRecharger = true
            }
        }
    }

    fun horlogeLineaire() {
        if (linRecharger) linCompteur = linRecharge else if (linCompteur > 0) linCompteur--
        if (!halte) linRecharger = false
    }

    fun horlogeDuree() { if (!halte && duree > 0) duree-- }

    fun horlogeTimer() {
        if (compteur == 0) {
            compteur = periode
            if (duree > 0 && linCompteur > 0 && periode >= 2) pas = (pas + 1) and 31
        } else compteur--
    }

    /** Au repos le triangle tient sa derniere valeur : comportement du circuit. */
    fun sortie() = if (!active) 0 else SUITE_TRIANGLE[pas]

    fun ecrire(e: Etat) {
        e.put(active); e.put(duree); e.put(periode); e.put(compteur); e.put(pas)
        e.put(halte); e.put(linRecharge); e.put(linCompteur); e.put(linRecharger)
    }
    fun lire(e: Etat) {
        active = e.b(); duree = e.i(); periode = e.i(); compteur = e.i(); pas = e.i()
        halte = e.b(); linRecharge = e.i(); linCompteur = e.i(); linRecharger = e.b()
    }
}

class Bruit {
    @JvmField var active = false
    @JvmField var duree = 0
    @JvmField var registre = 1        // ne doit jamais valoir zero
    @JvmField val env = Enveloppe()
    private var mode = false
    private var periode = 4
    private var compteur = 0
    private var halte = false

    fun ecrire(r: Int, v: Int) {
        when (r) {
            0 -> {
                halte = v and 0x20 != 0
                env.boucle = halte
                env.constant = v and 0x10 != 0
                env.volume = v and 0x0F
            }
            2 -> { mode = v and 0x80 != 0; periode = PERIODES_BRUIT[v and 0x0F] }
            3 -> { if (active) duree = DUREES[v shr 3]; env.demarrer = true }
        }
    }

    fun horlogeDuree() { if (!halte && duree > 0) duree-- }

    fun horlogeTimer() {
        if (compteur == 0) {
            compteur = periode
            val bit = if (mode) 6 else 1
            val retour = (registre xor (registre shr bit)) and 1
            registre = (registre shr 1) or (retour shl 14)
        } else compteur--
    }

    fun sortie(): Int {
        if (!active || duree == 0 || registre and 1 != 0) return 0
        return env.sortie()
    }

    fun ecrire(e: Etat) {
        e.put(active); e.put(duree); e.put(registre); env.ecrire(e)
        e.put(mode); e.put(periode); e.put(compteur); e.put(halte)
    }
    fun lire(e: Etat) {
        active = e.b(); duree = e.i(); registre = e.i(); env.lire(e)
        mode = e.b(); periode = e.i(); compteur = e.i(); halte = e.b()
    }
}

class Dmc(private val lireMemoire: (Int) -> Int) {
    @JvmField var active = false
    @JvmField var restant = 0
    @JvmField var irqEnAttente = false
    private var boucle = false
    private var irqActif = false
    private var periode = PERIODES_DMC[0]
    private var compteur = 0
    private var niveau = 0
    private var adresseDepart = 0xC000
    private var longueurDepart = 1
    private var adresse = 0xC000
    private var tampon = -1
    private var decaleur = 0
    private var bits = 0

    fun ecrire(r: Int, v: Int) {
        when (r) {
            0 -> {
                irqActif = v and 0x80 != 0
                boucle = v and 0x40 != 0
                periode = PERIODES_DMC[v and 0x0F]
                if (!irqActif) irqEnAttente = false
            }
            1 -> niveau = v and 0x7F
            2 -> adresseDepart = 0xC000 + (v shl 6)
            else -> longueurDepart = (v shl 4) + 1
        }
    }

    fun demarrer() { adresse = adresseDepart; restant = longueurDepart }

    fun horlogeTimer() {
        if (!active) return
        if (tampon < 0 && restant > 0) {
            tampon = lireMemoire(adresse)
            adresse = 0x8000 or ((adresse + 1) and 0x7FFF)
            restant--
            if (restant == 0) {
                if (boucle) demarrer() else if (irqActif) irqEnAttente = true
            }
        }
        if (compteur == 0) {
            compteur = periode
            if (bits == 0 && tampon >= 0) { decaleur = tampon; tampon = -1; bits = 8 }
            if (bits > 0) {
                if (decaleur and 1 != 0) { if (niveau <= 125) niveau += 2 }
                else if (niveau >= 2) niveau -= 2
                decaleur = decaleur shr 1
                bits--
            }
        } else compteur--
    }

    fun sortie() = niveau

    fun ecrire(e: Etat) {
        e.put(active); e.put(restant); e.put(irqEnAttente); e.put(boucle); e.put(irqActif)
        e.put(periode); e.put(compteur); e.put(niveau); e.put(adresseDepart)
        e.put(longueurDepart); e.put(adresse); e.put(tampon); e.put(decaleur); e.put(bits)
    }
    fun lire(e: Etat) {
        active = e.b(); restant = e.i(); irqEnAttente = e.b(); boucle = e.b(); irqActif = e.b()
        periode = e.i(); compteur = e.i(); niveau = e.i(); adresseDepart = e.i()
        longueurDepart = e.i(); adresse = e.i(); tampon = e.i(); decaleur = e.i(); bits = e.i()
    }
}

class Apu(lireMemoire: (Int) -> Int, private val frequence: Int = 44100) {
    @JvmField val p1 = Carre(false)
    @JvmField val p2 = Carre(true)
    @JvmField val tri = Triangle()
    @JvmField val bruit = Bruit()
    @JvmField val dmc = Dmc(lireMemoire)

    private var cycle = 0
    private var mode5 = false
    private var irqInhibee = false
    @JvmField var irqEnAttente = false

    private val parEchantillon = CPU_HZ / frequence
    private var accumulateur = 0.0
    private var tampon = ShortArray(4096)
    private var nEchantillons = 0

    fun ecrire(a: Int, v0: Int) {
        val v = v0 and 0xFF
        when {
            a in 0x4000..0x4003 -> p1.ecrire(a and 3, v)
            a in 0x4004..0x4007 -> p2.ecrire(a and 3, v)
            a in 0x4008..0x400B -> tri.ecrire(a and 3, v)
            a in 0x400C..0x400F -> bruit.ecrire(a and 3, v)
            a in 0x4010..0x4013 -> dmc.ecrire(a and 3, v)
            a == 0x4015 -> {
                p1.active = v and 1 != 0
                p2.active = v and 2 != 0
                tri.active = v and 4 != 0
                bruit.active = v and 8 != 0
                val etait = dmc.active
                dmc.active = v and 16 != 0
                if (!p1.active) p1.duree = 0
                if (!p2.active) p2.duree = 0
                if (!tri.active) tri.duree = 0
                if (!bruit.active) bruit.duree = 0
                if (dmc.active && !etait && dmc.restant == 0) dmc.demarrer()
                if (!dmc.active) dmc.restant = 0
                dmc.irqEnAttente = false
            }
            a == 0x4017 -> {
                mode5 = v and 0x80 != 0
                irqInhibee = v and 0x40 != 0
                if (irqInhibee) irqEnAttente = false
                cycle = 0
                if (mode5) { quart(); demi() }
            }
        }
    }

    fun lireStatus(): Int {
        var v = 0
        if (p1.duree > 0) v = v or 1
        if (p2.duree > 0) v = v or 2
        if (tri.duree > 0) v = v or 4
        if (bruit.duree > 0) v = v or 8
        if (dmc.restant > 0) v = v or 16
        if (irqEnAttente) v = v or 0x40
        if (dmc.irqEnAttente) v = v or 0x80
        irqEnAttente = false          // la lecture acquitte l'interruption
        return v
    }

    private fun quart() {
        p1.env.horloge(); p2.env.horloge(); bruit.env.horloge(); tri.horlogeLineaire()
    }

    private fun demi() {
        p1.horlogeDuree(); p1.horlogeBalayage()
        p2.horlogeDuree(); p2.horlogeBalayage()
        tri.horlogeDuree(); bruit.horlogeDuree()
    }

    private fun sequenceur() {
        if (!mode5) {
            when (cycle) {
                7457 -> quart()
                14913 -> { quart(); demi() }
                22371 -> quart()
                29829 -> {
                    quart(); demi()
                    if (!irqInhibee) irqEnAttente = true
                    cycle = 0
                    return
                }
            }
        } else {
            when (cycle) {
                7457 -> quart()
                14913 -> { quart(); demi() }
                22371 -> quart()
                37281 -> { quart(); demi(); cycle = 0; return }
            }
        }
        cycle++
    }

    /** Un cycle processeur. */
    fun tick() {
        tri.horlogeTimer()                    // le triangle tourne au rythme du CPU
        if (cycle and 1 == 0) {                // les autres a la moitie
            p1.horlogeTimer(); p2.horlogeTimer(); bruit.horlogeTimer(); dmc.horlogeTimer()
        }
        sequenceur()

        accumulateur += 1.0
        if (accumulateur >= parEchantillon) {
            accumulateur -= parEchantillon
            if (nEchantillons < tampon.size) {
                // melange centre puis converti en entier 16 bits signe
                tampon[nEchantillons++] = ((melange() - 0.35) * 46000.0)
                    .coerceIn(-32000.0, 32000.0).toInt().toShort()
            }
        }
    }

    /** Formule non lineaire du materiel. Renvoie une valeur entre 0 et 1. */
    fun melange(): Double {
        val c = p1.sortie() + p2.sortie()
        val carres = if (c == 0) 0.0 else 95.88 / (8128.0 / c + 100.0)
        val s = tri.sortie() / 8227.0 + bruit.sortie() / 12241.0 + dmc.sortie() / 22638.0
        val autres = if (s == 0.0) 0.0 else 159.79 / (1.0 / s + 100.0)
        return carres + autres
    }

    /** Recupere les echantillons produits depuis le dernier appel et vide le tampon. */
    fun ecrire(e: Etat) {
        p1.ecrire(e); p2.ecrire(e); tri.ecrire(e); bruit.ecrire(e); dmc.ecrire(e)
        e.put(cycle); e.put(mode5); e.put(irqInhibee); e.put(irqEnAttente)
    }

    fun lire(e: Etat) {
        p1.lire(e); p2.lire(e); tri.lire(e); bruit.lire(e); dmc.lire(e)
        cycle = e.i(); mode5 = e.b(); irqInhibee = e.b(); irqEnAttente = e.b()
        // le tampon audio en cours n'a plus de sens apres un saut d'etat
        accumulateur = 0.0
        nEchantillons = 0
    }

    fun vider(): ShortArray {
        val r = tampon.copyOf(nEchantillons)
        nEchantillons = 0
        return r
    }
}
