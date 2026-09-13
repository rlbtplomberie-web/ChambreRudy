package com.rudy.chambre.cartes

/**
 * Le jeu de cartes commun au poker et au blackjack de Rudy.
 *
 * Ses quatre couleurs portent ses noms — triforce, gemme, fee et coeur — et
 * ses treize hauteurs vont de l'as au roi.
 */
object Paquet {
    val couleurs = listOf("tri", "gem", "fairy", "heart")
    val hauteurs = listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")

    /** Les 52 cartes, melangees. */
    fun melange(): ArrayList<String> {
        val d = ArrayList<String>()
        for (c in couleurs) for (h in hauteurs) d.add("${c}_$h")
        d.shuffle()
        return d
    }

    fun hauteurDe(carte: String) = carte.substringAfter('_')
    fun imageDe(carte: String) = "$carte.webp"
}

/**
 * Le blackjack de Rudy : quatre joueurs contre le croupier.
 *
 * L'as vaut onze, puis un s'il fait depasser ; le croupier tire jusqu'a dix-sept.
 */
class Blackjack {

    class Main { val cartes = ArrayList<String>(); var etat = "" }

    private var paquet = Paquet.melange()
    val joueurs = List(4) { Main() }
    val croupier = Main()
    var tour = 0
    var enCours = false
    var croupierDecouvert = false
    var message = ""

    /** Son « val » : l'as retombe a un quand la main depasse. */
    fun valeur(m: Main): Int {
        var n = 0; var as1 = 0
        for (c in m.cartes) {
            when (val h = Paquet.hauteurDe(c)) {
                "A" -> { n += 11; as1++ }
                "J", "Q", "K" -> n += 10
                else -> n += h.toInt()
            }
        }
        while (n > 21 && as1 > 0) { n -= 10; as1-- }
        return n
    }

    fun estBlackjack(m: Main) = m.cartes.size == 2 && valeur(m) == 21

    private fun piocher() = paquet.removeAt(paquet.size - 1)

    /** Son « start » : deux cartes a chacun, deux au croupier. */
    fun distribuer() {
        paquet = Paquet.melange()
        for (j in joueurs) { j.cartes.clear(); j.etat = "" }
        croupier.cartes.clear(); croupier.etat = ""
        tour = 0; enCours = true; croupierDecouvert = false
        repeat(2) {
            for (j in joueurs) j.cartes.add(piocher())
            croupier.cartes.add(piocher())
        }
        message = "À VOUS : CARTE ou RESTER"
        if (estBlackjack(joueurs[0])) { joueurs[0].etat = "BLACKJACK !" }
    }

    /** Le joueur tire. Renvoie vrai si son tour s'arrete. */
    fun carte(): Boolean {
        if (!enCours || tour > 3) return false
        joueurs[tour].cartes.add(piocher())
        val v = valeur(joueurs[tour])
        if (v > 21) { joueurs[tour].etat = "DÉPASSÉ 21"; return true }
        if (v == 21) { joueurs[tour].etat = "21"; return true }
        return false
    }

    fun rester() {
        if (!enCours || tour > 3) return
        joueurs[tour].etat = "RESTE À ${valeur(joueurs[tour])}"
    }

    /** Le tour d'un adversaire : il tire tant qu'il est sous dix-sept. */
    fun coupDeLOrdinateur(): String {
        val p = joueurs[tour]
        val v = valeur(p)
        if (v < 17) { p.cartes.add(piocher()); p.etat = "CPU tire"; return "tire" }
        p.etat = "CPU RESTE À $v"
        return "reste"
    }

    fun apresLeCoupDeLOrdinateur(): Boolean {
        val p = joueurs[tour]
        val v = valeur(p)
        if (v > 21) { p.etat = "CPU DÉPASSÉ 21"; return true }
        if (v >= 17) { p.etat = "CPU RESTE À $v"; return true }
        return false
    }

    /** Le croupier tire jusqu'a dix-sept. */
    fun croupierDoitTirer(): Boolean = valeur(croupier) < 17
    fun croupierTire() { croupier.cartes.add(piocher()) }

    /** Son « finish » : qui gagne, qui perd, qui fait match nul. */
    fun conclure() {
        val dv = valeur(croupier)
        val dbj = estBlackjack(croupier)
        for (p in joueurs) {
            val pv = valeur(p)
            val pbj = estBlackjack(p)
            p.etat = when {
                pv > 21 -> "PERDU"
                dbj && !pbj -> "PERDU"
                pbj && !dbj -> "BLACKJACK — GAGNÉ"
                dv > 21 || pv > dv -> "GAGNÉ"
                pv < dv -> "PERDU"
                else -> "ÉGALITÉ"
            }
        }
        enCours = false
        croupierDecouvert = true
        message = "Manche terminée — croupier : $dv"
    }
}

/**
 * Le poker de Rudy : sa table de quatre, le flop, le turn et la river.
 */
class Poker {

    private var paquet = Paquet.melange()
    val maMain = ArrayList<String>()
    val tapis = ArrayList<String>()
    var phase = 0                 // 0 attente, 1 avant flop, 2 flop, 3 turn, 4 river
    var pot = 0
    var jetons = 500
    var message = ""

    private fun piocher() = paquet.removeAt(paquet.size - 1)

    /** Son « deal » : la mise de depart, et deux cartes pour toi. */
    fun distribuer() {
        paquet = Paquet.melange()
        maMain.clear(); tapis.clear()
        phase = 1; pot = 40
        jetons = maxOf(0, jetons - 10)
        maMain.add(piocher()); maMain.add(piocher())
        message = "Tes cartes sont distribuées — Suivre pour voir le flop"
    }

    /** Son « reveal » : le flop puis le turn puis la river, 20 au pot. */
    fun suivre() {
        when (phase) {
            1 -> { repeat(3) { tapis.add(piocher()) }; pot += 20; phase = 2
                   message = "Le flop tombe sur les trois premières cartes" }
            2 -> { tapis.add(piocher()); pot += 20; phase = 3; message = "Le turn est dévoilé" }
            3 -> { tapis.add(piocher()); pot += 20; phase = 4; message = "La river est dévoilée" }
            4 -> { phase = 0; message = "Main terminée — Distribuer pour rejouer" }
        }
    }

    fun seCoucher() {
        if (phase == 0) return
        phase = 0
        message = "Tu te couches — Distribuer pour une nouvelle main"
    }

    fun ajouterDesJetons() { jetons += 100; message = "+100 jetons ajoutés" }
}
