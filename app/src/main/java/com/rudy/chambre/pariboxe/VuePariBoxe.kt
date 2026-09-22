package com.rudy.chambre.pariboxe

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Une planche prete a dessiner. (dx, dy) : place du recadrage dans l'image
 * d'origine (largeur x hauteur). L'ombre portee du HTML (drop-shadow 0 9px
 * 5px, noir 50 %) est precalculee et posee dans le repere de l'image.
 */
private const val RUDY_DEPART = 37.0
private const val CPU_DEPART = 62.5

class Sprite(
    val bmp: Bitmap, val largeur: Int, val hauteur: Int, val dx: Int, val dy: Int,
    val ombre: Bitmap?, val ombreRect: RectF,
    val dest: RectF = RectF(dx.toFloat(), dy.toFloat(), (dx + bmp.width).toFloat(), (dy + bmp.height).toFloat())
)

/**
 * PariBoxe en natif : reprise fidele du fichier HTML de Rudy.
 *
 * Toutes les mesures sont en « px CSS » (= dp), exactement comme la page :
 *  - Rudy : boite 520x760 (390x470 si l'ecran fait 520 px de haut ou
 *    moins), centree a 50 %, pieds a 3 % ; chaque planche y est posee
 *    « contain » en bas au centre AVEC SA PROPRE TAILLE (coups et garde en
 *    900x760, le reste en 980x820 : c'est ce qui donne leur vraie taille) ;
 *  - l'adversaire : boite 520x760 mise a l'echelle pour avoir la meme
 *    hauteur et la meme ligne de pieds que Rudy (script cpu-meme-niveau) ;
 *  - filtre des deux combattants : luminosite .93, contraste 1.06,
 *    saturation 1.04 et ombre portee ;
 *  - manette et boutons aux positions « positions-exactes-utilisateur » ;
 *  - meme moteur de combat : cadences, IA, boites de coups, degats.
 */
@SuppressLint("ViewConstructor")
class VuePariBoxe(ctx: Context, private val son: SonPariBoxe) : View(ctx) {

    private val dens = resources.displayMetrics.density
    private var W = 0f
    private var H = 0f
    /** Ecran tenu en vertical : comme le HTML (@media portrait), le jeu entier pivote de 90 degres. */
    private var pivote = false
    private val main = Handler(Looper.getMainLooper())
    private val fil = Executors.newSingleThreadExecutor()
    @Volatile private var ferme = false

    // ------------------------------------------------------------------ images
    private var dossier: File? = null
    private var fond: Bitmap? = null
    private var depart: Bitmap? = null
    private var choix: Bitmap? = null
    private var iconeValor: Bitmap? = null
    private var iconeMody: Bitmap? = null
    private var imageFin: Bitmap? = null
    private var F: Map<String, Array<Sprite>>? = null            // Rudy
    private var E: Map<String, Array<Sprite>>? = null            // l'adversaire equipe
    private var jeuCharge: String? = null                         // "E", "V" ou "MO"
    private var chargementJeu: String? = null
    private var apercuTheo: Sprite? = null                        // E.idle[0] avant le choix
    private var chargementLance = false
    @Volatile private var extraitOk = false

    // ------------------------------------------------------------------ ecrans
    private var ecran = DEPART
    private var tEcran = 0.0
    private var starting = false
    private var revanche: String? = null      // sessionStorage « pariboxeRejouer »

    private class Carte(
        val nom: String, val jeu: String,
        val l: Float, val t: Float, val w: Float, val h: Float,
        val art: FloatArray?, val plaque: FloatArray?
    )
    /** Zones des trois cartes, en % de l'image du decor (1672x941). */
    private val cartes = listOf(
        Carte("TH\u00c9O", "E", 32.3f, 38.6f, 15.6f, 43.2f, null, null),
        Carte("VALOR", "V", 49.3f, 38.6f, 15.4f, 41.4f,
            floatArrayOf(49.4f, 38.9f, 15.2f, 34.9f), floatArrayOf(49.4f, 74.0f, 15.2f, 5.8f)),
        Carte("MODY", "MO", 65.4f, 38.6f, 15.3f, 41.4f,
            floatArrayOf(65.5f, 38.9f, 15.1f, 34.9f), floatArrayOf(65.5f, 74.0f, 15.1f, 5.8f))
    )
    private var actif = 0
    private var fini = false
    private var tValide = -1.0

    private var equipe = false
    private var cpuJeu = "E"
    private var cpuNom = "CPU"
    private var perso = 1f               // window.__cpuTaille
    private var cdTexte = ""

    // ------------------------------------------------------------------ Rudy
    private val attacks = arrayOf("direct1", "direct2", "kick", "uppercut")
    private val actions = setOf("direct1", "direct2", "kick", "uppercut", "dodge", "damage")
    private var ready = false            // window.__combatReady
    private var mode = "idle"
    private var frame = 0
    private var lastR = 0.0
    private var attackBusy = false
    private var guardHeld = false
    private var guardRelease = false
    private var attackIndex = 0
    private var move = 0
    private var label = "GARDE"
    private var worldOffset = 0.0
    private var lastTick = -1.0
    private var rudyPct = RUDY_DEPART

    // ------------------------------------------------------------------ CPU
    private val finsCpu = setOf("damage", "dodge", "guard", "direct", "kick", "lowkick", "backfist")
    private var cpuHP = 100
    private var rudyHP = 100
    private var em = "idle"
    private var ef = 0
    private var eBusy = false
    private var ex = CPU_DEPART
    private var lastE = 0.0
    private var cpuImpactDone = false
    private var cpuNext = 0.0
    private var seen = ""
    private var serial = 0

    // ------------------------------------------------------------------ divers
    private var gen = 0
    private var joyId = -1
    private var guardId = -1
    private var stickX = 0f
    private var stickY = 0f
    private var vuRudy = ""
    private var dernierSrcE = ""
    private var affiche = false
    private var combatCommence = false
    private var vCpu = 100f
    private var vRudy = 100f
    private var flashType = 0            // 1 : Rudy touche, 2 : Rudy encaisse
    private var flashT0 = -1e9
    private var teinteCpu = 0
    private var teinteRudy = 0
    private val barreCpu = Barre()
    private val barreRudy = Barre()
    private var tImage = 0.0

    /** width:% avec « transition: width .22s ease-out ». */
    private inner class Barre {
        var de = 100f; var vers = 100f; var t0 = -1e9
        fun fixer(v: Float) { val m = maintenant(); de = valeur(m); vers = v; t0 = m }
        fun remettre() { de = 100f; vers = 100f; t0 = -1e9 }
        fun valeur(t: Double): Float {
            val q = ((t - t0) / 220.0).coerceIn(0.0, 1.0)
            return de + (vers - de) * EASE_OUT.y(q).toFloat()
        }
    }

    init {
        isClickable = true
        intervalle(35) { pollCoupsRudy() }
        intervalle(40) { pollSouffleRudy() }
        intervalle(150) { verifie() }
        intervalle(100) { pollFlash() }
    }

    private fun maintenant() = System.nanoTime() / 1e6

    /** setInterval */
    private fun intervalle(ms: Long, f: () -> Unit) {
        val r = object : Runnable {
            override fun run() {
                if (ferme) return
                try { f() } catch (_: Throwable) { }
                main.postDelayed(this, ms)
            }
        }
        main.postDelayed(r, ms)
    }

    /** setTimeout, annule par un « rechargement » de la page. */
    private fun plusTard(ms: Long, f: () -> Unit) {
        val g = gen
        main.postDelayed({ if (!ferme && g == gen) f() }, ms)
    }

    // ================================================================ cycle (requestAnimationFrame)

    private val rappel = object : Choreographer.FrameCallback {
        override fun doFrame(ns: Long) {
            if (ferme) return
            val t = ns / 1e6
            tImage = t
            try { boucle(t) } catch (_: Throwable) { }
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(rappel)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(rappel)
        super.onDetachedFromWindow()
    }

    fun liberer() {
        ferme = true
        main.removeCallbacksAndMessages(null)
        Choreographer.getInstance().removeFrameCallback(rappel)
        fil.shutdownNow()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        pivote = h > w
        W = (if (pivote) h else w) / dens
        H = (if (pivote) w else h) / dens
        if (w > 0 && h > 0 && !chargementLance) {
            chargementLance = true
            lancerChargement(H <= 520f)
        }
    }

    // ================================================================ chargement

    private fun lancerChargement(petitEcran: Boolean) {
        val app = context.applicationContext
        fil.execute {
            try {
                Planches.extraire(app)
                val dir = Planches.dossier(app)
                val dep = Planches.image(File(dir, "depart.img"))
                val fo = Planches.image(File(dir, "fond.img"))
                val ch = Planches.image(File(dir, "choix.img"))
                val iv = Planches.image(File(dir, "icone_valor.img"))
                val im = Planches.image(File(dir, "icone_mody.img"))
                main.post {
                    dossier = dir
                    depart = dep; fond = fo; choix = ch; iconeValor = iv; iconeMody = im
                }
                extraitOk = true
                try { son.preparer(dir) } catch (_: Throwable) { }
                val cpt = Planches.lireComptes(dir)
                val tampon = IntArray(980 * 820)
                val apercu = Planches.planche(File(dir, "theo/idle_0.img"), tampon)?.let { preparer(it, 520f, 760f, echCpuMax()) }
                main.post { apercuTheo = apercu }
                val bw = if (petitEcran) 390f else 520f
                val bh = if (petitEcran) 470f else 760f
                val rudy = chargerJeu(dir, cpt, "rudy", bw, bh, tampon, reduc())
                main.post { F = rudy }
            } catch (_: Throwable) { }
        }
    }

    private fun nomDossier(jeu: String) = when (jeu) { "V" -> "valor"; "MO" -> "mody"; else -> "theo" }

    private fun chargerAdversaire(jeu: String) {
        if (jeuCharge == jeu || chargementJeu == jeu) return
        chargementJeu = jeu
        val app = context.applicationContext
        fil.execute {
            try {
                while (!extraitOk && !ferme) Thread.sleep(50)
                val dir = Planches.dossier(app)
                val jeuE = chargerJeu(dir, Planches.lireComptes(dir), nomDossier(jeu), 520f, 760f, IntArray(980 * 820), echCpuMax())
                main.post {
                    if (chargementJeu != jeu) { recycler(jeuE); return@post }
                    val ancien = E
                    E = jeuE; jeuCharge = jeu; chargementJeu = null
                    if (ancien != null && ancien !== jeuE) recycler(ancien)
                }
            } catch (_: Throwable) { }
        }
    }

    private fun recycler(j: Map<String, Array<Sprite>>) {
        j.values.forEach { l -> l.forEach { s -> try { s.bmp.recycle(); s.ombre?.recycle() } catch (_: Throwable) { } } }
    }

    private fun chargerJeu(
        dir: File, cpt: Map<String, Map<String, Int>>, nom: String,
        bw: Float, bh: Float, tampon: IntArray, ech: Float = 1f
    ): Map<String, Array<Sprite>> {
        val res = LinkedHashMap<String, Array<Sprite>>()
        val modes = cpt[nom] ?: emptyMap()
        for ((m, n) in modes) {
            val liste = ArrayList<Sprite>()
            for (i in 0 until n) {
                if (ferme) return res
                val pl = Planches.planche(File(dir, "$nom/${m}_$i.img"), tampon) ?: continue
                liste.add(preparer(pl, bw, bh, ech))
            }
            if (liste.isNotEmpty()) res[m] = liste.toTypedArray()
        }
        return res
    }

    /** Ombre portee precalculee + passage de l'image en memoire graphique. */
    private fun preparer(pl: Planche, bw: Float, bh: Float, ech: Float = 1f): Sprite {
        val k = min(bw / pl.largeur, bh / pl.hauteur)      // echelle « contain » de CETTE planche
        var ombre: Bitmap? = null
        val r = RectF()
        try {
            val b = pl.bmp
            val f = 4
            val sw = max(1, (b.width + f - 1) / f)
            val sh = max(1, (b.height + f - 1) / f)
            val reduit = Bitmap.createScaledBitmap(b, sw, sh, true)
            val px = IntArray(sw * sh)
            reduit.getPixels(px, 0, sw, 0, 0, sw, sh)
            if (reduit !== b) reduit.recycle()
            // drop-shadow : flou de 5 px CSS = ecart-type 2.5 px CSS
            val sig = max(0.35f, (2.5f / k) * sw / b.width)
            val rk = max(1, ceil(3f * sig).toInt())
            val pad = rk + 1
            val w2 = sw + 2 * pad
            val h2 = sh + 2 * pad
            val a = FloatArray(w2 * h2)
            for (y in 0 until sh) for (x in 0 until sw) a[(y + pad) * w2 + x + pad] = (px[y * sw + x] ushr 24).toFloat()
            val noyau = FloatArray(2 * rk + 1)
            var somme = 0f
            for (i in -rk..rk) { val v = exp(-(i * i) / (2f * sig * sig)); noyau[i + rk] = v; somme += v }
            for (i in noyau.indices) noyau[i] /= somme
            val c = FloatArray(w2 * h2)
            for (y in 0 until h2) {
                val base = y * w2
                for (x in 0 until w2) {
                    var s = 0f
                    for (i in -rk..rk) { val xx = x + i; if (xx in 0 until w2) s += a[base + xx] * noyau[i + rk] }
                    c[base + x] = s
                }
            }
            val sortie = Bitmap.createBitmap(w2, h2, Bitmap.Config.ALPHA_8)
            val rb = sortie.rowBytes
            val oct = ByteArray(rb * h2)
            for (y in 0 until h2) for (x in 0 until w2) {
                var s = 0f
                for (i in -rk..rk) { val yy = y + i; if (yy in 0 until h2) s += c[yy * w2 + x] * noyau[i + rk] }
                oct[y * rb + x] = s.roundToInt().coerceIn(0, 255).toByte()
            }
            sortie.copyPixelsFromBuffer(ByteBuffer.wrap(oct))
            val sx = b.width.toFloat() / sw
            val sy = b.height.toFloat() / sh
            val decal = 9f / k                              // 9 px CSS vers le bas
            r.set(pl.dx - pad * sx, pl.dy - pad * sy + decal, pl.dx + (sw + pad) * sx, pl.dy + (sh + pad) * sy + decal)
            ombre = sortie
        } catch (_: Throwable) { ombre = null }
        val dest = RectF(pl.dx.toFloat(), pl.dy.toFloat(), (pl.dx + pl.bmp.width).toFloat(), (pl.dy + pl.bmp.height).toFloat())
        // Image ramenee a sa taille reelle a l'ecran, par divisions successives
        // par 2 : contours lisses au lieu de crenelés quand les persos sont petits.
        var src = pl.bmp
        try {
            val q = k * ech * dens * 1.15f
            if (q < 0.85f) {
                val cw = max(1, (pl.bmp.width * q).roundToInt())
                val ch = max(1, (pl.bmp.height * q).roundToInt())
                while (src.width / 2 >= cw && src.height / 2 >= ch) {
                    val d = Bitmap.createScaledBitmap(src, src.width / 2, src.height / 2, true)
                    if (src !== pl.bmp && d !== src) src.recycle()
                    src = d
                }
                if (src.width != cw || src.height != ch) {
                    val d = Bitmap.createScaledBitmap(src, cw, ch, true)
                    if (src !== pl.bmp && d !== src) src.recycle()
                    src = d
                }
                if (src !== pl.bmp) pl.bmp.recycle()
            }
        } catch (_: Throwable) { src = pl.bmp }
        val bmp = try { src.copy(Bitmap.Config.HARDWARE, false)?.also { src.recycle() } ?: src } catch (_: Throwable) { src }
        return Sprite(bmp, pl.largeur, pl.hauteur, pl.dx, pl.dy, ombre, r, dest)
    }

    // ================================================================ geometrie (px CSS)

    private val petit get() = H <= 520f
    private val rbw get() = if (petit) 390f else 520f
    private val rbh get() = if (petit) 470f else 760f
    private val kC = min(520f / 980f, 760f / 820f)
    private fun kR() = min(rbw / 980f, rbh / 820f)
    /** « cpu-meme-niveau-que-rudy » : RUDY_H 743, RUDY_PIEDS 30, CPU_H 650, CPU_PIEDS 21. */
    private fun cpuEchelle() = kR() * 743f * perso / (kC * 650f)
    private fun cpuBas(): Float {
        val pieds = H * 3f / 100f + 30f * kR()
        return pieds - 21f * kC * cpuEchelle()
    }
    /**
     * Taille des combattants sur telephone : le HTML les fait occuper ~80 %
     * de la hauteur d'un ecran de telephone couche. On les ramene a la
     * proportion du HTML sur grand ecran (Rudy = la moitie de la hauteur),
     * pieds toujours sur la meme ligne. Sur grand ecran rien ne change.
     */
    private fun reduc() = min(1f, 0.34f * H / (743f * kR()))
    private fun piedsY() = H - H * 0.03f - 30f * kR()
    /** Pieds poses sur le tapis du ring (75 % de la hauteur), comme la capture du HTML. */
    private fun piedsCible() = H * 0.753f
    private fun echCpuMax() = reduc() * kR() * 743f * 1.09f / (kC * 650f)
    private fun reduire(r: RectF): RectF {
        val k = reduc()
        val cx = r.centerX(); val py = piedsY(); val dy = piedsCible() - py
        return RectF(cx + (r.left - cx) * k, py + dy + (r.top - py) * k, cx + (r.right - cx) * k, py + dy + (r.bottom - py) * k)
    }
    private fun rectRudy(): RectF {
        val l = W * rudyPct.toFloat() / 100f - rbw / 2f
        val b = H - H * 0.03f
        return reduire(RectF(l, b - rbh, l + rbw, b))
    }
    private fun rectCpu(): RectF {
        val s = cpuEchelle()
        val cx = W * ex.toFloat() / 100f
        val b = H - cpuBas()
        return reduire(RectF(cx - 260f * s, b - 760f * s, cx + 260f * s, b))
    }
    private fun overlap(a: RectF, b: RectF) = a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
    private fun bodyBox(r: RectF) = RectF(r.left + r.width() * .20f, r.top + r.height() * .10f,
        r.right - r.width() * .20f, r.bottom - r.height() * .05f)
    private fun rudyHitBox(kind: String): RectF {
        val r = rectRudy()
        return when (kind) {
            "kick" -> RectF(r.left + r.width() * .55f, r.top + r.height() * .42f, r.right, r.top + r.height() * .82f)
            "uppercut" -> RectF(r.left + r.width() * .52f, r.top + r.height() * .15f, r.right, r.top + r.height() * .58f)
            else -> RectF(r.left + r.width() * .55f, r.top + r.height() * .20f, r.right, r.top + r.height() * .58f)
        }
    }
    private fun cpuHitBox(kind: String): RectF {
        val r = rectCpu()
        return if (kind == "kick" || kind == "lowkick")
            RectF(r.left, r.top + r.height() * .42f, r.left + r.width() * .48f, r.top + r.height() * .86f)
        else RectF(r.left, r.top + r.height() * .18f, r.left + r.width() * .48f, r.top + r.height() * .58f)
    }
    private fun realRudyHitsCPU(kind: String) = overlap(rudyHitBox(kind), bodyBox(rectCpu()))
    private fun realCPUHitsRudy(kind: String) = overlap(cpuHitBox(kind), bodyBox(rectRudy()))

    private val joyTaille get() = if (petit) 145f else 180f
    private val stickTaille get() = if (petit) 60f else 74f
    private val stickPos get() = if (petit) 38f else 53f
    private val joyEch = .8f
    private fun joyCx() = W * 9.462570231237109f / 100f
    private fun joyCy() = H * 66.57407681147257f / 100f

    private class Bouton(val px: Float, val py: Float, val ech: Float, val couleur: Int, val texte: String, val police: Float)
    private val gardeB = Bouton(96.32917952560999f, 55.89247147242227f, .4f, 0xFF00602D.toInt(), "GARDE", 19f)
    private val poingB = Bouton(89.2761723548869f, 78.11360756556192f, .35f, 0xFFA90000.toInt(), "POING", 22f)
    private val esquiveB = Bouton(94.67678556429941f, 68.56770912806192f, .5f, 0xFF08277D.toInt(), "ESQUIVE", 14f)
    private fun tailleB(b: Bouton) = when (b.texte) {
        "GARDE" -> if (petit) 105f else 125f
        "POING" -> if (petit) 125f else 150f
        else -> 92f
    }
    private fun dansBouton(b: Bouton, x: Float, y: Float) =
        hypot(x - W * b.px / 100f, y - H * b.py / 100f) <= tailleB(b) / 2f * b.ech

    // ================================================================ moteur (celui du HTML)

    private fun setMode(m: String) { if (mode != m) { mode = m; frame = 0; lastR = 0.0 } }
    private fun setE(m: String) { if (em == m) return; em = m; ef = 0; lastE = maintenant() }
    private fun listeE(m: String): Array<Sprite>? = E?.get(m) ?: E?.get("idle")

    private fun boucle(t: Double) {
        val f = F ?: return
        // --- animate() de Rudy (planche toutes les 70 ms)
        if (lastR == 0.0) lastR = t
        if (!ready) {
            lastR = t; frame = 0; mode = "idle"
            attackBusy = false; guardHeld = false; guardRelease = false; move = 0
        } else if (t - lastR >= 70) {
            lastR = t
            if (mode == "guard") {
                if (guardHeld) {
                    if (frame < 2) frame++ else frame = 2
                } else if (guardRelease) {
                    if (frame < 4) frame++
                    else { guardRelease = false; setMode(if (move < 0) "back" else if (move > 0) "forward" else "idle") }
                }
            } else if (actions.contains(mode)) {
                frame++
                if (frame >= (f[mode]?.size ?: 1)) {
                    attackBusy = false
                    setMode(if (guardHeld) "guard" else if (move < 0) "back" else if (move > 0) "forward" else "idle")
                }
            } else {
                frame = (frame + 1) % (f[mode]?.size ?: 1)
            }
        }
        // --- tick() : deplacement de Rudy
        if (lastTick < 0) lastTick = t
        val dt = min(40.0, t - lastTick)
        lastTick = t
        if (ready && move != 0 && !guardHeld) {
            worldOffset += move * dt * 0.018
            worldOffset = max(8.0 - RUDY_DEPART, min(92.0 - RUDY_DEPART, worldOffset))
            rudyPct = RUDY_DEPART + worldOffset
        }
        // --- draw() de l'adversaire (105 ms, 118 ms en marche)
        if (lastE == 0.0) lastE = t
        if (!ready) {
            lastE = t; ef = 0; em = "idle"; eBusy = false
            dernierSrcE = "idle:0"
        } else if (E != null) {
            val n = listeE(em)?.size ?: 1
            val step = if (em == "forward" || em == "back") 118 else 105
            if (t - lastE >= step) { lastE = t; ef++ }
            if (ef >= n) {
                if (finsCpu.contains(em)) { eBusy = false; setE("idle") } else ef = 0
            }
            // souffle de l'adversaire : la 2e image d'un coup vient de s'afficher
            val idx = min(ef, (listeE(em)?.size ?: 1) - 1)
            val src = "$em:$idx"
            if (src != dernierSrcE) {
                dernierSrcE = src
                if (idx == 1 && (em == "direct" || em == "kick" || em == "lowkick" || em == "backfist")) son.souffle()
            }
        }
        // --- cpuThink() toutes les 120 ms
        if (t >= cpuNext) {
            cpuNext = t + 120
            if (ready && E != null && !eBusy && cpuHP > 0 && rudyHP > 0) cpuThink()
        }
    }

    private fun cpuAnyHit() = realCPUHitsRudy("direct") || realCPUHitsRudy("backfist") ||
        realCPUHitsRudy("kick") || realCPUHitsRudy("lowkick")

    private fun cpuThink() {
        val a = rectRudy(); val b = rectCpu()
        // il reste du cote droit de Rudy et s'approche jusqu'a portee
        if (!cpuAnyHit()) {
            ex = if (b.centerX() > a.centerX()) max(20.0, ex - .55) else min(92.0, ex + .55)
            setE("forward")
            return
        }
        val rnd = Math.random()
        if (rnd < .10) { eBusy = true; setE("dodge"); return }
        if (rnd < .22) { eBusy = true; setE("guard"); return }
        val possible = listOf("direct", "backfist", "kick", "lowkick").filter { realCPUHitsRudy(it) }
        if (possible.isEmpty()) return
        val kind = possible[min(possible.size - 1, (Math.random() * possible.size).toInt())]
        eBusy = true; cpuImpactDone = false; setE(kind)
        plusTard(if (kind == "direct" || kind == "backfist") 285L else 350L) {
            if (!cpuImpactDone) {
                cpuImpactDone = true
                if (realCPUHitsRudy(kind)) hitRudy(kind)
            }
        }
    }

    private fun hitRudy(kind: String) {
        if (!realCPUHitsRudy(kind)) return
        if (kind == "direct" && mode == "dodge") return      // l'esquive annule un DIRECT
        if (guardHeld) return                               // la GARDE absorbe le coup
        val avant = rudyHP
        rudyHP = max(0, rudyHP - (if (kind == "kick" || kind == "lowkick") 6 else 4))
        bars(avant, cpuHP)
        if (!attackBusy) { attackBusy = true; setMode("damage"); label = "D\u00c9G\u00c2TS" }
    }

    private fun hitCpu(power: Int) {
        if (!realRudyHitsCPU(mode) || em == "dodge" || em == "guard") return
        val avant = cpuHP
        cpuHP = max(0, cpuHP - power)
        bars(rudyHP, avant)
        eBusy = true; setE("damage")
    }

    /** bars() + ce que les MutationObserver du HTML faisaient a chaque changement. */
    private fun bars(rudyAvant: Int, cpuAvant: Int) {
        if (cpuHP != cpuAvant) {
            barreCpu.fixer(cpuHP.toFloat())
            if (cpuHP < cpuAvant) son.coup(cpuHP.toFloat(), (cpuAvant - cpuHP).toFloat())
        }
        if (rudyHP != rudyAvant) {
            barreRudy.fixer(rudyHP.toFloat())
            if (rudyHP < rudyAvant) son.coup(rudyHP.toFloat(), (rudyAvant - rudyHP).toFloat())
        }
        verifie()
    }

    private fun pollCoupsRudy() {
        if (!ready) return
        if (attacks.contains(mode) && mode != seen) {
            val my = ++serial
            val kind = mode
            plusTard(180) { if (my == serial) hitCpu(if (kind == "kick" || kind == "uppercut") 7 else 5) }
        }
        seen = mode
    }

    private fun pollSouffleRudy() {
        val m = mode
        if (m != vuRudy && attacks.contains(m)) son.souffle()
        vuRudy = m
    }

    private fun pollFlash() {
        val a = cpuHP.toFloat(); val b = rudyHP.toFloat()
        if (a < vCpu - 0.5f) { flashType = 1; flashT0 = maintenant() }
        if (b < vRudy - 0.5f) { flashType = 2; flashT0 = maintenant() }
        vCpu = a; vRudy = b
        teinteCpu = if (a <= 25f) 2 else if (a <= 55f) 1 else 0
        teinteRudy = if (b <= 25f) 2 else if (b <= 55f) 1 else 0
    }

    private fun verifie() {
        if (affiche) return
        if (ready) combatCommence = true
        if (!combatCommence) return
        if (cpuHP < 1) {
            com.rudy.chambre.Argent.ajouter(context, 100)        // PariBoxe : +100 € quand Rudy gagne
            ecranFin(when (cpuJeu) { "V" -> "rudy_valor"; "MO" -> "rudy_mody"; else -> "rudy_theo" })
        } else if (rudyHP < 1) {
            com.rudy.chambre.Argent.ajouter(context, -70)        // PariBoxe : -70 € quand Rudy perd
            ecranFin(when (cpuJeu) { "V" -> "valor"; "MO" -> "mody"; else -> "theo" })
        }
    }

    private fun doAttack() {
        if (attackBusy) return
        attackBusy = true; guardHeld = false; guardRelease = false
        val a = attacks[attackIndex]
        attackIndex = (attackIndex + 1) % attacks.size
        setMode(a)
        label = when (a) { "direct1" -> "DIRECT 1"; "direct2" -> "DIRECT 2"; "kick" -> "COUP DE PIED"; else -> "UPPERCUT" }
    }
    private fun esquive() {
        if (attackBusy) return
        attackBusy = true; guardHeld = false; guardRelease = false
        setMode("dodge"); label = "ESQUIVE"
    }
    private fun garde() {
        if (attackBusy) return          // le coup reste prioritaire
        guardHeld = true; guardRelease = false; setMode("guard"); label = "PROTECTION"
    }
    private fun releaseGuard() { if (mode == "guard") { guardHeld = false; guardRelease = true } }
    private fun joyMoveXY(x: Float, y: Float) {
        val dx = x - joyCx(); val dy = y - joyCy()
        val mx = joyTaille * joyEch * .30f
        val d0 = hypot(dx, dy)
        val d = if (d0 == 0f) 1f else d0
        val k = min(1f, mx / d)
        stickX = dx * k; stickY = dy * k
        move = if (dx > 22) 1 else if (dx < -22) -1 else 0
        if (!attackBusy && mode != "guard") {
            setMode(if (move > 0) "forward" else if (move < 0) "back" else "idle")
            label = if (move > 0) "AVANCE" else if (move < 0) "RECULE" else "GARDE"
        }
    }
    private fun resetJoy() {
        joyId = -1; move = 0; stickX = 0f; stickY = 0f
        if (!attackBusy && mode != "guard") { setMode("idle"); label = "GARDE" }
    }

    // ================================================================ ecrans

    private fun begin() {
        if (starting) return
        starting = true
        val r = revanche
        if (r != null) {
            revanche = null
            equiper(cartes.firstOrNull { it.jeu == r } ?: cartes[0])
            lancerCombat()
            return
        }
        ecran = CHOIX; tEcran = maintenant()
        actif = 0; fini = false; tValide = -1.0
    }

    private fun equiper(c: Carte) {
        perso = if (c.jeu == "V") 1.09f else 1f     // Valor un peu plus grand que Rudy
        cpuJeu = c.jeu; cpuNom = c.nom
        equipe = true
        chargerAdversaire(c.jeu)
    }

    private fun valider() {
        if (fini) return
        fini = true
        equiper(cartes[actif])
        tValide = maintenant()
        plusTard(1100) { lancerCombat() }
    }

    private fun lancerCombat() {
        // les planches doivent etre pretes (dans le HTML elles le sont deja)
        if (F == null || jeuCharge != cpuJeu) { plusTard(100) { lancerCombat() }; return }
        ecran = COMBAT
        son.demarrerMusique()
        cdTexte = "3"
        plusTard(1000) { cdTexte = "2" }
        plusTard(2000) { cdTexte = "1" }
        plusTard(3000) { cdTexte = ""; ready = true }
    }

    private fun ecranFin(clef: String) {
        if (affiche) return
        affiche = true
        ready = false                   // les combattants se figent
        son.arreterMusique()
        imageFin = null
        val dir = dossier
        if (dir != null) fil.execute {
            val b = Planches.image(File(dir, "fin_$clef.img"))
            main.post { if (affiche && ecran == FIN) imageFin = b else b?.recycle() }
        }
        ecran = FIN; tEcran = maintenant()
    }

    /** location.reload() : tout repart de zero, seule la revanche est gardee. */
    private fun recharge(rejouer: Boolean) {
        revanche = if (rejouer) cpuJeu else null
        gen++
        ecran = DEPART; starting = false
        actif = 0; fini = false; tValide = -1.0
        equipe = false; cpuJeu = "E"; cpuNom = "CPU"; perso = 1f
        cdTexte = ""
        ready = false; mode = "idle"; frame = 0; lastR = 0.0; attackBusy = false
        guardHeld = false; guardRelease = false; attackIndex = 0; move = 0; label = "GARDE"
        worldOffset = 0.0; lastTick = -1.0; rudyPct = RUDY_DEPART
        cpuHP = 100; rudyHP = 100; em = "idle"; ef = 0; eBusy = false; ex = CPU_DEPART
        lastE = 0.0; cpuImpactDone = false; cpuNext = 0.0; seen = ""; serial = 0
        joyId = -1; guardId = -1; stickX = 0f; stickY = 0f
        vuRudy = ""; dernierSrcE = ""; affiche = false; combatCommence = false
        vCpu = 100f; vRudy = 100f; flashType = 0; flashT0 = -1e9; teinteCpu = 0; teinteRudy = 0
        barreCpu.remettre(); barreRudy.remettre()
        imageFin?.recycle(); imageFin = null
    }

    // ================================================================ toucher

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        fun jx(k: Int) = (if (pivote) e.getY(k) else e.getX(k)) / dens
        fun jy(k: Int) = (if (pivote) width - e.getX(k) else e.getY(k)) / dens
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                appui(e.getPointerId(i), jx(i), jy(i))
            MotionEvent.ACTION_MOVE -> {
                if (joyId >= 0 && ready && ecran == COMBAT) {
                    val k = e.findPointerIndex(joyId)
                    if (k >= 0) joyMoveXY(jx(k), jy(k))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                relache(e.getPointerId(i), jx(i), jy(i), false)
            MotionEvent.ACTION_CANCEL ->
                for (k in 0 until e.pointerCount) relache(e.getPointerId(k), jx(k), jy(k), true)
        }
        return true
    }

    private fun appui(id: Int, x: Float, y: Float) {
        when (ecran) {
            CHOIX -> appuiChoix(x, y)
            FIN -> appuiFin(x, y)
            COMBAT -> {
                if (!ready) return        // « combat-countdown-lock »
                when {
                    dansBouton(esquiveB, x, y) -> esquive()
                    dansBouton(poingB, x, y) -> doAttack()
                    dansBouton(gardeB, x, y) -> { guardId = id; garde() }
                    hypot(x - joyCx(), y - joyCy()) <= joyTaille * joyEch / 2f -> { joyId = id; joyMoveXY(x, y) }
                }
            }
        }
    }

    private fun relache(id: Int, x: Float, y: Float, annule: Boolean) {
        if (ecran == DEPART) {
            if (!annule && depart != null && rectCommencer().contains(x, y)) begin()
            return
        }
        if (ecran != COMBAT || !ready) {
            if (id == joyId) joyId = -1
            if (id == guardId) guardId = -1
            return
        }
        if (id == joyId) resetJoy()
        if (id == guardId) { guardId = -1; releaseGuard() }
    }

    // ================================================================ dessin

    private val pSprite = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        // filter: brightness(.93) contrast(1.06) saturate(1.04)
        val cm = ColorMatrix()
        cm.setScale(.93f, .93f, .93f, 1f)
        val c = 1.06f
        val o = (0.5f - 0.5f * c) * 255f
        cm.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, o, 0f, c, 0f, 0f, o, 0f, 0f, c, 0f, o, 0f, 0f, 0f, 1f, 0f)))
        val s = 1.04f
        cm.postConcat(ColorMatrix(floatArrayOf(
            .213f + .787f * s, .715f - .715f * s, .072f - .072f * s, 0f, 0f,
            .213f - .213f * s, .715f + .285f * s, .072f - .072f * s, 0f, 0f,
            .213f - .213f * s, .715f - .715f * s, .072f + .928f * s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f)))
        colorFilter = ColorMatrixColorFilter(cm)
    }
    private val pOmbre = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { color = 0x80000000.toInt() }
    private val pImg = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pT = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SANS_SERIF, 900, false) }
    private val rTmp = RectF()
    private val chemin = Path()

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        if (W <= 0f) return
        val t = if (tImage > 0) tImage else maintenant()
        c.save()
        if (pivote) { c.translate(width.toFloat(), 0f); c.rotate(90f) }
        c.scale(dens, dens)
        when (ecran) {
            DEPART -> dessinerDepart(c)
            CHOIX -> {
                // #pariboxeStart.selectPerso : fondu d'entree .45s, le ring apparait derriere
                dessinerJeu(c)
                val o = EASE.y(((t - tEcran) / 450.0).coerceIn(0.0, 1.0)).toFloat()
                calque(c, o) { dessinerChoix(c, t) }
            }
            FIN -> {
                dessinerJeu(c)
                val o = EASE.y(((t - tEcran) / 250.0).coerceIn(0.0, 1.0)).toFloat()
                calque(c, o) { dessinerFin(c, t) }
            }
            else -> dessinerJeu(c)
        }
        c.restore()
    }

    private inline fun calque(c: Canvas, o: Float, bloc: () -> Unit) {
        if (o >= 0.999f) { bloc(); return }
        val n = c.saveLayerAlpha(0f, 0f, W, H, (o * 255).roundToInt().coerceIn(0, 255))
        bloc()
        c.restoreToCount(n)
    }

    /** background-size: cover, centre. */
    private fun couvrir(c: Canvas, b: Bitmap, x: Float, y: Float, w: Float, h: Float) {
        val e = max(w / b.width, h / b.height)
        val dw = b.width * e; val dh = b.height * e
        rTmp.set(x + (w - dw) / 2f, y + (h - dh) / 2f, x + (w + dw) / 2f, y + (h + dh) / 2f)
        c.save(); c.clipRect(x, y, x + w, y + h)
        c.drawBitmap(b, null, rTmp, pImg)
        c.restore()
    }

    /** « aspect-ratio 1672/941 ; max-width/height 100% ; margin auto ». */
    private fun scene(): RectF {
        val e = min(W / 1672f, H / 941f)
        val sw = 1672f * e; val sh = 941f * e
        return RectF((W - sw) / 2f, (H - sh) / 2f, (W + sw) / 2f, (H + sh) / 2f)
    }

    /** line-height « normal » d'Arial, arrondi comme Chrome. */
    private fun lh(fs: Float) = ((fs * .905f).roundToInt() + (fs * .212f).roundToInt() + (fs * .0327f).roundToInt()).toFloat()
    private fun asc(fs: Float) = (fs * .905f).roundToInt().toFloat()
    private fun borne(a: Float, v: Float, b: Float) = max(a, min(v, b))
    private fun police(fs: Float, espacement: Float = 0f) {
        pT.textSize = fs
        pT.letterSpacing = if (fs > 0f) espacement / fs else 0f
        pT.clearShadowLayer()
        pT.textAlign = Paint.Align.LEFT
        pT.alpha = 255
    }
    /** rayon Android pour un ecart-type donne. */
    private fun rayon(sig: Float) = max(0.1f, (sig - 0.5f) / 0.57735f)

    /** box-shadow exterieur (invisible sous la boite, comme en CSS). */
    private fun ombreBoite(c: Canvas, r: RectF, ra: Float, dy: Float, flou: Float, etal: Float, couleur: Int) {
        c.save()
        chemin.reset(); chemin.addRoundRect(r, ra, ra, Path.Direction.CW)
        c.clipOutPath(chemin)
        p.reset(); p.isAntiAlias = true; p.color = couleur
        if (flou > 0f) p.maskFilter = BlurMaskFilter(rayon(flou / 2f), BlurMaskFilter.Blur.NORMAL)
        rTmp.set(r.left - etal, r.top - etal + dy, r.right + etal, r.bottom + etal + dy)
        c.drawRoundRect(rTmp, ra + etal, ra + etal, p)
        p.maskFilter = null
        c.restore()
    }

    // ---------------------------------------------------------------- combat

    private fun dessinerJeu(c: Canvas) {
        fond?.let { couvrir(c, it, 0f, 0f, W, H) }
        dessinerEtiquette(c)                 // #label (z auto)
        dessinerAmbiance(c)                  // #ambiance (z 2)
        // Rudy (#fighter, z 6)
        val f = F
        if (f != null) {
            val liste = if (ready) f[mode] else f["idle"]
            if (liste != null && liste.isNotEmpty()) {
                val s = liste[if (ready) min(frame, liste.size - 1) else 0]
                val l = W * rudyPct.toFloat() / 100f - rbw / 2f
                val b = H - H * 0.03f
                c.save(); c.translate(0f, piedsCible() - piedsY()); c.scale(reduc(), reduc(), l + rbw / 2f, piedsY())
                dessinerSprite(c, s, l, b, rbw, rbh, 1f, l + rbw / 2f, b)
                c.restore()
            }
        }
        dessinerFlash(c)                     // #impactFlash (z 45)
        // adversaire (#enemy, z 50)
        val s = spriteCpu()
        if (s != null) {
            val cx = W * ex.toFloat() / 100f
            val b = H - cpuBas()
            c.save(); c.translate(0f, piedsCible() - piedsY()); c.scale(reduc(), reduc(), cx, piedsY())
            dessinerSprite(c, s, cx - 260f, b, 520f, 760f, cpuEchelle(), cx, b)
            c.restore()
        }
        dessinerHud(c)                       // #hudFight (z 60)
        dessinerCommandes(c)                 // manette et boutons (z 9999)
        if (cdTexte.isNotEmpty()) dessinerCompte(c)
    }

    private fun spriteCpu(): Sprite? {
        if (!equipe || E == null || jeuCharge != cpuJeu) return if (!equipe) apercuTheo else null
        val l = (if (ready) listeE(em) else listeE("idle")) ?: return null
        if (l.isEmpty()) return null
        return l[if (ready) min(ef, l.size - 1) else 0]
    }

    /** object-fit: contain ; object-position: center bottom ; puis scale() autour du bas-centre. */
    private fun dessinerSprite(c: Canvas, s: Sprite, bl: Float, bb: Float, bw: Float, bh: Float, ech: Float, ax: Float, ay: Float) {
        val k = min(bw / s.largeur, bh / s.hauteur)
        val iw = s.largeur * k; val ih = s.hauteur * k
        c.save()
        if (ech != 1f) c.scale(ech, ech, ax, ay)
        c.translate(bl + (bw - iw) / 2f, bb - ih)
        c.scale(k, k)
        val o = s.ombre
        if (o != null && !o.isRecycled) c.drawBitmap(o, null, s.ombreRect, pOmbre)
        if (!s.bmp.isRecycled) c.drawBitmap(s.bmp, null, s.dest, pSprite)
        c.restore()
    }

    private fun degradeEllipse(cx: Float, cy: Float, rx: Float, ry: Float, couleurs: IntArray, arrets: FloatArray): Shader {
        val g = RadialGradient(cx, cy, rx, couleurs, arrets, Shader.TileMode.CLAMP)
        val m = Matrix(); m.setScale(1f, ry / rx, cx, cy)
        g.setLocalMatrix(m)
        return g
    }

    private fun dessinerAmbiance(c: Canvas) {
        p.reset(); p.isAntiAlias = true
        p.shader = degradeEllipse(W * .5f, H * .88f, W * .6f, H * .34f,
            intArrayOf(Color.argb(41, 255, 196, 110), Color.argb(13, 255, 170, 80), Color.argb(0, 255, 170, 80)),
            floatArrayOf(0f, .55f, .75f))
        c.drawRect(0f, 0f, W, H, p)
        p.shader = degradeEllipse(W * .5f, H * .45f, W * 1.2f, H * .9f,
            intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(107, 0, 0, 0)),
            floatArrayOf(0f, .55f, 1f))
        c.drawRect(0f, 0f, W, H, p)
        p.shader = null
    }

    private fun dessinerFlash(c: Canvas) {
        if (flashType == 0) return
        val d = if (flashType == 1) 180.0 else 260.0
        val q = (tImage - flashT0) / d
        if (q < 0 || q >= 1) return
        val o0 = if (flashType == 1) .55f else .7f
        val o = o0 * (1f - EASE_OUT.y(q).toFloat())
        p.reset(); p.isAntiAlias = true
        p.shader = if (flashType == 1)
            degradeEllipse(W * .5f, H * .55f, W * .7f, H * .6f,
                intArrayOf(Color.argb(140, 255, 255, 255), Color.argb(0, 255, 255, 255)), floatArrayOf(0f, .7f))
        else
            degradeEllipse(W * .5f, H * .55f, W * .75f, H * .65f,
                intArrayOf(Color.argb(153, 255, 60, 40), Color.argb(0, 255, 60, 40)), floatArrayOf(0f, .72f))
        p.alpha = (o * 255).roundToInt().coerceIn(0, 255)
        c.drawRect(0f, 0f, W, H, p)
        p.shader = null
    }

    private fun dessinerEtiquette(c: Canvas) {
        val fs = if (petit) 17f else 20f
        police(fs, 2f)
        val w = pT.measureText(label) + 50f + 4f
        val h = lh(fs) + 24f + 4f
        val top = if (petit) 12f else 24f
        val r = RectF(W / 2f - w / 2f, top, W / 2f + w / 2f, top + h)
        ombreBoite(c, r, 16f, 4f, 14f, 0f, Color.argb(153, 0, 0, 0))
        p.reset(); p.isAntiAlias = true
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.argb(209, 0, 0, 0), Color.argb(209, 18, 18, 18), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, 16f, 16f, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Color.argb(140, 255, 226, 122)
        rTmp.set(r.left + 1f, r.top + 1f, r.right - 1f, r.bottom - 1f)
        c.drawRoundRect(rTmp, 15f, 15f, p)
        p.style = Paint.Style.FILL
        pT.color = 0xFFFFE27A.toInt()
        c.drawText(label, r.left + 2f + 25f, r.top + 2f + 12f + asc(fs), pT)
    }

    private fun dessinerHud(c: Canvas) {
        val hw = if (petit) W * .52f else min(W * .58f, 780f)
        val left = (W - hw) / 2f
        val top = if (petit) 5f else 8f
        val sw = hw * .46f
        val fs = borne(11f, W * .015f, 19f)
        val hauteurB = lh(fs)
        val hh = if (petit) 11f else 15f
        val coteD = left + hw - sw
        val barres = listOf(
            Triple(left, barreRudy.valeur(tImage), teinteRudy),
            Triple(coteD, barreCpu.valeur(tImage), teinteCpu))
        for ((x0, v, teinte) in barres) {
            val r = RectF(x0, top + hauteurB, x0 + sw, top + hauteurB + hh)
            // drop-shadow(0 3px 5px rgba(0,0,0,.8)) du bandeau
            p.reset(); p.isAntiAlias = true; p.color = Color.argb(204, 0, 0, 0)
            p.maskFilter = BlurMaskFilter(rayon(2.5f), BlurMaskFilter.Blur.NORMAL)
            rTmp.set(r.left, r.top + 3f, r.right, r.bottom + 3f)
            c.drawRoundRect(rTmp, 9f, 9f, p)
            p.maskFilter = null
            p.shader = LinearGradient(0f, r.top, 0f, r.bottom, 0xFF141414.toInt(), 0xFF2A2A2A.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, 9f, 9f, p)
            p.shader = null
            val inner = RectF(r.left + 2f, r.top + 2f, r.right - 2f, r.bottom - 2f)
            c.save()
            chemin.reset(); chemin.addRoundRect(inner, 7f, 7f, Path.Direction.CW)
            c.clipPath(chemin)
            val cols = when (teinte) {
                2 -> intArrayOf(0xFFFF8A7A.toInt(), 0xFFC31C10.toInt(), 0xFF7A0D05.toInt())
                1 -> intArrayOf(0xFFFFE066.toInt(), 0xFFE0A21A.toInt(), 0xFF8A5E05.toInt())
                else -> intArrayOf(0xFF7EF29A.toInt(), 0xFF19A64A.toInt(), 0xFF0B6B2E.toInt())
            }
            val bw = inner.width() * max(0f, v) / 100f
            if (bw > 0f) {
                p.shader = LinearGradient(0f, inner.top, 0f, inner.bottom, cols, floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
                c.drawRect(inner.left, inner.top, inner.left + bw, inner.bottom, p)
                p.shader = null
                p.color = Color.argb(89, 255, 255, 255)
                c.drawRect(inner.left, inner.top, inner.left + bw, inner.top + 2f, p)
            }
            // ombre interieure (inset 0 2px 4px rgba(0,0,0,.9))
            p.color = Color.argb(230, 0, 0, 0)
            p.maskFilter = BlurMaskFilter(rayon(2f), BlurMaskFilter.Blur.NORMAL)
            chemin.reset()
            chemin.fillType = Path.FillType.EVEN_ODD
            chemin.addRect(inner.left - 20f, inner.top - 20f, inner.right + 20f, inner.bottom + 20f, Path.Direction.CW)
            rTmp.set(inner.left, inner.top + 2f, inner.right, inner.bottom + 2f)
            chemin.addRoundRect(rTmp, 7f, 7f, Path.Direction.CW)
            c.drawPath(chemin, p)
            chemin.fillType = Path.FillType.WINDING
            p.maskFilter = null
            c.restore()
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0xFFF0E2B0.toInt()
            rTmp.set(r.left + 1f, r.top + 1f, r.right - 1f, r.bottom - 1f)
            c.drawRoundRect(rTmp, 8f, 8f, p)
            p.style = Paint.Style.FILL
        }
        // noms (ombres 0 0 8px et 0 2px 0, puis le texte)
        police(fs, 1.5f)
        pT.color = 0xFFFFE27A.toInt()
        val base = top + asc(fs)
        for (passe in 0..1) {
            if (passe == 0) pT.setShadowLayer(rayon(4f), 0f, 0f, Color.argb(230, 0, 0, 0))
            else pT.setShadowLayer(0.1f, 0f, 2f, Color.BLACK)
            pT.textAlign = Paint.Align.LEFT
            c.drawText("RUDY", left + 2f, base, pT)
            pT.textAlign = Paint.Align.RIGHT
            c.drawText(cpuNom, coteD + sw - 2f, base, pT)
        }
        pT.textAlign = Paint.Align.LEFT
        pT.clearShadowLayer()
    }

    private fun dessinerCommandes(c: Canvas) {
        // #joy : translate(-50%,-50%) scale(.8)
        val cx = joyCx(); val cy = joyCy()
        val tj = joyTaille
        c.save()
        c.scale(joyEch, joyEch, cx, cy)
        p.reset(); p.isAntiAlias = true
        p.color = 0xCC080808.toInt()
        c.drawCircle(cx, cy, tj / 2f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = 0xFF666666.toInt()
        c.drawCircle(cx, cy, tj / 2f - 2.5f, p)
        p.style = Paint.Style.FILL
        val st = stickTaille
        val decal = 5f + stickPos + st / 2f - tj / 2f
        val sx = cx + decal + stickX / joyEch
        val sy = cy + decal + stickY / joyEch
        p.color = Color.BLACK
        p.maskFilter = BlurMaskFilter(rayon(11f), BlurMaskFilter.Blur.NORMAL)
        c.drawCircle(sx, sy, st / 2f, p)
        p.maskFilter = null
        p.color = 0xFF242424.toInt()
        c.drawCircle(sx, sy, st / 2f, p)
        c.restore()
        // GARDE, POING, ESQUIVE, dans l'ordre du HTML
        for (b in arrayOf(gardeB, poingB, esquiveB)) {
            val bx = W * b.px / 100f; val by = H * b.py / 100f
            val tb = tailleB(b)
            c.save()
            c.scale(b.ech, b.ech, bx, by)
            p.reset(); p.isAntiAlias = true; p.color = b.couleur
            c.drawCircle(bx, by, tb / 2f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = 0xFF555555.toInt()
            c.drawCircle(bx, by, tb / 2f - 2.5f, p)
            p.style = Paint.Style.FILL
            police(b.police)
            pT.color = Color.WHITE
            pT.textAlign = Paint.Align.CENTER
            c.drawText(b.texte, bx, by - lh(b.police) / 2f + asc(b.police), pT)
            pT.textAlign = Paint.Align.LEFT
            c.restore()
        }
    }

    private fun dessinerCompte(c: Canvas) {
        p.reset(); p.color = Color.argb(31, 0, 0, 0)
        c.drawRect(0f, 0f, W, H, p)
        val fs = borne(90f, W * .22f, 260f)
        police(fs)
        pT.textAlign = Paint.Align.CENTER
        val base = (H - lh(fs)) / 2f + asc(fs)
        pT.color = Color.BLACK
        pT.setShadowLayer(rayon(16f), 0f, 0f, Color.BLACK)
        c.drawText(cdTexte, W / 2f, base, pT)
        pT.setShadowLayer(rayon(8f), 0f, 0f, Color.BLACK)
        c.drawText(cdTexte, W / 2f, base, pT)
        pT.clearShadowLayer()
        c.drawText(cdTexte, W / 2f, base + 5f, pT)
        pT.color = 0xFFFFD800.toInt()
        c.drawText(cdTexte, W / 2f, base, pT)
        pT.textAlign = Paint.Align.LEFT
    }

    // ---------------------------------------------------------------- ecran de depart

    private fun rectCommencer(): RectF {
        val fs = borne(18f, W * .03f, 34f)
        police(fs)
        val w = pT.measureText("COMMENCER") + 3f * fs + 6f
        val h = lh(fs) + 1.1f * fs + 6f
        val bas = H - H * .07f
        return RectF(W / 2f - w / 2f, bas - h, W / 2f + w / 2f, bas)
    }

    private fun dessinerDepart(c: Canvas) {
        val d = depart
        if (d == null) {
            police(16f); pT.color = Color.WHITE; pT.textAlign = Paint.Align.CENTER
            c.drawText("Chargement\u2026", W / 2f, H / 2f, pT)
            pT.textAlign = Paint.Align.LEFT
            return
        }
        couvrir(c, d, 0f, 0f, W, H)
        boutonCss(c, rectCommencer(), 16f, 0xFFB40000.toInt(), Color.argb(209, 0, 0, 0),
            "COMMENCER", borne(18f, W * .03f, 34f), 18f)
    }

    /** bouton : bordure 3px blanche, anneau 3px colore, ombre 0 5px (flou) noire. */
    private fun boutonCss(c: Canvas, r: RectF, ra: Float, anneau: Int, fondC: Int, texte: String, fs: Float, flou: Float) {
        ombreBoite(c, r, ra, 5f, flou, 0f, Color.BLACK)
        p.reset(); p.isAntiAlias = true
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = anneau
        rTmp.set(r.left - 1.5f, r.top - 1.5f, r.right + 1.5f, r.bottom + 1.5f)
        c.drawRoundRect(rTmp, ra + 1.5f, ra + 1.5f, p)
        p.style = Paint.Style.FILL; p.color = fondC
        c.drawRoundRect(r, ra, ra, p)
        p.style = Paint.Style.STROKE; p.color = Color.WHITE
        rTmp.set(r.left + 1.5f, r.top + 1.5f, r.right - 1.5f, r.bottom - 1.5f)
        c.drawRoundRect(rTmp, ra - 1.5f, ra - 1.5f, p)
        p.style = Paint.Style.FILL
        police(fs)
        pT.color = Color.WHITE
        pT.textAlign = Paint.Align.CENTER
        c.drawText(texte, r.centerX(), r.centerY() - lh(fs) / 2f + asc(fs), pT)
        pT.textAlign = Paint.Align.LEFT
    }

    // ---------------------------------------------------------------- choix du combattant

    private fun pct(s: RectF, l: Float, t: Float, w: Float, h: Float) =
        RectF(s.left + s.width() * l / 100f, s.top + s.height() * t / 100f,
            s.left + s.width() * (l + w) / 100f, s.top + s.height() * (t + h) / 100f)

    /** selCarteIn .5s cubic-bezier(.2,.9,.3,1.3) both -> [opacite, decalage Y, echelle]. */
    private fun entree(t: Double, retard: Double): FloatArray {
        val q = ((t - tEcran - retard) / 500.0).coerceIn(0.0, 1.0)
        val e = ENTREE.y(q).toFloat()
        return floatArrayOf(e.coerceIn(0f, 1f), 38f * (1f - e), .9f + .1f * e)
    }

    /** animation infinite alternate ease-in-out -> 0..1 */
    private fun alterne(t: Double, duree: Double): Float {
        val cyc = ((t - tEcran) / duree) % 2.0
        val x = if (cyc <= 1.0) cyc else 2.0 - cyc
        return EASE_IN_OUT.y(x).toFloat()
    }

    private fun flecheRect(s: RectF, gauche: Boolean): RectF {
        val fs = borne(22f, W * .04f, 48f)
        val w = fs * .77f + 20f
        val h = lh(fs) + 20f
        val l = s.left + s.width() * (if (gauche) .265f else .83f)
        val cy = s.top + s.height() * .59f
        return RectF(l, cy - h / 2f, l + w, cy + h / 2f)
    }

    private fun appuiChoix(x: Float, y: Float) {
        if (fini) return
        val s = scene()
        // carte active au-dessus (z-index 3), puis les autres, la derniere en haut
        val ordre = listOf(actif) + cartes.indices.filter { it != actif }.reversed()
        for (i in ordre) {
            val r = pct(s, cartes[i].l, cartes[i].t, cartes[i].w, cartes[i].h)
            if (i == actif) r.inset(-7f, -7f)
            if (r.contains(x, y)) {
                if (actif != i) { actif = i; return }
                valider(); return
            }
        }
        if (flecheRect(s, false).contains(x, y)) { actif = (actif + 1) % cartes.size; return }
        if (flecheRect(s, true).contains(x, y)) { actif = (actif - 1 + cartes.size) % cartes.size; return }
    }

    private fun dessinerChoix(c: Canvas, t: Double) {
        c.drawColor(Color.BLACK)
        val s = scene()
        choix?.let { c.drawBitmap(it, null, s, pImg) }
        // #selFlash (sous les portraits)
        if (tValide >= 0) {
            val q = (t - tValide) / 500.0
            if (q in 0.0..1.0) {
                p.reset(); p.color = Color.WHITE
                p.alpha = (.9f * (1f - EASE_OUT.y(q).toFloat()) * 255).roundToInt().coerceIn(0, 255)
                c.drawRect(s, p)
            }
        }
        // portraits et plaques de nom (Valor, Mody)
        val a = entree(t, 220.0)
        if (a[0] > 0f) for (carte in cartes) {
            val art = carte.art
            if (art != null) {
                val r = pct(s, art[0], art[1], art[2], art[3])
                c.save()
                transformer(c, r, a)
                val n = c.saveLayerAlpha(r.left - 3f, r.top - 3f, r.right + 3f, r.bottom + 3f, (a[0] * 255).roundToInt())
                p.reset(); p.isAntiAlias = true
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Color.argb(217, 0, 0, 0)
                rTmp.set(r.left - 1f, r.top - 1f, r.right + 1f, r.bottom + 1f)
                c.drawRoundRect(rTmp, 5f, 5f, p)
                p.style = Paint.Style.FILL; p.color = 0xFF0B0B0B.toInt()
                c.drawRoundRect(r, 4f, 4f, p)
                val ic = if (carte.jeu == "V") iconeValor else iconeMody
                if (ic != null) {
                    c.save()
                    chemin.reset(); chemin.addRoundRect(r, 4f, 4f, Path.Direction.CW); c.clipPath(chemin)
                    val e = min(r.width() / ic.width, r.height() / ic.height)
                    val dw = ic.width * e; val dh = ic.height * e
                    rTmp.set(r.centerX() - dw / 2f, r.centerY() - dh / 2f, r.centerX() + dw / 2f, r.centerY() + dh / 2f)
                    c.drawBitmap(ic, null, rTmp, pImg)
                    c.restore()
                }
                c.restoreToCount(n)
                c.restore()
            }
            val pl = carte.plaque
            if (pl != null) {
                val r = pct(s, pl[0], pl[1], pl[2], pl[3])
                c.save()
                transformer(c, r, a)
                val n = c.saveLayerAlpha(r.left - 3f, r.top - 3f, r.right + 3f, r.bottom + 3f, (a[0] * 255).roundToInt())
                p.reset(); p.isAntiAlias = true; p.color = 0xFF0B0B0B.toInt()
                c.drawRoundRect(r, 4f, 4f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0xFFD8A400.toInt()
                rTmp.set(r.left + 1f, r.top + 1f, r.right - 1f, r.bottom - 1f)
                c.drawRoundRect(rTmp, 3f, 3f, p)
                p.style = Paint.Style.FILL
                val fs = borne(11f, W * .016f, 22f)
                police(fs, 1f)
                pT.color = 0xFFFFD800.toInt(); pT.textAlign = Paint.Align.CENTER
                pT.setShadowLayer(rayon(1f), 0f, 2f, Color.BLACK)
                c.drawText(carte.nom, r.centerX(), r.centerY() - lh(fs) / 2f + asc(fs), pT)
                pT.clearShadowLayer(); pT.textAlign = Paint.Align.LEFT
                c.restoreToCount(n)
                c.restore()
            }
        }
        // fleches
        if (!fini) {
            val fs = borne(22f, W * .04f, 48f)
            val o = .45f + .55f * alterne(t, 1000.0)
            for (g in booleanArrayOf(true, false)) {
                val r = flecheRect(s, g)
                val gw = fs * .77f
                val x0 = r.left + 10f
                val cy = r.centerY()
                val demi = gw * .5f
                chemin.reset()
                if (g) { chemin.moveTo(x0 + gw, cy - demi); chemin.lineTo(x0, cy); chemin.lineTo(x0 + gw, cy + demi) }
                else { chemin.moveTo(x0, cy - demi); chemin.lineTo(x0 + gw, cy); chemin.lineTo(x0, cy + demi) }
                chemin.close()
                val n = c.saveLayerAlpha(r.left - 20f, r.top - 20f, r.right + 20f, r.bottom + 20f, (o * 255).roundToInt())
                p.reset(); p.isAntiAlias = true; p.color = Color.BLACK
                p.maskFilter = BlurMaskFilter(rayon(6f), BlurMaskFilter.Blur.NORMAL)
                c.drawPath(chemin, p)
                p.maskFilter = null
                c.save(); c.translate(0f, 3f); c.drawPath(chemin, p); c.restore()
                p.color = 0xFFFFD800.toInt()
                c.drawPath(chemin, p)
                c.restoreToCount(n)
            }
        }
        // #selNom
        if (tValide >= 0) {
            val q = ((t - tValide) / 900.0).coerceIn(0.0, 1.0)
            val o: Float
            val e: Float
            if (q <= .35) { val u = EASE_OUT.y(q / .35).toFloat(); o = u; e = .6f + (1.12f - .6f) * u }
            else { val u = EASE_OUT.y((q - .35) / .65).toFloat(); o = 1f; e = 1.12f + (1f - 1.12f) * u }
            val fs = borne(30f, W * .07f, 90f)
            police(fs)
            val cx = W / 2f
            val cy = s.top + s.height() * .22f
            val nom = cartes[actif].nom
            c.save()
            c.scale(e, e, cx, cy)
            val base = cy - lh(fs) / 2f + asc(fs)
            val n = c.saveLayerAlpha(0f, 0f, W, H, (o * 255).roundToInt().coerceIn(0, 255))
            pT.textAlign = Paint.Align.CENTER
            pT.color = Color.BLACK
            pT.setShadowLayer(rayon(12f), 0f, 0f, Color.BLACK)
            c.drawText(nom, cx, base, pT)
            pT.clearShadowLayer()
            c.drawText(nom, cx, base + 5f, pT)
            pT.color = 0xFFFFD800.toInt()
            c.drawText(nom, cx, base, pT)
            pT.textAlign = Paint.Align.LEFT
            c.restoreToCount(n)
            c.restore()
        }
        // #selAstuce
        if (!fini) {
            val fs = borne(10f, W * .015f, 18f)
            police(fs)
            val o = .35f + .65f * alterne(t, 1200.0)
            val al = (o * 255).roundToInt()
            pT.color = Color.WHITE
            pT.alpha = al
            pT.textAlign = Paint.Align.CENTER
            pT.setShadowLayer(rayon(1.5f), 0f, 2f, Color.argb(al, 0, 0, 0))
            val bas = s.bottom - s.height() * .025f
            c.drawText("APPUIE SUR LE COMBATTANT POUR LE CHOISIR", W / 2f, bas - lh(fs) + asc(fs), pT)
            pT.clearShadowLayer(); pT.textAlign = Paint.Align.LEFT; pT.alpha = 255
        }
        // cadre dore de la carte active (::before, z-index 3)
        val carte = cartes[actif]
        val ae = entree(t, 100.0 + 120.0 * actif)
        if (ae[0] > 0f) {
            val r = pct(s, carte.l, carte.t, carte.w, carte.h)
            c.save()
            transformer(c, r, ae)
            val n = c.saveLayerAlpha(r.left - 60f, r.top - 60f, r.right + 60f, r.bottom + 60f, (ae[0] * 255).roundToInt())
            val puls = alterne(t, 850.0)
            val cadre = RectF(r.left - 7f, r.top - 7f, r.right + 7f, r.bottom + 7f)
            c.scale(1f + .02f * puls, 1f + .02f * puls, cadre.centerX(), cadre.centerY())
            ombreBoite(c, cadre, 9f, 0f, 12f + 18f * puls, 3f + 7f * puls,
                Color.argb(((.55f + .40f * puls) * 255).roundToInt(), 255, 216, 0))
            val dedans = RectF(cadre.left + 4f, cadre.top + 4f, cadre.right - 4f, cadre.bottom - 4f)
            c.save()
            chemin.reset(); chemin.addRoundRect(dedans, 5f, 5f, Path.Direction.CW); c.clipPath(chemin)
            p.reset(); p.isAntiAlias = true
            p.color = Color.argb(((.25f + .25f * puls) * 255).roundToInt(), 255, 216, 0)
            p.maskFilter = BlurMaskFilter(rayon((14f + 12f * puls) / 2f), BlurMaskFilter.Blur.NORMAL)
            chemin.reset(); chemin.fillType = Path.FillType.EVEN_ODD
            chemin.addRect(dedans.left - 60f, dedans.top - 60f, dedans.right + 60f, dedans.bottom + 60f, Path.Direction.CW)
            chemin.addRoundRect(dedans, 5f, 5f, Path.Direction.CW)
            c.drawPath(chemin, p)
            chemin.fillType = Path.FillType.WINDING
            p.maskFilter = null
            c.restore()
            p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = 0xFFFFD800.toInt()
            rTmp.set(cadre.left + 2f, cadre.top + 2f, cadre.right - 2f, cadre.bottom - 2f)
            c.drawRoundRect(rTmp, 7f, 7f, p)
            p.style = Paint.Style.FILL
            c.restoreToCount(n)
            c.restore()
        }
    }

    /** translateY puis scale autour du centre de la boite. */
    private fun transformer(c: Canvas, r: RectF, a: FloatArray) {
        c.translate(0f, a[1])
        c.scale(a[2], a[2], r.centerX(), r.centerY())
    }

    // ---------------------------------------------------------------- fin du combat

    private fun boutonsFin(): Pair<RectF, RectF> {
        val s = scene()
        val fs = borne(14f, W * .022f, 28f)
        police(fs)
        val h = lh(fs) + .9f * fs + 6f
        val w1 = pT.measureText("REJOUER") + 2.4f * fs + 6f
        val w2 = pT.measureText("RETOUR") + 2.4f * fs + 6f
        val tot = w1 + 14f + w2
        val bas = s.bottom - s.height() * .035f
        val x0 = s.centerX() - tot / 2f
        return Pair(RectF(x0, bas - h, x0 + w1, bas), RectF(x0 + w1 + 14f, bas - h, x0 + tot, bas))
    }

    private fun appuiFin(x: Float, y: Float) {
        val (a, b) = boutonsFin()
        if (a.contains(x, y)) recharge(true)
        else if (b.contains(x, y)) recharge(false)
    }

    private fun dessinerFin(c: Canvas, t: Double) {
        c.drawColor(Color.BLACK)
        val s = scene()
        imageFin?.let { if (!it.isRecycled) c.drawBitmap(it, null, s, pImg) }
        val (a, b) = boutonsFin()
        val fs = borne(14f, W * .022f, 28f)
        val e = 1f + .05f * alterne(t, 900.0)
        val boutons = listOf(Triple(a, "REJOUER", 0xFFB40000.toInt()), Triple(b, "RETOUR", 0xFF087D20.toInt()))
        for ((r, txt, ann) in boutons) {
            c.save()
            c.scale(e, e, r.centerX(), r.centerY())
            boutonCss(c, r, 14f, ann, Color.argb(217, 0, 0, 0), txt, fs, 16f)
            c.restore()
        }
    }

    // ================================================================ courbes CSS

    private class Bezier(val x1: Double, val y1: Double, val x2: Double, val y2: Double) {
        private fun bx(t: Double) = 3 * (1 - t) * (1 - t) * t * x1 + 3 * (1 - t) * t * t * x2 + t * t * t
        private fun by(t: Double) = 3 * (1 - t) * (1 - t) * t * y1 + 3 * (1 - t) * t * t * y2 + t * t * t
        fun y(x: Double): Double {
            if (x <= 0) return 0.0
            if (x >= 1) return 1.0
            var lo = 0.0
            var hi = 1.0
            for (i in 0 until 30) {
                val m = (lo + hi) / 2
                if (bx(m) < x) lo = m else hi = m
            }
            return by((lo + hi) / 2)
        }
    }

    private companion object {
        const val DEPART = 0
        const val CHOIX = 1
        const val COMBAT = 2
        const val FIN = 3
        val EASE = Bezier(.25, .1, .25, 1.0)
        val EASE_OUT = Bezier(0.0, 0.0, .58, 1.0)
        val EASE_IN_OUT = Bezier(.42, 0.0, .58, 1.0)
        val ENTREE = Bezier(.2, .9, .3, 1.3)
    }
}
