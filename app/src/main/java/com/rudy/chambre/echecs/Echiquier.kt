package com.rudy.chambre.echecs

/**
 * Les regles du jeu d'echecs de Rudy : Hyrule contre Ganondorf.
 *
 * Ses deplacements, sa promotion, et son adversaire qui prefere les prises.
 */
class Echiquier {

    class Piece(val camp: String, var genre: Char)

    /** Un coup : vers ou, et s'il prend une piece. */
    data class Coup(val dr: Int, val dc: Int, val r: Int, val c: Int, val prise: Boolean = false)

    val pos = Array(8) { arrayOfNulls<Piece>(8) }

    var joueur = "H"          // le camp du joueur
    var ordinateur = "G"
    var tour = "H"
    var choisie: Pair<Int, Int>? = null
    var possibles = listOf<Coup>()
    var promotion: Pair<Int, Int>? = null
    var occupe = false
    var finie = false
    var etat = ""

    var surSon: ((String) -> Unit)? = null

    /** Sa rangee du fond : tour, cavalier, fou, dame, roi, fou, cavalier, tour. */
    private val fond = charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r')

    fun nom(camp: String) = if (camp == "H") "HYRULE" else "GANONDORF"

    /** Son « init » : le joueur en bas, l'adversaire en haut. */
    fun commencer(camp: String) {
        joueur = camp
        ordinateur = if (camp == "H") "G" else "H"
        tour = joueur; choisie = null; possibles = emptyList()
        promotion = null; occupe = false; finie = false
        for (r in 0 until 8) for (c in 0 until 8) pos[r][c] = null
        for (c in 0 until 8) {
            pos[0][c] = Piece(ordinateur, fond[c])
            pos[1][c] = Piece(ordinateur, 'p')
            pos[6][c] = Piece(joueur, 'p')
            pos[7][c] = Piece(joueur, fond[c])
        }
        etat = "À TOI — " + nom(joueur)
    }

    private fun dedans(r: Int, c: Int) = r in 0..7 && c in 0..7

    /** Son « movesFor » : les coups d'une piece. */
    fun coupsDe(r: Int, c: Int): List<Coup> {
        val p = pos[r][c] ?: return emptyList()
        val sortie = ArrayList<Coup>()

        fun pousser(dr: Int, dc: Int, glisse: Boolean) {
            var rr = r + dr; var cc = c + dc
            while (dedans(rr, cc)) {
                val cible = pos[rr][cc]
                if (cible == null) sortie.add(Coup(r, c, rr, cc))
                else {
                    if (cible.camp != p.camp) sortie.add(Coup(r, c, rr, cc, true))
                    break
                }
                if (!glisse) break
                rr += dr; cc += dc
            }
        }

        when (p.genre) {
            'p' -> {
                val d = if (p.camp == joueur) -1 else 1
                val depart = if (p.camp == joueur) 6 else 1
                if (dedans(r + d, c) && pos[r + d][c] == null) {
                    sortie.add(Coup(r, c, r + d, c))
                    if (r == depart && pos[r + 2 * d][c] == null)
                        sortie.add(Coup(r, c, r + 2 * d, c))
                }
                for (dc in intArrayOf(-1, 1)) {
                    if (dedans(r + d, c + dc)) {
                        val cible = pos[r + d][c + dc]
                        if (cible != null && cible.camp != p.camp)
                            sortie.add(Coup(r, c, r + d, c + dc, true))
                    }
                }
            }
            'n' -> for (s in listOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2,
                                    1 to -2, 1 to 2, 2 to -1, 2 to 1))
                pousser(s.first, s.second, false)
            'k' -> for (dr in -1..1) for (dc in -1..1) if (dr != 0 || dc != 0) pousser(dr, dc, false)
            else -> {
                val sens = when (p.genre) {
                    'r' -> listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
                    'b' -> listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
                    else -> listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1,
                                   1 to 1, 1 to -1, -1 to 1, -1 to -1)
                }
                for (s in sens) pousser(s.first, s.second, true)
            }
        }
        return sortie
    }

    fun tousLesCoups(camp: String): List<Coup> {
        val a = ArrayList<Coup>()
        for (r in 0 until 8) for (c in 0 until 8)
            if (pos[r][c]?.camp == camp) a.addAll(coupsDe(r, c))
        return a
    }

    /** Son « tap ». Renvoie vrai si l'affichage doit changer. */
    fun toucher(r: Int, c: Int): Boolean {
        if (occupe || tour != joueur || finie || promotion != null) return false
        val p = pos[r][c]
        if (choisie != null) {
            val m = possibles.firstOrNull { it.r == r && it.c == c }
            if (m != null) { jouer(m); return true }
        }
        if (p != null && p.camp == joueur) {
            choisie = r to c
            possibles = coupsDe(r, c)
            return true
        }
        return false
    }

    /** Son « doMove » : on deplace, et on s'arrete si une promotion attend. */
    fun jouer(m: Coup) {
        val p = pos[m.dr][m.dc] ?: return
        val prise = pos[m.r][m.c] != null
        pos[m.r][m.c] = p
        pos[m.dr][m.dc] = null
        choisie = null; possibles = emptyList()
        surSon?.invoke(if (prise) "capture" else "deplacement")

        if (p.genre == 'p' && (m.r == 0 || m.r == 7)) {
            if (p.camp == joueur) { promotion = m.r to m.c; return }
            else p.genre = 'q'
        }
        finirLeTour(p.camp)
    }

    /** Le joueur a choisi sa piece de promotion. */
    fun promouvoir(genre: Char) {
        val q = promotion ?: return
        pos[q.first][q.second]?.genre = genre
        promotion = null
        finirLeTour(joueur)
    }

    fun finirLeTour(camp: String) {
        if (verifierFin()) return
        tour = if (camp == joueur) ordinateur else joueur
        if (tour == ordinateur) { occupe = true; etat = "À " + nom(ordinateur) }
        else { occupe = false; etat = "À TOI — " + nom(joueur) }
    }

    /** Son adversaire : il prend s'il peut, sinon il joue au hasard. */
    fun coupDeLOrdinateur(): Coup? {
        val a = tousLesCoups(ordinateur)
        if (a.isEmpty()) { etat = "VICTOIRE — " + nom(joueur); occupe = true; finie = true; return null }
        val prises = a.filter { it.prise }
        return (if (prises.isNotEmpty()) prises else a).random()
    }

    /** Son « checkEnd » : la partie s'arrete quand un roi tombe. */
    fun verifierFin(): Boolean {
        var roiH = false; var roiG = false
        for (r in 0 until 8) for (c in 0 until 8) {
            val p = pos[r][c] ?: continue
            if (p.genre == 'k') { if (p.camp == "H") roiH = true else roiG = true }
        }
        if (!roiH || !roiG) {
            etat = "VICTOIRE — " + nom(if (roiH) "H" else "G")
            occupe = true; finie = true
            return true
        }
        return false
    }

    /**
     * L'image d'une piece : vue de face pour l'adversaire, de dos pour le
     * joueur — c'est son sens de marche qui decide, comme chez lui.
     */
    fun imageDe(p: Piece): String {
        val genres = mapOf('p' to "pawn", 'r' to "rook", 'n' to "knight",
                           'b' to "bishop", 'q' to "queen", 'k' to "king")
        val face = if (p.camp == ordinateur) "front" else "back"
        return "${p.camp.lowercase()}_${genres[p.genre]}_$face.webp"
    }
}
