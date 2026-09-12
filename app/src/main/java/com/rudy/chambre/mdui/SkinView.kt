package com.rudy.chambre.mdui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * L'habillage de la console : il se dessine, et il transforme les gestes en
 * commandes.
 *
 * L'image du jeu est peinte ici, dans le rectangle de l'ecran. Rien n'est
 * superpose ni rendu transparent : tout tient dans cette vue.
 */
class SkinView(ctx: Context) : View(ctx) {

    private class Touche(val repos: Bitmap,
                         val appui: List<Bitmap>,
                         val directions: Map<String, List<Bitmap>>) {
        fun liberer() {
            if (!repos.isRecycled) repos.recycle()
            for (b in appui) if (!b.isRecycled) b.recycle()
            for (l in directions.values) for (b in l) if (!b.isRecycled) b.recycle()
        }
    }

    /** Position de la croix, de -1 a 1. */
    private class Axe { var x = 0f; var y = 0f; var pid = -1 }

    // ---- rappels vers l'application ----
    var surCommandes: ((Int, Boolean) -> Unit)? = null
    var surMenu: (() -> Unit)? = null
    var surJeux: (() -> Unit)? = null
    var surManette: (() -> Unit)? = null

    // ---- etat visible ----
    var ecranVide = true
        set(v) { field = v; invalidate() }
    var cadence = 0.0
    var lissage = false

    var listeJeux: List<String> = emptyList()
        set(v) { field = v; rangListe = 0; defileListe = 0f; invalidate() }
    var listeVisible = false
        set(v) { field = v; invalidate() }
    var surChoixJeu: ((Int) -> Unit)? = null

    private var rangListe = 0
    private var defileListe = 0f

    /**
     * Mode MODIFIER.
     *
     * On y deplace les touches au doigt, et l'on change leur taille a deux
     * doigts. L'ecran se traite comme une touche : il se deplace et se
     * redimensionne pareil.
     */
    var modifier = false
        set(v) { field = v; choisie = null; invalidate() }

    /**
     * Opacite des touches de l'habillage transparent, de 0,12 a 1. Elle ne
     * touche que lui : une console dessinee n'a pas a s'effacer.
     */
    var opaciteBoutons = 0.85f
        set(v) { field = v.coerceIn(0.12f, 1f); invalidate() }

    /** Habillage en cours et orientation : l'activite s'en sert pour ses reglages. */
    val habillageCourant: String get() = dossier
    val estPaysage: Boolean get() = enPaysage
    private var choisie: String? = null
    private var priseX = 0f
    private var priseY = 0f
    private var priseRect: Rect4? = null
    private var ecartDepart = 0f
    private var tailleDepart: Rect4? = null

    /** Poignee saisie : "hg", "h", "hd", "g", "d", "bg", "b", "bd", ou null. */
    private var poignee: String? = null

    /** L'image du jeu garde-t-elle le 4:3 de la console ? */
    /*
     * Par defaut l'image epouse le cadre : c'est la taille du rectangle,
     * reglee au doigt, qui decide du format. Le 4:3 de la console reste
     * disponible dans le panneau MODIFIER pour qui le prefere.
     */
    var respecterRapport = false

    private val remplissagePoignee = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFFFFC400.toInt()
    }
    private val bordPoignee = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF202020.toInt()
    }

    /** Cote d'une poignee, a l'ecran. */
    private val cotePoignee get() = 22f * resources.displayMetrics.density

    /** Ce qu'une piece ne doit jamais devenir plus petite que. */
    private val MINI = 24f

    /**
     * Les huit poignees d'une piece, en coordonnees d'ecran.
     *
     * Quatre aux coins pour etirer en diagonale, quatre au milieu des cotes
     * pour n'agir que sur la largeur ou que sur la hauteur.
     */
    private fun poignees(r: Rect4): List<Pair<String, RectF>> {
        versEcran(r, tmp)
        val c = cotePoignee / 2f
        val xs = listOf("g" to tmp.left, "" to tmp.centerX(), "d" to tmp.right)
        val ys = listOf("h" to tmp.top, "" to tmp.centerY(), "b" to tmp.bottom)
        val sortie = ArrayList<Pair<String, RectF>>(8)
        for ((cy, y) in ys) for ((cx, x) in xs) {
            val nom = cy + cx
            if (nom.isEmpty()) continue          // le centre sert a deplacer
            sortie.add(nom to RectF(x - c, y - c, x + c, y + c))
        }
        return sortie
    }

    private fun poigneeSous(x: Float, y: Float): String? {
        val id = choisie ?: return null
        val r = dispo.items[id] ?: return null
        /* Une marge de confort : le doigt est plus large que la poignee. */
        val m = cotePoignee * 0.35f
        for ((nom, boite) in poignees(r))
            if (x >= boite.left - m && x <= boite.right + m &&
                y >= boite.top - m && y <= boite.bottom + m) return nom
        return null
    }

    // ---- habillages ----
    private val fonds = HashMap<String, Bitmap>()
    private val touches = HashMap<String, HashMap<String, Touche>>()

    private var dispo: Disposition
    private var enPaysage = false
    private var dossier: String

    // ---- peintures ----
    private val peinture = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val peintureJeu = Paint()
    private val noir = Paint().apply { color = Color.BLACK }
    private val texteVide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6A7A5A.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val fondListe = Paint().apply { color = 0xFF0B0B10.toInt() }
    private val surbrillance = Paint().apply { color = 0xFF1E3A5F.toInt() }
    private val texteListe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val texteTitre = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8AB4F8.toInt()
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val cadreEdition = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF4CAF50.toInt()
    }
    private val cadreChoisi = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFC107.toInt()
    }
    private val texteAide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val fondAide = Paint().apply { color = 0xCC000000.toInt() }

    // ---- image du jeu ----
    private val cadreJeu = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
    private val srcJeu = Rect(0, 0, 1, 1)
    private var jeuPret = false

    // ---- mise a l'echelle ----
    private var ech = 1f
    private var decX = 0f
    private var decY = 0f
    private var fondPret: Bitmap? = null

    private val tmp = RectF()
    private val tmp2 = RectF()

    private val TABLE = mapOf(
        Ids.A to Pad.A, Ids.B to Pad.B, Ids.C to Pad.C,
        Ids.X to Pad.X, Ids.Y to Pad.Y, Ids.Z to Pad.Z,
        Ids.START to Pad.START)

    private val doigts = HashMap<Int, String>()
    private val actifs = HashMap<String, Long>()
    private val croix = Axe()
    private var doigtListe = -1
    private var listeY0 = 0f
    private var listeDepart = 0f
    private var listeBouge = false

    init {
        dossier = Dispositions.dossier(ctx, false)
        dispo = Dispositions.charger(ctx, false)
        opaciteBoutons = Dispositions.opacite(ctx)
        for (d in Dispositions.tous()) {
            val m = HashMap<String, Touche>()
            chargerSkin(d, m)?.let { fonds[d] = it }
            touches[d] = m
        }
        isFocusable = true
        isClickable = true
    }

    private fun chargerSkin(dossier: String, cible: HashMap<String, Touche>): Bitmap? {
        fun charge(chemin: String): Bitmap =
            context.assets.open(chemin).use { BitmapFactory.decodeStream(it) }
        val texte = context.assets.open("skins/md/skin/$dossier/positions.json")
            .bufferedReader().use { it.readText() }
        val tab = org.json.JSONObject(texte).getJSONArray("elements")
        for (i in 0 until tab.length()) {
            val e = tab.getJSONObject(i)
            val im = e.getJSONObject("images")
            val repos = charge("skins/md/skin/$dossier/" + im.getString("repos"))
            val appui = ArrayList<Bitmap>()
            if (im.has("appui")) {
                val a = im.getJSONArray("appui")
                for (k in 0 until a.length()) appui.add(charge("skins/md/skin/$dossier/" + a.getString(k)))
            }
            val dirs = HashMap<String, List<Bitmap>>()
            for (sens in listOf("h", "b", "g", "d", "hg", "hd", "bg", "bd")) {
                if (!im.has(sens)) continue
                val a = im.getJSONArray(sens)
                val l = ArrayList<Bitmap>()
                for (k in 0 until a.length()) l.add(charge("skins/md/skin/$dossier/" + a.getString(k)))
                dirs[sens] = l
            }
            cible[e.getString("id")] = Touche(repos, appui, dirs)
        }
        return charge("skins/md/skin/$dossier/fond.png")
    }

    /** Passe a l'habillage suivant, dans l'orientation en cours. */
    fun habillageSuivant(): String {
        val libelle = Dispositions.suivant(context, enPaysage)
        dossier = Dispositions.dossier(context, enPaysage)
        dispo = Dispositions.charger(context, enPaysage)
        fondPret?.recycle(); fondPret = null
        if (width > 0 && height > 0) recalculer(width, height)
        invalidate()
        return libelle
    }

    // ================= mise en place =================

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val paysage = w > h
        if (paysage != enPaysage) {
            enPaysage = paysage
            dossier = Dispositions.dossier(context, paysage)
            dispo = Dispositions.charger(context, paysage)
        }
        recalculer(w, h)
    }

    private fun recalculer(w: Int, h: Int) {
        /*
         * La console tient ENTIEREMENT dans l'ecran.
         *
         * Je prenais le plus grand des deux rapports, pour remplir la surface.
         * Sur les habillages verticaux, tres hauts, cela debordait des deux
         * cotes et les touches du haut sortaient de l'ecran — le bouton
         * manette devenait inatteignable.
         */
        ech = min(w.toFloat() / dispo.sw, h.toFloat() / dispo.sh)
        decX = (w - dispo.sw * ech) / 2f
        decY = (h - dispo.sh * ech) / 2f
        fondPret?.recycle()
        fondPret = null
    }

    private fun versEcran(r: Rect4, sortie: RectF) {
        sortie.set(decX + r.x * ech, decY + r.y * ech,
                   decX + (r.x + r.w) * ech, decY + (r.y + r.h) * ech)
    }

    // ================= dessin =================

    override fun onDraw(c: Canvas) {
        val source = fonds[dossier] ?: return
        if (fondPret == null && width > 0 && height > 0) {
            /* Le fond est mis a l'echelle une seule fois : le redimensionner
               a chaque image couterait bien plus cher. */
            val n = Bitmap.createBitmap(max(1, width), max(1, height), Bitmap.Config.ARGB_8888)
            val cc = Canvas(n)
            cc.drawColor(Color.BLACK)
            tmp.set(decX, decY, decX + dispo.sw * ech, decY + dispo.sh * ech)
            cc.drawBitmap(source, null, tmp, peinture)
            fondPret = n
        }
        fondPret?.let { c.drawBitmap(it, 0f, 0f, null) }

        dispo.items[Ids.ECRAN]?.let { dessinerEcran(c, it) }

        val jeu = touches[dossier] ?: return
        // Sur l'habillage transparent, les touches se dessinent a l'opacite
        // reglee dans MODIFIER ; le fond et le jeu restent pleins.
        val gardeAlpha = peinture.alpha
        if (dossier == Dispositions.TRANSPARENT)
            peinture.alpha = (255 * opaciteBoutons).toInt().coerceIn(24, 255)
        for ((id, t) in jeu) {
            val r = dispo.items[id] ?: continue
            versEcran(r, tmp)
            val marge = (dispo.marges[id] ?: 0) * ech
            tmp2.set(tmp.left - marge, tmp.top - marge, tmp.right + marge, tmp.bottom + marge)

            if (id in Ids.DIRECTIONNELS) { dessinerCroix(c, t, id, tmp, tmp2); continue }
            if (actifs.containsKey(id) && t.appui.isNotEmpty())
                c.drawBitmap(t.appui[t.appui.size - 1], null, tmp2, peinture)
            else
                c.drawBitmap(t.repos, null, tmp, peinture)
        }

        peinture.alpha = gardeAlpha

        if (modifier) dessinerEdition(c)
    }

    /** En mode MODIFIER : un cadre autour de chaque piece, et un rappel. */
    /**
     * Etire une piece par l'une de ses poignees.
     *
     * Le cote oppose ne bouge pas : c'est lui l'ancre. Tirer le bord droit
     * n'allonge que vers la droite, tirer un coin agit sur les deux
     * dimensions a la fois.
     *
     * Aucune poignee n'agit sur les deux dimensions a la fois, sauf les
     * coins. Un cote tire n'allonge QUE dans son sens : c'est le choix qu'on
     * veut avoir, et lier les deux dimensions le retirerait.
     */
    private fun etirer(d: Rect4, dx: Float, dy: Float, prise: String): Rect4 {
        var x = d.x; var y = d.y; var w = d.w; var h = d.h
        if (prise.contains("g")) { x = d.x + dx; w = d.w - dx }
        if (prise.contains("d")) { w = d.w + dx }
        if (prise.contains("h")) { y = d.y + dy; h = d.h - dy }
        if (prise.contains("b")) { h = d.h + dy }

        if (w < MINI) { if (prise.contains("g")) x = d.x + d.w - MINI; w = MINI }
        if (h < MINI) { if (prise.contains("h")) y = d.y + d.h - MINI; h = MINI }

        return Rect4(x, y, w, h)
    }

    private fun dessinerEdition(c: Canvas) {
        cadreEdition.strokeWidth = 2f * resources.displayMetrics.density
        cadreChoisi.strokeWidth = 3f * resources.displayMetrics.density
        val jeu = touches[dossier] ?: return
        for (id in jeu.keys + setOf(Ids.ECRAN)) {
            val r = dispo.items[id] ?: continue
            versEcran(r, tmp)
            c.drawRect(tmp, if (id == choisie) cadreChoisi else cadreEdition)
        }
        choisie?.let { id ->
            dispo.items[id]?.let { r ->
                bordPoignee.strokeWidth = 1.5f * resources.displayMetrics.density
                val rayon = cotePoignee * 0.22f
                for ((_, boite) in poignees(r)) {
                    c.drawRoundRect(boite, rayon, rayon, remplissagePoignee)
                    c.drawRoundRect(boite, rayon, rayon, bordPoignee)
                }
            }
        }
        val h = height * 0.055f
        c.drawRect(0f, 0f, width.toFloat(), h, fondAide)
        texteAide.textSize = h * 0.38f
        c.drawText("MODIFIER — glisser déplace, les poignées étirent",
                   width / 2f, h * 0.64f, texteAide)
    }

    /** La croix bascule vers l'endroit touche : un bras rentre, l'oppose sort. */
    private fun dessinerCroix(c: Canvas, t: Touche, id: String,
                              boite: RectF, large: RectF) {
        val n = hypot(croix.x.toDouble(), croix.y.toDouble()).toFloat()
        if (n < 0.06f || t.directions.isEmpty()) {
            c.drawBitmap(t.repos, null, boite, peinture); return
        }
        val sens = StringBuilder()
        if (croix.y < -0.24f) sens.append("h") else if (croix.y > 0.24f) sens.append("b")
        if (croix.x < -0.24f) sens.append("g") else if (croix.x > 0.24f) sens.append("d")
        val cle = if (sens.isEmpty())
            (if (abs(croix.x) > abs(croix.y)) (if (croix.x < 0) "g" else "d")
             else (if (croix.y < 0) "h" else "b"))
        else sens.toString()
        val l = t.directions[cle]
        if (l.isNullOrEmpty()) { c.drawBitmap(t.repos, null, boite, peinture); return }
        val img = l[if (n > 0.34f) l.size - 1 else 0]
        val course = (dispo.courses[id] ?: 0f) * ech * min(1f, n)
        c.save()
        c.translate(croix.x * course, croix.y * course)
        c.drawBitmap(img, null, large, peinture)
        c.restore()
    }

    /**
     * Rapport d'affichage de la Mega Drive.
     *
     * La console sort une image de 320 sur 224 — parfois 256 de large, selon
     * le mode — mais le televiseur l'etirait toujours en 4:3. C'est donc ce
     * rapport-la qu'il faut tenir, et non celui des pixels : s'aligner sur
     * les pixels tasserait l'image en hauteur et les personnages seraient
     * trapus.
     */
    private val RAPPORT_CONSOLE = 4f / 3f

    private val ecranJeu = RectF()

    /**
     * Cale l'image dans la dalle dessinee : la plus grande possible, centree,
     * sans jamais deborder du cadre ni deformer l'image.
     *
     * On essaie d'abord d'occuper toute la largeur ; si la hauteur ne suit
     * pas, on repart de la hauteur. Ce qui reste autour est peint en noir,
     * comme les bandes d'un televiseur.
     */
    private fun calerImage(cadre: RectF, sortie: RectF) {
        var w = cadre.width()
        var h = w / RAPPORT_CONSOLE
        if (h > cadre.height()) { h = cadre.height(); w = h * RAPPORT_CONSOLE }
        val x = cadre.centerX() - w / 2f
        val y = cadre.centerY() - h / 2f
        sortie.set(x, y, x + w, y + h)
    }

    private fun dessinerEcran(c: Canvas, r: Rect4) {
        versEcran(r, tmp)
        c.drawRect(tmp, noir)
        if (listeVisible) { dessinerListe(c, tmp); return }
        if (!ecranVide && jeuPret) {
            if (respecterRapport) calerImage(tmp, ecranJeu) else ecranJeu.set(tmp)
            c.save(); c.clipRect(tmp)
            /* Sans lissage par defaut : les gros pixels de la Mega Drive
               doivent rester nets, c'est tout leur charme. */
            peintureJeu.isFilterBitmap = lissage
            synchronized(cadreJeu) { c.drawBitmap(cadreJeu, srcJeu, ecranJeu, peintureJeu) }
            c.restore()
            return
        }
        texteVide.textSize = tmp.height() * 0.07f
        c.drawText("AUCUN JEU", tmp.centerX(), tmp.centerY(), texteVide)
        texteVide.textSize = tmp.height() * 0.05f
        c.drawText("bouton JEUX", tmp.centerX(), tmp.centerY() + tmp.height() * 0.11f, texteVide)
    }

    private fun hauteurLigne(r: RectF) = r.height() * 0.085f

    private fun dessinerListe(c: Canvas, r: RectF) {
        c.drawRect(r, fondListe)
        val hl = hauteurLigne(r)
        texteTitre.textSize = hl * 0.56f
        texteListe.textSize = hl * 0.50f
        val marge = r.width() * 0.045f
        c.drawText("JEUX  (" + listeJeux.size + ")", r.left + marge, r.top + hl * 0.9f, texteTitre)
        if (listeJeux.isEmpty()) {
            c.drawText("Aucun jeu — bouton Dossier", r.left + marge, r.top + hl * 2.2f, texteListe)
            return
        }
        val haut = r.top + hl * 1.35f
        val visibles = ((r.bottom - haut) / hl).toInt().coerceAtLeast(1)
        val premier = (defileListe / hl).toInt()
            .coerceIn(0, (listeJeux.size - visibles).coerceAtLeast(0))
        c.save(); c.clipRect(r.left, haut, r.right, r.bottom)
        for (i in premier until min(listeJeux.size, premier + visibles + 1)) {
            val y = haut + (i - premier) * hl
            if (i == rangListe) c.drawRect(r.left, y, r.right, y + hl, surbrillance)
            var nom = listeJeux[i]
            while (texteListe.measureText(nom) > r.width() - marge * 2 && nom.length > 4)
                nom = nom.substring(0, nom.length - 2)
            c.drawText(nom, r.left + marge, y + hl * 0.68f, texteListe)
        }
        c.restore()
    }

    fun poserImage(pixels: IntArray, l: Int, h: Int) {
        if (l <= 0 || h <= 0 || l > cadreJeu.width || h > cadreJeu.height) return
        synchronized(cadreJeu) {
            cadreJeu.setPixels(pixels, 0, l, 0, 0, l, h)
            srcJeu.set(0, 0, l, h)
        }
        jeuPret = true
        postInvalidateOnAnimation()
    }

    // ================= gestes =================

    /** Les touches de fonction d'abord : elles sont petites. */
    private val ordreCapture = Ids.FONCTIONS + listOf(
        Ids.FF, Ids.START, Ids.A, Ids.B, Ids.C, Ids.X, Ids.Y, Ids.Z, Ids.CROIX)

    private fun sous(x: Float, y: Float): String? {
        val jeu = touches[dossier] ?: return null
        for (id in ordreCapture) {
            if (!jeu.containsKey(id)) continue
            val r = dispo.items[id] ?: continue
            versEcran(r, tmp2)
            if (tmp2.contains(x, y)) return id
        }
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (modifier) return gestesEdition(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                appuyer(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val pid = e.getPointerId(i)
                    val x = e.getX(i); val y = e.getY(i)
                    if (pid == doigtListe) { glisserListe(y); continue }
                    val id = doigts[pid]
                    if (id == Ids.CROIX) { majCroix(x, y); continue }
                    /* Glisser d'une touche a l'autre l'enfonce, sans lever le
                       doigt — comme sur une vraie console. */
                    val nouveau = sous(x, y)
                    if (nouveau != id && id != Ids.CROIX) {
                        if (id != null) {
                            doigts.remove(pid)
                            if (doigts.values.none { it == id }) actifs.remove(id)
                        }
                        if (nouveau != null && nouveau != Ids.CROIX) {
                            doigts[pid] = nouveau
                            actifs[nouveau] = System.currentTimeMillis()
                        }
                    }
                }
                majBoutons()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                val i = e.actionIndex
                relacher(e.getPointerId(i), e.getX(i), e.getY(i))
            }
        }
        invalidate()
        return true
    }

    /**
     * Les gestes du mode MODIFIER.
     *
     * Un doigt deplace la piece choisie ; deux doigts la redimensionnent
     * autour de son centre. L'ecran se traite comme une touche.
     */
    private fun gestesEdition(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = e.getX(0); val y = e.getY(0)
                /* On regarde D'ABORD les poignees de la piece deja choisie :
                   elles debordent souvent sur la piece voisine, et sans cette
                   priorite un coin serait impossible a attraper. */
                poignee = poigneeSous(x, y)
                if (poignee == null) choisie = sousEdition(x, y)
                choisie?.let {
                    priseX = x; priseY = y
                    priseRect = dispo.items[it]?.copie()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount >= 2 && choisie != null) {
                    ecartDepart = ecart(e)
                    tailleDepart = dispo.items[choisie]?.copie()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val id = choisie ?: return true
                val prise = poignee
                if (prise != null) {
                    val d = priseRect ?: return true
                    dispo.items[id] = etirer(d,
                        (e.getX(0) - priseX) / ech, (e.getY(0) - priseY) / ech, prise)
                    invalidate()
                    return true
                }
                if (e.pointerCount >= 2 && ecartDepart > 1f) {
                    val d = tailleDepart ?: return true
                    val f = (ecart(e) / ecartDepart).coerceIn(0.35f, 3.0f)
                    val nw = d.w * f
                    val nh = d.h * f
                    /* on redimensionne AUTOUR DU CENTRE : la piece ne se
                       sauve pas d'un coin quand on l'agrandit */
                    dispo.items[id] = Rect4(d.x + (d.w - nw) / 2f,
                                            d.y + (d.h - nh) / 2f, nw, nh)
                } else {
                    val d = priseRect ?: return true
                    dispo.items[id] = Rect4(d.x + (e.getX(0) - priseX) / ech,
                                            d.y + (e.getY(0) - priseY) / ech,
                                            d.w, d.h)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                ecartDepart = 0f
                poignee = null
                Dispositions.enregistrer(context, enPaysage, dispo)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                ecartDepart = 0f
                priseRect = dispo.items[choisie]?.copie()
                if (e.pointerCount >= 2) {
                    val autre = if (e.actionIndex == 0) 1 else 0
                    priseX = e.getX(autre); priseY = e.getY(autre)
                }
            }
        }
        return true
    }

    private fun ecart(e: MotionEvent): Float =
        hypot((e.getX(0) - e.getX(1)).toDouble(),
              (e.getY(0) - e.getY(1)).toDouble()).toFloat()

    /** La piece sous le doigt, l'ecran compris. */
    private fun sousEdition(x: Float, y: Float): String? {
        val jeu = touches[dossier] ?: return null
        for (id in ordreCapture) {
            if (!jeu.containsKey(id)) continue
            val r = dispo.items[id] ?: continue
            versEcran(r, tmp2)
            if (tmp2.contains(x, y)) return id
        }
        dispo.items[Ids.ECRAN]?.let {
            versEcran(it, tmp2)
            if (tmp2.contains(x, y)) return Ids.ECRAN
        }
        return null
    }

    /** Rend a l'habillage en cours sa disposition d'origine. */
    /**
     * L'ecran prend toute la vue, bandes noires comprises.
     *
     * L'habillage est cale au plus grand a l'interieur de l'ecran du
     * telephone : quand leurs proportions different, il reste une bande de
     * chaque cote. Le rectangle de jeu, lui, n'est pas tenu par l'habillage —
     * on le calcule donc en coordonnees du skin a partir de la vue entiere,
     * et l'image recouvre alors jusqu'aux bandes.
     */
    fun ecranPleinePage() {
        if (width <= 0 || height <= 0 || ech <= 0f) return
        dispo.items[Ids.ECRAN] = Rect4(-decX / ech, -decY / ech,
                                       width / ech, height / ech)
        choisie = Ids.ECRAN
        Dispositions.enregistrer(context, enPaysage, dispo)
        invalidate()
    }

    /** L'ecran retrouve la dalle dessinee sur l'habillage. */
    fun ecranOrigine() {
        val defaut = Dispositions.parDefaut(context, enPaysage).items[Ids.ECRAN] ?: return
        dispo.items[Ids.ECRAN] = defaut
        choisie = Ids.ECRAN
        Dispositions.enregistrer(context, enPaysage, dispo)
        invalidate()
    }

    fun reinitialiserDisposition() {
        dispo = Dispositions.parDefaut(context, enPaysage)
        Dispositions.enregistrer(context, enPaysage, dispo)
        choisie = null
        invalidate()
    }

    private fun appuyer(pid: Int, x: Float, y: Float) {
        if (listeVisible) {
            val r = dispo.items[Ids.ECRAN]
            if (r != null) {
                versEcran(r, tmp2)
                if (tmp2.contains(x, y)) {
                    doigtListe = pid; listeY0 = y
                    listeDepart = defileListe; listeBouge = false
                    return
                }
            }
        }
        val id = sous(x, y) ?: return
        doigts[pid] = id
        actifs[id] = System.currentTimeMillis()
        if (id == Ids.CROIX) {
            croix.pid = pid
            majCroix(x, y)
        } else {
            when (id) {
                Ids.MENU -> { relacherPlusTard(id); surMenu?.invoke() }
                Ids.JEUX -> { relacherPlusTard(id); surJeux?.invoke() }
                Ids.MANETTE -> { relacherPlusTard(id); surManette?.invoke() }
                else -> performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }
        majBoutons()
    }

    /**
     * Relache une touche au bout d'un instant.
     *
     * Les touches de fonction ouvrent une fenetre : le doigt ne revient jamais
     * les lever, et elles resteraient enfoncees.
     */
    private fun relacherPlusTard(id: String) {
        postDelayed({
            doigts.entries.filter { it.value == id }.map { it.key }
                .forEach { doigts.remove(it) }
            actifs.remove(id)
            invalidate()
        }, 180L)
    }

    private fun relacher(pid: Int, x: Float, y: Float) {
        if (pid == doigtListe) { finirListe(x, y); doigtListe = -1; return }
        val id = doigts.remove(pid) ?: return
        if (doigts.values.none { it == id }) actifs.remove(id)
        if (id == Ids.CROIX && croix.pid == pid) {
            croix.pid = -1; croix.x = 0f; croix.y = 0f
        }
        majBoutons()
    }

    /**
     * Position de la croix sous le doigt.
     *
     * Le deplacement se mesure depuis le CENTRE de la piece : partir du point
     * d'appui inverserait le sens des le second contact.
     */
    private fun majCroix(x: Float, y: Float) {
        val r = dispo.items[Ids.CROIX] ?: return
        versEcran(r, tmp)
        val course = max(tmp.width(), tmp.height()) * 0.42f
        croix.x = ((x - tmp.centerX()) / course).coerceIn(-1f, 1f)
        croix.y = ((y - tmp.centerY()) / course).coerceIn(-1f, 1f)
        if (abs(croix.x) < 0.08f) croix.x = 0f
        if (abs(croix.y) < 0.08f) croix.y = 0f
        majBoutons()
    }

    private fun majBoutons() {
        var b = 0
        for ((id, bit) in TABLE) if (actifs.containsKey(id)) b = b or bit
        if (croix.y < -0.35f) b = b or Pad.HAUT
        if (croix.y > 0.35f) b = b or Pad.BAS
        if (croix.x < -0.35f) b = b or Pad.GAUCHE
        if (croix.x > 0.35f) b = b or Pad.DROITE
        surCommandes?.invoke(b, actifs.containsKey(Ids.FF))
    }

    // ---- liste des jeux ----

    private fun glisserListe(y: Float) {
        if (abs(y - listeY0) > 12f) listeBouge = true
        val r = dispo.items[Ids.ECRAN] ?: return
        versEcran(r, tmp2)
        val hl = hauteurLigne(tmp2)
        val maxi = (listeJeux.size * hl - (tmp2.height() - hl * 1.35f)).coerceAtLeast(0f)
        defileListe = (listeDepart - (y - listeY0)).coerceIn(0f, maxi)
        invalidate()
    }

    private fun finirListe(x: Float, y: Float) {
        if (listeBouge) return
        val r = dispo.items[Ids.ECRAN] ?: return
        versEcran(r, tmp2)
        if (!tmp2.contains(x, y)) return
        val hl = hauteurLigne(tmp2)
        val haut = tmp2.top + hl * 1.35f
        if (y < haut) return
        val i = (defileListe / hl).toInt() + ((y - haut) / hl).toInt()
        if (i in listeJeux.indices) { rangListe = i; surChoixJeu?.invoke(i) }
    }

    fun diagnostic(): String =
        "%.0f i/s  touches %d  croix %.2f,%.2f".format(
            cadence, actifs.size, croix.x, croix.y)

    fun liberer() {
        for (m in touches.values) { for (t in m.values) t.liberer(); m.clear() }
        for (b in fonds.values) if (!b.isRecycled) b.recycle()
        fonds.clear()
        fondPret?.recycle()
        if (!cadreJeu.isRecycled) cadreJeu.recycle()
    }
}
