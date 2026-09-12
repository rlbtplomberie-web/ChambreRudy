package com.skinnes.app

import android.content.Context
import android.graphics.*
import android.graphics.Typeface
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.skinnes.app.core.CoeurNes
import com.skinnes.app.core.NesCore
import com.skinnes.app.core.Pad
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

class SkinView(ctx: Context) : View(ctx) {

    // ---------- reglages que tu peux toucher ----------
    /** Inclinaison de la croix, en degres. */
    var angleCroix = 22f
    /** Zone morte au centre de la croix, en fraction du rayon. */
    var zoneMorte = 0.24f
    /** Duree minimale d'affichage d'un appui, en ms. Sans ca un tap bref ne se voit pas. */
    var appuiMiniMs = 130L
    /** Intensite de l'ombre portee sur le cote enfonce (0..1). */
    var forceOmbre = 0.50f
    /** Intensite de la lumiere sur le cote releve (0..1). */
    var forceLumiere = 0.26f
    // --------------------------------------------------

    var coeur: NesCore = CoeurNes()

    /**
     * true  : le rectangle de jeu est laisse TRANSPARENT, le skin est dessine autour.
     *         C'est le mode a utiliser au-dessus de la GLSurfaceView de NostalgiaLite.
     * false : la vue dessine elle-meme l'image du coeur (mode autonome, pour tester).
     */
    var trouEcran = false

    /** Sortie audio, posee par l'activite. */
    var son: Son? = null
    /** Appele au premier contact du doigt. L'activite s'en sert pour replier sa barre. */
    var surTouche: (() -> Unit)? = null
    /** Appeles quand on appuie sur les touches dessinees dans le skin. */
    var surMenu: (() -> Unit)? = null
    var surPower: (() -> Unit)? = null
    var surReset: (() -> Unit)? = null
    var surJeux: (() -> Unit)? = null
    var surQuit: (() -> Unit)? = null
    /** Appele avec l'indice du jeu choisi dans la liste affichee a l'ecran. */
    var surChoixJeu: ((Int) -> Unit)? = null
    /** Appele des que le rectangle de jeu bouge, en pixels ecran. A brancher sur la GLSurfaceView. */
    var surEcranDeplace: ((RectF) -> Unit)? = null
    private val dernierEcran = RectF()
    var modeEdition = false
        set(v) { field = v; invalidate() }

    /** Etat courant des boutons, a lire par le moteur. */
    var boutons = 0
        private set

    private val actifs = HashMap<String, Long>()      // id -> instant d'appui
    private val relaches = HashMap<String, Long>()    // id -> instant de relachement
    private var dirX = 0
    private var dirY = 0

    private lateinit var skinP: Bitmap
    private lateinit var skinL: Bitmap
    private lateinit var skinL2: Bitmap
    private lateinit var skinL3: Bitmap

    /**
     * Presentation horizontale en cours : 0 la console, 1 la manette, 2 la
     * couche transparente posee sur le jeu.
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
     * Opacite des touches de la troisieme presentation, de 0,12 (a peine
     * visible) a 1 (bien marquee). Elle ne concerne qu'elle : les deux autres
     * dessinent une console, qui n'a pas a s'effacer.
     */
    var opaciteBoutons = 0.85f
        set(v) { field = v.coerceIn(0.12f, 1f); invalidate() }

    /** Prevenu quand la presentation horizontale a change. */
    var surManette: (() -> Unit)? = null
    private val sprites = HashMap<String, Bitmap>()

    private var dispo = Dispositions.portraitParDefaut()
    private var enPaysage = false
    private var dispoChargee = false

    private val cadreJeu = Bitmap.createBitmap(256, 240, Bitmap.Config.ARGB_8888)

    private val peinture = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val peintureJeu = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val peintureMasque = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val peintureTeinte = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }
    private val traitEdition = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFFC82128.toInt()
        pathEffect = DashPathEffect(floatArrayOf(12f, 9f), 0f)
    }
    private val poignee = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC82128.toInt() }
    private val poigneeBord = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.WHITE
    }

    // ================= liste des jeux, affichee dans l'ecran =================
    private var liste: List<String>? = null
    private var defilement = 0f
    private var rangPresse = -1

    val listeOuverte: Boolean get() = liste != null

    fun afficherListe(noms: List<String>) {
        liste = noms; defilement = 0f; rangPresse = -1; invalidate()
    }

    fun fermerListe() { liste = null; rangPresse = -1; invalidate() }

    private val fondListe = Paint().apply { color = Color.BLACK }
    private val texteListe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = Color.WHITE
    }
    private val barreListe = Paint().apply { color = Color.WHITE }

    private val camera = Camera()
    private val matrice = Matrix()

    // transformation skin -> ecran (cover)
    private var ech = 1f
    private var decX = 0f
    private var decY = 0f

    init {
        val a = ctx.assets
        fun charge(n: String) = a.open("nes/skin/$n").use { BitmapFactory.decodeStream(it) }
        skinP = charge("skin_portrait.jpg")
        skinL = charge("skin_paysage.jpg")
        skinL2 = charge("skin_paysage2.jpg")
        skinL3 = charge("skin_paysage3.jpg")
        variantePaysage = Dispositions.variante(ctx)
        opaciteBoutons = Dispositions.opacite(ctx)
        for (n in listOf("pad_bascule", "pad_contour", "pad_masque", "socle_A", "socle_B",
                         "rond_A", "rond_B", "ss_cadre", "pilule_select", "pilule_start",
                         "avance_rapide",
                         // pieces propres a la seconde presentation horizontale
                         "manette", "pad2_bascule", "pad2_masque",
                         "rond2_A", "rond2_B",
                         "pilule2_select", "pilule2_start", "ff2",
                         // pieces de la troisieme presentation, la couche transparente
                         "p3_croix", "p3_A", "p3_B", "p3_select", "p3_start",
                         "p3_ff", "p3_menu"))
            sprites[n] = charge("$n.png")
        isFocusable = true
    }

    private var boucleLancee = false

    /**
     * La boucle ne peut pas demarrer depuis init : le premier appel de Choreographer
     * arrive en phase animation, AVANT que la vue soit attachee. Le test
     * isAttachedToWindow echouait alors des la premiere image et la boucle
     * s'arretait definitivement. On la lance donc a l'attachement.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!boucleLancee) { boucleLancee = true; boucle() }
    }

    /** Duree d'une image NES en nanosecondes (60,0988 Hz). */
    private val nsParImage = 1_000_000_000.0 / 60.0988
    private var dernierNs = 0L
    private var reste = 0.0

    private fun boucle() {
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(ns: Long) {
                if (!isAttachedToWindow) { boucleLancee = false; return }
                Choreographer.getInstance().postFrameCallback(this)

                // L'ecran peut battre a 90 ou 120 Hz. Sans ce calage, la console
                // tournerait a la vitesse de l'ecran au lieu de ses 60 images par seconde.
                if (dernierNs == 0L) dernierNs = ns
                var ecoule = (ns - dernierNs).toDouble()
                dernierNs = ns
                if (ecoule > 250_000_000.0) ecoule = nsParImage   // reprise apres une pause
                reste += ecoule

                val rapide = actifs.containsKey(Ids.FF)
                if (rapide) reste += ecoule * 2.0                 // trois fois la vitesse

                var images = 0
                while (reste >= nsParImage && images < 8) { reste -= nsParImage; images++ }
                if (images == 0) return

                repeat(images) {
                    coeur.imageSuivante(boutons)
                    // on vide toujours le tampon audio, mais on ne le joue pas
                    // en avance rapide : ca saturerait la sortie et grincerait
                    (coeur as? CoeurNes)?.let { c ->
                        val ech = c.son()
                        if (!rapide) son?.jouer(ech)
                    }
                }
                cadreJeu.setPixels(coeur.tampon, 0, 256, 0, 0, 256, 240)
                invalidate()
            }
        })
    }

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
        // "cover" : on remplit tout, quitte a rogner. Rien ne doit rester noir sur les bords.
        ech = max(w / dispo.sw, h / dispo.sh)
        decX = (w - dispo.sw * ech) / 2f
        decY = (h - dispo.sh * ech) / 2f
    }

    private fun versEcran(r: Rect4, out: RectF) {
        out.set(decX + r.x * ech, decY + r.y * ech,
                decX + (r.x + r.w) * ech, decY + (r.y + r.h) * ech)
    }

    private val tmp = RectF()
    private val tmp2 = RectF()

    // ================= dessin =================

    override fun onDraw(c: Canvas) {
        val skin = if (!enPaysage) skinP
                   else when (variantePaysage) { 1 -> skinL2; 2 -> skinL3; else -> skinL }
        val ecran = dispo.items[Ids.SCREEN]
        if (ecran != null) {
            versEcran(ecran, tmp2)
            if (tmp2 != dernierEcran) { dernierEcran.set(tmp2); surEcranDeplace?.invoke(RectF(tmp2)) }
        }

        if (trouEcran && ecran != null) {
            dessinerSkinAutourDe(c, skin, tmp2)
        } else {
            tmp.set(decX, decY, decX + dispo.sw * ech, decY + dispo.sh * ech)
            c.drawBitmap(skin, null, tmp, peinture)
            ecran?.let { versEcran(it, tmp); c.drawBitmap(cadreJeu, null, tmp, peintureJeu) }
        }
        if (enPaysage && variantePaysage == 2) {
            // Troisieme presentation : rien qu'un trace pose sur le jeu.
            dessinerCouche3(c)
        } else if (enPaysage && variantePaysage == 1) {
            // Seconde presentation : le liseré du pave et les plaques des
            // boutons rouges sont DANS le fond. On ne dessine donc que les
            // pieces qui bougent.
            dispo.items[Ids.DPAD]?.let { dessinerCroix2(c, it) }
            dispo.items[Ids.BTN_B]?.let { dessinerRond2(c, it, "rond2_B", Ids.BTN_B) }
            dispo.items[Ids.BTN_A]?.let { dessinerRond2(c, it, "rond2_A", Ids.BTN_A) }
            dispo.items[Ids.SS]?.let { dessinerSelectStart2(c, it) }
            dispo.items[Ids.FF]?.let { dessinerSimple(c, it, "ff2", Ids.FF) }
            dispo.items[Ids.MANETTE]?.let { dessinerSimple(c, it, "manette", Ids.MANETTE) }
        } else {
            dispo.items[Ids.DPAD]?.let { dessinerCroix(c, it) }
            dispo.items[Ids.BTN_B]?.let { dessinerRond(c, it, "socle_B", "rond_B", Ids.BTN_B) }
            dispo.items[Ids.BTN_A]?.let { dessinerRond(c, it, "socle_A", "rond_A", Ids.BTN_A) }
            dispo.items[Ids.SS]?.let { dessinerSelectStart(c, it) }
            dispo.items[Ids.FF]?.let { dessinerAvanceRapide(c, it) }
            if (enPaysage) dispo.items[Ids.MANETTE]?.let {
                dessinerSimple(c, it, "manette", Ids.MANETTE) }
        }
        // Sur la couche transparente, MENU a son propre dessin : il ne faut pas
        // lui rejouer par-dessus le decoupage du fond, qui est noir.
        if (!(enPaysage && variantePaysage == 2))
            for (id in Ids.TOUCHES_SKIN) dispo.items[id]?.let { dessinerToucheSkin(c, skin, it, id) }
        if (liste != null) ecran?.let { dessinerListe(c, it) }

        if (modeEdition) dessinerEdition(c)
    }

    /**
     * Dessine le skin en quatre bandes autour du rectangle de jeu, qui reste transparent.
     * On evite ainsi un PorterDuff.CLEAR, dont le comportement varie selon les pilotes.
     */
    private fun dessinerSkinAutourDe(c: Canvas, skin: Bitmap, jeu: RectF) {
        val g = skin.width / dispo.sw          // image du skin -> coordonnees de disposition
        val gauche = decX; val haut = decY
        val droite = decX + dispo.sw * ech; val bas = decY + dispo.sh * ech
        val jg = jeu.left.coerceIn(gauche, droite); val jd = jeu.right.coerceIn(gauche, droite)
        val jh = jeu.top.coerceIn(haut, bas);       val jb = jeu.bottom.coerceIn(haut, bas)

        fun bande(l: Float, t2: Float, r: Float, b: Float) {
            if (r - l < 0.5f || b - t2 < 0.5f) return
            val src = Rect(
                (((l - decX) / ech) * g).toInt().coerceIn(0, skin.width),
                (((t2 - decY) / ech) * g).toInt().coerceIn(0, skin.height),
                (((r - decX) / ech) * g).toInt().coerceIn(0, skin.width),
                (((b - decY) / ech) * g).toInt().coerceIn(0, skin.height))
            if (src.width() <= 0 || src.height() <= 0) return
            tmp.set(l, t2, r, b)
            c.drawBitmap(skin, src, tmp, peinture)
        }
        bande(gauche, haut, droite, jh)     // au-dessus
        bande(gauche, jb, droite, bas)      // en dessous
        bande(gauche, jh, jg, jb)           // a gauche
        bande(jd, jh, droite, jb)           // a droite
    }

    /** true si l'element doit apparaitre enfonce (appui en cours, ou trop recent pour disparaitre). */
    private fun enfonce(id: String): Boolean {
        if (actifs.containsKey(id)) return true
        val r = relaches[id] ?: return false
        return System.currentTimeMillis() - r < appuiMiniMs
    }

    private fun filtreLumi(f: Float) = ColorMatrixColorFilter(
        ColorMatrix(floatArrayOf(f,0f,0f,0f,0f, 0f,f,0f,0f,0f, 0f,0f,f,0f,0f, 0f,0f,0f,1f,0f)))

    private fun dessinerCroix(c: Canvas, r: Rect4) {
        versEcran(r, tmp)
        val noyau = sprites["pad_bascule"]!!
        val contour = sprites["pad_contour"]!!
        val masque = sprites["pad_masque"]!!

        val actif = enfonce(Ids.DPAD) && (dirX != 0 || dirY != 0)
        if (!actif) {
            c.drawBitmap(noyau, null, tmp, peinture)
        } else {
            val couche = c.saveLayer(tmp, null)
            val cx = tmp.centerX(); val cy = tmp.centerY()

            // rotateX positif = le haut recule | rotateY positif = la droite recule
            camera.save()
            camera.setLocation(0f, 0f, -6f)          // perspective courte : la bascule se voit
            camera.rotateX(-dirY * angleCroix)
            camera.rotateY(dirX * angleCroix)
            camera.getMatrix(matrice)
            camera.restore()
            matrice.preTranslate(-cx, -cy)
            matrice.postTranslate(cx, cy)

            c.save()
            c.concat(matrice)
            c.translate(dirX * r.w * ech * 0.016f, dirY * r.h * ech * 0.016f)
            c.drawBitmap(noyau, null, tmp, peinture)
            c.restore()

            // eclairage directionnel : c'est lui qui rend la profondeur lisible en petit
            val ray = max(tmp.width(), tmp.height()) / 2f
            val nx = dirX.toFloat(); val ny = dirY.toFloat()
            val norme = max(1e-3f, kotlin.math.sqrt(nx * nx + ny * ny))
            val ux = nx / norme; val uy = ny / norme
            peintureTeinte.shader = LinearGradient(
                cx - ux * ray, cy - uy * ray, cx + ux * ray, cy + uy * ray,
                intArrayOf(
                    Color.argb((255 * forceLumiere).toInt(), 255, 255, 255),
                    Color.argb((255 * forceLumiere * 0.22f).toInt(), 255, 255, 255),
                    Color.argb((255 * forceOmbre * 0.16f).toInt(), 0, 0, 0),
                    Color.argb((255 * forceOmbre).toInt(), 0, 0, 0)),
                floatArrayOf(0f, 0.32f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(tmp, peintureTeinte)
            peintureTeinte.shader = null

            // decoupe sur la silhouette : sinon la rotation deborde du contour
            c.drawBitmap(masque, null, tmp, peintureMasque)
            c.restoreToCount(couche)
        }
        // le contour ne bouge jamais
        c.drawBitmap(contour, null, tmp, peinture)
    }

    private fun dessinerRond(c: Canvas, r: Rect4, socle: String, rond: String, id: String) {
        versEcran(r, tmp)
        c.drawBitmap(sprites[socle]!!, null, tmp, peinture)
        val w = tmp.width(); val h = tmp.height()
        tmp2.set(tmp.left + w * 0.059f, tmp.top + h * 0.074f,
                 tmp.left + w * (0.059f + 0.859f), tmp.top + h * (0.074f + 0.859f))
        if (enfonce(id)) {
            c.save()
            c.translate(w * 0.016f, h * 0.055f)
            c.scale(0.93f, 0.93f, tmp2.centerX(), tmp2.centerY())
            peinture.colorFilter = filtreLumi(0.74f)
            c.drawBitmap(sprites[rond]!!, null, tmp2, peinture)
            peinture.colorFilter = null
            c.restore()
        } else c.drawBitmap(sprites[rond]!!, null, tmp2, peinture)
    }

    /** Une piece qui s'enfonce sur place : elle rapetisse et s'assombrit. */
    private fun dessinerSimple(c: Canvas, r: Rect4, spr: String, id: String) {
        versEcran(r, tmp)
        val b = sprites[spr] ?: return
        if (enfonce(id)) {
            c.save()
            c.translate(tmp.width() * 0.018f, tmp.height() * 0.055f)
            c.scale(0.92f, 0.92f, tmp.centerX(), tmp.centerY())
            peinture.colorFilter = filtreLumi(0.72f)
            c.drawBitmap(b, null, tmp, peinture)
            peinture.colorFilter = null
            c.restore()
        } else c.drawBitmap(b, null, tmp, peinture)
    }

    /**
     * Bouton rond de la seconde presentation.
     *
     * Pas de socle a dessiner : la plaque grise reste dans le fond, comme
     * demande. Seul le bouton rouge bouge.
     */
    private fun dessinerRond2(c: Canvas, r: Rect4, spr: String, id: String) =
        dessinerSimple(c, r, spr, id)

    /**
     * Troisieme presentation : une couche transparente.
     *
     * Toutes les pieces sont dessinees avec la meme opacite, reglable dans le
     * mode Modifier. On la pose sur la peinture commune et on la retire
     * ensuite, pour que le reste du dessin ne s'en trouve pas change.
     */
    private fun dessinerCouche3(c: Canvas) {
        val garde = peinture.alpha
        peinture.alpha = (255 * opaciteBoutons).toInt().coerceIn(24, 255)
        dispo.items[Ids.DPAD]?.let { dessinerCroix3(c, it) }
        dispo.items[Ids.BTN_B]?.let { dessinerSimple(c, it, "p3_B", Ids.BTN_B) }
        dispo.items[Ids.BTN_A]?.let { dessinerSimple(c, it, "p3_A", Ids.BTN_A) }
        dispo.items[Ids.SS]?.let { dessinerSelectStart3(c, it) }
        dispo.items[Ids.FF]?.let { dessinerSimple(c, it, "p3_ff", Ids.FF) }
        dispo.items[Ids.MENU]?.let { dessinerSimple(c, it, "p3_menu", Ids.MENU) }
        dispo.items[Ids.MANETTE]?.let { dessinerSimple(c, it, "manette", Ids.MANETTE) }
        peinture.alpha = garde
    }

    /**
     * Croix de la couche transparente : elle bascule vers la direction poussee.
     * Pas de masque ici, contrairement aux deux consoles : le trace n'a pas de
     * logement dont il pourrait deborder.
     */
    private fun dessinerCroix3(c: Canvas, r: Rect4) {
        versEcran(r, tmp)
        val b = sprites["p3_croix"] ?: return
        val actif = enfonce(Ids.DPAD) && (dirX != 0 || dirY != 0)
        if (!actif) { c.drawBitmap(b, null, tmp, peinture); return }

        val cx = tmp.centerX(); val cy = tmp.centerY()
        camera.save()
        camera.setLocation(0f, 0f, -6f)
        camera.rotateX(-dirY * angleCroix)
        camera.rotateY(dirX * angleCroix)
        camera.getMatrix(matrice)
        camera.restore()
        matrice.preTranslate(-cx, -cy)
        matrice.postTranslate(cx, cy)

        c.save()
        c.concat(matrice)
        c.translate(dirX * r.w * ech * 0.02f, dirY * r.h * ech * 0.02f)
        c.drawBitmap(b, null, tmp, peinture)
        c.restore()
    }

    /** SELECT et START de la couche transparente : deux barrettes dans la meme boite. */
    private fun dessinerSelectStart3(c: Canvas, r: Rect4) {
        versEcran(r, tmp)
        val w = tmp.width(); val h = tmp.height()
        fun barrette(spr: String, gx: Float, largeur: Float, id: String) {
            tmp2.set(tmp.left + w * gx, tmp.top,
                     tmp.left + w * (gx + largeur), tmp.top + h)
            val b = sprites[spr] ?: return
            if (enfonce(id)) {
                c.save()
                c.translate(0f, tmp2.height() * 0.14f)
                c.scale(0.95f, 0.86f, tmp2.centerX(), tmp2.centerY())
                peinture.colorFilter = filtreLumi(0.62f)
                c.drawBitmap(b, null, tmp2, peinture)
                peinture.colorFilter = null
                c.restore()
            } else c.drawBitmap(b, null, tmp2, peinture)
        }
        // mesures de l'image : SELECT sur 237 px, START 272 px plus loin, dans 506 px
        barrette("p3_select", 0f, 237f / 506f, "select")
        barrette("p3_start", 272f / 506f, 234f / 506f, "start")
    }

    /**
     * Pave directionnel de la seconde presentation.
     *
     * Son liseré blanc ne bouge jamais : il reste dans le fond. On ne fait
     * donc basculer que la croix noire, avec le meme relief que sur l'autre
     * presentation.
     */
    private fun dessinerCroix2(c: Canvas, r: Rect4) {
        versEcran(r, tmp)
        val noyau = sprites["pad2_bascule"] ?: return
        val actif = enfonce(Ids.DPAD) && (dirX != 0 || dirY != 0)
        if (!actif) { c.drawBitmap(noyau, null, tmp, peinture); return }

        val couche = c.saveLayer(tmp, null)
        val cx = tmp.centerX(); val cy = tmp.centerY()
        camera.save()
        camera.setLocation(0f, 0f, -6f)
        camera.rotateX(-dirY * angleCroix)
        camera.rotateY(dirX * angleCroix)
        camera.getMatrix(matrice)
        camera.restore()
        matrice.preTranslate(-cx, -cy)
        matrice.postTranslate(cx, cy)

        c.save()
        c.concat(matrice)
        c.translate(dirX * r.w * ech * 0.016f, dirY * r.h * ech * 0.016f)
        c.drawBitmap(noyau, null, tmp, peinture)
        c.restore()

        val ray = max(tmp.width(), tmp.height()) / 2f
        val nx = dirX.toFloat(); val ny = dirY.toFloat()
        val norme = max(1e-3f, kotlin.math.sqrt(nx * nx + ny * ny))
        val ux = nx / norme; val uy = ny / norme
        peintureTeinte.shader = LinearGradient(
            cx - ux * ray, cy - uy * ray, cx + ux * ray, cy + uy * ray,
            intArrayOf(
                Color.argb((255 * forceLumiere).toInt(), 255, 255, 255),
                Color.argb((255 * forceLumiere * 0.22f).toInt(), 255, 255, 255),
                Color.argb((255 * forceOmbre * 0.16f).toInt(), 0, 0, 0),
                Color.argb((255 * forceOmbre).toInt(), 0, 0, 0)),
            floatArrayOf(0f, 0.32f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(tmp, peintureTeinte)
        peintureTeinte.shader = null
        // La silhouette NON inclinee sert de decoupe : sans elle, la
        // rotation deborde du logement. C'est ce que fait l'ancien pave avec
        // son propre masque, et c'est ce qui lui donne sa nettete.
        c.drawBitmap(sprites["pad2_masque"] ?: noyau, null, tmp, peintureMasque)
        c.restoreToCount(couche)
    }

    /** SELECT et START de la seconde presentation : deux pilules separees. */
    private fun dessinerSelectStart2(c: Canvas, r: Rect4) {
        versEcran(r, tmp)
        val w = tmp.width(); val h = tmp.height()
        fun pilule(spr: String, gx: Float, largeur: Float, id: String) {
            tmp2.set(tmp.left + w * gx, tmp.top,
                     tmp.left + w * (gx + largeur), tmp.top + h)
            val b = sprites[spr] ?: return
            if (enfonce(id)) {
                c.save()
                c.translate(0f, tmp2.height() * 0.22f)
                c.scale(1f, 0.82f, tmp2.centerX(), tmp2.centerY())
                peinture.colorFilter = filtreLumi(0.58f)
                c.drawBitmap(b, null, tmp2, peinture)
                peinture.colorFilter = null
                c.restore()
            } else c.drawBitmap(b, null, tmp2, peinture)
        }
        // positions mesurees : SELECT a gauche, START a 155 px de la, dans un
        // rectangle de 239 px de large
        pilule("pilule2_select", 0f, 83f / 239f, "select")
        pilule("pilule2_start", 155f / 239f, 84f / 239f, "start")
    }

    private fun dessinerSelectStart(c: Canvas, r: Rect4) {
        versEcran(r, tmp)
        c.drawBitmap(sprites["ss_cadre"]!!, null, tmp, peinture)
        val w = tmp.width(); val h = tmp.height()
        fun pilule(spr: String, gx: Float, id: String) {
            tmp2.set(tmp.left + w * gx, tmp.top + h * 0.570f,
                     tmp.left + w * (gx + 0.230f), tmp.top + h * (0.570f + 0.264f))
            if (enfonce(id)) {
                c.save()
                c.translate(0f, tmp2.height() * 0.28f)
                c.scale(1f, 0.78f, tmp2.centerX(), tmp2.centerY())
                peinture.colorFilter = filtreLumi(0.54f)
                c.drawBitmap(sprites[spr]!!, null, tmp2, peinture)
                peinture.colorFilter = null
                c.restore()
            } else c.drawBitmap(sprites[spr]!!, null, tmp2, peinture)
        }
        pilule("pilule_select", 0.096f, "select")
        pilule("pilule_start", 0.666f, "start")
    }

    private fun dessinerAvanceRapide(c: Canvas, r: Rect4) {
        versEcran(r, tmp)
        if (enfonce(Ids.FF)) {
            c.save()
            c.translate(tmp.width() * 0.022f, tmp.height() * 0.075f)
            c.scale(0.90f, 0.90f, tmp.centerX(), tmp.centerY())
            peinture.colorFilter = filtreLumi(0.72f)
            c.drawBitmap(sprites["avance_rapide"]!!, null, tmp, peinture)
            peinture.colorFilter = null
            c.restore()
        } else c.drawBitmap(sprites["avance_rapide"]!!, null, tmp, peinture)
    }

    /**
     * MENU, POWER et RESET font partie de l'image du skin, sans sprite a eux.
     * Pour l'animer on redecoupe sa zone dans l'image et on la redessine :
     * une passe sombre qui efface les aretes d'origine, puis la touche reduite
     * et assombrie par-dessus. L'oeil lit un bouton rentre dans son logement.
     */
    private fun dessinerToucheSkin(c: Canvas, skin: Bitmap, r: Rect4, id: String) {
        if (!enfonce(id)) return
        val g = skin.width / dispo.sw
        val src = Rect(
            (r.x * g).toInt().coerceIn(0, skin.width),
            (r.y * g).toInt().coerceIn(0, skin.height),
            ((r.x + r.w) * g).toInt().coerceIn(0, skin.width),
            ((r.y + r.h) * g).toInt().coerceIn(0, skin.height))
        if (src.width() <= 0 || src.height() <= 0) return
        versEcran(r, tmp)

        peinture.colorFilter = filtreLumi(0.42f)
        c.drawBitmap(skin, src, tmp, peinture)          // le logement, dans l'ombre

        tmp2.set(tmp)
        val dx = tmp.width() * 0.03f
        val dy = tmp.height() * 0.05f
        tmp2.inset(dx, dy)
        tmp2.offset(0f, dy * 0.9f)
        peinture.colorFilter = filtreLumi(0.70f)
        c.drawBitmap(skin, src, tmp2, peinture)         // la touche, enfoncee
        peinture.colorFilter = null
    }

    /**
     * Le catalogue s'affiche dans le rectangle de jeu : blanc sur noir,
     * chasse fixe, majuscules et un chevron devant la ligne touchee.
     * C'est la mise en page des menus de l'epoque, et c'est aussi la plus
     * lisible sur un ecran de 256 pixels de large.
     */
    private fun dessinerListe(c: Canvas, r: Rect4) {
        val noms = liste ?: return
        versEcran(r, tmp)
        c.save()
        c.clipRect(tmp)
        c.drawRect(tmp, fondListe)

        val rangs = 9f
        val h = tmp.height() / rangs
        texteListe.textSize = h * 0.58f
        val marge = tmp.width() * 0.045f

        // bandeau de titre
        texteListe.color = 0xFF9A9A9A.toInt()
        c.drawText("CHOISIR UN JEU   ${noms.size}", tmp.left + marge, tmp.top + h * 0.72f, texteListe)
        c.drawRect(tmp.left + marge, tmp.top + h * 0.95f,
                   tmp.right - marge, tmp.top + h * 0.95f + 2f, barreListe)

        val hautListe = tmp.top + h * 1.3f
        val premier = ((defilement / h).toInt()).coerceAtLeast(0)
        val dernier = (premier + rangs.toInt()).coerceAtMost(noms.size - 1)
        for (i in premier..dernier) {
            val y = hautListe + i * h - defilement
            if (y > tmp.bottom) break
            val presse = i == rangPresse
            texteListe.color = if (presse) Color.BLACK else Color.WHITE
            if (presse) c.drawRect(tmp.left, y - h * 0.72f, tmp.right, y + h * 0.24f, barreListe)
            val nom = noms[i].substringBeforeLast('.').uppercase()
            c.drawText(tronquer(nom, tmp.width() - marge * 3f),
                       tmp.left + marge * 2f, y, texteListe)
            if (presse) {
                texteListe.color = Color.BLACK
                c.drawText(">", tmp.left + marge * 0.5f, y, texteListe)
            }
        }
        c.restore()
    }

    private fun tronquer(s: String, largeur: Float): String {
        if (texteListe.measureText(s) <= largeur) return s
        var n = s.length
        while (n > 1 && texteListe.measureText(s.substring(0, n) + "…") > largeur) n--
        return s.substring(0, n) + "…"
    }

    private fun dessinerEdition(c: Canvas) {
        for (id in Ids.ALL) {
            val r = dispo.items[id] ?: continue
            versEcran(r, tmp)
            traitEdition.color = if (id == Ids.SCREEN) 0xFF3AA0FF.toInt() else 0xFFC82128.toInt()
            poignee.color = traitEdition.color
            c.drawRect(tmp, traitEdition)
            val p = 22f
            for (pt in listOf(
                PointF(tmp.right, tmp.centerY()),
                PointF(tmp.centerX(), tmp.bottom),
                PointF(tmp.right, tmp.bottom))) {
                c.drawCircle(pt.x, pt.y, p, poignee)
                c.drawCircle(pt.x, pt.y, p, poigneeBord)
            }
        }
    }

    // ================= tactile =================

    private val doigts = HashMap<Int, String>()      // pointerId -> element touche
    /** Doigts poses, meme lorsqu'ils survolent le vide entre deux touches. */
    private val suivis = HashSet<Int>()

    private var edPoignee: String? = null            // "e" / "s" / "se" / null
    private var edId: String? = null
    private var edDepart = Rect4(0f, 0f, 0f, 0f)
    private var edX0 = 0f
    private var edY0 = 0f

    /** Ordre inverse du dessin : les elements du dessus captent en premier. */
    private val ordreCapture =
        Ids.TOUCHES_SKIN + listOf(Ids.MANETTE, Ids.FF, Ids.SS,
                                  Ids.BTN_A, Ids.BTN_B, Ids.DPAD)

    private fun elementSous(x: Float, y: Float): String? {
        // ordre inverse du dessin : les elements du dessus captent en premier
        for (id in ordreCapture) {
            val r = dispo.items[id] ?: continue
            versEcran(r, tmp)
            if (tmp.contains(x, y)) return id
        }
        return null
    }

    // etat du glissement dans la liste
    private var listeY0 = 0f
    private var listeDefil0 = 0f
    private var listeAGlisse = false
    private var listeActive = false

    /** Un doigt dans le rectangle de jeu pendant que la liste est ouverte :
     *  glisser fait defiler, relacher sans avoir glisse choisit le jeu. */
    private fun listeTactile(e: MotionEvent): Boolean {
        val noms = liste ?: return false
        val r = dispo.items[Ids.SCREEN] ?: return false
        versEcran(r, tmp)
        val x = e.x; val y = e.y
        val h = tmp.height() / 9f
        val hautListe = tmp.top + h * 1.3f

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
                    defilement = (listeDefil0 - d).coerceIn(0f, max(0f, total))
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!listeActive) return false
                listeActive = false
                val choisi = rangPresse
                rangPresse = -1
                invalidate()
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

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (liste != null && !modeEdition) {
            if (listeTactile(e)) return true
            // en dehors de l'ecran, seules les touches du skin repondent
        }
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val i = e.actionIndex
            val el = elementSous(e.getX(i), e.getY(i))
            if (el == null || !Ids.TOUCHES_SKIN.contains(el)) surTouche?.invoke()
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
                    if (doigts[id] == Ids.DPAD) { majCroix(x, y); continue }
                    // Glisser d'un bouton a l'autre enfonce le nouveau sans
                    // qu'on ait a lever le doigt. Le doigt reste suivi meme
                    // au-dessus du vide entre deux touches, sans quoi le
                    // passage se perdait des le premier pixel d'ecart.
                    val sous = elementSous(x, y)
                    if (sous == doigts[id]) continue
                    if (sous == Ids.DPAD) continue          // la croix se prend en la visant
                    relacher(id)
                    suivis.add(id)
                    // On ne declenche jamais une touche de l'application au
                    // passage du doigt : ouvrir le menu ou eteindre la console
                    // en glissant serait facheux.
                    if (sous != null && sous !in Ids.TOUCHES_SKIN) appuyer(id, x, y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) {
                    doigts.keys.toList().forEach { relacher(it) }
                    suivis.clear()
                } else {
                    val pid = e.getPointerId(e.actionIndex)
                    relacher(pid)
                    suivis.remove(pid)
                }
            }
        }
        majBoutons()
        return true
    }

    private fun appuyer(pid: Int, x: Float, y: Float) {
        suivis.add(pid)
        val el = elementSous(x, y) ?: return
        val maintenant = System.currentTimeMillis()
        when (el) {
            Ids.DPAD -> { doigts[pid] = Ids.DPAD; actifs[Ids.DPAD] = maintenant; majCroix(x, y) }
            Ids.SS -> {
                versEcran(dispo.items[Ids.SS]!!, tmp)
                val cible = if ((x - tmp.left) / tmp.width() < 0.5f) "select" else "start"
                doigts[pid] = cible; actifs[cible] = maintenant
            }
            Ids.MENU -> { doigts[pid] = el; actifs[el] = maintenant; surMenu?.invoke() }
            Ids.POWER -> { doigts[pid] = el; actifs[el] = maintenant; surPower?.invoke() }
            Ids.RESET -> { doigts[pid] = el; actifs[el] = maintenant; surReset?.invoke() }
            Ids.JEUX -> { doigts[pid] = el; actifs[el] = maintenant; surJeux?.invoke() }
            Ids.QUIT -> { doigts[pid] = el; actifs[el] = maintenant; surQuit?.invoke() }
            // La manette passe a la presentation horizontale suivante.
            Ids.MANETTE -> {
                doigts[pid] = el; actifs[el] = maintenant
                variantePaysage += 1
                surManette?.invoke()
            }
            else -> { doigts[pid] = el; actifs[el] = maintenant }
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun relacher(pid: Int) {
        val el = doigts.remove(pid) ?: return
        // le suivi, lui, ne cesse qu'au lever du doigt
        actifs.remove(el)
        relaches[el] = System.currentTimeMillis()
        if (el == Ids.DPAD) { dirX = 0; dirY = 0 }
    }

    private fun majCroix(x: Float, y: Float) {
        val r = dispo.items[Ids.DPAD] ?: return
        versEcran(r, tmp)
        val nx = (x - tmp.left) / tmp.width() * 2f - 1f
        val ny = (y - tmp.top) / tmp.height() * 2f - 1f
        dirX = if (abs(nx) < zoneMorte) 0 else sign(nx).toInt()
        dirY = if (abs(ny) < zoneMorte) 0 else sign(ny).toInt()
    }

    private fun majBoutons() {
        var b = 0
        if (actifs.containsKey(Ids.BTN_A)) b = b or Pad.A
        if (actifs.containsKey(Ids.BTN_B)) b = b or Pad.B
        if (actifs.containsKey("select")) b = b or Pad.SELECT
        if (actifs.containsKey("start")) b = b or Pad.START
        if (dirY < 0) b = b or Pad.HAUT
        if (dirY > 0) b = b or Pad.BAS
        if (dirX < 0) b = b or Pad.GAUCHE
        if (dirX > 0) b = b or Pad.DROITE
        boutons = b
    }

    /** Deplacement et redimensionnement : largeur, hauteur, ou diagonale a proportions gardees. */
    private fun editionTactile(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edId = null; edPoignee = null
                for (id in Ids.ALL.reversed()) {
                    val r = dispo.items[id] ?: continue
                    versEcran(r, tmp)
                    val p = 34f
                    when {
                        abs(x - tmp.right) < p && abs(y - tmp.bottom) < p -> { edId = id; edPoignee = "se" }
                        abs(x - tmp.right) < p && abs(y - tmp.centerY()) < p -> { edId = id; edPoignee = "e" }
                        abs(x - tmp.centerX()) < p && abs(y - tmp.bottom) < p -> { edId = id; edPoignee = "s" }
                        tmp.contains(x, y) -> { edId = id; edPoignee = null }
                    }
                    if (edId != null) break
                }
                edId?.let { edDepart = dispo.items[it]!!.copy4(); edX0 = x; edY0 = y }
            }
            MotionEvent.ACTION_MOVE -> {
                val id = edId ?: return true
                val r = dispo.items[id]!!
                val dx = (x - edX0) / ech
                val dy = (y - edY0) / ech
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

    // ================= disposition =================

    fun enregistrerDisposition() = Dispositions.enregistrer(context, enPaysage, dispo)

    fun reinitialiserDisposition() {
        Dispositions.reinitialiser(context, enPaysage)
        dispo = Dispositions.defautDe(context, enPaysage)
        recalculer(width, height)
        invalidate()
    }
}
