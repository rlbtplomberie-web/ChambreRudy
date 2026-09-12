package com.skinpsp.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import org.ppsspp.ppsspp.NativeApp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * L'habillage tactile : le boitier, ses touches, et ce qu'elles envoient.
 *
 * La vue ne calcule rien du jeu. Elle dessine, elle ecoute les doigts, et elle
 * transmet a PPSSPP exactement ce qu'une manette lui enverrait. C'est la
 * difference avec la version precedente, ou cette meme vue devait aussi
 * afficher l'image du jeu : ici l'image est en dessous, dessinee par le moteur
 * lui-meme, et nous n'y touchons pas.
 */
class PadView(contexte: Context) : View(contexte) {

    /*
     * Le moteur ne connait pas « croix » ou « triangle » : il connait les
     * touches d'une manette Android. On lui parle donc dans sa langue, et
     * c'est sa table de correspondance habituelle qui fait le reste.
     *
     * Le numero d'appareil 10 est celui d'une premiere manette. Employer
     * celui du clavier donnerait d'autres correspondances par defaut, et les
     * touches ne tomberaient pas ou il faut.
     */
    // Les commandes PSP fonctionnaient sur PAD_0 : ne jamais les melanger
    // avec le clavier virtuel, qui utilise une autre table de correspondance.
    private val MANETTE = NativeApp.DEVICE_ID_PAD_0
    private val CLAVIER = NativeApp.DEVICE_ID_KEYBOARD

    private val TOUCHES = mapOf(
        "croixB"   to KeyEvent.KEYCODE_BUTTON_A,      // la croix de la PSP
        "rond"     to KeyEvent.KEYCODE_BUTTON_B,
        "carre"    to KeyEvent.KEYCODE_BUTTON_X,
        "triangle" to KeyEvent.KEYCODE_BUTTON_Y,
        "L"        to KeyEvent.KEYCODE_BUTTON_L1,
        "R"        to KeyEvent.KEYCODE_BUTTON_R1,
        "start"    to KeyEvent.KEYCODE_BUTTON_START,
        "select"   to KeyEvent.KEYCODE_BUTTON_SELECT,
        "haut"     to KeyEvent.KEYCODE_DPAD_UP,
        "bas"      to KeyEvent.KEYCODE_DPAD_DOWN,
        "gauche"   to KeyEvent.KEYCODE_DPAD_LEFT,
        "droite"   to KeyEvent.KEYCODE_DPAD_RIGHT
    )

    /*
     * MENU, JEUX, CHEAT et QUIT ouvrent le menu de PPSSPP.
     *
     * Ces quatre-la commandaient l'application, du temps ou l'interface etait
     * la notre. PPSSPP a maintenant son propre menu — liste des jeux,
     * reglages, triches, sauvegardes — et il s'ouvre par la touche Retour.
     * On les y renvoie donc toutes les quatre plutot que de faire semblant
     * d'avoir quatre ecrans differents.
     */
    private val VERS_MENU = setOf("menu", "jeux", "cheat", "quit")

    private class Touche(
        val id: String, val x: Float, val y: Float, val w: Float, val h: Float,
        val repos: Bitmap?, val appui: Bitmap?, val marge: Float,
        val estStick: Boolean, val course: Float, val zoneMorte: Float
    )

    private var dossier = ""
    private var fond: Bitmap? = null
    private var largeurSkin = 1f
    private var hauteurSkin = 1f
    private val ecranSkin = RectF()
    private val ecranTelephone = RectF()
    private val touches = ArrayList<Touche>()

    /** Informe l'activite de la place exacte reservee a l'image du jeu. */
    var surCadreEcran: ((RectF) -> Unit)? = null
    var surActionSysteme: ((String) -> Unit)? = null

    /** Quel doigt tient quelle touche. */
    private val tenues = HashMap<Int, Touche>()
    private val enfoncees = HashSet<String>()

    /** Position du manche, entre -1 et 1. */
    private var stickX = 0f
    private var stickY = 0f
    private var doigtStick = -1

    private val peinture = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val peintureLibelle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val cible = RectF()

    init {
        setWillNotDraw(false)
        isFocusable = false          // le moteur garde le clavier physique
        relire()
    }

    /** Recharge l'habillage qui convient a l'orientation du moment. */
    fun relire() {
        val voulu = if (width >= height) "paysage" else "portrait"
        if (voulu == dossier && fond != null) return
        charger(voulu)
        invalidate()
    }

    private fun ouvrir(nom: String): Bitmap? = try {
        context.assets.open("psp/skin/$dossier/$nom").use { BitmapFactory.decodeStream(it) }
    } catch (_: Throwable) { null }

    private fun charger(nom: String) {
        dossier = nom
        touches.clear()
        try {
            val texte = context.assets.open("psp/skin/$nom/positions.json")
                .bufferedReader().use { it.readText() }
            val p = JSONObject(texte)
            largeurSkin = p.getDouble("largeur").toFloat()
            hauteurSkin = p.getDouble("hauteur").toFloat()
            val e = p.getJSONObject("ecran")
            ecranSkin.set(
                e.getDouble("x").toFloat(), e.getDouble("y").toFloat(),
                (e.getDouble("x") + e.getDouble("w")).toFloat(),
                (e.getDouble("y") + e.getDouble("h")).toFloat()
            )
            fond = ouvrir("fond.png")
            val liste = p.getJSONArray("elements")
            for (i in 0 until liste.length()) {
                val e = liste.getJSONObject(i)
                val im = e.getJSONObject("images")
                val estStick = e.optString("type") == "stick"
                /* On ne garde que la deuxieme image d'appui : elle suffit a
                   montrer la touche enfoncee, et charger les deux doublerait
                   la memoire pour un gain invisible au doigt. */
                val appui = when {
                    estStick -> null
                    im.has("appui") -> ouvrir(im.getJSONArray("appui").getString(1))
                    else -> null
                }
                touches.add(
                    Touche(
                        e.getString("id"),
                        e.getDouble("x").toFloat(), e.getDouble("y").toFloat(),
                        e.getDouble("w").toFloat(), e.getDouble("h").toFloat(),
                        ouvrir(im.getString("repos")), appui,
                        e.optDouble("marge", 0.0).toFloat(),
                        estStick,
                        e.optDouble("course", 20.0).toFloat(),
                        e.optDouble("zoneMorte", 0.14).toFloat()
                    )
                )
            }
        } catch (_: Throwable) {
            // Un habillage illisible ne doit pas empecher de jouer : sans lui,
            // les commandes tactiles de PPSSPP restent disponibles.
            touches.clear(); fond = null
        }
    }

    override fun onSizeChanged(l: Int, h: Int, al: Int, ah: Int) {
        super.onSizeChanged(l, h, al, ah)
        relire()
        calculer()
        // Meme cycle d'affichage que le changement du pad : l'ecran du jeu ne
        // reste pas une fraction de seconde dans son ancienne orientation.
        surCadreEcran?.invoke(RectF(ecranTelephone))
    }

    // ---------- mise a l'echelle ----------

    private var ech = 1f
    private var decX = 0f
    private var decY = 0f

    private fun calculer() {
        if (largeurSkin <= 1f || width <= 0 || height <= 0) return
        /* Une seule echelle conserve exactement les proportions du dessin.
           max remplit tout le telephone sans bandes ni coins visibles. Le
           tres leger excedent est centre et rogne seulement le bord externe. */
        ech = max(width / largeurSkin, height / hauteurSkin)
        decX = (width - largeurSkin * ech) / 2f
        decY = (height - hauteurSkin * ech) / 2f
        ecranTelephone.set(
            decX + ecranSkin.left * ech,
            decY + ecranSkin.top * ech,
            decX + ecranSkin.right * ech,
            decY + ecranSkin.bottom * ech
        )
    }

    fun cadreEcran(): RectF {
        calculer()
        return RectF(ecranTelephone)
    }

    private fun place(t: Touche, marge: Float, sortie: RectF) {
        sortie.set(
            decX + (t.x - marge) * ech, decY + (t.y - marge) * ech,
            decX + (t.x + t.w + marge) * ech, decY + (t.y + t.h + marge) * ech
        )
    }

    override fun onDraw(c: Canvas) {
        if (touches.isEmpty()) return
        calculer()
        fond?.let {
            cible.set(decX, decY, decX + largeurSkin * ech, decY + hauteurSkin * ech)
            /* Le trou reste transparent pour laisser voir la vraie SurfaceView
               PPSSPP, redimensionnee au meme rectangle par JeuActivity. */
            c.save()
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                c.clipOutRect(ecranTelephone)
            } else {
                @Suppress("DEPRECATION")
                c.clipRect(ecranTelephone, android.graphics.Region.Op.DIFFERENCE)
            }
            c.drawBitmap(it, null, cible, peinture)
            c.restore()
        }
        // Le dessin d'origine contient CHEAT. On le remplace visuellement par
        // S/C (Sauvegarder/Charger) sans deformer ni regenerer le skin.
        touches.firstOrNull { it.id == "cheat" }?.let { t ->
            val paysage = largeurSkin > hauteurSkin
            val haut = t.y + t.h + 3f
            val bas = haut + if (paysage) 38f else 30f
            c.save()
            c.translate(decX, decY)
            c.scale(ech, ech)
            peintureLibelle.color = android.graphics.Color.rgb(12, 12, 14)
            c.drawRect(t.x - 22f, haut, t.x + t.w + 22f, bas, peintureLibelle)
            peintureLibelle.color = android.graphics.Color.WHITE
            peintureLibelle.textSize = if (paysage) 24f else 19f
            c.drawText("S/C", t.x + t.w / 2f, bas - 7f, peintureLibelle)
            c.restore()
        }
        for (t in touches) {
            if (t.estStick) {
                /* Le manche suit le doigt : on deplace son image, plutot que
                   d'en garder huit figees. Un manche n'a pas huit positions. */
                val r = t.course * ech
                place(t, 0f, cible)
                cible.offset(stickX * r, stickY * r)
                t.repos?.let { c.drawBitmap(it, null, cible, peinture) }
                continue
            }
            val enfoncee = t.id in enfoncees
            val im = if (enfoncee) (t.appui ?: t.repos) else t.repos
            place(t, if (enfoncee && t.appui != null) t.marge else 0f, cible)
            im?.let { c.drawBitmap(it, null, cible, peinture) }
        }
    }

    // ---------- les doigts ----------

    private fun sous(x: Float, y: Float): Touche? {
        var gagnante: Touche? = null
        var meilleure = Float.MAX_VALUE
        for (t in touches) {
            place(t, 0f, cible)
            if (x < cible.left || x > cible.right || y < cible.top || y > cible.bottom) continue
            val d = hypot(x - cible.centerX(), y - cible.centerY())
            if (d < meilleure) { meilleure = d; gagnante = t }
        }
        return gagnante
    }

    private fun presser(t: Touche) {
        if (!enfoncees.add(t.id)) return
        when {
            t.id == "ff" -> {
                // TAB est le raccourci clavier officiel. R2 est aussi la
                // correspondance d'avance rapide du profil Android PPSSPP.
                NativeApp.keyDown(CLAVIER, KeyEvent.KEYCODE_TAB, false)
                NativeApp.keyDown(MANETTE, KeyEvent.KEYCODE_BUTTON_R2, false)
            }
            t.id in VERS_MENU -> surActionSysteme?.invoke(t.id)
            else -> TOUCHES[t.id]?.let { NativeApp.keyDown(MANETTE, it, false) }
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun relacher(t: Touche) {
        if (!enfoncees.remove(t.id)) return
        when {
            t.id == "ff" -> {
                NativeApp.keyUp(CLAVIER, KeyEvent.KEYCODE_TAB)
                NativeApp.keyUp(MANETTE, KeyEvent.KEYCODE_BUTTON_R2)
            }
            t.id in VERS_MENU -> Unit // l'activite a deja execute l'action
            else -> TOUCHES[t.id]?.let { NativeApp.keyUp(MANETTE, it) }
        }
    }

    private val axes = intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y)
    private val valeurs = FloatArray(2)

    private fun envoyerStick() {
        valeurs[0] = stickX; valeurs[1] = stickY
        NativeApp.joystickAxis(MANETTE, axes, valeurs, 2)
    }

    private fun bougerStick(t: Touche, x: Float, y: Float) {
        place(t, 0f, cible)
        val r = min(cible.width(), cible.height()) / 2f
        var dx = (x - cible.centerX()) / r
        var dy = (y - cible.centerY()) / r
        val d = hypot(dx, dy)
        if (d > 1f) { dx /= d; dy /= d }
        // Sous la zone morte, on renvoie zero franc : sinon le personnage
        // derive tout seul quand le pouce repose sans pousser.
        if (hypot(dx, dy) < t.zoneMorte) { dx = 0f; dy = 0f }
        stickX = dx; stickY = dy
        envoyerStick()
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (touches.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                saisir(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                /* On relit la position de CHAQUE doigt a chaque deplacement :
                   c'est ce qui permet de glisser d'une touche a l'autre sans
                   lever le doigt. Un manche saisi, lui, garde son doigt. */
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val t = tenues[id]
                    if (t != null && t.estStick) { bougerStick(t, e.getX(i), e.getY(i)); continue }
                    saisir(id, e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                val id = e.getPointerId(if (e.actionMasked == MotionEvent.ACTION_CANCEL) 0 else e.actionIndex)
                lacher(id)
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) {
                    for (k in tenues.keys.toList()) lacher(k)
                }
            }
        }
        invalidate()
        return true
    }

    private fun saisir(id: Int, x: Float, y: Float) {
        val avant = tenues[id]
        val apres = sous(x, y)
        if (avant === apres) {
            if (apres != null && apres.estStick) bougerStick(apres, x, y)
            return
        }
        if (avant != null) {
            if (avant.estStick) { stickX = 0f; stickY = 0f; envoyerStick(); doigtStick = -1 }
            else relacher(avant)
        }
        if (apres == null) { tenues.remove(id); return }
        tenues[id] = apres
        if (apres.estStick) { doigtStick = id; bougerStick(apres, x, y) } else presser(apres)
    }

    private fun lacher(id: Int) {
        val t = tenues.remove(id) ?: return
        if (t.estStick) {
            stickX = 0f; stickY = 0f; envoyerStick(); doigtStick = -1
        } else relacher(t)
    }
}
