package com.skingc.app

import android.content.Context
import android.graphics.*
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.skingc.app.core.Pad
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
 * position, de -1 a 1 sur chaque axe, est transmise en continu a la couche
 * OpenGL, qui la remet au coeur.
 */
class SkinView(ctx: Context) : View(ctx) {

    // ---------- reglages ----------
    /** Zone morte des sticks, en fraction de la course. */
    var zoneMorte = 0.08f

    /** Duree d'une etape d'enfoncement, en ms. */
    var etapeMs = 34L
    /** Duree minimale d'affichage d'un appui bref. */
    var appuiMiniMs = 110L
    // --------------------------------

    /** Etat des commandes, lu par la couche OpenGL a chaque image. */
    var surCommandes: ((Int, Float, Float, Float, Float, Boolean) -> Unit)? = null
    /** Le rectangle de l'ecran, en pixels de la vue, pose a chaque mesure. */
    var surCadre: ((Float, Float, Float, Float) -> Unit)? = null
    /**
     * true quand la couche OpenGL dessine elle-meme l'image, sous le skin.
     * Le rectangle de l'ecran doit alors rester transparent.
     */
    var trouEcran = false
        set(v) { field = v; fondPret?.recycle(); fondPret = null; invalidate() }

    /** true tant qu'aucun jeu ne tourne : on affiche alors un message. */
    var ecranVide = true
        set(v) { field = v; invalidate() }

    var lissage = true
        set(v) { field = v; peintureJeu.isFilterBitmap = v; invalidate() }

    private val peintureJeu = Paint().apply { isFilterBitmap = true }
    /** Assez grand pour la plus haute resolution proposee. */
    private val cadreJeu = Bitmap.createBitmap(1920, 1440, Bitmap.Config.ARGB_8888)
    private val srcJeu = Rect(0, 0, 1, 1)
    private var jeuPret = false
    private var refusNotes = 0

    /**
     * Depose l'image relue par la couche OpenGL. Appele depuis le fil du
     * rendu : on ne fait que recopier, l'affichage suit a la prochaine image.
     */
    fun poserImage(pixels: IntArray, l: Int, h: Int) {
        // Bornes verifiees explicitement : setPixels lit l * h entiers et
        // depasser le tableau ferait tomber l'application sans message.
        if (l <= 0 || h <= 0) return
        // Ces trois refus etaient silencieux : l'image restait celle d'avant,
        // sans que rien ne le dise. Ils parlent maintenant.
        if (l > cadreJeu.width || h > cadreJeu.height) {
            if (refusNotes < 3) {
                refusNotes++
                surErreur?.invoke("image " + l + "x" + h + " plus grande que le cadre " +
                                  cadreJeu.width + "x" + cadreJeu.height + " : ignorée")
            }
            return
        }
        if (l.toLong() * h > pixels.size) {
            if (refusNotes < 3) {
                refusNotes++
                surErreur?.invoke("image " + l + "x" + h + " plus grande que le tableau : ignorée")
            }
            return
        }
        if (l != srcJeu.width() || h != srcJeu.height()) {
            surErreur?.invoke("image affichée : " + l + " x " + h)
        }
        synchronized(cadreJeu) {
            try {
                cadreJeu.setPixels(pixels, 0, l, 0, 0, l, h)
                srcJeu.set(0, 0, l, h)
                jeuPret = true
            } catch (e: Exception) {
                surErreur?.invoke("dépôt de l'image impossible : " + e.message)
            }
        }
        postInvalidateOnAnimation()
    }

    /** Pour signaler un incident au journal depuis cette vue. */
    var surErreur: ((String) -> Unit)? = null
    var surTouche: (() -> Unit)? = null
    var surMenu: (() -> Unit)? = null
    var surQuit: (() -> Unit)? = null
    var surCheat: (() -> Unit)? = null
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
    /**
     * Un manche par directionnel. Le stick C en fait partie : sans le sien,
     * le dessin le reclamait et l'ecran s'arretait des la premiere image.
     */
    private val sticks = mapOf(
        Ids.CROIX to Stick(),
        Ids.STICK to Stick(),
        Ids.CSTICK to Stick()
    )

    // ---------- images ----------
    private class Touche(val repos: Bitmap, val appui: List<Bitmap>, val directions: Map<String, List<Bitmap>>)
    private var fondP: Bitmap? = null
    private var fondL: Bitmap? = null
    private val touchesP = HashMap<String, Touche>()
    private val touchesL = HashMap<String, Touche>()
    /** La seconde presentation horizontale, celle a grand ecran. */
    private var fondL2: Bitmap? = null
    private val touchesL2 = HashMap<String, Touche>()

    /**
     * Seconde presentation horizontale.
     *
     * Le bouton manette passe d'une presentation a l'autre. On recharge la
     * disposition sur place : la taille de la vue ne changeant pas, il ne se
     * passerait rien autrement.
     */
    var varianteLarge = false
        set(v) {
            if (field == v) return
            field = v
            Dispositions.poserVariante(context, if (v) 1 else 0)
            if (!enPaysage) return
            dispo = Dispositions.charger(context, true)
            fondPret?.recycle(); fondPret = null
            if (width > 0 && height > 0) recalculer(width, height)
            invalidate()
        }

    /** Prevenu quand la presentation horizontale a change. */
    var surManette: (() -> Unit)? = null

    private var dispo: Disposition
    private var enPaysage = false
    private var dispoChargee = false


    private val peinture = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
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
        varianteLarge = Dispositions.variante(ctx) == 1
        isFocusable = true
    }

    private fun charge(chemin: String): Bitmap =
        context.assets.open(chemin).use { BitmapFactory.decodeStream(it) }

    /** Lit positions.json et charge toutes les images d'un skin. */
    private fun chargerSkin(dossier: String, cible: HashMap<String, Touche>): Bitmap {
        val texte = context.assets.open("gc/skin/$dossier/positions.json").bufferedReader().use { it.readText() }
        val els = org.json.JSONObject(texte).getJSONArray("elements")
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val id = e.getString("id")
            val im = e.getJSONObject("images")
            val repos = charge("gc/skin/$dossier/" + im.getString("repos"))
            val appui = ArrayList<Bitmap>()
            val dirs = HashMap<String, List<Bitmap>>()
            im.keys().forEach { k ->
                if (k == "repos") return@forEach
                val liste = im.getJSONArray(k)
                val bms = (0 until liste.length()).map { charge("gc/skin/$dossier/" + liste.getString(it)) }
                if (k == "appui") appui.addAll(bms) else dirs[k] = bms
            }
            cible[id] = Touche(repos, appui, dirs)
        }
        return charge("gc/skin/$dossier/fond.png")
    }

    // ================= rafraichissement =================
    // L'emulation tourne sur le fil OpenGL, pas ici : cette vue ne fait plus
    // que dessiner le skin et transmettre l'etat des commandes.

    private var boucleLancee = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!boucleLancee) { boucleLancee = true; boucle() }
    }

    private fun boucle() {
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(ns: Long) {
                if (!isAttachedToWindow) { boucleLancee = false; return }
                Choreographer.getInstance().postFrameCallback(this)
                val st = sticks[Ids.STICK]!!
                val c = stickC()
                surCommandes?.invoke(boutons, st.x, st.y, c.first, c.second,
                                     actifs.containsKey(Ids.FF))
                if (animationEnCours()) invalidate()
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

    /**
     * Fond deja mis a l'echelle de la vue.
     *
     * Redimensionner une image de deux millions de pixels a chaque rafraichis-
     * sement coutait cher pour rien : le fond ne bouge jamais. On le prepare
     * une fois par changement de taille.
     */
    private var fondPret: Bitmap? = null

    private fun recalculer(w: Int, h: Int) {
        ech = max(w / dispo.sw, h / dispo.sh)
        decX = (w - dispo.sw * ech) / 2f
        decY = (h - dispo.sh * ech) / 2f
        fondPret?.recycle()
        fondPret = null
        val source = (if (!enPaysage) fondP
                      else if (varianteLarge) fondL2 else fondL) ?: return
        if (w <= 0 || h <= 0) return
        try {
            val prete = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(prete)
            tmp.set(decX, decY, decX + dispo.sw * ech, decY + dispo.sh * ech)
            val ecr = dispo.items[Ids.SCREEN]
            if (trouEcran && ecr != null) {
                // on epargne le rectangle de l'ecran : la couche OpenGL est
                // dessous, et un fond opaque la masquerait
                versEcran(ecr, tmp2)
                c.save(); c.clipOutRect(tmp2)
                c.drawBitmap(source, null, tmp, peinture)
                c.restore()
            } else {
                c.drawBitmap(source, null, tmp, peinture)
            }
            fondPret = prete
        } catch (_: OutOfMemoryError) { fondPret = null }
    }

    private fun versEcran(r: Rect4, out: RectF) {
        out.set(decX + r.x * ech, decY + r.y * ech, decX + (r.x + r.w) * ech, decY + (r.y + r.h) * ech)
    }

    // ================= dessin =================

    override fun onDraw(c: Canvas) {
        val fond = (if (!enPaysage) fondP else if (varianteLarge) fondL2 else fondL) ?: return
        val touches = if (!enPaysage) touchesP else if (varianteLarge) touchesL2 else touchesL
        tmp.set(decX, decY, decX + dispo.sw * ech, decY + dispo.sh * ech)
        c.drawBitmap(fond, null, tmp, peinture)

        dispo.items[Ids.SCREEN]?.let { r -> dessinerJeu(c, r) }

        val maintenant = System.currentTimeMillis()
        for ((id, t) in touches) {
            val r = dispo.items[id] ?: continue
            versEcran(r, tmp)
            val marge = (dispo.marges[id] ?: 0) * ech
            tmp2.set(tmp.left - marge, tmp.top - marge, tmp.right + marge, tmp.bottom + marge)

            if (id in Ids.DIRECTIONNELS) {
                dessinerDirectionnel(c, t, id, tmp, tmp2, maintenant)
                continue
            }

            val etape = etapeAppui(id, maintenant)
            if (etape < 0 || t.appui.isEmpty()) c.drawBitmap(t.repos, null, tmp, peinture)
            else c.drawBitmap(t.appui[etape.coerceAtMost(t.appui.size - 1)], null, tmp2, peinture)
        }

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
    private fun dessinerDirectionnel(c: Canvas, t: Touche, id: String, repos: RectF, large: RectF, maintenant: Long) {
        val st = sticks[id] ?: return      // un directionnel sans manche : on passe
        var px = st.x; var py = st.y
        if (!st.actif && maintenant - st.retourDepuis < 90) {
            val f = 1f - (maintenant - st.retourDepuis) / 90f
            px = st.rx * f; py = st.ry * f
        } else if (!st.actif) { px = 0f; py = 0f }
        val course = (dispo.courses[id] ?: 0f) * ech
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

    /** L'image du jeu dans le rectangle d'ecran, sur fond noir. */
    private fun dessinerJeu(c: Canvas, r: Rect4) {
        versEcran(r, tmp)
        surCadre?.invoke(tmp.left, tmp.top, tmp.right, tmp.bottom)
        // La couche OpenGL trace l'image sous le skin : on ne recouvre rien.
        if (trouEcran && !ecranVide) return
        c.drawRect(tmp, noir)
        if (ecranVide || !jeuPret) {
            texteVide.textSize = tmp.height() * 0.062f
            c.drawText("AUCUN JEU CHARGÉ", tmp.centerX(),
                       tmp.centerY() - texteVide.textSize * 0.2f, texteVide)
            texteVide.textSize = tmp.height() * 0.045f
            c.drawText("bouton JEUX", tmp.centerX(),
                       tmp.centerY() + texteVide.textSize * 1.6f, texteVide)
            return
        }
        // L'image remplit le rectangle de l'ecran, toujours. Sa taille se
        // regle dans l'editeur de disposition, en deplacant ce rectangle : la
        // finesse, elle, ne doit rien changer a ce qui s'affiche ou.
        val w = tmp.width()
        val h = tmp.height()
        val cx = tmp.centerX(); val cy = tmp.centerY()
        tmp2.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        c.save(); c.clipRect(tmp)
        synchronized(cadreJeu) { c.drawBitmap(cadreJeu, srcJeu, tmp2, peintureJeu) }
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
    /** Renseigne par MainActivity : cadence reelle de l'emulation. */
    var cadence = 0.0

    fun diagnostic(): String {
        val st = sticks[Ids.STICK]!!
        val cr = sticks[Ids.CROIX]!!
        return "%.1f i/s  boutons %04X  doigts %d  stick %.2f,%.2f  croix %.2f,%.2f".format(
            cadence, boutons, doigts.size, st.x, st.y, cr.x, cr.y)
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
    private val ordreCapture = Ids.FONCTIONS + listOf(Ids.FF, Ids.L, Ids.R, Ids.Z,
        Ids.CROIX, Ids.STICK)

    /** Les sticks captent un peu au-dela de leur capuchon : le doigt glisse. */
    private fun elementSousLarge(x: Float, y: Float): String? {
        elementSous(x, y)?.let { return it }
        for (id in Ids.DIRECTIONNELS) {
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
                    if (id != null && id in Ids.DIRECTIONNELS) { majStick(id, e.getX(i), e.getY(i)); continue }
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
        if (sous != null && sous !in Ids.DIRECTIONNELS && sous !in Ids.FONCTIONS) {
            doigts[pid] = sous
            actifs[sous] = System.currentTimeMillis()
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private fun appuyer(pid: Int, x: Float, y: Float) {
        suivis.add(pid)
        val el = elementSousLarge(x, y) ?: return
        val t = System.currentTimeMillis()
        doigts[pid] = el
        if (el in Ids.DIRECTIONNELS) {
            val st = sticks[el] ?: return
            st.actif = true; st.x0 = x; st.y0 = y; st.x = 0f; st.y = 0f
        } else {
            actifs[el] = t
            when (el) {
                Ids.MENU -> surMenu?.invoke()
                Ids.QUIT -> surQuit?.invoke()
                Ids.JEUX -> surJeux?.invoke()
                Ids.CHEAT -> surCheat?.invoke()
                // La manette bascule d'une presentation horizontale a l'autre
                Ids.MANETTE -> { varianteLarge = !varianteLarge; surManette?.invoke() }
            }
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun relacher(pid: Int) {
        suivis.remove(pid)
        val el = doigts.remove(pid) ?: return
        if (el in Ids.DIRECTIONNELS) {
            val st = sticks[el] ?: return
            st.rx = st.x; st.ry = st.y; st.x = 0f; st.y = 0f
            st.actif = false; st.retourDepuis = System.currentTimeMillis()
            return
        }
        if (doigts.values.none { it == el }) {
            actifs.remove(el)
            relaches[el] = System.currentTimeMillis()
        }
    }

    /** Position du stick d'apres le deplacement du doigt depuis l'appui, bornee a la course. */
    private fun majStick(id: String, x: Float, y: Float) {
        val st = sticks[id] ?: return
        var nx: Float; var ny: Float
        val course = (dispo.courses[id] ?: 0f)
        if (course > 0f) {
            // le dome glisse : on mesure le deplacement depuis l'appui
            val c = course * ech
            nx = (x - st.x0) / c; ny = (y - st.y0) / c
        } else {
            // la croix bascule sur place : la direction vient de l'endroit touche
            val r = dispo.items[id] ?: return
            versEcran(r, tmp)
            nx = (x - tmp.centerX()) / (tmp.width() / 2f)
            ny = (y - tmp.centerY()) / (tmp.height() / 2f)
        }
        val n = hypot(nx, ny)
        if (n > 1f) { nx /= n; ny /= n }
        if (n < zoneMorte) { nx = 0f; ny = 0f }
        st.x = nx; st.y = ny
    }

    private val TABLE = mapOf(
        Ids.A to Pad.A, Ids.B to Pad.B, Ids.X to Pad.X, Ids.Y to Pad.Y,
        Ids.START to Pad.START,
        Ids.L to Pad.L, Ids.R to Pad.R, Ids.Z to Pad.Z)

    /** Position du second stick, deduite des boutons C enfonces. */
    fun stickC(): Pair<Float, Float> {
        var x = 0f; var y = 0f
        val n = hypot(x, y)
        return if (n > 1f) Pair(x / n, y / n) else Pair(x, y)
    }

    private fun majBoutons() {
        var b = 0
        for ((id, bit) in TABLE) if (actifs.containsKey(id)) b = b or bit
        // la croix bascule : sa position devient quatre directions
        val cr = sticks[Ids.CROIX]!!
        if (cr.actif || System.currentTimeMillis() - cr.retourDepuis < 60) {
            if (cr.y < -zoneMorte) b = b or Pad.HAUT
            if (cr.y > zoneMorte) b = b or Pad.BAS
            if (cr.x < -zoneMorte) b = b or Pad.GAUCHE
            if (cr.x > zoneMorte) b = b or Pad.DROITE
        }
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
