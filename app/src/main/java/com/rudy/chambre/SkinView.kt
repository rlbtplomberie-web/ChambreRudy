package com.rudy.chambre

import android.content.Context
import android.graphics.*
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.rudy.chambre.core.CoeurSnes
import com.rudy.chambre.core.Pad
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/**
 * Dessine le skin et fait tourner la console.
 *
 * Contrairement au skin NES, les touches ne sont pas animees a la volee :
 * chaque touche a ses images pre-calculees (repos, mi-course, fond de course ;
 * huit directions pour la croix), et la vue se contente de choisir laquelle
 * afficher. L'ombre du logement et l'eclairage y sont deja peints.
 */
class SkinView(ctx: Context) : View(ctx) {

    // ---------- reglages ----------
    /** Zone morte au centre de la croix, en fraction du rayon. */
    var zoneMorte = 0.26f
    /** Duree d'une etape d'enfoncement, en ms. */
    var etapeMs = 34L
    /** Duree minimale d'affichage d'un appui bref. */
    var appuiMiniMs = 110L
    // --------------------------------

    lateinit var coeur: CoeurSnes
    var son: Son? = null
    var surTouche: (() -> Unit)? = null
    var surMenu: (() -> Unit)? = null
    var surPower: (() -> Unit)? = null
    var surReset: (() -> Unit)? = null
    var surJeux: (() -> Unit)? = null
    var surChoixJeu: ((Int) -> Unit)? = null
    var modeEdition = false
        set(v) { field = v; invalidate() }

    var boutons = 0
        private set

    // ---------- etat des touches ----------
    private val actifs = HashMap<String, Long>()
    private val relaches = HashMap<String, Long>()
    private var dirX = 0
    private var dirY = 0
    private var dirDepuis = 0L
    private var dernierDir = ""

    // ---------- images ----------
    private class Touche(val repos: Bitmap, val appui: List<Bitmap>, val directions: Map<String, List<Bitmap>>)
    private var fondP: Bitmap? = null
    private var fondL: Bitmap? = null
    private val touchesP = HashMap<String, Touche>()
    private val touchesL = HashMap<String, Touche>()
    /** La seconde presentation horizontale, celle a grand ecran. */
    private var fondL2: Bitmap? = null
    private val touchesL2 = HashMap<String, Touche>()
    /** La troisieme : une couche transparente posee sur le jeu. */
    private var fondL3: Bitmap? = null
    private val touchesL3 = HashMap<String, Touche>()

    /**
     * Presentation horizontale en cours : 0 l'ecran normal, 1 le grand ecran,
     * 2 la couche transparente.
     *
     * Le bouton manette passe a la suivante. On recharge la disposition sur
     * place : la taille de la vue ne changeant pas, il ne se passerait rien
     * autrement.
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

    /** true quand la vue est en horizontal : l'activite s'en sert pour ses reglages. */
    val estPaysage: Boolean get() = enPaysage

    /**
     * Opacite des touches de la couche transparente, de 0,12 (a peine
     * visible) a 1. Les deux autres presentations n'en tiennent pas compte :
     * une console dessinee n'a pas a s'effacer.
     */
    var opaciteBoutons = 0.85f
        set(v) { field = v.coerceIn(0.12f, 1f); invalidate() }

    /** Prevenu quand la presentation horizontale a change. */
    var surManette: (() -> Unit)? = null

    private var dispo: Disposition
    private var enPaysage = false
    private var dispoChargee = false

    private val cadreJeu = Bitmap.createBitmap(CoeurSnes.MAX_L, CoeurSnes.MAX_H, Bitmap.Config.ARGB_8888)
    private var jeuL = 256
    private var jeuH = 224
    private val srcJeu = Rect(0, 0, 256, 224)

    private val peinture = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val peintureJeu = Paint().apply { isFilterBitmap = true }
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
        dispo = Dispositions.parDefaut(ctx, false)
        chargerSkin("portrait", touchesP).also { fondP = it }
        chargerSkin("paysage", touchesL).also { fondL = it }
        chargerSkin("paysage2", touchesL2).also { fondL2 = it }
        chargerSkin("paysage3", touchesL3).also { fondL3 = it }
        variantePaysage = Dispositions.variante(ctx)
        opaciteBoutons = Dispositions.opacite(ctx)
        isFocusable = true
    }

    private fun charge(chemin: String): Bitmap =
        context.assets.open(chemin).use { BitmapFactory.decodeStream(it) }

    /** Lit positions.json et charge toutes les images d'un skin. */
    private fun chargerSkin(dossier: String, cible: HashMap<String, Touche>): Bitmap {
        val texte = context.assets.open("skin/$dossier/positions.json").bufferedReader().use { it.readText() }
        val els = org.json.JSONObject(texte).getJSONArray("elements")
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val id = e.getString("id")
            val im = e.getJSONObject("images")
            val repos = charge("skin/$dossier/" + im.getString("repos"))
            val appui = ArrayList<Bitmap>()
            val dirs = HashMap<String, List<Bitmap>>()
            im.keys().forEach { k ->
                if (k == "repos") return@forEach
                val liste = im.getJSONArray(k)
                val bms = (0 until liste.length()).map { charge("skin/$dossier/" + liste.getString(it)) }
                if (k == "appui") appui.addAll(bms) else dirs[k] = bms
            }
            cible[id] = Touche(repos, appui, dirs)
        }
        return charge("skin/$dossier/fond.png")
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
                repeat(images) {
                    if (coeur.imageSuivante(boutons)) neuve = true
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
        val couche = enPaysage && variantePaysage == 2
        val fond = (if (!enPaysage) fondP
                    else when (variantePaysage) { 1 -> fondL2; 2 -> fondL3; else -> fondL }) ?: return
        val touches = if (!enPaysage) touchesP
                      else when (variantePaysage) { 1 -> touchesL2; 2 -> touchesL3; else -> touchesL }
        tmp.set(decX, decY, decX + dispo.sw * ech, decY + dispo.sh * ech)
        c.drawBitmap(fond, null, tmp, peinture)

        dispo.items[Ids.SCREEN]?.let { dessinerJeu(c, it) }

        // Sur la couche transparente, les touches se dessinent a l'opacite
        // reglee dans le mode Modifier. On la retire ensuite : le fond et le
        // jeu, eux, restent pleins.
        val gardeAlpha = peinture.alpha
        if (couche) peinture.alpha = (255 * opaciteBoutons).toInt().coerceIn(24, 255)

        val maintenant = System.currentTimeMillis()
        for ((id, t) in touches) {
            val r = dispo.items[id] ?: continue
            versEcran(r, tmp)
            val marge = (dispo.marges[id] ?: 0) * ech
            tmp2.set(tmp.left - marge, tmp.top - marge, tmp.right + marge, tmp.bottom + marge)

            if (id == Ids.CROIX) {
                val cle = if (dirX != 0 || dirY != 0) nomDirection(dirX, dirY) else ""
                if (cle.isEmpty() || !actifs.containsKey(Ids.CROIX)) {
                    c.drawBitmap(t.repos, null, tmp, peinture)
                } else {
                    val frames = t.directions[cle] ?: continue
                    val i = if (maintenant - dirDepuis < etapeMs) 0 else frames.size - 1
                    c.drawBitmap(frames[i], null, tmp2, peinture)
                }
                continue
            }

            val etape = etapeAppui(id, maintenant)
            if (etape < 0 || t.appui.isEmpty()) c.drawBitmap(t.repos, null, tmp, peinture)
            else c.drawBitmap(t.appui[etape.coerceAtMost(t.appui.size - 1)], null, tmp2, peinture)
        }

        peinture.alpha = gardeAlpha

        if (liste != null) dispo.items[Ids.SCREEN]?.let { dessinerListe(c, it) }
        if (modeEdition) dessinerEdition(c)
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

    private fun nomDirection(dx: Int, dy: Int): String = when {
        dx == 0 && dy < 0 -> "h"; dx == 0 && dy > 0 -> "b"
        dx < 0 && dy == 0 -> "g"; dx > 0 && dy == 0 -> "d"
        dx < 0 && dy < 0 -> "hg"; dx > 0 && dy < 0 -> "hd"
        dx < 0 && dy > 0 -> "bg"; dx > 0 && dy > 0 -> "bd"
        else -> ""
    }

    /** L'image de jeu, en 4/3 centree dans le rectangle d'ecran, sur fond noir. */
    private fun dessinerJeu(c: Canvas, r: Rect4) {
        versEcran(r, tmp)
        c.drawRect(tmp, noir)
        if (!::coeur.isInitialized || !coeur.romChargee) return
        val ratio = 4f / 3f
        var w = tmp.width(); var h = w / ratio
        if (h > tmp.height()) { h = tmp.height(); w = h * ratio }
        val cx = tmp.centerX(); val cy = tmp.centerY()
        tmp2.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        c.drawBitmap(cadreJeu, srcJeu, tmp2, peintureJeu)
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
    /** Doigts poses, meme lorsqu'ils survolent le vide entre deux touches. */
    private val suivis = HashSet<Int>()
    private val ordreCapture = Ids.FONCTIONS + listOf(Ids.FF, Ids.L, Ids.R, Ids.SELECT, Ids.START,
                                                       Ids.X, Ids.Y, Ids.A, Ids.B, Ids.CROIX)

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
                    val id = e.getPointerId(i)
                    if (!suivis.contains(id)) continue
                    val x = e.getX(i); val y = e.getY(i)
                    if (doigts[id] == Ids.CROIX) { majCroix(x, y); continue }
                    // Glisser d'une touche a l'autre enfonce la nouvelle sans
                    // qu'on ait a lever le doigt. Le doigt reste suivi meme
                    // au-dessus du vide entre deux touches, sans quoi le
                    // passage se perdait des le premier pixel d'ecart.
                    val sous = elementSous(x, y)
                    if (sous == doigts[id]) continue
                    if (sous == Ids.CROIX) continue    // la croix se prend en la visant
                    relacher(id)
                    suivis.add(id)
                    // Jamais une touche de l'application au passage du doigt :
                    // eteindre la console en glissant serait facheux.
                    if (sous != null && sous !in Ids.FONCTIONS) appuyer(id, x, y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) {
                    doigts.keys.toList().forEach { relacher(it) }
                    suivis.clear()
                } else {
                    val pid = e.getPointerId(e.actionIndex)
                    relacher(pid)
                    suivis.remove(pid)   // le suivi ne cesse qu'au lever du doigt
                }
            }
        }
        majBoutons()
        invalidate()
        return true
    }

    private fun appuyer(pid: Int, x: Float, y: Float) {
        suivis.add(pid)
        val el = elementSous(x, y) ?: return
        val t = System.currentTimeMillis()
        doigts[pid] = el; actifs[el] = t
        when (el) {
            Ids.CROIX -> majCroix(x, y)
            // La manette passe a la presentation horizontale suivante
            Ids.MANETTE -> { variantePaysage += 1; surManette?.invoke() }
            Ids.MENU -> surMenu?.invoke()
            Ids.POWER -> surPower?.invoke()
            Ids.RESET -> surReset?.invoke()
            Ids.JEUX -> surJeux?.invoke()
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun relacher(pid: Int) {
        val el = doigts.remove(pid) ?: return
        actifs.remove(el)
        relaches[el] = System.currentTimeMillis()
        if (el == Ids.CROIX) { dirX = 0; dirY = 0; dernierDir = "" }
    }

    private fun majCroix(x: Float, y: Float) {
        val r = dispo.items[Ids.CROIX] ?: return
        versEcran(r, tmp)
        val nx = (x - tmp.left) / tmp.width() * 2f - 1f
        val ny = (y - tmp.top) / tmp.height() * 2f - 1f
        dirX = if (abs(nx) < zoneMorte) 0 else sign(nx).toInt()
        dirY = if (abs(ny) < zoneMorte) 0 else sign(ny).toInt()
        val nom = nomDirection(dirX, dirY)
        if (nom != dernierDir) { dernierDir = nom; dirDepuis = System.currentTimeMillis() }
    }

    private fun majBoutons() {
        var b = 0
        if (actifs.containsKey(Ids.A)) b = b or Pad.A
        if (actifs.containsKey(Ids.B)) b = b or Pad.B
        if (actifs.containsKey(Ids.X)) b = b or Pad.X
        if (actifs.containsKey(Ids.Y)) b = b or Pad.Y
        if (actifs.containsKey(Ids.L)) b = b or Pad.L
        if (actifs.containsKey(Ids.R)) b = b or Pad.R
        if (actifs.containsKey(Ids.SELECT)) b = b or Pad.SELECT
        if (actifs.containsKey(Ids.START)) b = b or Pad.START
        if (dirY < 0) b = b or Pad.HAUT
        if (dirY > 0) b = b or Pad.BAS
        if (dirX < 0) b = b or Pad.GAUCHE
        if (dirX > 0) b = b or Pad.DROITE
        boutons = b
    }

    // ================= editeur de disposition =================

    private var edPoignee: String? = null
    private var edId: String? = null
    private var edDepart = Rect4(0f, 0f, 0f, 0f)
    private var edX0 = 0f
    private var edY0 = 0f

    private fun dessinerEdition(c: Canvas) {
        for ((id, r) in dispo.items) {
            versEcran(r, tmp)
            traitEdition.color = if (id == Ids.SCREEN) 0xFF3AA0FF.toInt() else 0xFFC82128.toInt()
            poignee.color = traitEdition.color
            c.drawRect(tmp, traitEdition)
            for (pt in listOf(PointF(tmp.right, tmp.centerY()), PointF(tmp.centerX(), tmp.bottom),
                              PointF(tmp.right, tmp.bottom))) {
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
                for ((id, r) in dispo.items) {
                    versEcran(r, tmp)
                    val p = 34f
                    when {
                        abs(x - tmp.right) < p && abs(y - tmp.bottom) < p -> { edId = id; edPoignee = "se" }
                        abs(x - tmp.right) < p && abs(y - tmp.centerY()) < p -> { edId = id; edPoignee = "e" }
                        abs(x - tmp.centerX()) < p && abs(y - tmp.bottom) < p -> { edId = id; edPoignee = "s" }
                        tmp.contains(x, y) && id != Ids.SCREEN -> { edId = id; edPoignee = null }
                    }
                    if (edId != null) break
                }
                if (edId == null) dispo.items[Ids.SCREEN]?.let { versEcran(it, tmp); if (tmp.contains(x, y)) edId = Ids.SCREEN }
                edId?.let { edDepart = dispo.items[it]!!.copy4(); edX0 = x; edY0 = y }
            }
            MotionEvent.ACTION_MOVE -> {
                val id = edId ?: return true
                val r = dispo.items[id]!!
                val dx = (x - edX0) / ech; val dy = (y - edY0) / ech
                when (edPoignee) {
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

    fun enregistrerDisposition() = Dispositions.enregistrer(context, enPaysage, dispo)

    fun reinitialiserDisposition() {
        Dispositions.reinitialiser(context, enPaysage)
        dispo = Dispositions.parDefaut(context, enPaysage)
        recalculer(width, height)
        invalidate()
    }
}
