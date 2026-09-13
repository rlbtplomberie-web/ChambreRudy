package com.rudy.chambre.balle

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Les regles de la balle au prisonnier, traduites du jeu de Rudy.
 *
 * Ce n'est pas une reecriture : chaque valeur, chaque duree et chaque condition
 * vient de son fichier. Les noms de ses fonctions sont conserves entre
 * parentheses pour qu'on s'y retrouve d'un fichier a l'autre.
 */
class Partie(var W: Float, var H: Float) {

    /** Un personnage (sa fonction « mk »). */
    class Joueur(
        val team: Int, var x: Float, var y: Float, val h: Boolean, val id: Int, val nom: String
    ) {
        var prison = false
        var vx = 0f; var vy = 0f
        var face = if (team == 1) Math.PI.toFloat() else 0f
        var cool = 0f
        var dodge = 0f
        var throwA = 0f
        var hitA = 0f
        var catchA = 0f
        var catchTry = 0f
        var passA = 0f
        var pickupA = 0f
        var fallA = 0f
        var riseA = 0f
        var pendingJail = false
        var run = (Math.random() * 6).toFloat()
        var energy = 0f
        var cpuCharge = 0f
        var zapT = 0f
        var readyPlayed = false
        var stepT = 0f
        var lookSide = 0
    }

    /** La balle. */
    class Balle {
        var x = 0f; var y = 0f; var z = 0f
        var vx = 0f; var vy = 0f; var vz = 0f
        var held: Joueur? = null
        var lastTeam: Int? = null
        var thrower: Joueur? = null
        var passTarget: Joueur? = null
        var deadBall = true
        var pickupOwner: Joueur? = null
        val r = 9f
        var charged = false
    }

    class Impact { var t = 0f; var x = 0f; var y = 0f; var p: Joueur? = null }

    val P = ArrayList<Joueur>()
    var B = Balle()
    val IMPACT = Impact()

    var T = 0f
    var over = false
    var countdown = 4.15f
    var etapeSonDecompte = -1
    var shotCharge = 0f
    var charging = false
    var resumeDelai = 0f
    var resumePorteur: Joueur? = null
    var chargePrete = false

    /** Le manche a balai : jx et jy, comme chez lui. */
    var jx = 0f
    var jy = 0f

    var msg = ""
    var note = ""
    private var noteT = 0f

    var surSon: ((String, Float) -> Unit)? = null
    var surTexteTir: ((String) -> Unit)? = null

    fun son(quoi: String, force: Float = 1f) { surSon?.invoke(quoi, force) }

    /** Son « say » : le petit mot qui s'affiche en haut. */
    fun dire(t: String) { note = t; noteT = 1.6f }

    /** Le petit mot s'efface tout seul. */
    fun majNote(dt: Float) {
        if (noteT > 0f) { noteT -= dt; if (noteT <= 0f) note = "" }
    }

    init { reset() }

    /** Sa mise en place (« reset »). */
    fun reset() {
        over = false; msg = ""
        P.clear()
        val noms = listOf("Rudy", "Sophie", "Mathis", "Shanna", "Théo", "Carlos")
        P.add(Joueur(0, W * .15f, H * .69f, true, 1, noms[0]))    // Rudy, milieu arriere
        P.add(Joueur(0, W * .28f, H * .56f, false, 2, noms[1]))   // Sophie, haut
        P.add(Joueur(0, W * .28f, H * .80f, false, 3, noms[2]))   // Mathis, bas
        P.add(Joueur(1, W * .72f, H * .56f, false, 1, noms[3]))   // Shanna, haut
        P.add(Joueur(1, W * .85f, H * .69f, false, 2, noms[4]))   // Theo, milieu arriere
        P.add(Joueur(1, W * .72f, H * .80f, false, 3, noms[5]))   // Carlos, bas

        B = Balle().apply { x = W / 2f; y = H * .66f }
        val starter = P[(Math.random() * P.size).toInt().coerceIn(0, P.size - 1)]
        B.held = starter; B.x = starter.x; B.y = starter.y; B.z = 15f
        starter.cool = .8f

        shotCharge = 0f; charging = false; resumeDelai = 0f; resumePorteur = null
        chargePrete = false
        countdown = 4.15f; etapeSonDecompte = -1
        IMPACT.t = 0f
        T = 0f
    }

    /** Ses limites de terrain (« bounds »). */
    fun bounds(p: Joueur) {
        val prisonEdgeL = W * .125f; val prisonEdgeR = W * .875f; val mid = W * .5f
        val top = H * .50f; val bottom = H * .84f
        if (p.prison) {
            if (p.team == 0) p.x = max(prisonEdgeR + 8f, min(W - 12f, p.x))
            else p.x = max(12f, min(prisonEdgeL - 8f, p.x))
        } else {
            if (p.team == 0) p.x = max(prisonEdgeL + 8f, min(mid - 8f, p.x))
            else p.x = max(mid + 8f, min(prisonEdgeR - 8f, p.x))
        }
        p.y = max(top, min(bottom, p.y))
    }

    fun enPrisonPlusTard(p: Joueur) {
        if (p.prison || p.pendingJail || p.hitA > 0f || p.fallA > 0f) return
        p.hitA = .35f; p.fallA = 0f; p.riseA = 0f; p.pendingJail = true
        p.vx = 0f; p.vy = 0f
        dire(if (p.h) "TOUCHÉ !" else "JOUEUR TOUCHÉ !")
    }

    fun enPrison(p: Joueur) {
        p.prison = true; p.pendingJail = false; p.fallA = 0f; p.riseA = .82f
        p.x = if (p.team == 1) W * .07f else W * .93f
        p.y = H * (.52f + .07f * P.count { it.team == p.team && it.prison })
        dire(if (p.h) "TU VAS EN PRISON !" else "JOUEUR EN PRISON !")
    }

    fun liberer(p: Joueur) {
        p.prison = false
        p.x = if (p.team == 1) W * .72f else W * .28f
        p.y = H * .66f; p.cool = .8f
        dire(if (p.h) "TU ES LIBÉRÉ !" else "PRISONNIER LIBÉRÉ !")
    }

    fun libererEquipe(team: Int) { for (q in P) if (q.team == team && q.prison) liberer(q) }

    /** Un tir charge qui manque envoie son auteur en prison (« chargedMiss »). */
    fun tirChargeRate() {
        if (!B.charged) return
        val shooter = B.thrower ?: return
        B.charged = false
        if (!shooter.prison && !shooter.pendingJail) {
            enPrisonPlusTard(shooter)
            dire(if (shooter.h) "TIR ENFLAMMÉ RATÉ : PRISON !" else "TIR CHARGÉ ENNEMI RATÉ : PRISON !")
        }
    }

    fun ciblesDe(p: Joueur) = P.filter { it.team != p.team && !it.prison }

    /** Le lancer (« launch »). */
    fun launch(p: Joueur, t: Joueur?, charge: Float = 0f) {
        if (B.held !== p || t == null || p.cool > 0f) return
        val dx = t.x - p.x; val dy = t.y - p.y
        val l = hypot(dx, dy).let { if (it == 0f) 1f else it }
        p.face = atan2(dy, dx); p.throwA = .38f
        p.cpuCharge = 0f
        B.held = null; B.lastTeam = p.team; B.thrower = p; B.deadBall = false; B.pickupOwner = null
        val canCharged = p.energy >= 100f && charge >= 3f
        B.charged = canCharged
        if (canCharged) p.energy = 0f else p.energy = min(100f, p.energy + 25f)
        p.readyPlayed = false
        B.x = p.x + dx / l * 24f; B.y = p.y + dy / l * 24f; B.z = 17f
        val power = if (B.charged) 820f else 650f
        B.vx = dx / l * power; B.vy = dy / l * power
        B.vz = if (B.charged) 155f else 130f
        p.cool = .7f
    }

    /** La passe (« passBall »). */
    fun passBall(p: Joueur) {
        if (B.held !== p) return
        val mates = P.filter { it !== p && it.team == p.team && !it.prison && !it.pendingJail }
            .sortedBy { hypot(it.x - p.x, it.y - p.y) }
        if (mates.isEmpty()) { dire("AUCUN COÉQUIPIER"); return }
        val t = mates[0]
        val hx = t.x + (if (t.team == 0) 18f else -18f); val hy = t.y - 48f
        val sx = p.x + (if (p.team == 0) 18f else -18f); val sy = p.y - 48f
        val dx = hx - sx; val dy = hy - sy
        val l = hypot(dx, dy).let { if (it == 0f) 1f else it }
        p.face = atan2(dy, dx); p.passA = .42f
        B.held = null; B.lastTeam = p.team; B.thrower = null
        B.passTarget = t; B.charged = false; B.deadBall = true; B.pickupOwner = null
        B.x = sx; B.y = sy; B.z = 20f
        B.vx = dx / l * 390f; B.vy = dy / l * 390f; B.vz = 35f
        p.cool = .35f
    }

    /** Un seul coequipier court apres la balle (« cpuMayChaseBall »). */
    fun peutCourirApresLaBalle(p: Joueur): Boolean {
        if (B.held != null || B.passTarget != null) return false
        var best: Joueur? = null; var bestD = Float.MAX_VALUE
        for (q in P) {
            if (q.h || q.prison || q.pendingJail || q.team != p.team) continue
            val dx = q.x - B.x; val dy = q.y - B.y; val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = q }
        }
        return best === p
    }

    fun me(): Joueur = P[0]
}

/* ===================================================================== */
/*  Sa boucle de jeu (« update »), traduite pas a pas.                    */
/* ===================================================================== */

fun Partie.update(dt: Float) {

    // ---- le decompte : les six joueurs restent en place jusqu'a GO ----
    if (countdown > 0f) {
        countdown = max(0f, countdown - dt)
        val elapsed = 4.15f - countdown
        val step = if (elapsed < 1f) 0 else if (elapsed < 2f) 1 else if (elapsed < 3f) 2 else 3
        if (step != etapeSonDecompte) {
            etapeSonDecompte = step
            if (step < 3) son("bip") else son("go")
        }
        for (p in P) { p.vx = 0f; p.vy = 0f; p.throwA = 0f; p.dodge = 0f; p.catchA = 0f; p.pickupA = 0f }
        B.held?.let { h -> B.x = h.x + cos(h.face) * 23f; B.y = h.y + sin(h.face) * 23f; B.z = 15f }
        return
    }

    // ---- la charge du tir, quand l'energie est pleine ----
    if (charging && B.held === P[0] && P[0].energy >= 100f) {
        shotCharge = min(3f, shotCharge + dt)
        surTexteTir?.invoke(if (shotCharge >= 3f) "FEU !" else "TIR " + Math.ceil(shotCharge.toDouble()).toInt() + "/3")
        P[0].zapT = max(0f, P[0].zapT - dt)
        if (P[0].zapT <= 0f) { son("zap"); P[0].zapT = max(.07f, .23f - shotCharge * .045f) }
        if (shotCharge >= 3f && !chargePrete) { son("pret"); chargePrete = true }
    } else {
        if (charging && B.held === P[0]) shotCharge = 0f
        chargePrete = false
        surTexteTir?.invoke("TIR")
    }

    // ---- la reprise apres une liberation ----
    if (resumeDelai > 0f) {
        resumeDelai = max(0f, resumeDelai - dt)
        resumePorteur?.let { r ->
            B.held = r; B.deadBall = true; B.lastTeam = null; B.thrower = null; B.charged = false
            B.vx = 0f; B.vy = 0f; B.vz = 0f
            r.cool = max(r.cool, resumeDelai)
        }
        if (resumeDelai > 0f) return
        resumePorteur?.let { r -> r.cool = 1f; B.held = r; resumePorteur = null; dire("REPRISE !") }
    }

    if (IMPACT.t > 0f) IMPACT.t = max(0f, IMPACT.t - dt)
    if (over) return
    T += dt
    majNote(dt)

    // ---- le heros, commande au doigt ----
    val me = P[0]
    if (me.dodge <= 0f) {
        val jm = min(1f, hypot(jx, jy))
        val js = if (jm < .08f) 0f else if (jm < .62f) 118f else 235f
        me.vx = (if (jm != 0f) jx / jm else 0f) * js
        me.vy = (if (jm != 0f) jy / jm else 0f) * js
    } else me.dodge -= dt
    me.x += me.vx * dt; me.y += me.vy * dt
    bounds(me)

    // ---- les cinq autres ----
    for (p in P) {
        if (!p.h && p.dodge > 0f) p.dodge = max(0f, p.dodge - dt)
        p.cool = max(0f, p.cool - dt)
        p.throwA = max(0f, p.throwA - dt)
        p.passA = max(0f, p.passA - dt)
        p.pickupA = max(0f, p.pickupA - dt)
        if (p.hitA > 0f) {
            p.hitA = max(0f, p.hitA - dt)
            if (p.hitA == 0f && p.pendingJail) enPrison(p)
        }
        p.catchTry = max(0f, p.catchTry - dt)
        if (B.held !== p) p.catchA = 0f else p.catchA = max(0f, p.catchA - dt)
        if (p.fallA > 0f) p.fallA = 0f
        if (p.riseA > 0f) p.riseA = max(0f, p.riseA - dt)
        val sp = hypot(p.vx, p.vy)
        p.run += dt * sp * .075f

        if (!p.h) {
            p.vx = 0f; p.vy = 0f

            // il esquive une balle rapide qui vient sur lui
            if (!p.prison && p.dodge <= 0f && B.held == null && B.lastTeam != null &&
                B.lastTeam != p.team && hypot(B.vx, B.vy) > 300f) {
                val dx = p.x - B.x; val dy = p.y - B.y; val d = hypot(dx, dy)
                if (d < 145f && (dx * B.vx + dy * B.vy) > 0f && Math.random() < dt * 4.5) {
                    p.dodge = .38f; p.vy = (if (p.y < B.y) -1f else 1f) * 220f
                }
            }

            if (B.held === p) {
                val ts = ciblesDe(p)
                if (ts.isNotEmpty() && p.cool <= 0f) {
                    val prisonniers = P.any { it.team == p.team && it.prison }
                    if (prisonniers && p.energy >= 100f) {
                        p.cpuCharge += dt
                        p.zapT = max(0f, p.zapT - dt)
                        if (p.zapT <= 0f) { son("zap"); p.zapT = max(.07f, .23f - p.cpuCharge * .045f) }
                        if (p.cpuCharge >= 3f && !p.readyPlayed) { son("pret"); p.readyPlayed = true }
                        if (p.cpuCharge >= 3f) {
                            launch(p, ts.minByOrNull { hypot(it.x - p.x, it.y - p.y) }, 3f)
                            p.cpuCharge = 0f
                        }
                    } else {
                        p.cpuCharge = 0f
                        launch(p, ts.minByOrNull { hypot(it.x - p.x, it.y - p.y) }, 0f)
                    }
                }
            } else if (B.held == null) {
                val d = hypot(B.x - p.x, B.y - p.y)
                val speed = hypot(B.vx, B.vy)
                if (peutCourirApresLaBalle(p) &&
                    (speed < 145f || B.lastTeam == p.team || B.lastTeam == null) && d < 270f) {
                    p.vx = (B.x - p.x) / max(1f, d) * 125f
                    p.vy = (B.y - p.y) / max(1f, d) * 125f
                } else if (B.lastTeam != p.team && d < 150f && !p.prison) {
                    p.vy = (if (p.y < B.y) -1f else 1f) * 170f
                }
                p.x += p.vx * dt; p.y += p.vy * dt
                bounds(p)
            }
        }
    }

    // ---- tout le monde regarde la balle ----
    val tx = B.held?.x ?: B.x
    for (p in P) {
        val side = if (tx < p.x) -1 else 1
        if (p.lookSide != side) { p.lookSide = side; p.face = if (side < 0) Math.PI.toFloat() else 0f }
    }

    // ---- la balle ----
    val porteur = B.held
    if (porteur != null) {
        B.deadBall = true; B.lastTeam = null; B.thrower = null; B.pickupOwner = null
        B.vx = 0f; B.vy = 0f; B.vz = 0f
        B.x = porteur.x + cos(porteur.face) * 23f
        B.y = porteur.y + sin(porteur.face) * 23f
        B.z = 15f
    } else {
        B.x += B.vx * dt; B.y += B.vy * dt; B.z += B.vz * dt
        B.vz -= 390f * dt
        if (B.z < 0f) {
            tirChargeRate()
            B.deadBall = true; B.lastTeam = null; B.thrower = null
            val impactV = abs(B.vz)
            B.z = 0f
            if (impactV > 42f) {
                son("rebond", impactV)
                B.vz = impactV * .38f; B.vx *= .76f; B.vy *= .76f
            } else { B.vz = 0f; B.vx *= .90f; B.vy *= .90f }
        }
        if (B.x < 5f || B.x > W - 5f) {
            tirChargeRate()
            B.deadBall = true; B.lastTeam = null; B.thrower = null; B.charged = false
            B.vx *= -.42f
            B.x = if (B.x < W / 2f) W * .145f else W * .855f
        }
        if (B.y < H * .45f || B.y > H * .88f) {
            tirChargeRate()
            B.deadBall = true; B.lastTeam = null; B.thrower = null; B.charged = false
            B.vy *= -.42f
            B.y = max(H * .515f, min(H * .825f, B.y))
        }
    }

    // ---- une balle arretee dans un coin revient vers le terrain ----
    if (B.held == null && B.deadBall && hypot(B.vx, B.vy) < 55f) {
        val gauche = W * .145f; val droite = W * .855f
        val haut = H * .515f; val bas = H * .825f
        if (B.x < gauche) B.x = min(gauche, B.x + 180f * dt)
        if (B.x > droite) B.x = max(droite, B.x - 180f * dt)
        if (B.y < haut) B.y = min(haut, B.y + 180f * dt)
        if (B.y > bas) B.y = max(bas, B.y - 180f * dt)
    }

    // ---- les pas : marche et course n'ont ni le meme son ni la meme cadence ----
    run {
        val p = P[0]; val ps = hypot(p.vx, p.vy)
        p.stepT = max(0f, p.stepT - dt)
        val auSol = p.hitA <= 0f && p.riseA <= 0f && p.dodge <= 0f
        if (auSol && ps > 18f && p.stepT <= 0f) {
            if (ps >= 150f) { son("course"); p.stepT = .22f } else { son("marche"); p.stepT = .36f }
        }
        if (ps <= 18f) p.stepT = 0f
    }

    // ---- les personnages ne se traversent pas ----
    for (i in P.indices) for (j in i + 1 until P.size) {
        val a = P[i]; val b = P[j]
        var dx = b.x - a.x; var dy = b.y - a.y
        val minD = 48f; val d2 = dx * dx + dy * dy
        if (d2 < minD * minD) {
            var d = sqrt(d2)
            if (d < .001f) { dx = 1f; dy = 0f; d = 1f }
            val push = (minD - d) / 2f; val nx = dx / d; val ny = dy / d
            a.x -= nx * push; a.y -= ny * push
            b.x += nx * push; b.y += ny * push
            bounds(a); bounds(b)
        }
    }

    // ---- la passe en vol rejoint les mains du coequipier ----
    B.passTarget?.let { t ->
        val hx = t.x + (if (t.team == 0) 18f else -18f); val hy = t.y - 48f
        val d = hypot(B.x - hx, B.y - hy)
        if (d > 2f) { B.vx += (hx - B.x) * dt * 9f; B.vy += (hy - B.y) * dt * 9f }
        if (d < 25f) {
            B.x = hx; B.y = hy; B.z = 20f; B.held = t; B.passTarget = null
            B.charged = false; B.deadBall = true; B.lastTeam = null; B.thrower = null
            B.vx = 0f; B.vy = 0f; B.vz = 0f
            t.catchA = .50f; t.cool = .22f
            son("attrape")
        }
    }

    // ---- touche, rattrape, ou ramasse ----
    for (p in P) {
        if (p.pendingJail || p.fallA > 0f || p.hitA > 0f || p.prison) continue
        val d = hypot(B.x - p.x, B.y - p.y)
        val s = hypot(B.vx, B.vy)
        // Elle ne touche que pendant le vol d'un vrai lancer : des qu'elle a
        // touche le sol ou un mur, elle devient inoffensive et ramassable.
        val dangereuse = B.thrower != null && B.lastTeam != null && !B.deadBall
        if (d < 24f && B.z < 34f) {
            if (dangereuse && s > 225f && B.lastTeam != p.team && p.dodge <= 0f) {
                if (p.h && p.catchTry > 0f) {
                    B.held = p; B.passTarget = null; B.charged = false; B.deadBall = true
                    B.lastTeam = null; B.thrower = null
                    B.vx = 0f; B.vy = 0f; B.vz = 0f
                    p.catchA = .50f; p.catchTry = 0f; p.cool = .18f
                    dire("RATTRAPÉ !")
                } else {
                    val tr = B.thrower
                    val etaitCharge = B.charged
                    IMPACT.t = .24f; IMPACT.x = B.x; IMPACT.y = B.y; IMPACT.p = p
                    enPrisonPlusTard(p)
                    son("touche")
                    B.passTarget = null; B.deadBall = true; B.lastTeam = null
                    B.thrower = null; B.charged = false
                    B.vx *= .18f; B.vy *= .18f; B.vz = -150f; B.z = max(B.z, 18f)
                    if (etaitCharge && tr != null) libererEquipe(tr.team)
                    if (tr != null && tr.prison) {
                        liberer(tr)
                        B.held = tr; B.vx = 0f; B.vy = 0f; B.vz = 0f; B.deadBall = true
                        resumePorteur = tr; resumeDelai = 1f; tr.cool = 1f
                        dire("LIBÉRÉ ! REPRISE DANS 1 SECONDE")
                    }
                }
                break
            } else if (s < 165f && p.cool <= 0f) {
                // une balle libre n'est ramassee que par un seul joueur
                if (B.pickupOwner != null && B.pickupOwner !== p) continue
                B.pickupOwner = p
                val auSol = B.z <= 8f || B.deadBall
                B.deadBall = true; B.lastTeam = null; B.thrower = null; B.passTarget = null
                B.vx = 0f; B.vy = 0f; B.vz = 0f
                B.held = p
                if (auSol) { p.pickupA = .48f; p.catchA = 0f } else p.catchA = .50f
                p.cool = .18f
                B.pickupOwner = null
                break
            }
        }
    }

    // ---- fin de partie ----
    val a = P.count { it.team == 0 && !it.prison }
    val b = P.count { it.team == 1 && !it.prison }
    if (a == 0 || b == 0) { over = true; msg = if (a != 0) "VICTOIRE !" else "DÉFAITE" }
}
