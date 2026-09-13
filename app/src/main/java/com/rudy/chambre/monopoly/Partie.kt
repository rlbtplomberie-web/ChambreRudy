package com.rudy.chambre.monopoly

/**
 * Les regles du Monopoly de Rudy, traduites de sa page.
 *
 * Ses loyers, ses hypotheques, sa prison, ses cartes, ses faillites et son
 * adversaire qui achete et propose des echanges.
 */
class Joueur(val i: Int, val nom: String, val jeton: String, val couleur: Int, val ia: Boolean) {
    var argent = 1500
    var pos = 0
    var prison = 0            // nombre d'essais restants
    var sorties = 0           // cartes « sortie de prison »
    var ruine = false
}

/** Une carte : son texte, et ce qu'elle fait. */
class Carte(val texte: String, val effet: (Partie, Joueur) -> Unit)

class Partie {

    val plateau = nouveauPlateau()
    val joueurs = ArrayList<Joueur>()
    var courant = 0
    var phase = "attente"          // attente, roule, achat, fin, finie
    var des = intArrayOf(1, 1)
    var doubles = 0
    var journal = ArrayList<String>()

    private val sortPaquet = ArrayList<Carte>()
    private val coffrePaquet = ArrayList<Carte>()

    var surSon: ((String) -> Unit)? = null
    var surCarte: ((String, String) -> Unit)? = null

    fun noter(t: String) {
        journal.add(t)
        if (journal.size > 60) journal.removeAt(0)
    }

    fun vivants() = joueurs.filter { !it.ruine }
    fun biens(j: Joueur) = plateau.filter { it.proprio == j.i }
    fun memeGroupe(c: Case) = plateau.filter { it.type == "terrain" && it.groupe == c.groupe }
    fun groupeComplet(c: Case) = c.proprio >= 0 && memeGroupe(c).all { it.proprio == c.proprio }

    /** Son « loyer » : gares, services, groupe complet, maisons. */
    fun loyer(c: Case, lancer: Int): Int {
        if (c.hypothequee || c.proprio < 0) return 0
        if (c.type == "gare") {
            val n = plateau.count { it.type == "gare" && it.proprio == c.proprio && !it.hypothequee }
            return listOf(0, 25, 50, 100, 200)[n]
        }
        if (c.type == "service") {
            val n = plateau.count { it.type == "service" && it.proprio == c.proprio && !it.hypothequee }
            return lancer * (if (n == 2) 10 else 4)
        }
        if (c.maisons > 0) return c.loyers[c.maisons]
        return if (groupeComplet(c)) c.loyers[0] * 2 else c.loyers[0]
    }

    /** Son « liquider » : on revend les maisons, puis on hypotheque. */
    private fun liquider(j: Joueur, besoin: Int) {
        while (j.argent < besoin) {
            val avecMaisons = biens(j).filter { it.maisons > 0 }.minByOrNull { it.prix }
            if (avecMaisons != null) {
                avecMaisons.maisons--
                j.argent += (GROUPES[avecMaisons.groupe]?.maison ?: 0) / 2
                continue
            }
            val aHypothequer = biens(j).filter { !it.hypothequee }.minByOrNull { it.prix }
            if (aHypothequer != null) {
                aHypothequer.hypothequee = true
                j.argent += aHypothequer.prix / 2
                continue
            }
            break
        }
    }

    /** Son « payer » : il liquide si besoin, et fait faillite s'il ne peut pas. */
    fun payer(j: Joueur, montant: Int, versQui: Joueur?): Boolean {
        if (j.argent < montant) liquider(j, montant)
        if (j.argent < montant) { ruiner(j, versQui); return false }
        j.argent -= montant
        surSon?.invoke("perte")
        versQui?.let { it.argent += montant }
        return true
    }

    fun encaisser(j: Joueur, n: Int) { j.argent += n; noter("${j.nom} touche $n €.") }
    fun payerBanque(j: Joueur, n: Int) { noter("${j.nom} paie $n €."); payer(j, n, null) }

    /** Son « ruiner » : tout passe au creancier, ou revient a la banque. */
    fun ruiner(j: Joueur, versQui: Joueur?) {
        j.ruine = true
        val reste = j.argent
        j.argent = 0
        for (c in biens(j)) {
            if (versQui != null) { c.proprio = versQui.i; c.maisons = 0 }
            else { c.proprio = -1; c.maisons = 0; c.hypothequee = false }
        }
        if (versQui != null) {
            versQui.argent += reste
            noter("${j.nom} est ruiné : tout revient à ${versQui.nom}.")
        } else noter("${j.nom} est ruiné : ses biens retournent à la banque.")
        surSon?.invoke("faillite")
    }

    /** Ses travaux : tant par maison, tant par hotel. */
    fun travaux(j: Joueur, parMaison: Int, parHotel: Int) {
        var total = 0
        for (c in biens(j)) total += if (c.maisons == 5) parHotel else c.maisons * parMaison
        if (total == 0) { noter("Rien à réparer chez ${j.nom}."); return }
        noter("${j.nom} paie $total € de travaux.")
        payer(j, total, null)
    }

    fun collecte(j: Joueur, n: Int) {
        for (a in vivants()) if (a !== j) payer(a, n, j)
        noter("${j.nom} encaisse $n € de chaque joueur.")
    }

    fun tournee(j: Joueur, n: Int) {
        for (a in vivants()) if (a !== j) payer(j, n, a)
        noter("${j.nom} offre $n € à chacun.")
    }

    fun gareSuivante(pos: Int): Int {
        for (k in 1..40) { val p = (pos + k) % 40; if (plateau[p].type == "gare") return p }
        return 5
    }

    fun enPrison(j: Joueur) {
        j.pos = 10; j.prison = 3; doubles = 0
        noter("${j.nom} part en prison.")
        surSon?.invoke("prison")
    }

    // ================= les cartes =================

    /** Ses douze Coups du sort. */
    private fun coupsDuSort() = listOf(
        Carte("Le maire t'invite à couper le ruban : avance jusqu'au Départ.") { p, j -> p.aller(j, 0, true) },
        Carte("Un contrat signé sur le quai : file jusqu'à l'Esplanade Royale.") { p, j -> p.aller(j, 39, true) },
        Carte("Tu prends le premier train : avance jusqu'à la gare suivante.") { p, j -> p.aller(j, p.gareSuivante(j.pos), true) },
        Carte("Panne d'ascenseur : 25 € par maison, 100 € par hôtel.") { p, j -> p.travaux(j, 25, 100) },
        Carte("Amende pour affichage sauvage : 15 €.") { p, j -> p.payerBanque(j, 15) },
        Carte("Ton oncle te renfloue : 150 €.") { p, j -> p.encaisser(j, 150) },
        Carte("Tu t'es trompé de rue : recule de trois cases.") { p, j -> p.aller(j, (j.pos + 37) % 40, false) },
        Carte("Contrôle de police : direction la prison.") { p, j -> p.enPrison(j) },
        Carte("Un ami avocat te glisse une sortie de prison. Garde-la.") { _, j -> j.sorties++ },
        Carte("Dividende du port : 50 €.") { p, j -> p.encaisser(j, 50) },
        Carte("Réunion de chantier Rue des Halles : avance jusque-là.") { p, j -> p.aller(j, 11, true) },
        Carte("Tout le quartier fête ton anniversaire : chacun te donne 10 €.") { p, j -> p.collecte(j, 10) }
    )

    /** Ses douze Coffres de la ville. */
    private fun coffres() = listOf(
        Carte("Erreur du percepteur en ta faveur : 200 €.") { p, j -> p.encaisser(j, 200) },
        Carte("Vente de ta vieille camionnette : 75 €.") { p, j -> p.encaisser(j, 75) },
        Carte("Frais de notaire : 50 €.") { p, j -> p.payerBanque(j, 50) },
        Carte("Concours des façades fleuries, premier prix : 100 €.") { p, j -> p.encaisser(j, 100) },
        Carte("Note du plombier : 40 €.") { p, j -> p.payerBanque(j, 40) },
        Carte("Remboursement d'assurance : 120 €.") { p, j -> p.encaisser(j, 120) },
        Carte("Le juge est formel : va en prison.") { p, j -> p.enPrison(j) },
        Carte("Sortie de prison offerte par la mairie. Garde-la.") { _, j -> j.sorties++ },
        Carte("Ravalement obligatoire : 40 € par maison, 115 € par hôtel.") { p, j -> p.travaux(j, 40, 115) },
        Carte("Héritage d'une grand-tante : 100 €.") { p, j -> p.encaisser(j, 100) },
        Carte("Retour à la case Départ.") { p, j -> p.aller(j, 0, true) },
        Carte("Tu offres la tournée générale : 30 € à chaque joueur.") { p, j -> p.tournee(j, 30) }
    )

    /** Le deplacement : il touche 200 € en passant par le Depart. */
    var surDeplacement: ((Joueur, Int) -> Unit)? = null

    fun aller(j: Joueur, cible: Int, versLAvant: Boolean) {
        val pas = if (versLAvant) (cible - j.pos + 40) % 40 else -((j.pos - cible + 40) % 40)
        avancerDe(j, pas)
    }

    fun avancerDe(j: Joueur, pas: Int) {
        val sens = if (pas >= 0) 1 else -1
        repeat(kotlin.math.abs(pas)) {
            j.pos = (j.pos + sens + 40) % 40
            if (sens > 0 && j.pos == 0) {
                j.argent += 200
                noter("${j.nom} passe par le Départ : +200 €.")
            }
        }
        surDeplacement?.invoke(j, pas)
    }

    fun tirerCarte(genre: String): Carte {
        val paquet = if (genre == "sort") sortPaquet else coffrePaquet
        if (paquet.isEmpty()) paquet.addAll(if (genre == "sort") coupsDuSort() else coffres())
        val carte = paquet.removeAt(0)
        paquet.add(carte)
        return carte
    }

    fun commencer(nombreJoueurs: Int, jeton: String) {
        joueurs.clear()
        joueurs.add(Joueur(0, "Toi", jeton, COULEURS[0], false))
        for (k in 1 until nombreJoueurs)
            joueurs.add(Joueur(k, NOMS_IA[k - 1], JETONS[k % JETONS.size], COULEURS[k], true))
        for (c in plateau) { c.proprio = -1; c.maisons = 0; c.hypothequee = false }
        courant = 0; phase = "attente"; doubles = 0
        journal.clear()
        sortPaquet.clear(); sortPaquet.addAll(coupsDuSort().shuffled())
        coffrePaquet.clear(); coffrePaquet.addAll(coffres().shuffled())
        noter("La partie commence.")
    }
}

/* ===================================================================== */
/*  Son adversaire : il achete, il construit, il negocie.                 */
/* ===================================================================== */

/** Son « valeurPour » : ce qu'une case vaut aux yeux d'un joueur. */
fun Partie.valeurPour(j: Joueur, c: Case): Int {
    if (c.type != "terrain") return Math.round(c.prix * 1.25f)
    val g = memeGroupe(c)
    val aMoi = g.count { it.proprio == j.i }
    if (aMoi == g.size - 1) return Math.round(c.prix * 3f)
    if (aMoi > 0) return Math.round(c.prix * 1.7f)
    return Math.round(c.prix * 1.15f)
}

/** Son « prixDemande ». Renvoie -1 quand le vendeur ne veut pas vendre. */
fun Partie.prixDemande(vendeur: Joueur, c: Case, acheteur: Joueur): Int {
    val g = if (c.type == "terrain") memeGroupe(c) else emptyList()
    if (c.type == "terrain" && g.count { it.proprio == vendeur.i } == g.size) return -1
    val donneGroupe = c.type == "terrain" && g.count { it.proprio == acheteur.i } == g.size - 1
    var seuil = c.prix * (if (donneGroupe) 2.3f else 1.25f)
    if (vendeur.argent < 250) seuil *= .75f
    return Math.round(seuil)
}

fun Partie.transferer(c: Case, vendeur: Joueur, acheteur: Joueur, montant: Int) {
    acheteur.argent -= montant
    vendeur.argent += montant
    c.proprio = acheteur.i
    surSon?.invoke("achat")
    noter("${acheteur.nom} rachète ${c.nom} à ${vendeur.nom} pour $montant €.")
}

/** Son « cibleInteressante » : la case qui lui completerait un groupe. */
fun Partie.cibleInteressante(j: Joueur): Case? {
    var meilleure: Case? = null
    var meilleurEcart = 0
    for (c in plateau) {
        if (c.type != "terrain" || c.proprio < 0 || c.proprio == j.i || c.maisons > 0) continue
        val v = joueurs.getOrNull(c.proprio) ?: continue
        if (v.ruine) continue
        val g = memeGroupe(c)
        if (g.count { it.proprio == j.i } != g.size - 1) continue
        val demande = prixDemande(v, c, j)
        if (demande < 0 || j.argent - demande < 150) continue
        val ecart = valeurPour(j, c) - demande
        if (meilleure == null || ecart > meilleurEcart) { meilleure = c; meilleurEcart = ecart }
    }
    return meilleure
}

/**
 * Son « decisionAchatIA » : il garde toujours une reserve, et prefere les
 * cases qui completent un groupe qu'il a deja entame.
 */
fun Partie.decisionAchatDeLIA(j: Joueur, c: Case, tours: Int): Boolean {
    val garde = if (tours < 20) 80 else 220
    val interessant = memeGroupe(c).any { it.proprio == j.i } ||
                      c.type != "terrain" || c.prix <= 200
    return j.argent - c.prix >= garde && (interessant || j.argent > 800)
}

fun Partie.acheter(j: Joueur, c: Case) {
    j.argent -= c.prix
    c.proprio = j.i
    surSon?.invoke("achat")
    noter("${j.nom} achète ${c.nom} pour ${c.prix} €.")
}

/**
 * Son « gererIA » : il construit tant qu'il peut, en gardant 250 €, et
 * toujours de facon egale sur le groupe.
 */
fun Partie.construireAvecLIA(j: Joueur) {
    while (true) {
        val candidats = biens(j).filter {
            it.type == "terrain" && groupeComplet(it) && !it.hypothequee && it.maisons < 5 &&
            memeGroupe(it).none { x -> x.hypothequee }
        }.sortedWith(compareBy({ it.maisons }, { -it.prix }))
        val c = candidats.firstOrNull() ?: break
        val prix = GROUPES[c.groupe]?.maison ?: break
        if (j.argent - prix < 250) break
        if (memeGroupe(c).any { it.maisons < c.maisons }) break
        j.argent -= prix
        c.maisons++
        noter("${j.nom} construit sur ${c.nom}.")
    }
}

/** Le joueur construit une maison, s'il en a le droit. */
fun Partie.construire(j: Joueur, c: Case): String {
    if (c.proprio != j.i || c.type != "terrain") return "Ce terrain n'est pas à toi."
    if (!groupeComplet(c)) return "Il te faut tout le groupe."
    if (c.hypothequee || memeGroupe(c).any { it.hypothequee }) return "Un terrain du groupe est hypothéqué."
    if (c.maisons >= 5) return "Il y a déjà un hôtel."
    if (memeGroupe(c).any { it.maisons < c.maisons }) return "Construis d'abord sur les autres."
    val prix = GROUPES[c.groupe]?.maison ?: return "Groupe inconnu."
    if (j.argent < prix) return "Pas assez d'argent."
    j.argent -= prix
    c.maisons++
    noter("${j.nom} construit sur ${c.nom}.")
    return ""
}

/** L'hypotheque, et son rachat a dix pour cent de plus. */
fun Partie.hypothequer(j: Joueur, c: Case): String {
    if (c.proprio != j.i) return "Ce bien n'est pas à toi."
    if (c.maisons > 0) return "Revends d'abord les maisons."
    if (c.hypothequee) {
        val prix = Math.round(c.prix / 2f * 1.1f)
        if (j.argent < prix) return "Pas assez pour lever l'hypothèque."
        j.argent -= prix
        c.hypothequee = false
        noter("${j.nom} lève l'hypothèque de ${c.nom}.")
    } else {
        c.hypothequee = true
        j.argent += c.prix / 2
        noter("${j.nom} hypothèque ${c.nom} pour ${c.prix / 2} €.")
    }
    return ""
}

/** La revente d'une maison, a moitie prix. */
fun Partie.revendre(j: Joueur, c: Case): String {
    if (c.proprio != j.i || c.maisons == 0) return "Rien à revendre ici."
    if (memeGroupe(c).any { it.maisons > c.maisons }) return "Revends d'abord sur les autres."
    c.maisons--
    j.argent += (GROUPES[c.groupe]?.maison ?: 0) / 2
    noter("${j.nom} revend une maison de ${c.nom}.")
    return ""
}
