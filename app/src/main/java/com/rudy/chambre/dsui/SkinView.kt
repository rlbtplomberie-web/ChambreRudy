package com.rudy.chambre.dsui

import android.content.Context
import android.graphics.*
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.rudy.chambre.dsui.core.Pad
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
    var surCadreHaut: ((Float, Float, Float, Float) -> Unit)? = null
    var surCadreBas: ((Float, Float, Float, Float) -> Unit)? = null
    /** Touche sur l'ecran du bas : x et y de 0 a 1, ou null au relachement. */
    var surTactile: ((Float, Float) -> Unit)? = null
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
    private val cadreJeu = Bitmap.createBitmap(1280, 1024, Bitmap.Config.ARGB_8888)
    private val srcJeu = Rect(0, 0, 1, 1)
    private var jeuPret = false

    /**
     * Depose l'image relue par la couche OpenGL. Appele depuis le fil du
     * rendu : on ne fait que recopier, l'affichage suit a la prochaine image.
     */
    fun poserImage(pixels: IntArray, l: Int, h: Int) {
        // Bornes verifiees explicitement : setPixels lit l * h entiers et
        // depasser le tableau ferait tomber l'application sans message.
        if (l <= 0 || h <= 0) return
        if (l > cadreJeu.width || h > cadreJeu.height) return
        if (l.toLong() * h > pixels.size) return
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
    var surJeux: (() -> Unit)? = null
    /** Bouton CHANGE : fait defiler les presentations horizontales. */
    var surChange: (() -> Unit)? = null
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
    private val sticks = mapOf(Ids.CROIX to Stick())

    // ---------- images ----------
    private class Touche(val repos: Bitmap, val appui: List<Bitmap>, val directions: Map<String, List<Bitmap>>)
    /**
     * Un fond et un jeu de touches par habillage : le vertical, plus les
     * quatre presentations horizontales que le bouton CHANGE fait defiler.
     */
    private val fonds = HashMap<String, Bitmap>()
    private val touchesPar = HashMap<String, HashMap<String, Touche>>()
    /** Rang de la presentation horizontale courante, de 0 a 3. */
    var rangPaysage = 0
        set(v) {
            field = ((v % 4) + 4) % 4
            if (!enPaysage) return
            // On recharge la disposition ici meme. Passer par requestLayout ne
            // servait a rien : la taille de la vue ne changeant pas, la mesure
            // n'etait jamais refaite, et les ecrans restaient a leur ancienne
            // place pendant que le dessin de la coque, lui, changeait.
            val h = Dispositions.habillage(true, field)
            dispo = Dispositions.charger(context, h)
            dispoChargee = true
            surErreur?.invoke("présentation « " + h.libelle + " » : " +
                (if (dispo.items.containsKey(Ids.ECRAN_HAUT)) "écran haut " else "") +
                (if (dispo.items.containsKey(Ids.ECRAN_BAS)) "écran bas" else ""))
            fondPret?.recycle(); fondPret = null
            if (width > 0 && height > 0) recalculer(width, height)
            invalidate()
        }

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
        dispo = Dispositions.parDefaut(ctx, Dispositions.PORTRAIT)
        for (h in listOf(Dispositions.PORTRAIT) + Dispositions.PAYSAGES) {
            val t = HashMap<String, Touche>()
            fonds[h.dossier] = chargerSkin(h.dossier, t)
            touchesPar[h.dossier] = t
        }
        isFocusable = true
    }

    private fun charge(chemin: String): Bitmap =
        context.assets.open(chemin).use { BitmapFactory.decodeStream(it) }

    /** Lit positions.json et charge toutes les images d'un skin. */
    private fun chargerSkin(dossier: String, cible: HashMap<String, Touche>): Bitmap {
        val texte = context.assets.open("skins/ds/skin/$dossier/positions.json").bufferedReader().use { it.readText() }
        val els = org.json.JSONObject(texte).getJSONArray("elements")
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val id = e.getString("id")
            val im = e.getJSONObject("images")
            val repos = charge("skins/ds/skin/$dossier/" + im.getString("repos"))
            val appui = ArrayList<Bitmap>()
            val dirs = HashMap<String, List<Bitmap>>()
            im.keys().forEach { k ->
                if (k == "repos") return@forEach
                val liste = im.getJSONArray(k)
                val bms = (0 until liste.length()).map { charge("skins/ds/skin/$dossier/" + liste.getString(it)) }
                if (k == "appui") appui.addAll(bms) else dirs[k] = bms
            }
            cible[id] = Touche(repos, appui, dirs)
        }
        return charge("skins/ds/skin/$dossier/fond.png")
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
                val st = sticks[Ids.CROIX]!!
                surCommandes?.invoke(boutons, st.x, st.y, 0f, 0f,
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
            dispo = Dispositions.charger(context, Dispositions.habillage(paysage, rangPaysage))
            dispoChargee = true
            fondPret?.recycle(); fondPret = null
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
        val source = fonds[Dispositions.habillage(enPaysage, rangPaysage).dossier] ?: return
        if (w <= 0 || h <= 0) return
        try {
            val prete = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(prete)
            tmp.set(decX, decY, decX + dispo.sw * ech, decY + dispo.sh * ech)
            val ecrans = listOfNotNull(dispo.items[Ids.ECRAN_HAUT], dispo.items[Ids.ECRAN_BAS])
            if (trouEcran && ecrans.isNotEmpty()) {
                // on epargne le rectangle de l'ecran : la couche OpenGL est
                // dessous, et un fond opaque la masquerait
                c.save()
                for (e in ecrans) { versEcran(e, tmp2); c.clipOutRect(tmp2) }
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
        val fond = fonds[Dispositions.habillage(enPaysage, rangPaysage).dossier] ?: return
        val touches = touchesPar[Dispositions.habillage(enPaysage, rangPaysage).dossier] ?: HashMap()
        tmp.set(decX, decY, decX + dispo.sw * ech, decY + dispo.sh * ech)
        c.drawBitmap(fond, null, tmp, peinture)

        // La console a deux ecrans, et certains habillages n'en montrent qu'un.
        dispo.items[Ids.ECRAN_HAUT]?.let { r -> dessinerJeu(c, r, true) }
        dispo.items[Ids.ECRAN_BAS]?.let { r -> dessinerJeu(c, r, false) }

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

        if (liste != null)
            (dispo.items[Ids.ECRAN_BAS] ?: dispo.items[Ids.ECRAN_HAUT])?.let { dessinerListe(c, it) }
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
        val st = sticks[id]!!
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
    private fun dessinerJeu(c: Canvas, r: Rect4, haut: Boolean) {
        versEcran(r, tmp)
        if (haut) surCadreHaut?.invoke(tmp.left, tmp.top, tmp.right, tmp.bottom)
        else surCadreBas?.invoke(tmp.left, tmp.top, tmp.right, tmp.bottom)
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
        // Le coeur remet une seule image ou les deux ecrans sont empiles. Un
        // ecran de DS fait 256 sur 192 : on decoupe d'apres ce rapport plutot
        // que de couper au milieu, car le coeur laisse parfois une bande entre
        // les deux, et la moitie exacte prenait alors un bout du voisin.
        val hUn = (srcJeu.width() * 192f / 256f).toInt().coerceIn(1, srcJeu.height())
        val part = if (haut)
            Rect(srcJeu.left, srcJeu.top, srcJeu.right, srcJeu.top + hUn)
        else
            Rect(srcJeu.left, srcJeu.bottom - hUn, srcJeu.right, srcJeu.bottom)
        // L'image remplit le rectangle de son ecran, toujours. La taille se
        // regle dans l'editeur de disposition, en deplacant ce rectangle : la
        // finesse ne doit rien changer a ce qui s'affiche ou.
        val w = tmp.width()
        val h = tmp.height()
        val cx = tmp.centerX(); val cy = tmp.centerY()
        tmp2.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        c.save(); c.clipRect(tmp)
        synchronized(cadreJeu) { c.drawBitmap(cadreJeu, part, tmp2, peintureJeu) }
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
        val st = sticks[Ids.CROIX]!!
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
        val r = dispo.items[Ids.ECRAN_BAS] ?: dispo.items[Ids.ECRAN_HAUT] ?: return false
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
    /** Doigt qui tient le stylet sur l'ecran du bas, s'il y en a un. */
    private var doigtStylet = -1
    private var tracesTactiles = 0
    private val ordreCapture = Ids.FONCTIONS + listOf(Ids.FF, Ids.L, Ids.R,
        Ids.SELECT, Ids.START, Ids.A, Ids.B, Ids.X, Ids.Y, Ids.CROIX)

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
                    if (pid == doigtStylet) {
                        if (!surEcranTactile(e.getX(i), e.getY(i))) {
                            doigtStylet = -1; surTactile?.invoke(-1f, -1f)
                        }
                        continue
                    }
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

    /**
     * L'ecran du bas de la DS est tactile : un doigt pose dessus n'enfonce
     * aucune touche, il commande le stylet. On transmet sa position en
     * fractions du rectangle, le coeur se chargeant de la convertir.
     */
    private fun surEcranTactile(x: Float, y: Float): Boolean {
        val r = dispo.items[Ids.ECRAN_BAS] ?: return false
        versEcran(r, tmp)
        if (!tmp.contains(x, y)) return false
        surTactile?.invoke((x - tmp.left) / tmp.width(), (y - tmp.top) / tmp.height())
        if (tracesTactiles < 5) {
            tracesTactiles++
            surErreur?.invoke("stylet : %.2f, %.2f dans l'écran du bas".format(
                (x - tmp.left) / tmp.width(), (y - tmp.top) / tmp.height()))
        }
        return true
    }

    private fun appuyer(pid: Int, x: Float, y: Float) {
        suivis.add(pid)
        if (surEcranTactile(x, y)) { doigtStylet = pid; return }
        val el = elementSousLarge(x, y) ?: return
        val t = System.currentTimeMillis()
        doigts[pid] = el
        if (el in Ids.DIRECTIONNELS) {
            val st = sticks[el]!!
            st.actif = true; st.x0 = x; st.y0 = y; st.x = 0f; st.y = 0f
        } else {
            actifs[el] = t
            when (el) {
                Ids.MENU -> surMenu?.invoke()
                Ids.CHANGE -> surChange?.invoke()
                Ids.JEUX -> surJeux?.invoke()
            }
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun relacher(pid: Int) {
        suivis.remove(pid)
        if (pid == doigtStylet) { doigtStylet = -1; surTactile?.invoke(-1f, -1f) }
        val el = doigts.remove(pid) ?: return
        if (el in Ids.DIRECTIONNELS) {
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

    /** Position du stick d'apres le deplacement du doigt depuis l'appui, bornee a la course. */
    private fun majStick(id: String, x: Float, y: Float) {
        val st = sticks[id]!!
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
        Ids.A to Pad.A, Ids.B to Pad.B, Ids.START to Pad.START,
        Ids.L to Pad.L, Ids.R to Pad.R,
        Ids.SELECT to Pad.SELECT, Ids.START to Pad.START,
        Ids.X to Pad.X, Ids.Y to Pad.Y)


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
            traitEdition.color = if (id == Ids.ECRAN_HAUT || id == Ids.ECRAN_BAS)
                0xFF3AA0FF.toInt() else 0xFFC82128.toInt()
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
                        tmp.contains(x, y) && id != Ids.ECRAN_HAUT && id != Ids.ECRAN_BAS ->
                            { edId = id; edPoignee = null }
                    }
                    if (edId != null) break
                }
                if (edId == null) for (cle in listOf(Ids.ECRAN_BAS, Ids.ECRAN_HAUT)) {
                    val e = dispo.items[cle] ?: continue
                    versEcran(e, tmp)
                    if (tmp.contains(x, y)) { edId = cle; break }
                }
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

    /** L'habillage en cours : le vertical, ou l'une des quatre presentations. */
    private fun habillage() = Dispositions.habillage(enPaysage, rangPaysage)

    fun enregistrerDisposition() = Dispositions.enregistrer(context, habillage(), dispo)

    fun reinitialiserDisposition() {
        Dispositions.reinitialiser(context, habillage())
        dispo = Dispositions.parDefaut(context, habillage())
        fondPret?.recycle(); fondPret = null
        recalculer(width, height)
        invalidate()
    }
}
