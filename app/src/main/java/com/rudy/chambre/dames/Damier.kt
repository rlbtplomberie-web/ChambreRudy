package com.rudy.chambre.dames

import kotlin.math.abs

/**
 * Les regles du jeu de dames de Rudy.
 *
 * Son damier n'est pas une grille reguliere : ce sont huit rangees dont il a
 * releve les positions une par une sur son image, cinq puis quatre cases en
 * alternance. Les voisines d'une case sont donc les deux plus proches de la
 * rangee suivante, et non des cases calculees.
 */
object Plateau {
    /** Ses huit rangees, en pourcentages de l'image. */
    val RANGEES: List<List<Pair<Float, Float>>> = listOf(
        listOf(30.2734f to 26.5046f, 40.0391f to 26.5046f, 50.0000f to 26.5046f,
               60.0260f to 26.5046f, 69.7266f to 26.5046f),
        listOf(34.3750f to 32.6389f, 44.7266f to 32.6389f, 54.9479f to 32.6389f,
               65.3646f to 32.6389f),
        listOf(28.3203f to 38.8889f, 39.0625f to 38.8889f, 49.9349f to 38.8889f,
               60.8073f to 38.8889f, 71.4193f to 38.8889f),
        listOf(33.0f to 46.25f, 44.1f to 46.25f, 55.2f to 46.25f, 66.3f to 46.25f),
        listOf(27.1f to 53.9f, 38.6f to 53.9f, 50.1f to 53.9f, 61.6f to 53.9f, 73.1f to 53.9f),
        listOf(25.5208f to 61.9213f, 37.5000f to 61.9213f, 49.8047f to 61.9213f,
               61.8490f to 61.9213f, 74.2839f to 61.9213f),
        listOf(30.3385f to 71.2963f, 42.9688f to 71.2963f, 55.7943f to 71.2963f,
               68.8802f to 71.2963f),
        listOf(22.6562f to 80.5556f, 36.1328f to 80.5556f, 49.8047f to 80.5556f,
               63.4115f to 80.5556f, 77.7344f to 80.5556f)
    )
}

class Dames {

    class Piece(val camp: String, var dame: Boolean = false)

    /** Un coup : d'ou, vers ou, et la piece prise s'il y en a une. */
    data class Coup(val dr: Int, val di: Int, val r: Int, val i: Int,
                    val prise: Pair<Int, Int>? = null)

    val B = Array(8) { r -> arrayOfNulls<Piece>(Plateau.RANGEES[r].size) }
    var choisie: Pair<Int, Int>? = null
    var tour = "moon"
    var occupe = false
    var message = ""
    var finie = false

    var surSon: ((String) -> Unit)? = null

    init { remettre() }

    /** Sa mise en place : trois rangees chacun. */
    fun remettre() {
        for (r in 0 until 8) for (i in B[r].indices) B[r][i] = null
        for (r in 0 until 3) for (i in B[r].indices) B[r][i] = Piece("majora")
        for (r in 5 until 8) for (i in B[r].indices) B[r][i] = Piece("moon")
        choisie = null; tour = "moon"; occupe = false; finie = false
        message = "À VOUS — LUNES DE MAJORA"
    }

    /** Son « neighbors » : les deux cases les plus proches de la rangee suivante. */
    private fun voisines(r: Int, i: Int, sens: Int): List<Int> {
        val nr = r + sens
        if (nr < 0 || nr >= 8) return emptyList()
        val x = Plateau.RANGEES[r][i].first
        return Plateau.RANGEES[nr].mapIndexed { j, p -> j to abs(p.first - x) }
            .sortedBy { it.second }.take(2).map { it.first }
    }

    /** Son « moves » : les coups d'une piece, prises comprises. */
    fun coups(r: Int, i: Int, prisesSeules: Boolean = false): List<Coup> {
        val p = B[r][i] ?: return emptyList()
        val sens = if (p.dame) listOf(-1, 1) else if (p.camp == "moon") listOf(-1) else listOf(1)
        val sortie = ArrayList<Coup>()
        for (dr in sens) {
            for (j in voisines(r, i, dr)) {
                val cible = B[r + dr][j]
                if (cible == null && !prisesSeules) sortie.add(Coup(r, i, r + dr, j))
                if (cible != null && cible.camp != p.camp) {
                    val nr = r + 2 * dr
                    if (nr < 0 || nr >= 8) continue
                    val libres = voisines(r + dr, j, dr).filter { B[nr][it] == null }
                    if (libres.isNotEmpty()) {
                        // la case la plus alignee avec le saut, comme chez lui
                        val vise = 2 * Plateau.RANGEES[r + dr][j].first - Plateau.RANGEES[r][i].first
                        val k = libres.minByOrNull { abs(Plateau.RANGEES[nr][it].first - vise) }!!
                        sortie.add(Coup(r, i, nr, k, (r + dr) to j))
                    }
                }
            }
        }
        return if (prisesSeules) sortie.filter { it.prise != null } else sortie
    }

    /** Son « all » : les prises d'abord, sinon les deplacements. */
    fun tousLesCoups(camp: String): List<Coup> {
        val prises = ArrayList<Coup>(); val simples = ArrayList<Coup>()
        for (r in 0 until 8) for (i in B[r].indices) {
            if (B[r][i]?.camp != camp) continue
            for (m in coups(r, i)) (if (m.prise != null) prises else simples).add(m)
        }
        return if (prises.isNotEmpty()) prises else simples
    }

    /** Son « apply » : on joue le coup, on retire la prise, on couronne. */
    fun jouer(m: Coup) {
        val p = B[m.dr][m.di] ?: return
        B[m.r][m.i] = p
        B[m.dr][m.di] = null
        if (m.prise != null) {
            B[m.prise.first][m.prise.second] = null
            surSon?.invoke("prise")
        } else surSon?.invoke("deplacement")
        if ((p.camp == "moon" && m.r == 0) || (p.camp == "majora" && m.r == 7)) p.dame = true
    }

    /** Son « tap » : ce qui se passe quand on touche une case. */
    fun toucher(r: Int, i: Int): Boolean {
        if (occupe || tour != "moon" || finie) return false
        if (B[r][i]?.camp == "moon") { choisie = r to i; return true }
        val depart = choisie ?: return false
        val obligatoire = tousLesCoups("moon").any { it.prise != null }
        val m = coups(depart.first, depart.second)
            .firstOrNull { it.r == r && it.i == i && (!obligatoire || it.prise != null) }
            ?: return false
        jouer(m)
        // sa prise multiple : on garde la main tant qu'on peut reprendre
        if (m.prise != null && coups(m.r, m.i, true).isNotEmpty()) {
            choisie = m.r to m.i
            message = "PRISE MULTIPLE — CONTINUEZ"
            return true
        }
        choisie = null; tour = "majora"
        if (verifierFin()) return true
        occupe = true
        message = "MAJORA JOUE…"
        return true
    }

    /** Le tour de Majora : il joue au hasard, et enchaine ses prises. */
    fun coupDeMajora(): Coup? {
        val possibles = tousLesCoups("majora")
        if (possibles.isEmpty()) { terminer("VICTOIRE — TERMINA EST SAUVÉE !"); return null }
        val m = possibles.random()
        jouer(m)
        return m
    }

    /** Peut-il reprendre depuis la case ou il vient d'arriver ? */
    fun repriseDeMajora(r: Int, i: Int, apresPrise: Boolean): Coup? {
        if (!apresPrise) return null
        return coups(r, i, true).randomOrNull()
    }

    fun finDuTourDeMajora() {
        tour = "moon"; occupe = false
        if (!verifierFin()) message = "À VOUS — LUNES DE MAJORA"
    }

    /** Son « end » : plus de pieces, ou plus de coups. */
    fun verifierFin(): Boolean {
        val lunes = B.flatten().count { it?.camp == "moon" }
        val majoras = B.flatten().count { it?.camp == "majora" }
        if (majoras == 0 || tousLesCoups("majora").isEmpty()) {
            terminer("VICTOIRE — TERMINA EST SAUVÉE !"); return true
        }
        if (lunes == 0 || tousLesCoups("moon").isEmpty()) {
            terminer("PERDU — MAJORA A GAGNÉ !"); return true
        }
        return false
    }

    private fun terminer(t: String) { occupe = true; finie = true; message = t }

    /** Les cases ou la piece choisie peut aller, pour les marquer. */
    fun coupsAffiches(): List<Coup> {
        val s = choisie ?: return emptyList()
        val obligatoire = tour == "moon" && tousLesCoups("moon").any { it.prise != null }
        return coups(s.first, s.second).filter { !obligatoire || it.prise != null }
    }
}
