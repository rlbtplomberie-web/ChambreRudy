package com.skinps1.app

import android.content.Context
import android.graphics.*
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.skinps1.app.core.CoeurPs1
import com.skinps1.app.core.Pad
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Dessine le skin et fait tourner la console.
 *
 * Les touches jouent des images pre-calculees (repos, mi-course, fond de
 * course). Les deux sticks sont differents : leur capuchon glisse sous le
 * doigt dans sa cuvette, et huit images inclinees donnent la bascule ; leur
 * position, de -1 a 1 sur chaque axe, est transmise en continu au coeur.
 */
class SkinView(ctx: Context) : View(ctx) {

    // ---------- reglages ----------
    /** Zone morte des sticks, en fraction de la course. */
    var zoneMorte = 0.08f

    /** 0 ajuste en 4/3, 1 remplit le cadre, 2 remplit en rognant, 3 pixel a pixel. */
    var modeEcran = 0
        set(v) { field = v; invalidate() }
    /** Agrandissement supplementaire applique a l'image, 0.5 a 2.0. */
    var zoomEcran = 1.0f
        set(v) { field = v.coerceIn(0.5f, 2.0f); invalidate() }
    /** Duree d'une etape d'enfoncement, en ms. */
    var etapeMs = 34L
    /** Duree minimale d'affichage d'un appui bref. */
    var appuiMiniMs = 110L
    // --------------------------------

    lateinit var coeur: CoeurPs1
    var son: Son? = null
    var surTouche: (() -> Unit)? = null
    var surMenu: (() -> Unit)? = null
    var surQuit: (() -> Unit)? = null
    /** Prevenu quand la presentation horizontale a change. */
    var surManette: (() -> Unit)? = null
    var surJeux: (() -> Unit)? = null
    var surChoixJeu: ((Int) -> Unit)? = null
    var modeEdition = false
        set(v) { field = v; invalidate() }

    var boutons = 0
        private set

    // ---------- etat des touches ----------
    private val actifs = HashMap<String, Long>()
    private val relaches = HashMap<String, Long>()

    /** Etat d'un stick : position courante (-1..1), point de depart du doigt, retour. */
    private class Stick {
        var x = 0f; var y = 0f
        var x0 = 0f; var y0 = 0f           // position ecran du doigt a l'appui
        var actif = false
        var retourDepuis = 0L; var rx = 0f; var ry = 0f
        var secteur = ""; var secteurDepuis = 0L
    }
    private val sticks = mapOf(Ids.STICK_G to Stick(), Ids.STICK_D to Stick())

    // ---------- images ----------
    private class Touche(val repos: Bitmap, val appui: List<Bitmap>, val directions: Map<String, List<Bitmap>>)
    private var fondP: Bitmap? = null
    private var fondL: Bitmap? = null
    private val touchesP = HashMap<String, Touche>()
    private val touchesL = HashMap<String, Touche>()
    private val touchesLL = HashMap<String, Touche>()
    private var fondLL: Bitmap? = null
    /** La troisieme : une couche de touches posee sur le jeu. */
    private val touchesT = HashMap<String, Touche>()
    private var fondT: Bitmap? = null

    private var dispo: Disposition
    private var enPaysage = false
    private var dispoChargee = false
    /**
     * Presentation horizontale a grand ecran.
     *
     * Le bouton manette fait passer d'une presentation a l'autre. On recharge
     * la disposition sur place : passer par requestLayout ne suffit pas si la
     * taille de la vue ne change pas.
     */
    var variantePaysage = 0
        set(v) {
            val n = ((v % 3) + 3) % 3
            if (field == n) return
            field = n
            Dispositions.poserVariante(context, n)
            if (!enPaysage) return
            dispo = Dispositions.charger(context, true)
            if (width > 0 && height > 0) recalculer(width, height)
            invalidate()
        }

    /**
     * Opacite des touches de la couche transparente, de 0,12 a 1. Elle ne
     * touche qu'elle : une console dessinee n'a pas a s'effacer.
     */
    var opaciteBoutons = 0.85f
        set(v) { field = v.coerceIn(0.12f, 1f); invalidate() }

    /** Presentation en cours : l'activite s'en sert pour ses reglages. */
    val surCoucheTransparente: Boolean get() = enPaysage && variantePaysage == 2

    private val cadreJeu = Bitmap.createBitmap(CoeurPs1.MAX_L, CoeurPs1.MAX_H, Bitmap.Config.ARGB_8888)
    private var jeuL = 256
    private var jeuH = 224
    private val srcJeu = Rect(0, 0, 256, 224)

    private val peinture = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val peintureJeu = Paint().apply { isFilterBitmap = true }

    /** Filtre d'affichage : true = lisse, false = pixels francs. */
    var lissage = true
        set(v) { field = v; peintureJeu.isFilterBitmap = v; invalidate() }
    private val noir = Paint().apply { color = Color.BLACK }
    private val traitEdition = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFFC82128.toInt()
        pathEffect = DashPathEffect(floatArrayOf(12f, 9f), 0f)
    }
    private val poignee = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC82128.toInt() }
    private val poigneeBord = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.WHITE
    }

    private var ech = 1f
    private var decX = 0f
    private var decY = 0f
    private val tmp = RectF()
    private val tmp2 = RectF()

    init {
        variantePaysage = Dispositions.variante(ctx)
        opaciteBoutons = Dispositions.opacite(ctx)
        dispo = Dispositions.parDefaut(ctx, false)
        chargerSkin("portrait", touchesP).also { fondP = it }
        chargerSkin("paysage", touchesL).also { fondL = it }
        // La seconde presentation horizontale, celle a grand ecran.
        chargerSkin("paysage_large", touchesLL).also { fondLL = it }
        chargerSkin("paysage_touches", touchesT).also { fondT = it }
        isFocusable = true
    }

    private fun charge(chemin: String): Bitmap =
        context.assets.open(chemin).use { BitmapFactory.decodeStream(it) }

    /** Lit positions.json et charge toutes les images d'un skin. */
    private fun chargerSkin(dossier: String, cible: HashMap<String, Touche>): Bitmap {
        val texte = context.assets.open("ps1/skin/$dossier/positions.json").bufferedReader().use { it.readText() }
        val els = org.json.JSONObject(texte).getJSONArray("elements")
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val id = e.getString("id")
            val im = e.getJSONObject("images")
            val repos = charge("ps1/skin/$dossier/" + im.getString("repos"))
            val appui = ArrayList<Bitmap>()
            val dirs = HashMap<String, List<Bitmap>>()
            im.keys().forEach { k ->
                if (k == "repos") return@forEach
                val liste = im.getJSONArray(k)
                val bms = (0 until liste.length()).map { charge("ps1/skin/$dossier/" + liste.getString(it)) }
                if (k == "appui") appui.addAll(bms) else dirs[k] = bms
            }
            cible[id] = Touche(repos, appui, dirs)
        }
        return charge("ps1/skin/$dossier/fond.png")
    }

    // ================= boucle =================

    private var boucleLancee = false
    private val nsParImage get() = 1_000_000_000.0 / (if (::coeur.isInitialized && coeur.romChargee) coeur.imagesParSeconde else 60.0988)
    private var dernierNs = 0L
    private var reste = 0.0

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!boucleLancee) { boucleLancee = true; boucle() }
    }

    private fun boucle() {
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(ns: Long) {
                if (!isAttachedToWindow) { boucleLancee = false; return }
                Choreographer.getInstance().postFrameCallback(this)
                if (!::coeur.isInitialized) return

                if (dernierNs == 0L) dernierNs = ns
                var ecoule = (ns - dernierNs).toDouble()
                dernierNs = ns
                if (ecoule > 250_000_000.0) ecoule = nsParImage
                reste += ecoule
                val rapide = actifs.containsKey(Ids.FF)
                if (rapide) reste += ecoule * 2.0

                var images = 0
                while (reste >= nsParImage && images < 8) { reste -= nsParImage; images++ }
                // une touche en cours d'animation doit se redessiner meme sans ROM
                if (images == 0) { if (animationEnCours()) invalidate(); return }

                var neuve = false
                val g = sticks[Ids.STICK_G]!!; val d = sticks[Ids.STICK_D]!!
                repeat(images) {
                    if (coeur.imageSuivante(boutons, g.x, g.y, d.x, d.y)) neuve = true
                    val ech = coeur.son()
                    if (!rapide) son?.jouer(ech)
                }
                if (neuve) {
                    jeuL = coeur.largeur; jeuH = coeur.hauteur
                    cadreJeu.setPixels(coeur.tampon, 0, jeuL, 0, 0, jeuL, jeuH)
                    srcJeu.set(0, 0, jeuL, jeuH)
                }
                invalidate()
            }
        })
    }

    private fun animationEnCours(): Boolean {
        val t = System.currentTimeMillis()
        if (actifs.isNotEmpty()) return true
        if (sticks.values.any { it.actif || t - it.retourDepuis < 90 }) return true
        return relaches.values.any { t - it < appuiMiniMs + etapeMs }
    }

    // ================= geometrie =================

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val paysage = w >= h
        if (!dispoChargee || paysage != enPaysage) {
            enPaysage = paysage
            dispo = Dispositions.charger(context, paysage)
            dispoChargee = true
        }
        recalculer(w, h)
    }

    private fun recalculer(w: Int, h: Int) {
        ech = max(w / dispo.sw, h / dispo.sh)
        decX = (w - dispo.sw * ech) / 2f
        decY = (h - dispo.sh * ech) / 2f
    }

    private fun versEcran(r: Rect4, out: RectF) {
        out.set(decX + r.x * ech, decY + r.y * ech, decX + (r.x + r.w) * ech, decY + (r.y + r.h) * ech)
    }

    // ================= dessin =================

    override fun onDraw(c: Canvas) {
        // Trois habillages : la verticale, et les deux presentations
        // horizontales que le bouton manette fait alterner.
        val fond = (if (!enPaysage) fondP
                    else when (variantePaysage) { 1 -> fondLL; 2 -> fondT; else -> fondL }) ?: return
        val touches = if (!enPaysage) touchesP
                      else when (variantePaysage) { 1 -> touchesLL; 2 -> touchesT; else -> touchesL }
        tmp.set(decX, decY, decX + dispo.sw * ech, decY + dispo.sh * ech)
        c.drawBitmap(fond, null, tmp, peinture)

        dispo.items[Ids.SCREEN]?.let { dessinerJeu(c, it) }

        // Sur la couche transparente, les touches se dessinent a l'opacite
        // reglee dans MODIFIER ; le fond et le jeu restent pleins.
        val gardeAlpha = peinture.alpha
        if (surCoucheTransparente)
            peinture.alpha = (255 * opaciteBoutons).toInt().coerceIn(24, 255)

        val maintenant = System.currentTimeMillis()
        for ((id, t) in touches) {
            val r = dispo.items[id] ?: continue
            versEcran(r, tmp)
            val marge = (dispo.marges[id] ?: 0) * ech
            tmp2.set(tmp.left - marge, tmp.top - marge, tmp.right + marge, tmp.bottom + marge)

            if (id in Ids.STICKS) {
                dessinerStick(c, t, id, tmp, tmp2, maintenant)
                continue
            }

            val etape = etapeAppui(id, maintenant)
            if (etape < 0 || t.appui.isEmpty()) c.drawBitmap(t.repos, null, tmp, peinture)
            else c.drawBitmap(t.appui[etape.coerceAtMost(t.appui.size - 1)], null, tmp2, peinture)
        }

        peinture.alpha = gardeAlpha

        if (liste != null) dispo.items[Ids.SCREEN]?.let { dessinerListe(c, it) }
        if (modeEdition) dessinerEdition(c)
        if (afficherDiagnostic) dessinerDiagnostic(c)
    }

    /** -1 au repos, 0 mi-course, 1 fond de course. */
    private fun etapeAppui(id: String, t: Long): Int {
        actifs[id]?.let { debut -> return if (t - debut < etapeMs) 0 else 1 }
        relaches[id]?.let { fin ->
            val d = t - fin
            if (d < appuiMiniMs) return 1
            if (d < appuiMiniMs + etapeMs) return 0
        }
        return -1
    }

    private val SECTEURS = arrayOf("d", "bd", "b", "bg", "g", "hg", "h", "hd")

    /**
     * Le capuchon glisse de sa position (-1..1) x course, et l'image choisie
     * est celle du secteur de 45 degres vers lequel il penche, a deux niveaux.
     * Pendant le retour au centre, la position decroit sur 90 ms.
     */
    private fun dessinerStick(c: Canvas, t: Touche, id: String, repos: RectF, large: RectF, maintenant: Long) {
        val st = sticks[id]!!
        var px = st.x; var py = st.y
        if (!st.actif && maintenant - st.retourDepuis < 90) {
            val f = 1f - (maintenant - st.retourDepuis) / 90f
            px = st.rx * f; py = st.ry * f
        } else if (!st.actif) { px = 0f; py = 0f }
        val course = (dispo.courses[id] ?: 20f) * ech
        val dx = px * course; val dy = py * course
        val norme = hypot(px, py)
        if (norme < 0.10f) {
            repos.offset(dx, dy); c.drawBitmap(t.repos, null, repos, peinture); return
        }
        val sect = SECTEURS[((atan2(py, px) / (Math.PI / 4)).roundToInt() and 7)]
        val frames = t.directions[sect] ?: run { repos.offset(dx, dy); c.drawBitmap(t.repos, null, repos, peinture); return }
        val i = if (norme < 0.55f) 0 else frames.size - 1
        large.offset(dx, dy)
        c.drawBitmap(frames[i], null, large, peinture)
    }

    private val texteVide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A8A8A.toInt(); textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    /**
     * L'image de jeu dans le rectangle d'ecran, sur fond noir.
     * Sans jeu charge, un message le dit : c'est la premiere chose a verifier
     * quand les touches s'animent mais ne font rien.
     */
    private fun dessinerJeu(c: Canvas, r: Rect4) {
        versEcran(r, tmp)
        c.drawRect(tmp, noir)
        if (!::coeur.isInitialized || !coeur.romChargee) {
            texteVide.textSize = tmp.height() * 0.062f
            c.drawText("AUCUN JEU CHARGÉ", tmp.centerX(), tmp.centerY() - texteVide.textSize * 0.2f, texteVide)
            texteVide.textSize = tmp.height() * 0.045f
            c.drawText("bouton JEUX", tmp.centerX(), tmp.centerY() + texteVide.textSize * 1.6f, texteVide)
            return
        }
        val ratio = 4f / 3f
        var w: Float; var h: Float
        when (modeEcran) {
            1 -> { w = tmp.width(); h = tmp.height() }                       // remplit, deforme
            2 -> {                                                            // remplit, rogne
                w = tmp.width(); h = w / ratio
                if (h < tmp.height()) { h = tmp.height(); w = h * ratio }
            }
            3 -> {                                                            // pixel a pixel
                val k = kotlin.math.floor(
                    minOf(tmp.width() / jeuL, tmp.height() / jeuH)).coerceAtLeast(1f)
                w = jeuL * k; h = jeuH * k
            }
            else -> {                                                         // ajuste en 4/3
                w = tmp.width(); h = w / ratio
                if (h > tmp.height()) { h = tmp.height(); w = h * ratio }
            }
        }
        w *= zoomEcran; h *= zoomEcran
        val cx = tmp.centerX(); val cy = tmp.centerY()
        tmp2.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        c.save(); c.clipRect(tmp)
        c.drawBitmap(cadreJeu, srcJeu, tmp2, peintureJeu)
        c.restore()
    }

    /** Bandeau de controle, affiche par-dessus tout quand il est actif. */
    var afficherDiagnostic = false
        set(v) { field = v; invalidate() }

    private val peintureDiag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7CE87C.toInt(); typeface = Typeface.MONOSPACE
    }
    private val fondDiag = Paint().apply { color = 0xB0000000.toInt() }

    private fun dessinerDiagnostic(c: Canvas) {
        val h = height * 0.028f
        peintureDiag.textSize = h * 0.72f
        c.drawRect(0f, 0f, width.toFloat(), h * 1.4f, fondDiag)
        c.drawText(diagnostic(), h * 0.4f, h, peintureDiag)
    }

    /** Ce que l'application recoit reellement : sert au diagnostic. */
    fun diagnostic(): String {
        val g = sticks[Ids.STICK_G]!!
        return "boutons %04X  doigts %d  stickG %.2f,%.2f  jeu %s %dx%d".format(
            boutons, doigts.size, g.x, g.y,
            if (::coeur.isInitialized && coeur.romChargee) "oui" else "non", jeuL, jeuH)
    }

    // ================= catalogue dans l'ecran =================

    private var liste: List<String>? = null
    private var defilement = 0f
    private var rangPresse = -1
    val listeOuverte: Boolean get() = liste != null
    fun afficherListe(noms: List<String>) { liste = noms; defilement = 0f; rangPresse = -1; invalidate() }
    fun fermerListe() { liste = null; rangPresse = -1; invalidate() }

    private val texteListe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); color = Color.WHITE
    }
    private val barreListe = Paint().apply { color = Color.WHITE }

    private fun dessinerListe(c: Canvas, r: Rect4) {
        val noms = liste ?: return
        versEcran(r, tmp)
        c.save(); c.clipRect(tmp); c.drawRect(tmp, noir)
        val rangs = 9f; val h = tmp.height() / rangs
        texteListe.textSize = h * 0.58f
        val marge = tmp.width() * 0.045f
        texteListe.color = 0xFF9A9A9A.toInt()
        c.drawText("CHOISIR UN JEU   ${noms.size}", tmp.left + marge, tmp.top + h * 0.72f, texteListe)
        c.drawRect(tmp.left + marge, tmp.top + h * 0.95f, tmp.right - marge, tmp.top + h * 0.95f + 2f, barreListe)
        val hautListe = tmp.top + h * 1.3f
        val premier = (defilement / h).toInt().coerceAtLeast(0)
        val dernier = (premier + rangs.toInt()).coerceAtMost(noms.size - 1)
        for (i in premier..dernier) {
            val y = hautListe + i * h - defilement
            if (y > tmp.bottom) break
            val presse = i == rangPresse
            texteListe.color = if (presse) Color.BLACK else Color.WHITE
            if (presse) c.drawRect(tmp.left, y - h * 0.72f, tmp.right, y + h * 0.24f, barreListe)
            c.drawText(tronquer(noms[i].substringBeforeLast('.').uppercase(), tmp.width() - marge * 3f),
                       tmp.left + marge * 2f, y, texteListe)
            if (presse) c.drawText(">", tmp.left + marge * 0.5f, y, texteListe)
        }
        c.restore()
    }

    private fun tronquer(s: String, largeur: Float): String {
        if (texteListe.measureText(s) <= largeur) return s
        var n = s.length
        while (n > 1 && texteListe.measureText(s.substring(0, n) + "…") > largeur) n--
        return s.substring(0, n) + "…"
    }

    private var listeY0 = 0f; private var listeDefil0 = 0f
    private var listeAGlisse = false; private var listeActive = false

    private fun listeTactile(e: MotionEvent): Boolean {
        val noms = liste ?: return false
        val r = dispo.items[Ids.SCREEN] ?: return false
        versEcran(r, tmp)
        val x = e.x; val y = e.y
        val h = tmp.height() / 9f; val hautListe = tmp.top + h * 1.3f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                listeActive = tmp.contains(x, y)
                if (!listeActive) return false
                listeY0 = y; listeDefil0 = defilement; listeAGlisse = false
                rangPresse = (((y - hautListe + defilement) + h * 0.72f) / h).toInt()
                if (rangPresse !in noms.indices) rangPresse = -1
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!listeActive) return false
                val d = y - listeY0
                if (abs(d) > 12f) {
                    listeAGlisse = true; rangPresse = -1
                    val total = noms.size * h - (tmp.height() - h * 1.3f)
                    defilement = (listeDefil0 - d).coerceIn(0f, max(0f, total)); invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!listeActive) return false
                listeActive = false
                val choisi = rangPresse; rangPresse = -1; invalidate()
                if (!listeAGlisse && choisi in noms.indices) surChoixJeu?.invoke(choisi)
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!listeActive) return false
                listeActive = false; rangPresse = -1; invalidate()
            }
            else -> return listeActive
        }
        return true
    }

    // ================= tactile =================

    private val doigts = HashMap<Int, String>()
    private val ordreCapture = Ids.FONCTIONS + listOf(Ids.FF, Ids.L1, Ids.L2, Ids.R1, Ids.R2,
        Ids.SELECT, Ids.START, Ids.TRIANGLE, Ids.ROND, Ids.CARRE, Ids.CROIX,
        Ids.HAUT, Ids.BAS, Ids.GAUCHE, Ids.DROITE, Ids.STICK_G, Ids.STICK_D)

    /** Les sticks captent un peu au-dela de leur capuchon : le doigt glisse. */
    private fun elementSousLarge(x: Float, y: Float): String? {
        elementSous(x, y)?.let { return it }
        for (id in Ids.STICKS) {
            val r = dispo.items[id] ?: continue
            versEcran(r, tmp)
            val m = tmp.width() * 0.35f
            tmp.inset(-m, -m)
            if (tmp.contains(x, y)) return id
        }
        return null
    }

    private fun elementSous(x: Float, y: Float): String? {
        for (id in ordreCapture) {
            val r = dispo.items[id] ?: continue
            versEcran(r, tmp)
            if (tmp.contains(x, y)) return id
        }
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (liste != null && !modeEdition && listeTactile(e)) return true
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val el = elementSous(e.getX(e.actionIndex), e.getY(e.actionIndex))
            if (el == null || !Ids.FONCTIONS.contains(el)) surTouche?.invoke()
        }
        if (modeEdition) return editionTactile(e)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                appuyer(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val pid = e.getPointerId(i)
                    if (!suivis.contains(pid)) continue
                    val id = doigts[pid]
                    if (id != null && id in Ids.STICKS) { majStick(id, e.getX(i), e.getY(i)); continue }
                    glisser(pid, e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) doigts.keys.toList().forEach { relacher(it) }
                else relacher(e.getPointerId(e.actionIndex))
            }
        }
        majBoutons()
        invalidate()
        return true
    }

    /**
     * Le doigt reste suivi une fois pose, meme s'il survole le vide entre deux
     * touches : sans cela, passer d'une fleche a l'autre en glissant faisait
     * perdre le doigt des le premier pixel d'ecart, et la seconde fleche ne
     * repondait plus.
     */
    private val suivis = HashSet<Int>()

    private fun glisser(pid: Int, x: Float, y: Float) {
        val avant = doigts[pid]
        // La croix d'abord : on doit pouvoir rouler le doigt d'une direction a
        // l'autre, et passer par les diagonales sans lever.
        if (majFleches(pid, x, y)) return
        if (avant in FLECHES) {
            // le doigt vient de quitter la croix
            for (id in FLECHES) if (doigts.values.none { it == id }) actifs.remove(id)
            doigts.remove(pid)
        }
        val sous = elementSousLarge(x, y)
        if (sous == avant) return
        // on quitte la touche precedente
        if (avant != null) {
            doigts.remove(pid)
            if (doigts.values.none { it == avant }) {
                actifs.remove(avant)
                relaches[avant] = System.currentTimeMillis()
            }
        }
        // les fonctions et les sticks ne se prennent pas au vol : seulement a l'appui
        if (sous != null && sous !in Ids.STICKS && sous !in Ids.FONCTIONS) {
            doigts[pid] = sous
            actifs[sous] = System.currentTimeMillis()
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private fun appuyer(pid: Int, x: Float, y: Float) {
        suivis.add(pid)
        if (majFleches(pid, x, y)) {
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            return
        }
        val el = elementSousLarge(x, y) ?: return
        val t = System.currentTimeMillis()
        doigts[pid] = el
        if (el in Ids.STICKS) {
            val st = sticks[el]!!
            st.actif = true; st.x0 = x; st.y0 = y; st.x = 0f; st.y = 0f
        } else {
            actifs[el] = t
            when (el) {
                Ids.MENU -> surMenu?.invoke()
                Ids.QUIT -> surQuit?.invoke()
                Ids.JEUX -> surJeux?.invoke()
                // La manette bascule d'une presentation horizontale a l'autre
                Ids.MANETTE -> { variantePaysage += 1; surManette?.invoke() }
            }
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun relacher(pid: Int) {
        suivis.remove(pid)
        val el = doigts.remove(pid) ?: return
        if (el in FLECHES) {
            // la croix est tenue par un seul doigt : tout retombe ensemble
            for (id in FLECHES) {
                actifs.remove(id)
                relaches[id] = System.currentTimeMillis()
            }
            return
        }
        if (el in Ids.STICKS) {
            val st = sticks[el]!!
            st.rx = st.x; st.ry = st.y; st.x = 0f; st.y = 0f
            st.actif = false; st.retourDepuis = System.currentTimeMillis()
            return
        }
        if (doigts.values.none { it == el }) {
            actifs.remove(el)
            relaches[el] = System.currentTimeMillis()
        }
    }

    /** Les quatre fleches, prises ensemble. */
    private val FLECHES = listOf(Ids.HAUT, Ids.BAS, Ids.GAUCHE, Ids.DROITE)

    /**
     * Directions tenues par la croix, d'apres la position du doigt.
     *
     * Les quatre fleches etaient quatre touches separees : un doigt ne pouvant
     * en toucher qu'une, aucune diagonale n'etait possible — sauter en avant,
     * ou toute combinaison d'un jeu de combat, restait hors de portee. On
     * raisonne donc sur la croix entiere : la direction vient de l'ecart entre
     * le doigt et le centre, ce qui donne les huit directions.
     */
    private fun majFleches(pid: Int, x: Float, y: Float): Boolean {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var trouve = false
        for (id in FLECHES) {
            val q = dispo.items[id] ?: continue
            versEcran(q, tmp)
            l = minOf(l, tmp.left); t = minOf(t, tmp.top)
            r = maxOf(r, tmp.right); b = maxOf(b, tmp.bottom)
            trouve = true
        }
        if (!trouve) return false
        // marge de confort : la croix reste tenue un peu au-dela de son dessin
        val mx = (r - l) * 0.14f; val my = (b - t) * 0.14f
        if (x < l - mx || x > r + mx || y < t - my || y > b + my) return false

        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        val nx = (x - cx) / ((r - l) / 2f)
        val ny = (y - cy) / ((b - t) / 2f)

        // Un seuil bas sur l'axe secondaire : il suffit de mordre un peu vers
        // le haut en poussant a droite pour obtenir la diagonale, comme sur
        // une vraie croix ou le doigt appuie sur deux bras a la fois.
        val seuil = 0.26f
        for (id in FLECHES) actifs.remove(id)
        if (ny < -seuil) actifs[Ids.HAUT] = System.currentTimeMillis()
        if (ny > seuil) actifs[Ids.BAS] = System.currentTimeMillis()
        if (nx < -seuil) actifs[Ids.GAUCHE] = System.currentTimeMillis()
        if (nx > seuil) actifs[Ids.DROITE] = System.currentTimeMillis()
        doigts[pid] = Ids.HAUT          // jeton : ce doigt tient la croix
        return true
    }

    /** Position du stick d'apres le deplacement du doigt depuis l'appui, bornee a la course. */
    private fun majStick(id: String, x: Float, y: Float) {
        val st = sticks[id]!!
        val course = (dispo.courses[id] ?: 20f) * ech
        var nx = (x - st.x0) / course
        var ny = (y - st.y0) / course
        val n = hypot(nx, ny)
        if (n > 1f) { nx /= n; ny /= n }
        if (n < zoneMorte) { nx = 0f; ny = 0f }
        st.x = nx; st.y = ny
    }

    private val TABLE = mapOf(
        Ids.ROND to Pad.A, Ids.CROIX to Pad.B, Ids.TRIANGLE to Pad.X, Ids.CARRE to Pad.Y,
        Ids.L1 to Pad.L1, Ids.R1 to Pad.R1, Ids.L2 to Pad.L2, Ids.R2 to Pad.R2,
        Ids.SELECT to Pad.SELECT, Ids.START to Pad.START,
        Ids.HAUT to Pad.HAUT, Ids.BAS to Pad.BAS, Ids.GAUCHE to Pad.GAUCHE, Ids.DROITE to Pad.DROITE)

    /**
     * Le stick gauche commande aussi la croix.
     *
     * Beaucoup de jeux PlayStation ne lisent que la croix — c'est le cas des
     * jeux de combat, et de tous ceux d'avant la manette analogique. Le stick
     * y restait donc muet. Il envoie desormais les deux : sa position exacte
     * pour les jeux qui la lisent, et les directions correspondantes pour les
     * autres.
     */
    var stickCommandeCroix = true

    private fun majBoutons() {
        var b = 0
        for ((id, bit) in TABLE) if (actifs.containsKey(id)) b = b or bit
        if (stickCommandeCroix) {
            val st = sticks[Ids.STICK_G]
            if (st != null && st.actif) {
                val seuil = 0.42f
                if (st.y < -seuil) b = b or Pad.HAUT
                if (st.y > seuil) b = b or Pad.BAS
                if (st.x < -seuil) b = b or Pad.GAUCHE
                if (st.x > seuil) b = b or Pad.DROITE
            }
        }
        boutons = b
    }

    // ================= editeur de disposition =================

    private var edPoignee: String? = null
    private var edId: String? = null
    private var edDepart = Rect4(0f, 0f, 0f, 0f)
    private var edX0 = 0f
    private var edY0 = 0f

    /**
     * Les huit poignees de l'ecran de jeu.
     *
     * Elles ne concernent que lui : les touches gardent les leurs, telles
     * qu'elles etaient. Les quatre coins etirent en diagonale, les quatre
     * milieux de cote n'agissent que sur la largeur ou que sur la hauteur, et
     * rien n'est tenu — l'image prend exactement la forme qu'on lui donne.
     */
    private fun poigneesEcran(r: Rect4): List<Pair<String, PointF>> {
        versEcran(r, tmp)
        val xs = listOf("g" to tmp.left, "" to tmp.centerX(), "d" to tmp.right)
        val ys = listOf("h" to tmp.top, "" to tmp.centerY(), "b" to tmp.bottom)
        val out = ArrayList<Pair<String, PointF>>(8)
        for ((cy, py) in ys) for ((cx, px) in xs) {
            val nom = cy + cx
            if (nom.isNotEmpty()) out.add(nom to PointF(px, py))
        }
        return out
    }

    /** Etire l'ecran par la poignee saisie, sans conserver aucun rapport. */
    private fun etirerEcran(d: Rect4, dx: Float, dy: Float, prise: String): Rect4 {
        var x = d.x; var y = d.y; var w = d.w; var h = d.h
        if (prise.contains("g")) { x = d.x + dx; w = d.w - dx }
        if (prise.contains("d")) { w = d.w + dx }
        if (prise.contains("h")) { y = d.y + dy; h = d.h - dy }
        if (prise.contains("b")) { h = d.h + dy }
        if (w < 30f) { if (prise.contains("g")) x = d.x + d.w - 30f; w = 30f }
        if (h < 30f) { if (prise.contains("h")) y = d.y + d.h - 30f; h = 30f }
        return Rect4(x, y, w, h)
    }

    private fun dessinerEdition(c: Canvas) {
        for ((id, r) in dispo.items) {
            versEcran(r, tmp)
            traitEdition.color = if (id == Ids.SCREEN) 0xFF3AA0FF.toInt() else 0xFFC82128.toInt()
            poignee.color = traitEdition.color
            c.drawRect(tmp, traitEdition)
            val points = if (id == Ids.SCREEN) poigneesEcran(r).map { it.second }
                         else listOf(PointF(tmp.right, tmp.centerY()),
                                     PointF(tmp.centerX(), tmp.bottom),
                                     PointF(tmp.right, tmp.bottom))
            for (pt in points) {
                c.drawCircle(pt.x, pt.y, 22f, poignee)
                c.drawCircle(pt.x, pt.y, 22f, poigneeBord)
            }
        }
    }

    private fun editionTactile(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edId = null; edPoignee = null
                val p = 34f
                /* L'ecran d'abord, par ses huit poignees : elles debordent
                   souvent sur une touche voisine, et sans cette priorite un
                   bord serait impossible a attraper. */
                dispo.items[Ids.SCREEN]?.let { r ->
                    for ((nom, pt) in poigneesEcran(r)) {
                        if (abs(x - pt.x) < p && abs(y - pt.y) < p) {
                            edId = Ids.SCREEN; edPoignee = nom; break
                        }
                    }
                }
                if (edId == null) for ((id, r) in dispo.items) {
                    if (id == Ids.SCREEN) continue
                    versEcran(r, tmp)
                    when {
                        abs(x - tmp.right) < p && abs(y - tmp.bottom) < p -> { edId = id; edPoignee = "se" }
                        abs(x - tmp.right) < p && abs(y - tmp.centerY()) < p -> { edId = id; edPoignee = "e" }
                        abs(x - tmp.centerX()) < p && abs(y - tmp.bottom) < p -> { edId = id; edPoignee = "s" }
                        tmp.contains(x, y) -> { edId = id; edPoignee = null }
                    }
                    if (edId != null) break
                }
                if (edId == null) dispo.items[Ids.SCREEN]?.let {
                    versEcran(it, tmp); if (tmp.contains(x, y)) edId = Ids.SCREEN
                }
                edId?.let { edDepart = dispo.items[it]!!.copy4(); edX0 = x; edY0 = y }
            }
            MotionEvent.ACTION_MOVE -> {
                val id = edId ?: return true
                val dx = (x - edX0) / ech; val dy = (y - edY0) / ech
                val prise = edPoignee
                if (id == Ids.SCREEN) {
                    dispo.items[id] = if (prise == null)
                        Rect4(edDepart.x + dx, edDepart.y + dy, edDepart.w, edDepart.h)
                    else etirerEcran(edDepart, dx, dy, prise)
                    invalidate()
                    return true
                }
                // Les touches se modifient comme avant : deplacement, largeur,
                // hauteur, et le coin qui agrandit la piece sans la deformer.
                val r = dispo.items[id]!!
                when (prise) {
                    null -> { r.x = edDepart.x + dx; r.y = edDepart.y + dy }
                    "e" -> r.w = max(30f, edDepart.w + dx)
                    "s" -> r.h = max(30f, edDepart.h + dy)
                    "se" -> {
                        val f = max(0.2f, (edDepart.w + dx) / edDepart.w)
                        r.w = max(30f, edDepart.w * f); r.h = max(30f, edDepart.h * f)
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { edId = null; edPoignee = null }
        }
        return true
    }

    /**
     * L'ecran prend toute la vue, bandes comprises.
     *
     * L'habillage est cale au plus grand dans l'ecran du telephone : quand
     * leurs proportions different, il reste une bande de chaque cote. Le
     * rectangle de jeu, lui, n'est pas tenu par l'habillage.
     */
    fun ecranPleinePage() {
        if (width <= 0 || height <= 0 || ech <= 0f) return
        dispo.items[Ids.SCREEN] = Rect4(-decX / ech, -decY / ech, width / ech, height / ech)
        Dispositions.enregistrer(context, enPaysage, dispo)
        invalidate()
    }

    /** L'ecran retrouve la dalle dessinee sur l'habillage. */
    fun ecranOrigine() {
        val defaut = Dispositions.parDefaut(context, enPaysage).items[Ids.SCREEN] ?: return
        dispo.items[Ids.SCREEN] = defaut.copy4()
        Dispositions.enregistrer(context, enPaysage, dispo)
        invalidate()
    }

    fun enregistrerDisposition() = Dispositions.enregistrer(context, enPaysage, dispo)

    fun reinitialiserDisposition() {
        Dispositions.reinitialiser(context, enPaysage)
        dispo = Dispositions.parDefaut(context, enPaysage)
        recalculer(width, height)
        invalidate()
    }
}
