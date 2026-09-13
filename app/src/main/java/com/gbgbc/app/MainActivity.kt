package com.gbgbc.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import com.gbgbc.app.core.Coeur
import java.io.File
import java.util.zip.ZipInputStream

/**
 * L'application.
 *
 * Elle assemble le coeur Gambatte et l'habillage qui dessine la console.
 * L'emulation tourne sur son propre fil, cadence par une horloge : sans
 * cette regulation, le jeu irait a la vitesse de l'ecran du telephone.
 */
class MainActivity : ComponentActivity() {

    private lateinit var coeur: Coeur
    private lateinit var vue: SkinView
    private lateinit var racine: FrameLayout
    private lateinit var barre: LinearLayout
    /** Panneau affiche pendant MODIFIER : l'ecran, et l'opacite des touches. */
    private lateinit var panneauModif: LinearLayout
    /** La ligne du curseur, montree seulement sur l'habillage transparent. */
    private lateinit var ligneOpacite: LinearLayout

    private val prefs by lazy {
        getSharedPreferences("gbgbc", Context.MODE_PRIVATE)
    }

    private var jeux: List<Bibliotheque.Jeu> = emptyList()
    private var nomJeu: String? = null
    private var fil: Thread? = null
    /*
     * Besognes a faire executer PAR LE FIL d'emulation.
     *
     * Sauver ou restaurer un etat traverse toute la memoire du coeur. Le
     * faire depuis l'interface pendant que le fil calcule une image donnerait
     * une sauvegarde prise a cheval sur deux instants — illisible, ou pire,
     * lisible mais fausse. On depose donc la besogne ici, et le fil s'en
     * charge entre deux images.
     */
    private val besognes = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()

    @Volatile private var tourne = false
    @Volatile private var boutons = 0
    @Volatile private var avanceRapide = false
    private var son: Son? = null

    private val choisirDossier =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Bibliotheque.poserDossier(this, uri)
            ouvrirListe()
        }

    /**
     * Un jeu designe par la chambre.
     *
     * Elle passe l'adresse du fichier dans l'intention qui ouvre cet ecran :
     * on le charge alors directement, sans passer par le catalogue.
     */
    private fun nomDe(u: android.net.Uri): String =
        try { androidx.documentfile.provider.DocumentFile.fromSingleUri(this, u)?.name
              ?: u.lastPathSegment ?: "jeu" } catch (_: Throwable) { "jeu" }

    private fun jeuDemandeParLaChambre() {
        val brut = intent?.getStringExtra("rom") ?: return
        noter("ROM recue : " + brut.takeLast(60))
        val u = try { android.net.Uri.parse(brut) } catch (_: Throwable) { return }
        try { contentResolver.takePersistableUriPermission(
                u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
        vue.postDelayed({ try { charger(u, nomDe(u)) } catch (_: Throwable) {} }, 400)
    }

    private fun noter(texte: String) {
        try {
            val d = File(filesDir, "systeme").apply { mkdirs() }
            File(d, "journal_appli.txt").appendText(texte + "\n")
        } catch (_: Throwable) {}
    }

    // ================= plein ecran =================

    private fun bordABord() {
        try {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            val c = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat
                .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } catch (_: Throwable) {}
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        } catch (_: Throwable) {}
    }

    override fun onWindowFocusChanged(focus: Boolean) {
        super.onWindowFocusChanged(focus)
        if (focus) bordABord()
    }

    // ================= barre =================

    private val replier = Runnable {
        if (::barre.isInitialized) barre.visibility = View.GONE
    }

    private fun basculerBarre() {
        if (!::barre.isInitialized) return
        barre.removeCallbacks(replier)
        if (barre.visibility == View.VISIBLE) barre.visibility = View.GONE
        else {
            barre.visibility = View.VISIBLE
            barre.postDelayed(replier, 6000L)
        }
    }

    private fun bouton(texte: String, action: () -> Unit): Button {
        val d = resources.displayMetrics.density
        return Button(this).apply {
            text = texte
            textSize = 12f
            isAllCaps = false
            setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
            setOnClickListener {
                if (::barre.isInitialized) {
                    barre.removeCallbacks(replier)
                    barre.postDelayed(replier, 6000L)
                }
                action()
            }
        }
    }

    /**
     * Le mode MODIFIER : deplacer et redimensionner les touches.
     *
     * On y entre par le menu, on en sort de meme. Les changements sont gardes
     * pour l'habillage en cours, et n'affectent pas les autres.
     */
    private fun basculerModifier() {
        vue.modifier = !vue.modifier
        majPanneauModif()
        if (vue.modifier) {
            Toast.makeText(this,
                "Un doigt déplace, deux doigts redimensionnent",
                Toast.LENGTH_LONG).show()
            barre.visibility = View.VISIBLE
            barre.removeCallbacks(replier)
        } else {
            Toast.makeText(this, "Disposition enregistrée", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Le panneau ne sert que pendant MODIFIER. Le curseur d'opacite, lui, ne
     * concerne que l'habillage transparent : ailleurs, on le retire.
     */
    private fun majPanneauModif() {
        if (!::panneauModif.isInitialized) return
        panneauModif.visibility = if (vue.modifier) View.VISIBLE else View.GONE
        ligneOpacite.visibility =
            if (vue.habillageCourant == Dispositions.TRANSPARENT) View.VISIBLE else View.GONE
    }

    /**
     * Le panneau de MODIFIER.
     *
     * L'ecran de jeu se deplace et s'etire au doigt comme les touches, mais
     * deux boutons evitent d'y passer du temps : l'un le fait couvrir toute
     * la vue, l'autre lui rend la dalle dessinee sur l'habillage.
     */
    private fun construirePanneauModif(): LinearLayout {
        val d = resources.displayMetrics.density
        val panneau = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xDD101018.toInt())
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        }

        val rangee = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rangee.addView(bouton("Écran plein") {
            vue.ecranPleinePage()
            Toast.makeText(this, "L'écran couvre toute la vue", Toast.LENGTH_SHORT).show()
        })
        rangee.addView(bouton("Écran d'origine") {
            vue.ecranOrigine()
            Toast.makeText(this, "Écran rendu à sa dalle", Toast.LENGTH_SHORT).show()
        })
        panneau.addView(rangee)

        ligneOpacite = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        ligneOpacite.addView(TextView(this).apply {
            text = "Touches : discrètes ← → bien visibles"
            textSize = 12f
            setTextColor(Color.WHITE)
        })
        ligneOpacite.addView(SeekBar(this).apply {
            max = 100
            progress = (((vue.opaciteBoutons - 0.12f) / 0.88f) * 100f).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, parUtilisateur: Boolean) {
                    vue.opaciteBoutons = 0.12f + p / 100f * 0.88f
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    Dispositions.poserOpacite(this@MainActivity, vue.opaciteBoutons)
                }
            })
        }, LinearLayout.LayoutParams((260 * d).toInt(), -2).apply { topMargin = (4 * d).toInt() })
        panneau.addView(ligneOpacite)
        return panneau
    }

    // ================= liste des jeux =================

    private fun ouvrirListe() {
        if (Bibliotheque.dossier(this) == null) { choisirDossier.launch(null); return }
        jeux = Bibliotheque.lister(this)
        vue.listeJeux = jeux.map { it.nom }
        vue.listeVisible = true
        if (jeux.isEmpty())
            Toast.makeText(this, "Aucun jeu dans ce dossier — bouton Dossier",
                           Toast.LENGTH_LONG).show()
    }

    // ================= chargement =================

    private fun charger(uri: Uri, nom: String) {
        Thread {
            var cible: File? = null
            var erreur: String? = null
            try {
                cible = if (Bibliotheque.estArchive(nom)) extraire(uri, nom)
                        else Bibliotheque.cheminReel(this, uri) ?: copier(uri, nom)
                if (cible == null) erreur = "Impossible de lire ce fichier"
            } catch (e: Throwable) {
                erreur = "Préparation impossible : " + e.message
            }

            val f = cible
            if (f == null) {
                runOnUiThread {
                    Toast.makeText(this, erreur ?: "Échec", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }

            arreterFil()
            coeur.decharger()
            appliquerReglages()
            val ok = coeur.chargerJeu(f)
            if (ok) {
                nomJeu = nom
                /* Les triches sont posees APRES le chargement : le coeur les
                   rattache au jeu en cours. */
                val codes = Triches.actifs(this, nom)
                if (codes.isNotEmpty()) {
                    coeur.poserTriches(codes)
                    noter("triches posées : " + codes.size)
                }
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (ok) {
                    son?.liberer()
                    son = Son(coeur.frequence)
                    vue.listeVisible = false
                    vue.ecranVide = false
                    barre.visibility = View.GONE
                    demarrerFil()
                    Toast.makeText(this, nom, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, coeur.derniereErreur ?: "Jeu refusé",
                                   Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun copier(uri: Uri, nom: String): File? {
        val dossier = File(cacheDir, "jeux").apply { mkdirs() }
        val f = File(dossier, nom.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val taille = DocumentFile.fromSingleUri(this, uri)?.length() ?: -1L
        if (f.exists() && f.length() == taille) return f
        return try {
            contentResolver.openInputStream(uri)?.use { e ->
                f.outputStream().use { s -> e.copyTo(s, 1 shl 16) }
            }
            f
        } catch (e: Throwable) {
            try { f.delete() } catch (_: Throwable) {}
            null
        }
    }

    private fun extraire(uri: Uri, nom: String): File? {
        val dossier = File(cacheDir, "archives").apply { mkdirs() }
        val sorties = ArrayList<File>()
        contentResolver.openInputStream(uri)?.use { e ->
            ZipInputStream(e.buffered()).use { z ->
                var entree = z.nextEntry
                while (entree != null) {
                    val n = entree.name.substringAfterLast('/')
                    val ext = n.substringAfterLast('.', "").lowercase()
                    if (!entree.isDirectory && ext in Bibliotheque.PRIORITE) {
                        val f = File(dossier, n.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                        f.outputStream().use { s -> z.copyTo(s, 1 shl 16) }
                        sorties.add(f)
                    }
                    z.closeEntry()
                    entree = z.nextEntry
                }
            }
        }
        for (ext in Bibliotheque.PRIORITE)
            sorties.firstOrNull { it.extension.lowercase() == ext }?.let { return it }
        return sorties.firstOrNull()
    }

    private fun appliquerReglages() {
        val p = prefs.getInt("palette", 0)
        if (prefs.getBoolean("auto", true)) coeur.couleursAutomatiques()
        else coeur.palette(p)
    }

    // ================= emulation =================

    /**
     * Le fil d'emulation.
     *
     * Il tourne a la cadence de la console — pres de soixante images par
     * seconde — mesuree sur l'horloge du systeme. Sans cette regulation, le
     * jeu irait a la vitesse de rafraichissement de l'ecran, souvent le
     * double, et paraitrait en avance rapide permanente.
     */
    private fun demarrerFil() {
        arreterFil()
        tourne = true
        fil = Thread {
            val tamponSon = ShortArray(8192)
            var horloge = System.nanoTime()
            var reliquat = 0.0
            var comptees = 0
            var depuis = System.nanoTime()

            while (tourne) {
                while (true) { (besognes.poll() ?: break).invoke() }

                val cadenceVisee = CADENCES[prefs.getInt("cadence", 0)
                    .coerceIn(0, CADENCES.size - 1)]
                val echelle = Coeur.ECHELLES[prefs.getInt("echelle", 3)
                    .coerceIn(0, Coeur.ECHELLES.size - 1)]

                val maintenant = System.nanoTime()
                var ecoule = (maintenant - horloge) / 1e9
                horloge = maintenant
                /* Apres une pause on ne rattrape pas : le jeu s'emballerait
                   pendant plusieurs secondes au retour. */
                if (ecoule > 0.25) ecoule = 0.0

                val base = if (cadenceVisee > 1.0) cadenceVisee else coeur.imagesParSeconde
                val vitesse = if (avanceRapide) echelle * 3.0 else echelle.toDouble()
                reliquat += ecoule * base * vitesse

                var tours = reliquat.toInt()
                if (tours > 4) { tours = 4; reliquat = 0.0 } else reliquat -= tours

                for (i in 0 until tours) {
                    coeur.image(boutons)
                    val n = coeur.lireSon(tamponSon)
                    /* Hors de la vitesse normale, le son n'a plus la bonne
                       hauteur : on le coupe plutot que de le laisser
                       crachoter. */
                    if (n > 0 && !avanceRapide && echelle > 0.9f && echelle < 1.1f)
                        son?.ecrire(tamponSon, n)
                }
                if (tours > 0 && coeur.lireImage())
                    vue.poserImage(coeur.pixels, coeur.imageL, coeur.imageH)

                if (tours > 0) {
                    comptees += tours
                    val ns = System.nanoTime()
                    if (ns - depuis > 1_000_000_000L) {
                        vue.cadence = comptees * 1e9 / (ns - depuis)
                        comptees = 0
                        depuis = ns
                    }
                } else {
                    try { Thread.sleep(2) } catch (_: InterruptedException) { break }
                }
            }
        }
        fil?.start()
    }

    private fun arreterFil() {
        tourne = false
        try { fil?.join(600) } catch (_: InterruptedException) {}
        fil = null
    }

    // ================= codes de triche =================

    /**
     * L'ecran des codes de triche, PAR JEU.
     *
     * Chaque jeu garde sa propre liste. On coche ce qu'on veut voir agir, on
     * ajoute ce qu'on veut, et rien ne se melange d'un jeu a l'autre.
     */
    private fun ouvrirTriches() {
        val jeu = nomJeu
        if (jeu == null) {
            Toast.makeText(this, "Charge d'abord un jeu", Toast.LENGTH_SHORT).show()
            return
        }
        if (!coeur.trichesPossibles) {
            Toast.makeText(this, "Ce cœur n'accepte pas les codes de triche",
                           Toast.LENGTH_LONG).show()
            return
        }
        val codes = Triches.lister(this, jeu)
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val d = (16 * resources.displayMetrics.density).toInt()
            setPadding(d, d, d, d)
        }
        ll.addView(TextView(this).apply {
            text = jeu + "\n"
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        if (codes.isEmpty()) {
            ll.addView(TextView(this).apply { text = "Aucun code pour ce jeu.\n" })
        }
        for (c in codes) {
            ll.addView(CheckBox(this).apply {
                text = c.nom + "   (" + c.code + ")"
                isChecked = c.actif
                setOnCheckedChangeListener { _, v ->
                    c.actif = v
                    Triches.enregistrer(this@MainActivity, jeu, codes)
                }
                setOnLongClickListener {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Retirer ce code ?")
                        .setMessage(c.nom)
                        .setPositiveButton("Retirer") { _, _ ->
                            codes.remove(c)
                            Triches.enregistrer(this@MainActivity, jeu, codes)
                            ouvrirTriches()
                        }
                        .setNegativeButton("Annuler", null)
                        .show()
                    true
                }
            })
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Codes de triche")
            .setView(ScrollView(this).apply { addView(ll) })
            .setPositiveButton("Appliquer") { _, _ ->
                val actifs = codes.filter { it.actif }.map { it.code }
                coeur.poserTriches(actifs)
                Toast.makeText(this, actifs.size.toString() + " code(s) actif(s)",
                               Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Base") { _, _ -> ouvrirBase(jeu, codes) }
            .setNegativeButton("Aide") { _, _ ->
                android.app.AlertDialog.Builder(this)
                    .setTitle("Formats reconnus")
                    .setMessage(Triches.AIDE)
                    .setPositiveButton("Fermer", null)
                    .show()
            }
            .show()
    }

    /**
     * Les codes deja connus pour ce jeu.
     *
     * L'application embarque une base de plus de douze cents jeux. On y coche
     * ce qu'on veut, et les codes retenus rejoignent la liste du jeu.
     */
    private fun ouvrirBase(jeu: String, codes: MutableList<Triches.Code>) {
        val connus = Triches.connus(this, jeu)
        if (connus.isEmpty()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Aucun code connu")
                .setMessage("La base ne contient rien pour « " + jeu + " ».\n\n" +
                            "Elle couvre " + Triches.nombreJeux(this) + " jeux, " +
                            "indexés sur leur titre. Tu peux ajouter un code " +
                            "à la main par le bouton Ajouter.")
                .setPositiveButton("Ajouter à la main") { _, _ ->
                    ajouterTriche(jeu, codes)
                }
                .setNegativeButton("Fermer", null)
                .show()
            return
        }
        val deja = codes.map { it.code }.toSet()
        val libelles = connus.map { it.nom + "   (" + it.code + ")" }.toTypedArray()
        val coches = BooleanArray(connus.size) { connus[it].code in deja }

        android.app.AlertDialog.Builder(this)
            .setTitle(connus.size.toString() + " codes connus")
            .setMultiChoiceItems(libelles, coches) { _, i, v -> coches[i] = v }
            .setPositiveButton("Ajouter") { _, _ ->
                var n = 0
                for (i in connus.indices) {
                    if (!coches[i] || connus[i].code in deja) continue
                    codes.add(Triches.Code(connus[i].nom, connus[i].code, true))
                    n++
                }
                Triches.enregistrer(this, jeu, codes)
                Toast.makeText(this, n.toString() + " code(s) ajouté(s)",
                               Toast.LENGTH_SHORT).show()
                ouvrirTriches()
            }
            .setNeutralButton("À la main") { _, _ -> ajouterTriche(jeu, codes) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun ajouterTriche(jeu: String, codes: MutableList<Triches.Code>) {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val d = (16 * resources.displayMetrics.density).toInt()
            setPadding(d, d, d, d)
        }
        val nom = EditText(this).apply { hint = "Nom du code (vies infinies…)" }
        val code = EditText(this).apply {
            hint = "019-14D-E6E ou 011556C1"
            inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        ll.addView(nom); ll.addView(code)

        android.app.AlertDialog.Builder(this)
            .setTitle("Nouveau code")
            .setView(ll)
            .setPositiveButton("Ajouter") { _, _ ->
                val propre = Triches.forme(code.text.toString())
                if (propre == null) {
                    Toast.makeText(this, "Format non reconnu — voir l'aide",
                                   Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val libelle = nom.text.toString().ifBlank { propre }
                codes.add(Triches.Code(libelle, propre, true))
                Triches.enregistrer(this, jeu, codes)
                Toast.makeText(this, "Code ajouté", Toast.LENGTH_SHORT).show()
                ouvrirTriches()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ================= reglages =================

    private val CADENCES = Coeur.CADENCES

    private fun ouvrirReglages() {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val d = (16 * resources.displayMetrics.density).toInt()
            setPadding(d, d, d, d)
        }
        fun titre(t: String) = TextView(this).apply {
            text = "\n" + t
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        ll.addView(titre("Couleurs"))
        ll.addView(CheckBox(this).apply {
            text = "Couleurs choisies par le cœur"
            isChecked = prefs.getBoolean("auto", true)
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("auto", v).apply()
                appliquerReglages()
            }
        })
        for ((i, libelle) in Coeur.PALETTES_FR.withIndex()) {
            ll.addView(bouton(libelle) {
                prefs.edit().putInt("palette", i).putBoolean("auto", false).apply()
                appliquerReglages()
                Toast.makeText(this, libelle, Toast.LENGTH_SHORT).show()
            })
        }

        ll.addView(titre("Vitesse d'affichage"))
        for ((i, libelle) in Coeur.CADENCES_FR.withIndex()) {
            ll.addView(bouton(libelle) {
                prefs.edit().putInt("cadence", i).apply()
                Toast.makeText(this, libelle, Toast.LENGTH_SHORT).show()
            })
        }

        ll.addView(titre("Échelle de vitesse"))
        for ((i, e) in Coeur.ECHELLES.withIndex()) {
            val t = if (e == e.toInt().toFloat()) e.toInt().toString() else e.toString()
            ll.addView(bouton("× $t") {
                prefs.edit().putInt("echelle", i).apply()
                Toast.makeText(this, "Vitesse × $t", Toast.LENGTH_SHORT).show()
            })
        }

        ll.addView(titre("Image"))
        ll.addView(CheckBox(this).apply {
            text = "Lisser l'image (sinon pixels nets)"
            isChecked = prefs.getBoolean("lissage", false)
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("lissage", v).apply()
                vue.lissage = v
                vue.invalidate()
            }
        })

        android.app.AlertDialog.Builder(this)
            .setTitle("Réglages")
            .setView(ScrollView(this).apply { addView(ll) })
            .setPositiveButton("Fermer", null)
            .show()
    }

    /**
     * Les sauvegardes d'etat du jeu en cours.
     *
     * Quatre cases, chacune datee. Une case vide ne propose que d'enregistrer,
     * une case pleine propose d'abord de charger — c'est le geste le plus
     * frequent, et le plus attendu en haut de liste.
     */
    private fun ouvrirSauvegardes() {
        val jeu = nomJeu
        if (jeu == null || !coeur.romChargee) {
            Toast.makeText(this, "Charge d'abord un jeu", Toast.LENGTH_SHORT).show()
            return
        }
        val libelles = (1..Sauvegardes.CASES).map { i ->
            val d = Sauvegardes.date(this, jeu, i)
            if (d == null) "Case $i — vide"
            else "Case $i — $d"
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Sauvegardes")
            .setItems(libelles) { _, i -> ouvrirCase(jeu, i + 1) }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun ouvrirCase(jeu: String, case: Int) {
        val pleine = Sauvegardes.existe(this, jeu, case)
        val actions = if (pleine) arrayOf("Charger", "Remplacer", "Effacer")
                      else arrayOf("Enregistrer")
        android.app.AlertDialog.Builder(this)
            .setTitle("Case $case")
            .setItems(actions) { _, i ->
                when (actions[i]) {
                    "Charger" -> chargerEtat(jeu, case)
                    "Enregistrer", "Remplacer" -> enregistrerEtat(jeu, case)
                    "Effacer" -> {
                        Sauvegardes.effacer(this, jeu, case)
                        Toast.makeText(this, "Case $case effacée", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun enregistrerEtat(jeu: String, case: Int) {
        besognes.add {
            val donnees = coeur.sauverEtat()
            runOnUiThread {
                val ok = donnees != null && donnees.isNotEmpty() &&
                         Sauvegardes.ecrire(this, jeu, case, donnees)
                Toast.makeText(this,
                    if (ok) "Enregistré dans la case $case"
                    else "Le cœur n'a pas pu figer cet état",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun chargerEtat(jeu: String, case: Int) {
        val donnees = Sauvegardes.lire(this, jeu, case)
        if (donnees == null) {
            Toast.makeText(this, "Case $case illisible", Toast.LENGTH_SHORT).show()
            return
        }
        besognes.add {
            val ok = coeur.restaurerEtat(donnees)
            runOnUiThread {
                Toast.makeText(this,
                    if (ok) "Case $case chargée"
                    else "Cet état ne correspond pas à ce jeu",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun ouvrirEtat() {
        android.app.AlertDialog.Builder(this)
            .setTitle("État")
            .setMessage(vue.diagnostic() + "\n" +
                        "cœur : " + (if (coeur.pret) "chargé" else "ABSENT") + "\n" +
                        "triches : " + (if (coeur.trichesPossibles) "acceptées" else "non") + "\n" +
                        "images émulées : " + coeur.imagesEmulees + "\n" +
                        "clarté de l'image : " + coeur.clarte + "\n" +
                        "cadence de la console : %.4f i/s".format(coeur.imagesParSeconde) + "\n" +
                        "habillage : " + Dispositions.libelle(this, vue.width > vue.height) + "\n" +
                        "jeu : " + (nomJeu ?: "aucun") + "\n" +
                        "sauvegardes : " + (nomJeu?.let {
                            Sauvegardes.occupees(this, it).toString() + " case(s) sur " +
                            Sauvegardes.CASES } ?: "—"))
            .setPositiveButton("Fermer", null)
            .show()
    }

    private fun ouvrirJournal() {
        val d = File(filesDir, "systeme")
        val texte = buildString {
            val chute = File(d, "chute.txt")
            if (chute.exists()) append("=== DERNIÈRE CHUTE ===\n" + chute.readText() + "\n")
            val a = File(d, "journal_appli.txt")
            if (a.exists()) append(a.readLines().takeLast(50).joinToString("\n") + "\n")
            val b = File(d, "journal.txt")
            if (b.exists()) append(b.readLines().takeLast(100).joinToString("\n"))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Journal")
            .setMessage(if (texte.isBlank()) "Journal vide" else texte)
            .setPositiveButton("Fermer", null)
            .setNeutralButton("Copier") { _, _ ->
                val cb = getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("journal", texte))
                Toast.makeText(this, "Journal copié", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ================= cycle de vie =================

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        bordABord()
        window.setFormat(PixelFormat.OPAQUE)

        noter("")
        noter("--- démarrage " + java.text.SimpleDateFormat(
            "HH:mm:ss", java.util.Locale.FRANCE).format(java.util.Date()) + " ---")
        noter("Android " + Build.VERSION.SDK_INT)
        noter("base de triches : " + Triches.nombreJeux(this) + " jeux")

        noter("avant ouverture du coeur Gambatte")
        coeur = Coeur(this)
        noter("coeur ouvert : " + coeur.pret)
        if (!coeur.pret) noter("cœur non prêt : " + (coeur.derniereErreur ?: "?"))

        vue = SkinView(this)
        vue.lissage = prefs.getBoolean("lissage", false)

        racine = FrameLayout(this)
        racine.setBackgroundColor(Color.BLACK)
        racine.addView(vue, FrameLayout.LayoutParams(-1, -1))

        barre = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC101018.toInt())
            addView(bouton("Jeux") { ouvrirListe() })
            addView(bouton("Modifier") { basculerModifier() })
            addView(bouton("Dossier") { choisirDossier.launch(null) })
            addView(bouton("Triches") { ouvrirTriches() })
            addView(bouton("Sauvegardes") { ouvrirSauvegardes() })
            addView(bouton("Réglages") { ouvrirReglages() })
            addView(bouton("État") { ouvrirEtat() })
            addView(bouton("Défaut") {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Disposition d'origine ?")
                    .setMessage("Les touches de cet habillage retrouveront " +
                                "leur place et leur taille d'origine.")
                    .setPositiveButton("Rétablir") { _, _ ->
                        vue.reinitialiserDisposition()
                        Toast.makeText(this@MainActivity, "Rétabli",
                                       Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            })
            addView(bouton("Journal") { ouvrirJournal() })
        }
        racine.addView(barre, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        barre.visibility = View.GONE

        panneauModif = construirePanneauModif()
        panneauModif.visibility = View.GONE
        racine.addView(panneauModif, FrameLayout.LayoutParams(-2, -2,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (18 * resources.displayMetrics.density).toInt()
            })

        setContentView(racine)

        vue.surCommandes = { b, ff -> boutons = b; avanceRapide = ff }
        vue.surMenu = { basculerBarre() }
        vue.surJeux = { ouvrirListe() }
        vue.surPower = {
            android.app.AlertDialog.Builder(this)
                .setTitle("Éteindre ?")
                .setPositiveButton("Oui") { _, _ -> finishAffinity() }
                .setNegativeButton("Non", null)
                .show()
        }
        vue.surManette = {
            val libelle = vue.habillageSuivant()
            majPanneauModif()
            Toast.makeText(this, libelle, Toast.LENGTH_SHORT).show()
        }
        vue.surChoixJeu = { i -> if (i in jeux.indices) charger(jeux[i].uri, jeux[i].nom) }

        jeuDemandeParLaChambre()
    }

    override fun onPause() {
        super.onPause()
        tourne = false
        son?.pause()
    }

    override fun onResume() {
        super.onResume()
        son?.reprendre()
        if (coeur.romChargee && fil == null) demarrerFil()
        bordABord()
    }

    override fun onDestroy() {
        super.onDestroy()
        arreterFil()
        son?.liberer()
        if (::vue.isInitialized) vue.liberer()
    }
}
