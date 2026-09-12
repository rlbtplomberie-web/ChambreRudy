package com.skinpsp.app

import android.content.Context
import android.graphics.*
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.skinpsp.app.core.Pad
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Dessine la console et lit les appuis.
 *
 * L'habillage est fait d'un fond — la coque, touches retirees — et d'un sprite
 * par touche. Au repos, fond plus sprites redonne exactement l'image
 * d'origine ; a l'appui, seul le sprite de la touche change.
 */
class SkinView(ctx: Context) : View(ctx) {

    // ---------- ce que la vue expose ----------

    /** Etat des commandes, lu a chaque image. */
    var surCommandes: ((Int, Float, Float, Boolean) -> Unit)? = null
    /** Rectangle de l'ecran, en pixels de la vue. */
    var surCadre: ((Float, Float, Float, Float) -> Unit)? = null
    var surMenu: (() -> Unit)? = null
    var surJeux: (() -> Unit)? = null
    var surCheat: (() -> Unit)? = null
    var surQuit: (() -> Unit)? = null
    var surTouche: (() -> Unit)? = null
    /** Un jeu a ete choisi dans la liste affichee sur l'ecran. */
    var surChoixJeu: ((Int) -> Unit)? = null
    var surErreur: ((String) -> Unit)? = null

    /**
     * Liste des jeux, dessinee DANS l'ecran de la console.
     *
     * Une fenetre par-dessus la console casse l'illusion : on affiche donc le
     * catalogue sur la dalle elle-meme, comme le ferait la console.
     */
    var listeJeux: List<String> = emptyList()
        set(v) { field = v; rangListe = 0; invalidate() }
    var listeVisible = false
        set(v) { field = v; invalidate() }
    private var rangListe = 0
    private var listeDefile = 0f

    /** true tant qu'aucun jeu ne tourne : on affiche alors un message. */
    var ecranVide = true
        set(v) { field = v; invalidate() }

    /** Adoucit l'image quand elle est agrandie a l'ecran. */
    /**
     * Mode d'affichage.
     *
     * true  : l'image est relue puis peinte ici, dans le rectangle de l'ecran.
     * false : elle est tracee directement par la couche OpenGL, qui se trouve
     *         derriere, et l'habillage laisse ce rectangle transparent.
     *
     * Le trace direct est celui de l'application de reference et ne coute
     * rien ; la relecture est une voie de secours si la transparence ne se
     * comporte pas comme prevu sur un appareil.
     */

    var lissage = true
        set(v) { field = v; peintureJeu.isFilterBitmap = v; invalidate() }

    /** Cadence mesuree, affichee dans le diagnostic. */
    var cadence = 0.0
    /** Ligne d'etat affichee sur l'ecran, pour comprendre d'un coup d'oeil. */
    var diagnostic = ""

    var modeEdition = false
        set(v) { field = v; invalidate() }

    // ---------- images de l'habillage ----------

    private class Touche(val repos: Bitmap, val appui: List<Bitmap>,
                        val directions: Map<String, List<Bitmap>>) {
        fun liberer() {
            if (!repos.isRecycled) repos.recycle()
            for (b in appui) if (!b.isRecycled) b.recycle()
            for (l in directions.values) for (b in l) if (!b.isRecycled) b.recycle()
        }
    }

    /**
     * Un seul habillage en memoire a la fois.
     *
     * Les deux reunis pesent une vingtaine de megaoctets une fois leurs images
     * decompressees ; les charger ensemble n'apporte rien puisqu'un seul est
     * visible.
     */
    private var dossierCharge: String? = null
    private var fond: Bitmap? = null
    private val touches = HashMap<String, Touche>()

    private var dispo: Disposition = Dispositions.parDefaut(ctx, Dispositions.PORTRAIT)
    private var dispoChargee = false
    private var enPaysage = false

    private var ech = 1f
    private var decX = 0f
    private var decY = 0f

    private val tmp = RectF()
    private val tmp2 = RectF()
    private val peinture = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val peintureJeu = Paint().apply { isFilterBitmap = true }
    private val noir = Paint().apply { color = Color.BLACK }
    private val texteVide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6E6E78.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }
    private val traitEdition = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    /** Assez grand pour l'image reduite que le pont nous remet. */
    private val cadreJeu = Bitmap.createBitmap(1024, 640, Bitmap.Config.ARGB_8888)
    private val srcJeu = Rect(0, 0, 1, 1)
    private var jeuPret = false

    private var fondPret: Bitmap? = null
    private var boucleLancee = false

    init { isFocusable = true }

    // ---------- chargement ----------

    private fun charge(chemin: String): Bitmap =
        context.assets.open(chemin).use { BitmapFactory.decodeStream(it) }

    private fun chargerHabillage(dossier: String): Bitmap? {
        if (dossier == dossierCharge) return fond
        touches.values.forEach { it.liberer() }
        touches.clear()
        fond?.let { if (!it.isRecycled) it.recycle() }
        fond = null
        dossierCharge = null
        return try {
            val texte = context.assets.open("psp/skin/$dossier/positions.json")
                .bufferedReader().use { it.readText() }
            val o = org.json.JSONObject(texte)
            val els = o.getJSONArray("elements")
            for (i in 0 until els.length()) {
                val e = els.getJSONObject(i)
                val id = e.getString("id")
                val im = e.getJSONObject("images")
                val repos = charge("psp/skin/$dossier/" + im.getString("repos"))
                val appui = ArrayList<Bitmap>()
                if (im.has("appui")) {
                    val a = im.getJSONArray("appui")
                    for (k in 0 until a.length()) appui.add(charge("psp/skin/$dossier/" + a.getString(k)))
                }
                // Le stick porte huit directions : sans elles, il ne bougeait
                // pas a l'ecran alors que la demonstration le montrait pencher.
                val dirs = HashMap<String, List<Bitmap>>()
                for (sens in listOf("h", "b", "g", "d", "hg", "hd", "bg", "bd")) {
                    if (!im.has(sens)) continue
                    val a = im.getJSONArray(sens)
                    val l = ArrayList<Bitmap>()
                    for (k in 0 until a.length()) l.add(charge("psp/skin/$dossier/" + a.getString(k)))
                    dirs[sens] = l
                }
                touches[id] = Touche(repos, appui, dirs)
            }
            val f = charge("psp/skin/$dossier/fond.png")
            fond = f
            dossierCharge = dossier
            f
        } catch (e: Throwable) {
            surErreur?.invoke("habillage $dossier illisible : " + e)
            null
        }
    }

    // ---------- mesure ----------

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        recalculer(w, h)
        if (!boucleLancee) { boucleLancee = true; boucle() }
    }

    private fun recalculer(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val paysage = w >= h
        if (!dispoChargee || paysage != enPaysage) {
            enPaysage = paysage
            dispo = Dispositions.charger(context, Dispositions.habillage(paysage))
            dispoChargee = true
            fondPret?.recycle(); fondPret = null
        }
        // La console remplit l'ecran : on prend le plus grand des deux
        // rapports, quitte a ce que les bords depassent un peu. Le plus petit
        // laissait des bandes noires de chaque cote.
        ech = max(w.toFloat() / dispo.sw, h.toFloat() / dispo.sh)
        decX = (w - dispo.sw * ech) / 2f
        decY = (h - dispo.sh * ech) / 2f
        fondPret?.recycle(); fondPret = null
    }

    private fun versEcran(r: Rect4, out: RectF) {
        out.set(decX + r.x * ech, decY + r.y * ech,
                decX + (r.x + r.w) * ech, decY + (r.y + r.h) * ech)
    }

    // ---------- dessin ----------

    private var dessinEnEchec = false

    override fun onDraw(c: Canvas) {
        try {
            dessiner(c)
        } catch (e: Throwable) {
            if (!dessinEnEchec) {
                dessinEnEchec = true
                surErreur?.invoke("dessin impossible : " + e)
            }
            c.drawColor(0xFF101014.toInt())
        }
    }

    private fun dessiner(c: Canvas) {
        val dossier = Dispositions.habillage(enPaysage).dossier
        val source = chargerHabillage(dossier) ?: return

        // le fond, mis a l'echelle une fois pour toutes
        var pret = fondPret
        if (pret == null || pret.width != width || pret.height != height) {
            pret?.recycle()
            try {
                val n = Bitmap.createBitmap(max(1, width), max(1, height), Bitmap.Config.ARGB_8888)
                val cc = Canvas(n)
                tmp.set(decX, decY, decX + dispo.sw * ech, decY + dispo.sh * ech)
                cc.drawBitmap(source, null, tmp, peinture)
                fondPret = n
                pret = n
            } catch (_: OutOfMemoryError) { fondPret = null }
        }
        c.drawColor(Color.BLACK)
        if (pret != null) c.drawBitmap(pret, 0f, 0f, null)
        else {
            tmp.set(decX, decY, decX + dispo.sw * ech, decY + dispo.sh * ech)
            c.drawBitmap(source, null, tmp, peinture)
        }

        dispo.items[Ids.ECRAN]?.let { dessinerJeu(c, it) }

        val t = System.currentTimeMillis()
        for ((id, touche) in touches) {
            val r = dispo.items[id] ?: continue
            val img = if (id == Ids.STICK) imageStick(touche) else {
                val etat = etatAppui(id, t)
                if (etat == 0 || touche.appui.isEmpty()) touche.repos
                else touche.appui[min(etat - 1, touche.appui.size - 1)]
            }
            val marge = dispo.marges[id] ?: 0
            if (img === touche.repos) {
                versEcran(r, tmp)
            } else {
                tmp.set(decX + (r.x - marge) * ech, decY + (r.y - marge) * ech,
                        decX + (r.x + r.w + marge) * ech, decY + (r.y + r.h + marge) * ech)
            }
            c.drawBitmap(img, null, tmp, peinture)
        }

        if (modeEdition) {
            for ((id, r) in dispo.items) {
                versEcran(r, tmp)
                traitEdition.color = if (id == Ids.ECRAN) 0xFF3AA0FF.toInt() else 0xFFC82128.toInt()
                c.drawRect(tmp, traitEdition)
            }
        }
    }

    /** Image du stick selon son inclinaison : huit directions, deux niveaux. */
    private fun imageStick(t: Touche): Bitmap {
        val n = kotlin.math.hypot(stickX.toDouble(), stickY.toDouble()).toFloat()
        if (n < 0.16f || t.directions.isEmpty()) return t.repos
        val sens = StringBuilder()
        if (stickY < -0.38f) sens.append("h") else if (stickY > 0.38f) sens.append("b")
        if (stickX < -0.38f) sens.append("g") else if (stickX > 0.38f) sens.append("d")
        val cle = if (sens.isEmpty()) (if (kotlin.math.abs(stickX) > kotlin.math.abs(stickY))
                                       (if (stickX < 0) "g" else "d")
                                       else (if (stickY < 0) "h" else "b"))
                  else sens.toString()
        val l = t.directions[cle] ?: return t.repos
        if (l.isEmpty()) return t.repos
        return l[if (n > 0.62f) l.size - 1 else 0]
    }

    private fun dessinerJeu(c: Canvas, r: Rect4) {
        versEcran(r, tmp)
        c.drawRect(tmp, noir)
        if (listeVisible) { dessinerListe(c, tmp); return }
        if (!ecranVide && jeuPret) {
            // l'image relue, peinte dans le rectangle de l'ecran
            c.save(); c.clipRect(tmp)
            synchronized(cadreJeu) { c.drawBitmap(cadreJeu, srcJeu, tmp, peintureJeu) }
            c.restore()
            return
        }
        if (ecranVide || !jeuPret) {
            texteVide.textSize = tmp.height() * 0.055f
            c.drawText("AUCUN JEU CHARGÉ", tmp.centerX(), tmp.centerY(), texteVide)
            texteVide.textSize = tmp.height() * 0.040f
            c.drawText("bouton JEUX", tmp.centerX(),
                       tmp.centerY() + texteVide.textSize * 1.8f, texteVide)
            return
        }

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

    /** Hauteur d'une ligne de la liste, en pixels de l'ecran. */
    private fun hauteurLigne(r: RectF) = r.height() * 0.085f

    private fun dessinerListe(c: Canvas, r: RectF) {
        c.drawRect(r, fondListe)
        val hl = hauteurLigne(r)
        texteTitre.textSize = hl * 0.58f
        texteListe.textSize = hl * 0.52f
        val marge = r.width() * 0.04f
        c.drawText("JEUX  (" + listeJeux.size + ")", r.left + marge, r.top + hl * 0.9f, texteTitre)
        if (listeJeux.isEmpty()) {
            c.drawText("Aucun jeu — bouton JEUX pour choisir un dossier",
                       r.left + marge, r.top + hl * 2.2f, texteListe)
            return
        }
        val haut = r.top + hl * 1.35f
        val visibles = ((r.bottom - haut) / hl).toInt().coerceAtLeast(1)
        val premier = ((listeDefile / hl).toInt()).coerceIn(0,
            (listeJeux.size - visibles).coerceAtLeast(0))
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

    /** Depose l'image relue par le pont. */
    fun poserImage(pixels: IntArray, l: Int, h: Int) {
        if (l <= 0 || h <= 0) return
        if (l > cadreJeu.width || h > cadreJeu.height) {
            surErreur?.invoke("image " + l + "x" + h + " plus grande que le cadre " +
                              cadreJeu.width + "x" + cadreJeu.height)
            return
        }
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

    // ---------- appuis ----------

    private val actifs = HashMap<String, Long>()
    private val relaches = HashMap<String, Long>()
    private val doigts = HashMap<Int, String>()
    /** Doigts poses, meme au-dessus du vide entre deux touches. */
    private val suivis = HashSet<Int>()
    private var boutons = 0

    /** Position du stick, de -1 a 1. */
    private var stickX = 0f
    private var stickY = 0f
    private var stickPid = -1
    private var stickOx = 0f
    private var stickOy = 0f

    private val DUREE_APPUI = 55L
    private val DUREE_RELACHE = 90L

    private fun etatAppui(id: String, t: Long): Int {
        actifs[id]?.let {
            val dt = t - it
            return if (dt < DUREE_APPUI) 1 else 2
        }
        relaches[id]?.let {
            val dt = t - it
            if (dt < DUREE_RELACHE) return if (dt < DUREE_RELACHE / 2) 2 else 1
        }
        return 0
    }

    private fun animationEnCours(): Boolean {
        val t = System.currentTimeMillis()
        if (actifs.isNotEmpty()) return true
        for (v in relaches.values) if (t - v < DUREE_RELACHE) return true
        return false
    }

    /** Piece situee sous le doigt, d'apres l'opacite reelle de son dessin. */
    private fun sous(x: Float, y: Float): String? {
        for (id in ordreCapture) {
            val r = dispo.items[id] ?: continue
            versEcran(r, tmp)
            if (!tmp.contains(x, y)) continue
            val t = touches[id] ?: continue
            val px = ((x - tmp.left) / tmp.width() * t.repos.width).toInt()
            val py = ((y - tmp.top) / tmp.height() * t.repos.height).toInt()
            if (px < 0 || py < 0 || px >= t.repos.width || py >= t.repos.height) continue
            if (Color.alpha(t.repos.getPixel(px, py)) > 60) return id
        }
        return null
    }

    /** Les touches de fonction d'abord : elles ne doivent jamais etre masquees. */
    private val ordreCapture = Ids.FONCTIONS + listOf(
        Ids.FF, Ids.L, Ids.R, Ids.SELECT, Ids.START,
        Ids.TRIANGLE, Ids.ROND, Ids.CARRE, Ids.CROIX,
        Ids.HAUT, Ids.BAS, Ids.GAUCHE, Ids.DROITE, Ids.STICK)

    private val TABLE = mapOf(
        Ids.CROIX to Pad.CROIX, Ids.ROND to Pad.ROND,
        Ids.CARRE to Pad.CARRE, Ids.TRIANGLE to Pad.TRIANGLE,
        Ids.L to Pad.L, Ids.R to Pad.R,
        Ids.SELECT to Pad.SELECT, Ids.START to Pad.START,
        Ids.HAUT to Pad.HAUT, Ids.BAS to Pad.BAS,
        Ids.GAUCHE to Pad.GAUCHE, Ids.DROITE to Pad.DROITE)

    private fun majBoutons() {
        var b = 0
        for ((id, bit) in TABLE) if (actifs.containsKey(id)) b = b or bit
        boutons = b
    }

    private fun appuyer(pid: Int, x: Float, y: Float) {
        suivis.add(pid)
        val el = sous(x, y) ?: return
        if (el == Ids.STICK) {
            stickPid = pid; stickOx = x; stickOy = y
            stickX = 0f; stickY = 0f
            actifs[el] = System.currentTimeMillis()
            doigts[pid] = el
            return
        }
        doigts[pid] = el
        actifs[el] = System.currentTimeMillis()
        when (el) {
            // Elles ouvrent une fenetre : le doigt ne revient jamais les
            // lever, donc elles se relachent seules.
            Ids.MENU -> { relacherPlusTard(el); surMenu?.invoke() }
            Ids.JEUX -> { relacherPlusTard(el); surJeux?.invoke() }
            Ids.CHEAT -> { relacherPlusTard(el); surCheat?.invoke() }
            Ids.QUIT -> { relacherPlusTard(el); surQuit?.invoke() }
            else -> performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }
        if (el !in Ids.FONCTIONS) surTouche?.invoke()
    }

    /** Relache une touche au bout d'un instant, sans attendre le doigt. */
    private fun relacherPlusTard(el: String) {
        postDelayed({
            doigts.entries.filter { it.value == el }.map { it.key }.forEach { doigts.remove(it) }
            actifs.remove(el)
            relaches[el] = System.currentTimeMillis()
            invalidate()
        }, 180L)
    }

    private fun relacher(pid: Int) {
        suivis.remove(pid)
        if (pid == stickPid) { stickPid = -1; stickX = 0f; stickY = 0f }
        val el = doigts.remove(pid) ?: return
        actifs.remove(el)
        relaches[el] = System.currentTimeMillis()
    }

    private fun majStick(x: Float, y: Float) {
        val r = dispo.items[Ids.STICK] ?: return
        versEcran(r, tmp)
        val course = max(tmp.width(), tmp.height()) * 0.55f
        // Depuis le CENTRE de la piece : partir du point d'appui inversait le
        // sens des le second contact, et le stick fuyait le doigt.
        stickX = ((x - tmp.centerX()) / course).coerceIn(-1f, 1f)
        stickY = ((y - tmp.centerY()) / course).coerceIn(-1f, 1f)
        if (abs(stickX) < 0.12f) stickX = 0f
        if (abs(stickY) < 0.12f) stickY = 0f
    }

    private var listeDepart = 0f
    private var listeY0 = 0f
    private var listeBouge = false

    /** Vrai si le doigt agit sur la liste affichee dans l'ecran. */
    private fun toucherListe(e: MotionEvent): Boolean {
        if (!listeVisible) return false
        val r = dispo.items[Ids.ECRAN] ?: return false
        versEcran(r, tmp2)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!tmp2.contains(e.x, e.y)) return false
                listeY0 = e.y; listeDepart = listeDefile; listeBouge = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(e.y - listeY0) > 12f) listeBouge = true
                val hl = hauteurLigne(tmp2)
                val maxi = (listeJeux.size * hl - (tmp2.height() - hl * 1.35f))
                    .coerceAtLeast(0f)
                listeDefile = (listeDepart - (e.y - listeY0)).coerceIn(0f, maxi)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!listeBouge && tmp2.contains(e.x, e.y)) {
                    val hl = hauteurLigne(tmp2)
                    val haut = tmp2.top + hl * 1.35f
                    if (e.y >= haut) {
                        val i = ((listeDefile / hl).toInt()) + ((e.y - haut) / hl).toInt()
                        if (i in listeJeux.indices) { rangListe = i; surChoixJeu?.invoke(i) }
                    }
                }
                invalidate()
                return true
            }
        }
        return listeVisible
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (modeEdition) return editer(e)
        if (toucherListe(e)) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                appuyer(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val pid = e.getPointerId(i)
                    if (!suivis.contains(pid)) continue
                    val x = e.getX(i); val y = e.getY(i)
                    if (pid == stickPid) { majStick(x, y); continue }
                    // Glisser d'une touche a l'autre enfonce la nouvelle sans
                    // qu'on ait a lever le doigt. Le doigt reste suivi meme
                    // au-dessus du vide entre deux touches.
                    val nouv = sous(x, y)
                    if (nouv == doigts[pid]) continue
                    if (nouv == Ids.STICK) continue
                    val ancien = doigts.remove(pid)
                    if (ancien != null) {
                        actifs.remove(ancien)
                        relaches[ancien] = System.currentTimeMillis()
                    }
                    // jamais une touche de l'application au passage du doigt
                    if (nouv != null && nouv !in Ids.FONCTIONS) {
                        doigts[pid] = nouv
                        actifs[nouv] = System.currentTimeMillis()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                relacher(e.getPointerId(e.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> {
                doigts.keys.toList().forEach { relacher(it) }
                suivis.clear()
            }
        }
        majBoutons()
        invalidate()
        return true
    }

    // ---------- editeur de disposition ----------

    private var edId: String? = null
    private var edPoignee: String? = null
    private var edX = 0f
    private var edY = 0f

    private fun editer(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edId = null; edPoignee = null
                for ((id, r) in dispo.items) {
                    versEcran(r, tmp)
                    val coin = 28f
                    if (abs(x - tmp.right) < coin && abs(y - tmp.bottom) < coin) {
                        edId = id; edPoignee = "taille"; break
                    }
                    if (tmp.contains(x, y)) { edId = id; edPoignee = "place" }
                }
                edX = x; edY = y
            }
            MotionEvent.ACTION_MOVE -> {
                val id = edId ?: return true
                val r = dispo.items[id] ?: return true
                val dx = (x - edX) / ech
                val dy = (y - edY) / ech
                if (edPoignee == "taille") {
                    r.w = (r.w + dx).coerceAtLeast(24f)
                    r.h = (r.h + dy).coerceAtLeast(24f)
                } else { r.x += dx; r.y += dy }
                edX = x; edY = y
                fondPret?.recycle(); fondPret = null
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                edId = null; edPoignee = null
                Dispositions.enregistrer(context, Dispositions.habillage(enPaysage), dispo)
            }
        }
        return true
    }

    fun reinitialiserDisposition() {
        val h = Dispositions.habillage(enPaysage)
        Dispositions.reinitialiser(context, h)
        dispo = Dispositions.parDefaut(context, h)
        fondPret?.recycle(); fondPret = null
        recalculer(width, height)
        invalidate()
    }

    // ---------- boucle ----------

    private fun boucle() {
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(ns: Long) {
                if (!isAttachedToWindow) { boucleLancee = false; return }
                Choreographer.getInstance().postFrameCallback(this)
                surCommandes?.invoke(boutons, stickX, stickY, actifs.containsKey(Ids.FF))
                if (animationEnCours()) invalidate()
            }
        })
    }

    fun diagnostic(): String =
        "%.1f i/s  boutons %04X  doigts %d  stick %.2f,%.2f".format(
            cadence, boutons, doigts.size, stickX, stickY)
}
