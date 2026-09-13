package com.rudy.chambre.balle

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Les regles de la balle au prisonnier, sans rien qui touche a l'affichage.
 *
 * Six joueurs, trois par equipe. Un joueur touche part en prison, dans la
 * prison adverse ; il revient s'il attrape une balle qui y traine. La partie
 * s'arrete quand une equipe n'a plus personne sur le terrain.
 */
class Jeu(var largeur: Float, var hauteur: Float) {

    class Joueur(
        val equipe: Int,
        val nom: String,
        val heros: Boolean,
        var x: Float,
        var y: Float
    ) {
        var vx = 0f; var vy = 0f
        var regard = 0f                  // vers ou il est tourne, en radians
        var prison = false
        var repos = 0f                   // temps avant de pouvoir agir
        var lancer = 0f                  // animations en cours
        var attrape = 0f
        var esquive = 0f
        var chute = 0f
        var releve = 0f
        var touche = 0f
        var energie = 0f                 // charge du tir
    }

    class Balle {
        var x = 0f; var y = 0f; var z = 0f
        var vx = 0f; var vy = 0f; var vz = 0f
        var porteur: Joueur? = null
        var lanceur: Joueur? = null
        var equipeLanceuse: Int? = null
        var morte = true                 // une balle morte ne touche personne
        var chargee = false
    }

    val joueurs = ArrayList<Joueur>()
    val balle = Balle()
    var decompte = Terrain.DECOMPTE
    var finie = false
    var message = ""
    var surMessage: ((String) -> Unit)? = null
    var surSon: ((String) -> Unit)? = null

    init { remettre() }

    /** Remet la partie a zero, avec la mise en place d'origine. */
    fun remettre() {
        joueurs.clear()
        for (d in Terrain.DEPARTS)
            joueurs.add(Joueur(d.equipe, d.nom, d.heros, d.x * largeur, d.y * hauteur))
        balle.apply {
            porteur = null; lanceur = null; equipeLanceuse = null
            morte = true; chargee = false
            vx = 0f; vy = 0f; vz = 0f
            x = largeur / 2f; y = hauteur * 0.66f; z = 0f
        }
        val premier = joueurs.random()
        balle.porteur = premier
        balle.x = premier.x; balle.y = premier.y; balle.z = 15f
        premier.repos = 0.8f
        decompte = Terrain.DECOMPTE
        finie = false
        dire(if (premier.heros) "TU COMMENCES AVEC LA BALLE" else "DÉPART ALÉATOIRE")
    }

    private fun dire(t: String) { message = t; surMessage?.invoke(t) }

    /** Le heros, celui que Rudy commande. */
    fun heros(): Joueur? = joueurs.firstOrNull { it.heros && !it.prison }

    /** Un joueur reste chez lui ; un prisonnier reste dans sa prison. */
    private fun limiter(p: Joueur) {
        val gauche = Terrain.BORD_PRISON_G * largeur
        val droite = Terrain.BORD_PRISON_D * largeur
        val milieu = Terrain.MILIEU * largeur
        p.x = if (p.prison) {
            if (p.equipe == 0) p.x.coerceIn(droite + 8f, largeur - 12f)
            else p.x.coerceIn(12f, gauche - 8f)
        } else {
            if (p.equipe == 0) p.x.coerceIn(gauche + 8f, milieu - 8f)
            else p.x.coerceIn(milieu + 8f, droite - 8f)
        }
        p.y = p.y.coerceIn(Terrain.HAUT * hauteur, Terrain.BAS * hauteur)
    }

    /** Un pas de jeu. [dt] est le temps ecoule, en secondes. */
    fun avancer(dt: Float) {
        if (finie) return
        if (decompte > 0f) { decompte -= dt; return }

        for (p in joueurs) {
            p.repos = (p.repos - dt).coerceAtLeast(0f)
            p.lancer = (p.lancer - dt).coerceAtLeast(0f)
            p.attrape = (p.attrape - dt).coerceAtLeast(0f)
            p.esquive = (p.esquive - dt).coerceAtLeast(0f)
            p.chute = (p.chute - dt).coerceAtLeast(0f)
            p.releve = (p.releve - dt).coerceAtLeast(0f)
            p.touche = (p.touche - dt).coerceAtLeast(0f)
            if (p.chute <= 0f && p.touche <= 0f) {
                p.x += p.vx * dt
                p.y += p.vy * dt
            }
            limiter(p)
        }

        avancerBalle(dt)
        cerveauxAdverses(dt)
        verifierFin()
    }

    private fun avancerBalle(dt: Float) {
        val porteur = balle.porteur
        if (porteur != null) {
            balle.morte = true
            balle.equipeLanceuse = null; balle.lanceur = null
            balle.vx = 0f; balle.vy = 0f; balle.vz = 0f
            balle.x = porteur.x + cos(porteur.regard) * 23f
            balle.y = porteur.y + sin(porteur.regard) * 23f
            balle.z = 15f
            return
        }

        balle.x += balle.vx * dt
        balle.y += balle.vy * dt
        balle.z += balle.vz * dt
        balle.vz -= Terrain.PESANTEUR * dt

        if (balle.z < 0f) {
            balle.morte = true
            balle.equipeLanceuse = null; balle.lanceur = null
            val choc = kotlin.math.abs(balle.vz)
            balle.z = 0f
            if (choc > 42f) {
                surSon?.invoke("rebond")
                balle.vz = choc * Terrain.REBOND
                balle.vx *= Terrain.FROTTEMENT_FORT
                balle.vy *= Terrain.FROTTEMENT_FORT
            } else {
                balle.vz = 0f
                balle.vx *= Terrain.FROTTEMENT_DOUX
                balle.vy *= Terrain.FROTTEMENT_DOUX
            }
        }

        // la balle sort : elle revient dans le terrain
        if (balle.x < 6f || balle.x > largeur - 6f) balle.vx = -balle.vx * 0.6f
        if (balle.y < Terrain.HAUT * hauteur - 20f || balle.y > Terrain.BAS * hauteur + 20f)
            balle.vy = -balle.vy * 0.6f

        toucher()
        ramasser()
    }

    /** Une balle vivante qui atteint un adversaire l'envoie en prison. */
    private fun toucher() {
        if (balle.morte) return
        val equipe = balle.equipeLanceuse ?: return
        if (balle.z > 55f) return
        for (p in joueurs) {
            if (p.equipe == equipe || p.prison) continue
            if (p.esquive > 0f) continue
            if (hypot(p.x - balle.x, p.y - balle.y) < 26f) {
                p.prison = true
                p.touche = Terrain.DUREE_TOUCHE
                p.chute = Terrain.DUREE_CHUTE
                p.x = if (p.equipe == 0) Terrain.BORD_PRISON_D * largeur + 30f
                      else Terrain.BORD_PRISON_G * largeur - 30f
                balle.morte = true
                balle.equipeLanceuse = null
                surSon?.invoke("touche")
                dire(p.nom + " EN PRISON")
                return
            }
        }
    }

    /** Une balle au sol se ramasse. */
    private fun ramasser() {
        if (balle.porteur != null) return
        if (balle.z > 30f) return
        for (p in joueurs) {
            if (p.repos > 0f || p.chute > 0f) continue
            if (hypot(p.x - balle.x, p.y - balle.y) < 28f) {
                balle.porteur = p
                p.attrape = Terrain.DUREE_ATTRAPE
                p.repos = 0.18f
                balle.chargee = false
                surSon?.invoke("attrape")
                if (p.prison) {                       // un prisonnier delivre
                    p.prison = false
                    p.x = if (p.equipe == 0) Terrain.MILIEU * largeur - 40f
                          else Terrain.MILIEU * largeur + 40f
                    dire(p.nom + " EST LIBÉRÉ !")
                }
                return
            }
        }
    }

    /** Lance la balle vers un point. [charge] donne un tir plus fort. */
    fun lancer(p: Joueur, versX: Float, versY: Float, charge: Boolean) {
        if (balle.porteur !== p || p.repos > 0f) return
        val dx = versX - p.x; val dy = versY - p.y
        val l = hypot(dx, dy).coerceAtLeast(1f)
        val puissance = if (charge) 760f else 560f
        balle.porteur = null
        balle.lanceur = p
        balle.equipeLanceuse = p.equipe
        balle.morte = false
        balle.chargee = charge
        balle.vx = dx / l * puissance
        balle.vy = dy / l * puissance
        balle.vz = if (charge) Terrain.TIR_Z_CHARGE else Terrain.TIR_Z
        p.lancer = Terrain.DUREE_LANCER
        p.repos = 0.7f
        p.regard = kotlin.math.atan2(dy, dx)
        surSon?.invoke(if (charge) "tir_charge" else "tir")
    }

    /** Une passe a un coequipier : rapide et rasante. */
    fun passer(p: Joueur) {
        if (balle.porteur !== p || p.repos > 0f) return
        val ami = joueurs.filter { it !== p && it.equipe == p.equipe && !it.prison }
            .minByOrNull { hypot(it.x - p.x, it.y - p.y) } ?: return
        val dx = ami.x - p.x; val dy = ami.y - p.y
        val l = hypot(dx, dy).coerceAtLeast(1f)
        balle.porteur = null
        balle.lanceur = p
        balle.equipeLanceuse = null                 // une passe ne touche personne
        balle.morte = true
        balle.vx = dx / l * Terrain.PASSE_VITESSE
        balle.vy = dy / l * Terrain.PASSE_VITESSE
        balle.vz = Terrain.PASSE_Z
        p.repos = 0.35f
        surSon?.invoke("passe")
    }

    fun esquiver(p: Joueur) {
        if (p.repos > 0f || p.esquive > 0f) return
        p.esquive = Terrain.DUREE_ESQUIVE
        p.repos = 0.30f
        surSon?.invoke("esquive")
    }

    /** Les cinq autres joueurs se debrouillent seuls. */
    private fun cerveauxAdverses(dt: Float) {
        for (p in joueurs) {
            if (p.heros || p.chute > 0f || p.touche > 0f) continue
            val porteur = balle.porteur

            if (porteur === p) {
                // il vise l'adversaire le plus proche, apres un court temps de visee
                p.energie += dt
                if (p.energie > 0.7f) {
                    val cible = joueurs.filter { it.equipe != p.equipe && !it.prison }
                        .minByOrNull { hypot(it.x - p.x, it.y - p.y) }
                    if (cible != null) { lancer(p, cible.x, cible.y, p.energie > 1.4f); p.energie = 0f }
                }
                p.vx = 0f; p.vy = 0f
                continue
            }
            p.energie = 0f

            // sans la balle : il la suit si elle traine, sinon il se replace
            val versBalle = balle.porteur == null && !balle.morte.not()
            val cx: Float; val cy: Float
            if (balle.porteur == null) { cx = balle.x; cy = balle.y }
            else { cx = p.x; cy = (Terrain.HAUT + Terrain.BAS) / 2f * hauteur }

            val dx = cx - p.x; val dy = cy - p.y
            val l = hypot(dx, dy)
            val vitesse = if (l > 40f) 165f else 0f
            if (l > 1f) { p.vx = dx / l * vitesse; p.vy = dy / l * vitesse }
            else { p.vx = 0f; p.vy = 0f }
            if (l > 1f) p.regard = kotlin.math.atan2(dy, dx)

            // il esquive si une balle vivante fonce sur lui
            if (!balle.morte && balle.equipeLanceuse != null && balle.equipeLanceuse != p.equipe
                && hypot(balle.x - p.x, balle.y - p.y) < 120f && p.esquive <= 0f
                && Math.random() < 0.02) esquiver(p)
        }
    }

    private fun verifierFin() {
        val bleus = joueurs.count { it.equipe == 0 && !it.prison }
        val rouges = joueurs.count { it.equipe == 1 && !it.prison }
        if (bleus == 0 || rouges == 0) {
            finie = true
            dire(if (rouges == 0) "VICTOIRE !" else "DÉFAITE")
        }
    }

    fun surTerrain(equipe: Int) = joueurs.count { it.equipe == equipe && !it.prison }
}
