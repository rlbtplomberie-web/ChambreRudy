package com.rudy.chambre.balle

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Le terrain, les joueurs et la balle, dessines par le telephone.
 *
 * Les six personnages sont les images de Rudy, reprises telles quelles : huit
 * poses pour marcher, courir, lancer, esquiver, passer, attraper, tomber, se
 * relever et encaisser.
 */
class VueBalle(ctx: Context) : View(ctx) {

    val jeu = Jeu(1f, 1f)
    var surFin: ((String) -> Unit)? = null

    private val images = HashMap<String, Bitmap>()
    private val peinture = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val texte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private var dernier = 0L
    private var tempsJeu = 0f
    private var finAnnoncee = false

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        Thread {
            for (action in listOf("walk", "run", "throw", "dodge", "pass", "catch", "fall", "rise", "hit"))
                for (i in 0 until 8) charger(String.format("%s_%02d.png", action, i))
            post { invalidate() }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun charger(nom: String): Bitmap? {
        images[nom]?.let { return it }
        return try {
            context.assets.open("balle/$nom").use {
                val b = BitmapFactory.decodeStream(it)
                if (b != null) images[nom] = b
                b
            }
        } catch (_: Throwable) { null }
    }

    override fun onSizeChanged(l: Int, h: Int, al: Int, ah: Int) {
        super.onSizeChanged(l, h, al, ah)
        jeu.largeur = l.toFloat(); jeu.hauteur = h.toFloat()
        jeu.remettre()
    }

    // ================= dessin =================

    override fun onDraw(c: Canvas) {
        val maintenant = System.nanoTime()
        val dt = if (dernier == 0L) 0f else ((maintenant - dernier) / 1_000_000_000.0).toFloat()
        dernier = maintenant
        tempsJeu += dt
        jeu.avancer(dt.coerceAtMost(0.05f))

        dessinerTerrain(c)
        dessinerJoueurs(c)
        dessinerBalle(c)
        dessinerTableau(c)

        if (jeu.decompte > 0f) dessinerDecompte(c)
        if (jeu.finie && !finAnnoncee) { finAnnoncee = true; surFin?.invoke(jeu.message) }
        invalidate()
    }

    private fun dessinerTerrain(c: Canvas) {
        val l = width.toFloat(); val h = height.toFloat()
        // le ciel et le sol
        p.shader = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(0xFF1B2A4A.toInt(), 0xFF35507F.toInt(), 0xFF2E7D32.toInt()),
            floatArrayOf(0f, .45f, .5f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, l, h, p)
        p.shader = null

        val haut = Terrain.HAUT * h; val bas = Terrain.BAS * h
        // les deux prisons, en rouge
        p.color = 0x553C1010
        c.drawRect(0f, haut, Terrain.BORD_PRISON_G * l, bas, p)
        c.drawRect(Terrain.BORD_PRISON_D * l, haut, l, bas, p)

        // les lignes
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = 0x99FFFFFF.toInt()
        c.drawRect(Terrain.BORD_PRISON_G * l, haut, Terrain.BORD_PRISON_D * l, bas, p)
        c.drawLine(Terrain.MILIEU * l, haut, Terrain.MILIEU * l, bas, p)
        p.style = Paint.Style.FILL
    }

    /** La pose qui correspond a ce que le joueur est en train de faire. */
    private fun poseDe(j: Jeu.Joueur): Bitmap? {
        fun pose(action: String, avancement: Float): Bitmap? {
            val i = (avancement.coerceIn(0f, 0.999f) * 8).toInt()
            return charger(String.format("%s_%02d.png", action, i))
        }
        return when {
            j.touche > 0f  -> pose("hit", 1f - j.touche / Terrain.DUREE_TOUCHE)
            j.chute > 0f   -> pose("fall", 1f - j.chute / Terrain.DUREE_CHUTE)
            j.releve > 0f  -> pose("rise", 1f - j.releve / Terrain.DUREE_RELEVE)
            j.lancer > 0f  -> pose("throw", 1f - j.lancer / Terrain.DUREE_LANCER)
            j.attrape > 0f && jeu.balle.porteur === j ->
                              pose("catch", 1f - j.attrape / Terrain.DUREE_ATTRAPE)
            j.esquive > 0f -> pose("dodge", 1f - j.esquive / Terrain.DUREE_ESQUIVE)
            jeu.balle.porteur === j -> charger("catch_07.png")
            hypot(j.vx, j.vy) > 150f -> charger(String.format("run_%02d.png", ((tempsJeu * 12).toInt()) % 8))
            hypot(j.vx, j.vy) > 12f  -> charger(String.format("walk_%02d.png", ((tempsJeu * 8).toInt()) % 8))
            else -> charger("walk_00.png")
        }
    }

    private fun dessinerJoueurs(c: Canvas) {
        // les plus loin d'abord, pour que les plus proches passent devant
        for (j in jeu.joueurs.sortedBy { it.y }) {
            val img = poseDe(j)
            // l'ombre au sol
            p.color = 0x66000000
            c.drawOval(RectF(j.x - 22f, j.y - 7f, j.x + 22f, j.y + 7f), p)

            if (img != null) {
                // hauteur constante : une pose couchee reste couchee
                val reference = charger("walk_00.png")?.height ?: img.height
                val cible = (height * 0.145f).coerceIn(92f, 118f)
                val echelle = cible / reference
                val dh = img.height * echelle
                val dl = img.width * echelle
                c.save()
                if (j.vx < -1f) { c.scale(-1f, 1f, j.x, j.y) }   // il regarde a gauche
                // la teinte de l'equipe, posee sur l'image
                peinture.colorFilter = if (j.equipe == 0)
                    PorterDuffColorFilter(0x3355AAFF, PorterDuff.Mode.SRC_ATOP)
                else PorterDuffColorFilter(0x33FF6655, PorterDuff.Mode.SRC_ATOP)
                c.drawBitmap(img, null, RectF(j.x - dl / 2f, j.y - dh, j.x + dl / 2f, j.y), peinture)
                peinture.colorFilter = null
                c.restore()
            } else {
                p.color = if (j.equipe == 0) 0xFF3F8CFF.toInt() else 0xFFE05545.toInt()
                c.drawCircle(j.x, j.y - 30f, 22f, p)
            }

            texte.textSize = 22f
            texte.color = if (j.prison) 0xFFFFB3A0.toInt() else 0xFFF2E6C8.toInt()
            c.drawText(j.nom, j.x, j.y + 26f, texte)
        }
    }

    private fun dessinerBalle(c: Canvas) {
        val b = jeu.balle
        p.color = 0x55000000
        c.drawOval(RectF(b.x - 10f, b.y - 4f, b.x + 10f, b.y + 4f), p)
        val cy = b.y - b.z
        p.shader = RadialGradient(b.x - 3f, cy - 3f, 14f,
            intArrayOf(0xFFFFF2C8.toInt(), 0xFFE8A33A.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(b.x, cy, 11f, p)
        p.shader = null
        if (b.chargee && !b.morte) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = 0xAAFFE27A.toInt()
            c.drawCircle(b.x, cy, 16f, p)
            p.style = Paint.Style.FILL
        }
    }

    private fun dessinerTableau(c: Canvas) {
        texte.textSize = 26f
        texte.color = 0xFFEFE3CC.toInt()
        c.drawText("🔵 ${jeu.surTerrain(0)} terrain   •   ${jeu.surTerrain(1)} terrain 🔴",
                   width / 2f, 44f, texte)
        if (jeu.message.isNotEmpty() && jeu.decompte <= 0f) {
            texte.textSize = 30f
            c.drawText(jeu.message, width / 2f, 84f, texte)
        }
    }

    private fun dessinerDecompte(c: Canvas) {
        val restant = jeu.decompte
        val n = restant.toInt()
        texte.textSize = height * 0.22f
        texte.color = 0xFFFFE9A8.toInt()
        val libelle = if (n >= 1) n.toString() else "GO"
        c.drawText(libelle, width / 2f, height / 2f, texte)
    }

    // ================= les commandes =================

    var doigtX = 0f; var doigtY = 0f; var manche = false
    private var centreX = 0f; private var centreY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val h = jeu.heros() ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { centreX = e.x; centreY = e.y; manche = true }
            MotionEvent.ACTION_MOVE -> if (manche) {
                val dx = e.x - centreX; val dy = e.y - centreY
                val l = hypot(dx, dy)
                if (l > 6f) {
                    val v = (l / 60f).coerceAtMost(1f) * 210f
                    h.vx = dx / l * v; h.vy = dy / l * v
                    h.regard = atan2(dy, dx)
                } else { h.vx = 0f; h.vy = 0f }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                manche = false; h.vx = 0f; h.vy = 0f
            }
        }
        return true
    }

    /** Les quatre boutons, commandes par l'activite. */
    fun actionLancer(charge: Boolean) {
        val h = jeu.heros() ?: return
        val cible = jeu.joueurs.filter { it.equipe != h.equipe && !it.prison }
            .minByOrNull { hypot(it.x - h.x, it.y - h.y) } ?: return
        jeu.lancer(h, cible.x, cible.y, charge)
    }
    fun actionPasse()   { jeu.heros()?.let { jeu.passer(it) } }
    fun actionEsquive() { jeu.heros()?.let { jeu.esquiver(it) } }
    fun actionAttraper(){ jeu.heros()?.let { it.attrape = Terrain.DUREE_ATTRAPE } }

    fun rejouer() { finAnnoncee = false; jeu.remettre(); invalidate() }
}
