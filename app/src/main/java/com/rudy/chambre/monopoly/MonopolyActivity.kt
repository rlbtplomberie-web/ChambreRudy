package com.rudy.chambre.monopoly

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * L'ecran du Monopoly : le plateau, les fiches des joueurs, le journal, les
 * des et les boutons — comme dans sa page.
 */
class MonopolyActivity : ComponentActivity() {

    private val partie = Partie()
    private lateinit var vue: VueMonopoly
    private lateinit var fiches: TextView
    private lateinit var journal: TextView
    private lateinit var desTexte: TextView
    private lateinit var boutons: LinearLayout
    private var son: SonMonopoly? = null
    private var tours = 0

    override fun onCreate(e: Bundle?) {
        super.onCreate(e)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        son = SonMonopoly()
        partie.surSon = { quoi -> son?.jouer(quoi) }
        partie.surDeplacement = { _, _ -> son?.jouer("pas") }

        vue = VueMonopoly(this, partie)
        vue.surCase = { i -> ficheDeLaCase(i) }

        fiches = TextView(this).apply { textSize = 12f; setTextColor(0xFFF3E2B8.toInt()) }
        journal = TextView(this).apply {
            textSize = 11f; setTextColor(0xFFCFC7B7.toInt()); maxLines = 6
        }
        desTexte = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE) }
        boutons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val cote = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(0xCC0F1A14.toInt())
            addView(desTexte)
            addView(fiches)
            addView(boutons)
            addView(journal)
        }

        val racine = FrameLayout(this)
        racine.setBackgroundColor(0xFF13210F.toInt())
        racine.addView(vue, FrameLayout.LayoutParams(-1, -1))
        racine.addView(cote, FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * .26f).toInt(), -1, Gravity.END))
        racine.addView(Button(this).apply {
            text = "← Bureau"; textSize = 12f
            setTextColor(Color.WHITE); setBackgroundColor(0xCC150F24.toInt())
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
            .apply { topMargin = 18; leftMargin = 18 })
        setContentView(racine)
        com.rudy.chambre.Ambiance.adoucirLaMusique()

        choisirLaPartie()
    }

    /** Sa fenetre de depart : combien de joueurs, et quel jeton. */
    private fun choisirLaPartie() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Combien de joueurs ?")
            .setItems(arrayOf("2 joueurs", "3 joueurs", "4 joueurs")) { _, i ->
                val nombre = i + 2
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Choisis ton jeton")
                    .setItems(JETONS.toTypedArray()) { _, k ->
                        partie.commencer(nombre, JETONS[k])
                        tours = 0
                        rafraichir()
                    }
                    .setCancelable(false)
                    .show()
            }
            .setCancelable(false)
            .show()
    }

    // ================= le tour =================

    private fun lancerLesDes() {
        if (partie.phase != "attente") return
        val j = partie.joueurs[partie.courant]
        partie.des = intArrayOf(1 + (Math.random() * 6).toInt(), 1 + (Math.random() * 6).toInt())
        partie.phase = "roule"
        son?.jouer("des")
        desTexte.text = "🎲 ${partie.des[0]} et ${partie.des[1]}"
        rafraichir()

        vue.postDelayed({
            val total = partie.des[0] + partie.des[1]
            val pareils = partie.des[0] == partie.des[1]

            if (j.prison > 0) {
                if (pareils) { j.prison = 0; partie.noter("${j.nom} fait un double et sort de prison.") }
                else {
                    j.prison--
                    if (j.prison == 0) {
                        partie.noter("${j.nom} paie 50 € et sort de prison.")
                        partie.payer(j, 50, null)
                    } else {
                        partie.noter("${j.nom} reste en prison (${j.prison} essais).")
                        rafraichir(); vue.postDelayed({ finDuTour() }, 500); return@postDelayed
                    }
                }
            } else if (pareils) {
                partie.doubles++
                if (partie.doubles == 3) {
                    partie.noter("Trois doubles d'affilée…")
                    partie.enPrison(j); rafraichir()
                    vue.postDelayed({ finDuTour() }, 500); return@postDelayed
                }
            } else partie.doubles = 0

            partie.noter("${j.nom} fait ${partie.des[0]} et ${partie.des[1]} ($total).")
            partie.avancerDe(j, total)
            rafraichir()
            vue.postDelayed({ resoudre(j) }, 250)
        }, 650)
    }

    /** Son « resoudre » : ce qui arrive selon la case. */
    private fun resoudre(j: Joueur) {
        val c = partie.plateau[j.pos]
        when {
            c.type == "depart" -> finOuSuite(j)
            c.type == "taxe" -> {
                partie.noter("${j.nom} règle ${c.nom} : ${c.montant} €.")
                partie.payer(j, c.montant, null)
                rafraichir(); vue.postDelayed({ finOuSuite(j) }, 400)
            }
            c.type == "allez" -> { partie.enPrison(j); rafraichir(); vue.postDelayed({ finDuTour() }, 500) }
            c.type == "prison" || c.type == "parc" -> { partie.noter("${j.nom} souffle un peu."); finOuSuite(j) }
            c.type == "sort" || c.type == "coffre" -> {
                val carte = partie.tirerCarte(if (c.type == "sort") "sort" else "coffre")
                val titre = if (c.type == "sort") "Coup du sort" else "Coffre de la ville"
                partie.noter("$titre — ${carte.texte}")
                rafraichir()
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(titre).setMessage(carte.texte)
                    .setPositiveButton("D'accord") { _, _ ->
                        val avant = j.pos
                        carte.effet(partie, j)
                        rafraichir()
                        if (j.pos != avant && j.prison == 0) vue.postDelayed({ resoudre(j) }, 300)
                        else if (j.prison > 0) vue.postDelayed({ finDuTour() }, 300)
                        else finOuSuite(j)
                    }
                    .setCancelable(false).show()
            }
            c.proprio < 0 -> {
                if (j.ia) {
                    if (partie.decisionAchatDeLIA(j, c, tours)) partie.acheter(j, c)
                    else partie.noter("${j.nom} laisse ${c.nom}.")
                    rafraichir(); vue.postDelayed({ finOuSuite(j) }, 600)
                } else { partie.phase = "achat"; rafraichir() }
            }
            c.proprio == j.i -> { partie.noter("${j.nom} est chez lui."); finOuSuite(j) }
            else -> {
                val pro = partie.joueurs[c.proprio]
                if (pro.ruine || c.hypothequee) {
                    partie.noter("${c.nom} est hypothéqué : rien à payer."); finOuSuite(j)
                } else {
                    val du = partie.loyer(c, partie.des[0] + partie.des[1])
                    partie.noter("${j.nom} doit $du € à ${pro.nom} pour ${c.nom}.")
                    partie.payer(j, du, pro)
                    rafraichir(); vue.postDelayed({ finOuSuite(j) }, 600)
                }
            }
        }
    }

    /** Son « finOuSuite » : on rejoue sur un double, sinon on passe la main. */
    private fun finOuSuite(j: Joueur) {
        if (j.ruine) { finDuTour(); return }
        if (partie.des[0] == partie.des[1] && j.prison == 0) {
            partie.phase = "attente"
            partie.noter("${j.nom} rejoue.")
            rafraichir()
            if (j.ia) vue.postDelayed({ lancerLesDes() }, 800)
            return
        }
        if (j.ia) {
            vue.postDelayed({
                partie.construireAvecLIA(j); rafraichir()
                vue.postDelayed({ finDuTour() }, 500)
            }, 600)
            return
        }
        partie.phase = "fin"
        rafraichir()
    }

    private fun finDuTour() {
        partie.doubles = 0
        if (partie.vivants().size <= 1) {
            val g = partie.vivants().firstOrNull()
            partie.phase = "finie"
            partie.noter("Partie terminée. " + (g?.let { "${it.nom} rafle tout." } ?: "Match nul."))
            rafraichir()
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Partie terminée")
                .setMessage(g?.let { "${it.nom} rafle tout." } ?: "Match nul.")
                .setPositiveButton("Rejouer") { _, _ -> choisirLaPartie() }
                .setNegativeButton("Retour au bureau") { _, _ -> finish() }
                .setCancelable(false).show()
            return
        }
        do { partie.courant = (partie.courant + 1) % partie.joueurs.size }
        while (partie.joueurs[partie.courant].ruine)
        tours++
        partie.phase = "attente"
        rafraichir()
        val suivant = partie.joueurs[partie.courant]
        if (suivant.ia) vue.postDelayed({ tourDeLIA(suivant) }, 800)
    }

    /** Son « tourIA » : il sort de prison s'il a une carte, negocie, puis lance. */
    private fun tourDeLIA(j: Joueur) {
        if (j.ruine || partie.phase == "finie") return
        if (j.prison > 0 && j.sorties > 0 && tours < 40) {
            j.sorties--; j.prison = 0
            partie.noter("${j.nom} utilise sa carte de sortie de prison.")
        }
        negocierPuisLancer(j)
    }

    private fun negocierPuisLancer(j: Joueur) {
        if (Math.random() > .4) { lancerLesDes(); return }
        val c = partie.cibleInteressante(j)
        if (c == null) { lancerLesDes(); return }
        val vendeur = partie.joueurs[c.proprio]
        val montant = minOf(Math.round(partie.prixDemande(vendeur, c, j) * 1.08f), j.argent - 120)
        if (montant < 10) { lancerLesDes(); return }

        if (vendeur.ia) {
            if (montant >= partie.prixDemande(vendeur, c, j)) partie.transferer(c, vendeur, j, montant)
            else partie.noter("${vendeur.nom} refuse de céder ${c.nom}.")
            rafraichir(); lancerLesDes(); return
        }
        // il te propose : a toi de decider
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("${j.nom} te propose un marché")
            .setMessage("Il t'offre $montant € pour ${c.nom}.")
            .setPositiveButton("J'accepte") { _, _ ->
                partie.transferer(c, vendeur, j, montant); rafraichir(); lancerLesDes()
            }
            .setNegativeButton("Je refuse") { _, _ ->
                partie.noter("Tu refuses de céder ${c.nom}."); rafraichir(); lancerLesDes()
            }
            .setCancelable(false).show()
    }

    // ================= l'affichage =================

    private fun rafraichir() {
        vue.invalidate()
        fiches.text = partie.joueurs.joinToString("\n") { j ->
            val marque = if (j.i == partie.courant) "▶ " else "   "
            val etat = when {
                j.ruine -> " (ruiné)"
                j.prison > 0 -> " (prison)"
                else -> ""
            }
            "$marque${j.nom} — ${j.argent} €$etat"
        }
        journal.text = partie.journal.takeLast(6).joinToString("\n")
        rendreBoutons()
    }

    /** Ses boutons : ils changent selon la phase. */
    private fun rendreBoutons() {
        boutons.removeAllViews()
        val j = partie.joueurs.getOrNull(partie.courant) ?: return
        fun bouton(titre: String, action: () -> Unit) {
            boutons.addView(Button(this).apply {
                text = titre; textSize = 12f
                setTextColor(Color.WHITE); setBackgroundColor(0xAA2E5C3A.toInt())
                setOnClickListener { action() }
            })
        }
        if (j.ia) return
        when (partie.phase) {
            "attente" -> bouton("Lancer les dés") { lancerLesDes() }
            "achat" -> {
                val c = partie.plateau[j.pos]
                bouton("Acheter ${c.nom} (${c.prix} €)") {
                    if (j.argent >= c.prix) { partie.acheter(j, c); rafraichir(); finOuSuite(j) }
                    else { partie.noter("Pas assez d'argent."); rafraichir() }
                }
                bouton("Laisser") { partie.noter("${j.nom} laisse ${c.nom}."); rafraichir(); finOuSuite(j) }
            }
            "fin" -> {
                bouton("Gérer mes biens") { gererMesBiens(j) }
                bouton("Terminer le tour") { finDuTour() }
            }
        }
    }

    /** Sa fenetre de gestion : construire, revendre, hypothequer. */
    private fun gererMesBiens(j: Joueur) {
        val mes = partie.biens(j)
        if (mes.isEmpty()) { partie.noter("Tu ne possèdes rien pour l'instant."); rafraichir(); return }
        val libelles = mes.map { c ->
            val maisons = when (c.maisons) { 0 -> ""; 5 -> " · hôtel"; else -> " · ${c.maisons} maison(s)" }
            c.nom + maisons + (if (c.hypothequee) " · hypothéqué" else "")
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Mes biens")
            .setItems(libelles.toTypedArray()) { _, i -> actionsSurUnBien(j, mes[i]) }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun actionsSurUnBien(j: Joueur, c: Case) {
        val choix = arrayOf("Construire", "Revendre une maison",
                            if (c.hypothequee) "Lever l'hypothèque" else "Hypothéquer")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(c.nom)
            .setItems(choix) { _, i ->
                val souci = when (i) {
                    0 -> partie.construire(j, c)
                    1 -> partie.revendre(j, c)
                    else -> partie.hypothequer(j, c)
                }
                if (souci.isNotEmpty()) partie.noter(souci)
                rafraichir()
            }
            .setNegativeButton("Retour", null)
            .show()
    }

    /** Son « fenetreCase » : le titre de propriete quand on touche une case. */
    private fun ficheDeLaCase(i: Int) {
        val c = partie.plateau[i]
        val lignes = StringBuilder()
        if (c.prix > 0) lignes.append("Prix : ${c.prix} €\n")
        if (c.type == "terrain") {
            lignes.append("Groupe : ${c.groupe}\n")
            lignes.append("Maison : ${GROUPES[c.groupe]?.maison ?: 0} €\n\n")
            val titres = listOf("Terrain nu", "1 maison", "2 maisons", "3 maisons", "4 maisons", "Hôtel")
            c.loyers.forEachIndexed { k, l -> lignes.append("${titres[k]} : $l €\n") }
        }
        if (c.type == "gare") lignes.append("\n1 gare : 25 €\n2 gares : 50 €\n3 gares : 100 €\n4 gares : 200 €\n")
        if (c.type == "service") lignes.append("\n1 service : 4 × les dés\n2 services : 10 × les dés\n")
        val pro = if (c.proprio >= 0) partie.joueurs[c.proprio].nom else "personne"
        lignes.append("\nPropriétaire : $pro")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(c.nom).setMessage(lignes.toString())
            .setPositiveButton("Fermer", null).show()
    }

    override fun onDestroy() { com.rudy.chambre.Ambiance.rendreLaMusique(); son?.liberer(); super.onDestroy() }
}
