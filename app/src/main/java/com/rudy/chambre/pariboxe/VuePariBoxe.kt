package com.rudy.chambre.pariboxe

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * PariBoxe en natif : la meme scene que le fichier HTML de Rudy.
 *
 * Toutes les mesures sont celles du HTML, en « px CSS » (= dp) puis
 * multipliees par la densite de l'ecran :
 *  - Rudy : boite 390x470 (ecran de moins de 520 de haut, sinon 520x760),
 *    image « contain » posee en bas au centre, centre a 50 %, pieds a 3 %,
 *    avec sa taille d'image propre a chaque geste (980x820 ou 900x760) ;
 *  - l'adversaire : boite 520x760, mise a l'echelle pour avoir la meme
 *    hauteur que Rudy et la meme ligne de pieds (calcul « cpu-meme-niveau ») ;
 *  - filtre des combattants : luminosite .93, contraste 1.06, saturation
 *    1.04 et ombre portee (0 9px 5px, noir 50 %) ;
 *  - commandes aux positions exactes validees dans le HTML.
 */
@SuppressLint("ViewConstructor")
class VuePariBoxe(ctx: Context) : View(ctx) {

    private enum class Ecran { CHARGEMENT, DEPART, CHOIX, COMBAT, FIN }

    private val d = resources.displayMetrics.density
    private val prefs = ctx.getSharedPreferences("pariboxe", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val chargeur = Executors.newSingleThreadExecutor()
    private val tampon = IntArray(980 * 820)
    var son: SonPariBoxe? = null

    var enMarche = true
        set(v) { field = v; if (v) postInvalidateOnAnimation() }

    // ------------------------------------------------------------------ images
    @Volatile private var ecran = Ecran.CHARGEMENT
    @Volatile private var comptes: Map<String, Map<String, Int>> = emptyMap()
    @Volatile private var fond: Bitmap? = null
    @Volatile private var depart: Bitmap? = null
    @Volatile private var choixImg: Bitmap? = null
    @Volatile private var iconeValor: Bitmap? = null
    @Volatile private var iconeMody: Bitmap? = null
    @Volatile private var finImg: Bitmap? = null
    @Volatile private var rudy: Combattant? = null
    @Volatile private var adversaire: Combattant? = null
    @Volatile private var erreur: String? = null

    // --------------------------------------------------------- etat de Rudy
    private var mode = "idle"
    private var frame = 0
    private var lastR = 0L
    private var attackBusy = false
    private var guardHeld = false
    private var guardRelease = false
    private var attackIndex = 0
    private var move = 0
    private val attacks = arrayOf("direct1", "direct2", "kick", "uppercut")
    private val actions = setOf("direct1", "direct2", "kick", "uppercut", "dodge", "damage")
    private var label = "GARDE"

    private var echelle = clampf(prefs.getFloat("fighterScale", 1f), .45f, 1.8f)
    private var editX = clampf(prefs.getFloat("fighterX", 50f), 5f, 95f)
    private var editY = clampf(prefs.getFloat("fighterY", 3f), 1.5f, 55f)
    private var gauche = editX
    private var decalage = 0f
    private var lastTick = 0L

    // ------------------------------------------------------ etat du CPU
    private var em = "idle"
    private var ef = 0
    private var eLast = 0L
    private var eBusy = false
    private var ex = 65.12054656786727f
    private var cpuHP = 100f
    private var rudyHP = 100f
    private var cpuNext = 0L
    private var cpuImpactDone = false
    private var seen = ""
    private var serial = 0
    private val eActions = setOf("damage", "dodge", "guard", "direct", "kick", "lowkick", "backfist")
    private val eCoups = setOf("direct", "kick", "lowkick", "backfist")

    private var jeuCpu = "E"
    private var nomCpu = "CPU"
    private var tailleCpu = 1f

    // ----------------------------------------------------------- deroulement
    private var ready = false
    private var demarrage = false
    private var revanche: String? = null
    private var compte: String? = null
    private var combatCommence = false
    private var finAffiche = false
    private var finCle = ""
    private var generation = 0

    private var actif = 0
    private var fini = false
    private var tChoix = 0L
    private var tValide = 0L
    private var nomChoisi = ""
    private var tFin = 0L

    private var vuRudy = ""
    private var dernierEnemy = ""
    private var hpVuCpu = 100f
    private var hpVuRudy = 100f
    private val barreCpu = Barre()
    private val barreRudy = Barre()
    private var flashType = 0
    private var tFlash = 0L

    // ----------------------------------------------------------- tactile
    private val cibles = HashMap<Int, String>()
    private var joyId = -1
    private var stickX = 0f
    private var stickY = 0f
    private var dragSx = 0f
    private var dragSy = 0f
    private var dragX0 = 0f
    private var dragY0 = 0f

    // --------------------------------------------------------------- pinceaux
    private val gras: Typeface = Typeface.create(Typeface.SANS_SERIF, 900, false)
    private val pImage = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pPerso = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(matriceFiltre())
    }
    private val pOmbre = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(Color.argb(128, 0, 0, 0), PorterDuff.Mode.SRC_IN)
    }
    private val pRemp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTrait = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pTexte = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = gras; textAlign = Paint.Align.CENTER }
    private val r1 = RectF()
    private val r2 = RectF()
    private val chemin = Path()
    private val matrice = Matrix()
    private val noeudRudy: RenderNode? = if (Build.VERSION.SDK_INT >= 29) RenderNode("ombreRudy") else null
    private val noeudCpu: RenderNode? = if (Build.VERSION.SDK_INT >= 29) RenderNode("ombreCpu") else null

    // =====================================================================
    // Chargement
    // =====================================================================

    fun lancer() {
        chargeur.execute {
            try {
                Planches.extraire(context)
                val dir = Planches.dossier(context)
                comptes = Planches.lireComptes(dir)
                fond = Planches.image(File(dir, "fond.img"))
                depart = Planches.image(File(dir, "depart.img"))
                main.post { if (ecran == Ecran.CHARGEMENT) ecran = Ecran.DEPART; invalidate() }
                son?.let { s -> Thread { s.preparer(dir) }.start() }
                choixImg = Planches.image(File(dir, "choix.img"))
                iconeValor = Planches.image(File(dir, "icone_valor.img"))
                iconeMody = Planches.image(File(dir, "icone_mody.img"))
                val r = Combattant("rudy", comptes["rudy"] ?: emptyMap())
                rudy = r
                main.post { if (adversaire == null) equiperPlanches("theo") }
                remplir(r)
            } catch (e: Throwable) {
                erreur = e.toString()
                main.post { invalidate() }
            }
        }
    }

    /** Charge les planches d'un combattant, en commencant par la garde. */
    private fun remplir(c: Combattant) {
        val dir = Planches.dossier(context)
        val ordre = (listOf("idle", "forward", "back", "guard") + c.comptes.keys).distinct()
        for (m in ordre) {
            for (i in 0 until c.compte(m)) {
                if (c.abandonne) return
                val cle = "${m}_$i"
                if (c.images.containsKey(cle)) continue
                val p = try { Planches.planche(File(dir, "${c.nom}/$cle.img"), tampon) } catch (_: Throwable) { null }
                if (p != null) c.images[cle] = p
            }
        }
    }

    /** Sur le fil principal : prepare les planches de l'adversaire choisi. */
    private fun equiperPlanches(cle: String) {
        if (adversaire?.nom == cle) return
        adversaire?.abandonne = true
        val c = Combattant(cle, comptes[cle] ?: emptyMap())
        adversaire = c
        chargeur.execute { remplir(c) }
    }

    fun fermer() {
        generation++
        adversaire?.abandonne = true
        rudy?.abandonne = true
        chargeur.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }

    // =====================================================================
    // Outils
    // =====================================================================

    private fun clampf(v: Float, a: Float, b: Float) = max(a, min(b, v))
    private fun W() = width / d
    private fun H() = height / d
    private fun petit() = H() <= 520f
    private fun vwClamp(a: Float, p: Float, b: Float) = clampf(W() * p / 100f, a, b)
    private fun flou(bCss: Float): Float = max(0.1f, (bCss * d / 2f - 0.5f) / 0.57735f)

    private fun apres(ms: Long, f: () -> Unit) {
        val g = generation
        main.postDelayed({ if (g == generation) f() }, ms)
    }

    private fun matriceFiltre(): ColorMatrix {
        val b = 0.93f; val c = 1.06f; val s = 1.04f
        val mb = ColorMatrix(floatArrayOf(
            b, 0f, 0f, 0f, 0f,  0f, b, 0f, 0f, 0f,  0f, 0f, b, 0f, 0f,  0f, 0f, 0f, 1f, 0f))
        val o = 255f * (0.5f - 0.5f * c)
        val mc = ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, o,  0f, c, 0f, 0f, o,  0f, 0f, c, 0f, o,  0f, 0f, 0f, 1f, 0f))
        val ms = ColorMatrix(floatArrayOf(
            0.213f + 0.787f * s, 0.715f - 0.715f * s, 0.072f - 0.072f * s, 0f, 0f,
            0.213f - 0.213f * s, 0.715f + 0.285f * s, 0.072f - 0.072f * s, 0f, 0f,
            0.213f - 0.213f * s, 0.715f - 0.715f * s, 0.072f + 0.928f * s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f))
        val r = ColorMatrix()
        r.setConcat(mc, mb)
        r.setConcat(ms, r)
        return r
    }

    /** cubic-bezier CSS */
    private fun bezier(x1: Float, y1: Float, x2: Float, y2: Float, x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        var lo = 0f; var hi = 1f; var u = x
        repeat(24) {
            u = (lo + hi) / 2f
            val bx = 3 * (1 - u) * (1 - u) * u * x1 + 3 * (1 - u) * u * u * x2 + u * u * u
            if (bx < x) lo = u else hi = u
        }
        return 3 * (1 - u) * (1 - u) * u * y1 + 3 * (1 - u) * u * u * y2 + u * u * u
    }
    private fun ease(p: Float) = bezier(.25f, .1f, .25f, 1f, p)
    private fun easeOut(p: Float) = bezier(0f, 0f, .58f, 1f, p)
    private fun easeInOut(p: Float) = bezier(.42f, 0f, .58f, 1f, p)
    /** animation « infinite alternate » en ease-in-out, de 0 a 1 */
    private fun vaEtVient(t: Long, dureeMs: Long): Float {
        val cycle = t / dureeMs
        var p = (t % dureeMs).toFloat() / dureeMs
        if (cycle % 2L == 1L) p = 1f - p
        return easeInOut(p)
    }

    private class Barre {
        var de = 100f; var vers = 100f; var t0 = 0L
        fun valeur(t: Long, f: (Float) -> Float): Float {
            val p = ((t - t0) / 220f).coerceIn(0f, 1f)
            return de + (vers - de) * f(p)
        }
        fun cible(v: Float, t: Long, f: (Float) -> Float) { de = valeur(t, f); vers = v; t0 = t }
        fun remettre() { de = 100f; vers = 100f; t0 = 0L }
    }

    // =====================================================================
    // Geometrie (px CSS), identique au HTML
    // =====================================================================

    private fun boiteRudyL() = if (petit()) 390f else 520f
    private fun boiteRudyH() = if (petit()) 470f else 760f

    private fun kR() = min(boiteRudyL() / 980f, boiteRudyH() / 820f)
    private fun kC() = min(520f / 980f, 760f / 820f)
    /** script cpu-meme-niveau-que-rudy */
    private fun cpuScale() = echelle * kR() * 743f * tailleCpu / (kC() * 650f)
    private fun cpuBottom(): Float {
        val gh = H()
        val pieds = gh * editY / 100f + 30f * kR() * echelle
        return (pieds - 21f * kC() * cpuScale()) / gh * 100f
    }

    private fun rectRudy(r: RectF): RectF {
        val w = boiteRudyL() * echelle; val h = boiteRudyH() * echelle
        val cx = W() * gauche / 100f; val bas = H() - H() * editY / 100f
        r.set(cx - w / 2f, bas - h, cx + w / 2f, bas)
        return r
    }

    private fun rectCpu(r: RectF): RectF {
        val sc = cpuScale()
        val w = 520f * sc; val h = 760f * sc
        val cx = W() * ex / 100f; val bas = H() - H() * cpuBottom() / 100f
        r.set(cx - w / 2f, bas - h, cx + w / 2f, bas)
        return r
    }

    private fun corps(r: RectF, o: RectF): RectF {
        o.set(r.left + r.width() * .20f, r.top + r.height() * .10f,
              r.right - r.width() * .20f, r.bottom - r.height() * .05f)
        return o
    }

    private fun chevauche(a: RectF, b: RectF) =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

    private fun rudyTouche(kind: String): Boolean {
        val r = rectRudy(RectF())
        val h = when (kind) {
            "kick" -> RectF(r.left + r.width() * .55f, r.top + r.height() * .42f, r.right, r.top + r.height() * .82f)
            "uppercut" -> RectF(r.left + r.width() * .52f, r.top + r.height() * .15f, r.right, r.top + r.height() * .58f)
            else -> RectF(r.left + r.width() * .55f, r.top + r.height() * .20f, r.right, r.top + r.height() * .58f)
        }
        return chevauche(h, corps(rectCpu(RectF()), RectF()))
    }

    private fun cpuTouche(kind: String): Boolean {
        val r = rectCpu(RectF())
        val h = if (kind == "kick" || kind == "lowkick")
            RectF(r.left, r.top + r.height() * .42f, r.left + r.width() * .48f, r.top + r.height() * .86f)
        else RectF(r.left, r.top + r.height() * .18f, r.left + r.width() * .48f, r.top + r.height() * .58f)
        return chevauche(h, corps(rectRudy(RectF()), RectF()))
    }

    // commandes : centre (% ecran), diametre, echelle
    private fun joyCx() = W() * 9.462570231237109f / 100f
    private fun joyCy() = H() * 66.57407681147257f / 100f
    private fun joyTaille() = if (petit()) 145f else 180f

    private class Commande(val id: String, val px: Float, val py: Float, val grand: Float, val petitD: Float,
                           val ech: Float, val couleur: Int, val texte: String, val police: Float)

    private val commandes = listOf(
        Commande("guard", 96.32917952560999f, 55.89247147242227f, 125f, 105f, .4f, 0xFF00602D.toInt(), "GARDE", 19f),
        Commande("attack", 89.2761723548869f, 78.11360756556192f, 150f, 125f, .35f, 0xFFA90000.toInt(), "POING", 22f),
        Commande("dodge", 94.67678556429941f, 68.56770912806192f, 92f, 92f, .5f, 0xFF08277D.toInt(), "ESQUIVE", 14f)
    )

    private fun stage(r: RectF): RectF {
        val w = W(); val h = H()
        val sw = min(w, h * 1672f / 941f); val sh = sw * 941f / 1672f
        r.set((w - sw) / 2f, (h - sh) / 2f, (w + sw) / 2f, (h + sh) / 2f)
        return r
    }

    // =====================================================================
    // Deroulement : depart, choix, compte a rebours, fin
    // =====================================================================

    private fun begin() {
        if (demarrage) return
        demarrage = true
        val r = revanche
        if (r != null) {
            revanche = null
            val c = when (r) { "V" -> Triple("V", "VALOR", "valor"); "MO" -> Triple("MO", "MODY", "mody")
                               else -> Triple("E", "TH\u00c9O", "theo") }
            equiper(c.first, c.second)
            ecran = Ecran.COMBAT
            lancerCombat()
        } else {
            ecran = Ecran.CHOIX
            actif = 0; fini = false; tChoix = SystemClock.uptimeMillis()
        }
    }

    private fun equiper(jeu: String, nom: String) {
        tailleCpu = if (jeu == "V") 1.09f else 1f
        jeuCpu = jeu
        nomCpu = nom
        equiperPlanches(when (jeu) { "V" -> "valor"; "MO" -> "mody"; else -> "theo" })
    }

    private fun lancerCombat() {
        son?.demarrerMusique()
        compte = "3"
        apres(1000) { compte = "2" }
        apres(2000) { compte = "1" }
        apres(3000) { compte = null; ready = true }
    }

    private val cartes = listOf(
        floatArrayOf(32.3f, 38.6f, 15.6f, 43.2f),
        floatArrayOf(49.3f, 38.6f, 15.4f, 41.4f),
        floatArrayOf(65.4f, 38.6f, 15.3f, 41.4f))
    private val nomsCartes = listOf("TH\u00c9O", "VALOR", "MODY")
    private val jeuxCartes = listOf("E", "V", "MO")
    private val arts = listOf(null, floatArrayOf(49.4f, 38.9f, 15.2f, 34.9f), floatArrayOf(65.5f, 38.9f, 15.1f, 34.9f))
    private val plaques = listOf(null, floatArrayOf(49.4f, 74.0f, 15.2f, 5.8f), floatArrayOf(65.5f, 74.0f, 15.1f, 5.8f))

    private fun valider() {
        if (fini) return
        fini = true
        equiper(jeuxCartes[actif], nomsCartes[actif])
        nomChoisi = nomsCartes[actif]
        tValide = SystemClock.uptimeMillis()
        apres(1100) {
            ecran = Ecran.COMBAT
            lancerCombat()
        }
    }

    private fun ecranFin(cle: String) {
        if (finAffiche) return
        finAffiche = true
        ready = false
        son?.arreterMusique()
        finCle = cle
        finImg = null
        tFin = SystemClock.uptimeMillis()
        ecran = Ecran.FIN
        val dir = Planches.dossier(context)
        Thread {
            val b = Planches.image(File(dir, "fin_$cle.img"))
            main.post { if (finCle == cle && finAffiche) finImg = b }
        }.start()
    }

    /** location.reload() du HTML : on repart de l'ecran COMMENCER. */
    private fun recharger(rejouer: Boolean) {
        generation++
        revanche = if (rejouer) jeuCpu else null
        mode = "idle"; frame = 0; lastR = 0L
        attackBusy = false; guardHeld = false; guardRelease = false; attackIndex = 0; move = 0
        label = "GARDE"
        editX = clampf(prefs.getFloat("fighterX", 50f), 5f, 95f)
        editY = clampf(prefs.getFloat("fighterY", 3f), 1.5f, 55f)
        gauche = editX; decalage = 0f; lastTick = 0L
        em = "idle"; ef = 0; eLast = 0L; eBusy = false; ex = 65.12054656786727f
        cpuHP = 100f; rudyHP = 100f; cpuNext = 0L; cpuImpactDone = false; seen = ""; serial = 0
        hpVuCpu = 100f; hpVuRudy = 100f; barreCpu.remettre(); barreRudy.remettre()
        flashType = 0
        nomCpu = "CPU"; tailleCpu = 1f; jeuCpu = "E"
        ready = false; demarrage = false; compte = null
        combatCommence = false; finAffiche = false; finImg = null
        cibles.clear(); joyId = -1; stickX = 0f; stickY = 0f
        vuRudy = ""; dernierEnemy = ""
        ecran = Ecran.DEPART
    }

    // =====================================================================
    // Logique du combat (chaque image)
    // =====================================================================

    private fun setMode(m: String) { if (mode != m) { mode = m; frame = 0; lastR = 0L } }
    private fun setE(m: String, t: Long) { if (em == m) return; em = m; ef = 0; eLast = t }
    private fun nbR(m: String) = rudy?.compte(m) ?: 0
    private fun nbE(m: String) = adversaire?.compte(m) ?: 0

    private fun logique(t: Long) {
        // --- animation de Rudy (script principal, 70 ms par image) ---
        if (lastR == 0L) lastR = t
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
            } else if (mode in actions) {
                frame++
                if (frame >= nbR(mode)) {
                    attackBusy = false
                    setMode(if (guardHeld) "guard" else if (move < 0) "back" else if (move > 0) "forward" else "idle")
                }
            } else {
                val n = nbR(mode)
                frame = if (n > 0) (frame + 1) % n else 0
            }
        }

        // --- deplacement de Rudy ---
        val dt = if (lastTick == 0L) 0L else min(40L, t - lastTick)
        lastTick = t
        if (ready && move != 0 && !guardHeld) {
            decalage = clampf(decalage + move * dt * 0.018f, -42f, 42f)
            gauche = editX + decalage
        }

        // --- animation du CPU ---
        if (eLast == 0L) eLast = t
        if (!ready) {
            eLast = t; ef = 0; em = "idle"; eBusy = false
        } else {
            val step = if (em == "forward" || em == "back") 118 else 105
            if (t - eLast >= step) { eLast = t; ef++ }
            if (ef >= max(1, nbE(em))) {
                if (em in eActions) { eBusy = false; setE("idle", t) } else ef = 0
            }
        }

        // --- IA du CPU, toutes les 120 ms ---
        if (t >= cpuNext) {
            cpuNext = t + 120
            if (ready && !eBusy && cpuHP > 0f && rudyHP > 0f) penser(t)
        }

        // --- les coups de Rudy frappent 180 ms apres leur debut ---
        if (ready) {
            if (mode in attacks && mode != seen) {
                val my = ++serial
                val kind = mode
                apres(180) { if (my == serial) hitCpu(if (kind == "kick" || kind == "uppercut") 7f else 5f) }
            }
            seen = mode
        }

        // --- souffles ---
        if (mode != vuRudy && mode in attacks) son?.souffle()
        vuRudy = mode
        val ei = if (ready) min(ef, max(0, nbE(em) - 1)) else 0
        val cleE = (if (ready) em else "idle") + "_" + ei
        if (cleE != dernierEnemy) {
            dernierEnemy = cleE
            if (ready && em in eCoups && ei == 1) son?.souffle()
        }

        // --- barres de vie, impacts, flash ---
        if (cpuHP != hpVuCpu) {
            if (cpuHP < hpVuCpu - .5f) { son?.coup(cpuHP, hpVuCpu - cpuHP); flashType = 1; tFlash = t }
            barreCpu.cible(cpuHP, t, this::easeOut); hpVuCpu = cpuHP
        }
        if (rudyHP != hpVuRudy) {
            if (rudyHP < hpVuRudy - .5f) { son?.coup(rudyHP, hpVuRudy - rudyHP); flashType = 2; tFlash = t }
            barreRudy.cible(rudyHP, t, this::easeOut); hpVuRudy = rudyHP
        }

        // --- fin du combat ---
        if (!finAffiche) {
            if (ready) combatCommence = true
            if (combatCommence) {
                if (cpuHP < 1f) ecranFin(when (jeuCpu) { "V" -> "rudy_valor"; "MO" -> "rudy_mody"; else -> "rudy_theo" })
                else if (rudyHP < 1f) ecranFin(when (jeuCpu) { "V" -> "valor"; "MO" -> "mody"; else -> "theo" })
            }
        }
    }

    private fun penser(t: Long) {
        val a = rectRudy(RectF()); val b = rectCpu(RectF())
        val coups = listOf("direct", "backfist", "kick", "lowkick")
        if (coups.none { cpuTouche(it) }) {
            ex = if (b.centerX() > a.centerX()) max(52f, ex - .55f) else min(92f, ex + .55f)
            setE("forward", t)
            return
        }
        val rnd = Math.random()
        if (rnd < .10) { eBusy = true; setE("dodge", t); return }
        if (rnd < .22) { eBusy = true; setE("guard", t); return }
        val possibles = coups.filter { cpuTouche(it) }
        if (possibles.isEmpty()) return
        val kind = possibles[(Math.random() * possibles.size).toInt().coerceAtMost(possibles.size - 1)]
        eBusy = true; cpuImpactDone = false; setE(kind, t)
        apres(if (kind == "direct" || kind == "backfist") 285L else 350L) {
            if (!cpuImpactDone) {
                cpuImpactDone = true
                if (cpuTouche(kind)) hitRudy(kind)
            }
        }
    }

    private fun hitRudy(kind: String) {
        if (!cpuTouche(kind)) return
        if (kind == "direct" && mode == "dodge") return
        if (guardHeld) return
        rudyHP = max(0f, rudyHP - if (kind == "kick" || kind == "lowkick") 6f else 4f)
        if (!attackBusy) { attackBusy = true; setMode("damage"); label = "D\u00c9G\u00c2TS" }
    }

    private fun hitCpu(power: Float) {
        if (!rudyTouche(mode) || em == "dodge" || em == "guard") return
        cpuHP = max(0f, cpuHP - power)
        eBusy = true
        setE("damage", SystemClock.uptimeMillis())
    }

    // =====================================================================
    // Commandes (memes regles que le HTML)
    // =====================================================================

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
        if (attackBusy) return
        guardHeld = true; guardRelease = false; setMode("guard"); label = "PROTECTION"
    }

    private fun releaseGuard() {
        if (mode == "guard") { guardHeld = false; guardRelease = true }
    }

    private fun joyMove(x: Float, y: Float) {
        val dx = x - joyCx(); val dy = y - joyCy()
        val maxi = joyTaille() * .8f * .30f
        val dist = hypot(dx, dy).let { if (it == 0f) 1f else it }
        val k = min(1f, maxi / dist)
        stickX = dx * k; stickY = dy * k
        move = if (dx > 22f) 1 else if (dx < -22f) -1 else 0
        if (!attackBusy && mode != "guard") {
            setMode(if (move > 0) "forward" else if (move < 0) "back" else "idle")
            label = if (move > 0) "AVANCE" else if (move < 0) "RECULE" else "GARDE"
        }
    }

    private fun resetJoy() {
        joyId = -1; move = 0; stickX = 0f; stickY = 0f
        if (!attackBusy && mode != "guard") { setMode("idle"); label = "GARDE" }
    }

    private fun cibleCombat(x: Float, y: Float): String? {
        if (hypot(x - joyCx(), y - joyCy()) <= joyTaille() * .8f / 2f) return "joy"
        for (c in commandes) {
            val dia = if (petit()) c.petitD else c.grand
            if (hypot(x - W() * c.px / 100f, y - H() * c.py / 100f) <= dia * c.ech / 2f) return c.id
        }
        if (rectRudy(r1).contains(x, y)) return "rudy"
        return null
    }

    private fun rectCommencer(r: RectF): RectF {
        val fs = vwClamp(18f, 3f, 34f)
        pTexte.textSize = fs * d; pTexte.letterSpacing = 0f
        val w = pTexte.measureText("COMMENCER") / d + 3f * fs + 6f
        val h = 1.15f * fs + 1.1f * fs + 6f
        val bas = H() - H() * .07f
        r.set((W() - w) / 2f, bas - h, (W() + w) / 2f, bas)
        return r
    }

    private fun rectsFin(a: RectF, b: RectF) {
        val st = stage(RectF())
        val fs = vwClamp(14f, 2.2f, 28f)
        pTexte.textSize = fs * d; pTexte.letterSpacing = 0f
        val w1 = pTexte.measureText("REJOUER") / d + 2.4f * fs + 6f
        val w2 = pTexte.measureText("RETOUR") / d + 2.4f * fs + 6f
        val h = 1.15f * fs + .9f * fs + 6f
        val bas = st.bottom - st.height() * .035f
        val g = st.centerX() - (w1 + 14f + w2) / 2f
        a.set(g, bas - h, g + w1, bas)
        b.set(g + w1 + 14f, bas - h, g + w1 + 14f + w2, bas)
    }

    private fun flecheRect(droite: Boolean, r: RectF): RectF {
        val st = stage(RectF())
        val fs = vwClamp(22f, 4f, 48f)
        val l = st.left + st.width() * (if (droite) .83f else .265f)
        val cy = st.top + st.height() * .59f
        val h = 1.15f * fs + 20f
        r.set(l, cy - h / 2f, l + .62f * fs + 20f, cy + h / 2f)
        return r
    }

    private fun carteRect(i: Int, r: RectF): RectF {
        val st = stage(RectF()); val c = cartes[i]
        r.set(st.left + st.width() * c[0] / 100f, st.top + st.height() * c[1] / 100f,
              st.left + st.width() * (c[0] + c[2]) / 100f, st.top + st.height() * (c[1] + c[3]) / 100f)
        return r
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                appui(e.getPointerId(i), e.getX(i) / d, e.getY(i) / d)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount)
                deplace(e.getPointerId(i), e.getX(i) / d, e.getY(i) / d)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                relache(e.getPointerId(i), e.getX(i) / d, e.getY(i) / d, false)
            }
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until e.pointerCount) relache(e.getPointerId(i), e.getX(i) / d, e.getY(i) / d, true)
                cibles.clear()
            }
        }
        return true
    }

    private fun appui(id: Int, x: Float, y: Float) {
        when (ecran) {
            Ecran.DEPART -> if (rectCommencer(r1).contains(x, y)) cibles[id] = "depart"
            Ecran.CHOIX -> {
                if (fini) return
                if (flecheRect(false, r1).contains(x, y)) { actif = (actif + 2) % 3; return }
                if (flecheRect(true, r1).contains(x, y)) { actif = (actif + 1) % 3; return }
                for (i in 0..2) if (carteRect(i, r1).contains(x, y)) {
                    if (actif != i) actif = i else valider()
                    return
                }
            }
            Ecran.FIN -> {
                rectsFin(r1, r2)
                if (r1.contains(x, y)) recharger(true)
                else if (r2.contains(x, y)) recharger(false)
            }
            Ecran.COMBAT -> {
                if (!ready) return
                when (cibleCombat(x, y)) {
                    "joy" -> { joyId = id; cibles[id] = "joy"; joyMove(x, y) }
                    "guard" -> { cibles[id] = "guard"; garde() }
                    "attack" -> doAttack()
                    "dodge" -> esquive()
                    "rudy" -> { cibles[id] = "rudy"; dragSx = x; dragSy = y; dragX0 = editX; dragY0 = editY }
                    else -> {}
                }
            }
            else -> {}
        }
    }

    private fun deplace(id: Int, x: Float, y: Float) {
        when (cibles[id]) {
            "joy" -> if (id == joyId && ready) joyMove(x, y)
            "rudy" -> if (ready) {
                editX = clampf(dragX0 + (x - dragSx) / W() * 100f, 5f, 95f)
                editY = clampf(dragY0 - (y - dragSy) / H() * 100f, 1.5f, 55f)
                gauche = editX
            }
        }
    }

    private fun relache(id: Int, x: Float, y: Float, annule: Boolean) {
        when (cibles.remove(id)) {
            "depart" -> if (!annule && ecran == Ecran.DEPART && rectCommencer(r1).contains(x, y)) begin()
            "joy" -> if (id == joyId) resetJoy()
            "guard" -> releaseGuard()
            "rudy" -> prefs.edit().putFloat("fighterX", editX).putFloat("fighterY", editY).apply()
        }
    }

    // =====================================================================
    // Dessin
    // =====================================================================

    override fun onDraw(c: Canvas) {
        val t = SystemClock.uptimeMillis()
        if (ecran == Ecran.CHARGEMENT || width == 0) {
            c.drawColor(Color.BLACK)
            pTexte.textSize = 16f * d; pTexte.letterSpacing = 0f; pTexte.color = Color.WHITE
            pTexte.clearShadowLayer()
            c.drawText(if (erreur != null) "PariBoxe : $erreur" else "Chargement\u2026", width / 2f, height / 2f, pTexte)
            if (enMarche && erreur == null) postInvalidateOnAnimation()
            return
        }
        logique(t)
        dessinerJeu(c, t)
        compte?.let { dessinerCompte(c, it) }
        when (ecran) {
            Ecran.DEPART -> dessinerDepart(c)
            Ecran.CHOIX -> dessinerChoix(c, t)
            Ecran.FIN -> dessinerFin(c, t)
            else -> {}
        }
        if (enMarche) postInvalidateOnAnimation()
    }

    private fun couvrir(c: Canvas, b: Bitmap, l: Float, tp: Float, w: Float, h: Float) {
        val s = max(w / b.width, h / b.height)
        val dw = b.width * s; val dh = b.height * s
        r1.set(l + (w - dw) / 2f, tp + (h - dh) / 2f, l + (w + dw) / 2f, tp + (h + dh) / 2f)
        c.save(); c.clipRect(l, tp, l + w, tp + h)
        c.drawBitmap(b, null, r1, pImage)
        c.restore()
    }

    private fun dessinerJeu(c: Canvas, t: Long) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(Color.BLACK)
        fond?.let { couvrir(c, it, 0f, 0f, w, h) }

        dessinerLabel(c)
        dessinerAmbiance(c, w, h)

        // Rudy
        rudy?.let { r ->
            val m = if (ready) mode else "idle"
            val f = if (ready) min(frame, max(0, nbR(m) - 1)) else 0
            val p = r.image(m, f) ?: r.image("idle", 0)
            if (p != null) dessinerPerso(c, p, boiteRudyL(), boiteRudyH(), W() * gauche / 100f,
                H() - H() * editY / 100f, echelle, noeudRudy)
        }

        dessinerFlash(c, t, w, h)

        // adversaire
        adversaire?.let { a ->
            val m = if (ready) em else "idle"
            val f = if (ready) min(ef, max(0, nbE(m) - 1)) else 0
            val p = a.image(m, f) ?: a.image("idle", 0)
            if (p != null) dessinerPerso(c, p, 520f, 760f, W() * ex / 100f,
                H() - H() * cpuBottom() / 100f, cpuScale(), noeudCpu)
        }

        dessinerHud(c, t)
        dessinerCommandes(c)
    }

    /** <img> object-fit:contain, object-position:center bottom, transform scale S depuis le bas-centre. */
    private fun dessinerPerso(c: Canvas, p: Planche, bw: Float, bh: Float, cx: Float, bas: Float, s: Float, noeud: RenderNode?) {
        val k = min(bw / p.largeur, bh / p.hauteur)
        val dw = p.largeur * k; val dh = p.hauteur * k
        val l = -dw / 2f + p.dx * k
        val tp = -dh + p.dy * k
        r2.set((cx + s * l) * d, (bas + s * tp) * d,
               (cx + s * (l + p.bmp.width * k)) * d, (bas + s * (tp + p.bmp.height * k)) * d)
        val dec = 9f * s * d
        val sigma = 2.5f * s * d
        if (Build.VERSION.SDK_INT >= 31 && noeud != null && c.isHardwareAccelerated) {
            ombreFloue(c, p.bmp, r2, dec, sigma, noeud)
        } else {
            r1.set(r2); r1.offset(0f, dec)
            c.drawBitmap(p.bmp, null, r1, pOmbre)
        }
        c.drawBitmap(p.bmp, null, r2, pPerso)
    }

    @android.annotation.TargetApi(31)
    private fun ombreFloue(c: Canvas, b: Bitmap, dst: RectF, dec: Float, sigma: Float, noeud: RenderNode) {
        val marge = sigma * 3f
        val l = (dst.left - marge).toInt()
        val tp = (dst.top + dec - marge).toInt()
        val w = (dst.width() + 2 * marge).toInt() + 2
        val h = (dst.height() + 2 * marge).toInt() + 2
        noeud.setPosition(l, tp, l + w, tp + h)
        val rc = noeud.beginRecording(w, h)
        r1.set(dst.left - l, dst.top + dec - tp, dst.right - l, dst.bottom + dec - tp)
        rc.drawBitmap(b, null, r1, pOmbre)
        noeud.endRecording()
        val rayon = max(0.1f, (sigma - 0.5f) / 0.57735f)
        noeud.setRenderEffect(RenderEffect.createBlurEffect(rayon, rayon, Shader.TileMode.DECAL))
        c.drawRenderNode(noeud)
    }

    private fun dessinerAmbiance(c: Canvas, w: Float, h: Float) {
        // vignettage (dessous), puis halo du ring (dessus)
        pRemp.color = Color.BLACK
        pRemp.shader = RadialGradient(0f, 0f, 1f,
            intArrayOf(0x00000000, 0x00000000, 0x6B000000), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            .also { matrice.setScale(1.2f * w, .9f * h); matrice.postTranslate(.5f * w, .45f * h); it.setLocalMatrix(matrice) }
        c.drawRect(0f, 0f, w, h, pRemp)
        pRemp.shader = RadialGradient(0f, 0f, 1f,
            intArrayOf(0x29FFC46E, 0x0DFFAA50, 0x00FFAA50, 0x00FFAA50), floatArrayOf(0f, .55f, .75f, 1f), Shader.TileMode.CLAMP)
            .also { matrice.setScale(.6f * w, .34f * h); matrice.postTranslate(.5f * w, .88f * h); it.setLocalMatrix(matrice) }
        c.drawRect(0f, 0f, w, h, pRemp)
        pRemp.shader = null
    }

    private fun dessinerFlash(c: Canvas, t: Long, w: Float, h: Float) {
        if (flashType == 0) return
        val duree = if (flashType == 1) 180f else 260f
        val p = (t - tFlash) / duree
        if (p >= 1f) { flashType = 0; return }
        val op = (if (flashType == 1) .55f else .7f) * (1f - easeOut(p))
        val g = if (flashType == 1)
            RadialGradient(0f, 0f, 1f, intArrayOf(0x8CFFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, .70f), Shader.TileMode.CLAMP)
                .also { matrice.setScale(.70f * w, .60f * h); matrice.postTranslate(.5f * w, .55f * h); it.setLocalMatrix(matrice) }
        else
            RadialGradient(0f, 0f, 1f, intArrayOf(0x99FF3C28.toInt(), 0x00FF3C28), floatArrayOf(0f, .72f), Shader.TileMode.CLAMP)
                .also { matrice.setScale(.75f * w, .65f * h); matrice.postTranslate(.5f * w, .55f * h); it.setLocalMatrix(matrice) }
        pRemp.color = Color.BLACK
        pRemp.shader = g
        pRemp.alpha = (op * 255).toInt().coerceIn(0, 255)
        c.drawRect(0f, 0f, w, h, pRemp)
        pRemp.shader = null
        pRemp.alpha = 255
    }

    /** ombre portee d'une forme a fond transparent : on ne la dessine qu'autour. */
    private fun ombreForme(c: Canvas, r: RectF, rayon: Float, dy: Float, flouCss: Float, couleur: Int) {
        chemin.reset(); chemin.addRoundRect(r, rayon, rayon, Path.Direction.CW)
        c.save()
        c.clipOutPath(chemin)
        pRemp.color = couleur
        pRemp.setShadowLayer(flou(flouCss), 0f, dy * d, couleur)
        c.drawPath(chemin, pRemp)
        pRemp.clearShadowLayer()
        pRemp.color = Color.BLACK
        c.restore()
    }

    private fun texteCentre(c: Canvas, s: String, x: Float, cy: Float, p: Paint) {
        val fm = p.fontMetrics
        c.drawText(s, x, cy - (fm.ascent + fm.descent) / 2f, p)
    }

    /** texte avec ses text-shadow CSS (liste du dessus vers le dessous) */
    private fun texteOmbre(c: Canvas, s: String, x: Float, cy: Float, couleur: Int, dures: List<Float>, floues: List<Pair<Float, Int>>) {
        for ((b, col) in floues.reversed()) {
            pTexte.color = col
            pTexte.setShadowLayer(flou(b), 0f, 0f, col)
            texteCentre(c, s, x, cy, pTexte)
        }
        pTexte.clearShadowLayer()
        for (dy in dures) { pTexte.color = Color.BLACK; texteCentre(c, s, x, cy + dy * d, pTexte) }
        pTexte.color = couleur
        texteCentre(c, s, x, cy, pTexte)
    }

    private fun dessinerLabel(c: Canvas) {
        val fs = if (petit()) 17f else 20f
        val top = if (petit()) 12f else 24f
        pTexte.textSize = fs * d; pTexte.letterSpacing = 2f / fs; pTexte.clearShadowLayer()
        val tw = pTexte.measureText(label) / d
        val bw = tw + 50f + 4f
        val bh = 1.15f * fs + 24f + 4f
        val l = (W() - bw) / 2f
        r1.set(l * d, top * d, (l + bw) * d, (top + bh) * d)
        ombreForme(c, r1, 16f * d, 4f, 14f, 0x99000000.toInt())
        pRemp.shader = LinearGradient(0f, r1.top, 0f, r1.bottom, 0xD1000000.toInt(), 0xD1121212.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(r1, 16f * d, 16f * d, pRemp)
        pRemp.shader = null
        pTrait.color = 0x8CFFE27A.toInt(); pTrait.strokeWidth = 2f * d
        r2.set(r1); r2.inset(d, d)
        c.drawRoundRect(r2, 15f * d, 15f * d, pTrait)
        pTexte.color = 0xFFFFE27A.toInt()
        texteCentre(c, label, r1.centerX(), r1.centerY(), pTexte)
    }

    private fun dessinerHud(c: Canvas, t: Long) {
        val hw = if (petit()) W() * .52f else min(W() * .58f, 780f)
        val left = (W() - hw) / 2f
        val top = if (petit()) 5f else 8f
        val side = min(hw * .46f, (hw - 70f) / 2f)
        val fs = vwClamp(11f, 1.5f, 19f)
        val lineH = 1.12f * fs
        val barH = if (petit()) 11f else 15f

        pTexte.textSize = fs * d; pTexte.letterSpacing = 1.5f / fs
        pTexte.textAlign = Paint.Align.LEFT
        texteOmbre(c, "RUDY", (left + 2f) * d, (top + lineH / 2f) * d, 0xFFFFE27A.toInt(),
            listOf(2f), listOf(8f to 0xE6000000.toInt()))
        pTexte.textAlign = Paint.Align.RIGHT
        texteOmbre(c, nomCpu, (left + hw - 2f - 1.5f) * d, (top + lineH / 2f) * d, 0xFFFFE27A.toInt(),
            listOf(2f), listOf(8f to 0xE6000000.toInt()))
        pTexte.textAlign = Paint.Align.CENTER

        barre(c, left, top + lineH, side, barH, barreRudy.valeur(t, this::easeOut), rudyHP)
        barre(c, left + hw - side, top + lineH, side, barH, barreCpu.valeur(t, this::easeOut), cpuHP)
    }

    private fun barre(c: Canvas, l: Float, tp: Float, w: Float, h: Float, pct: Float, cible: Float) {
        r1.set(l * d, tp * d, (l + w) * d, (tp + h) * d)
        val rad = 9f * d
        // ombre portee de la barre (filter drop-shadow du HUD)
        pRemp.color = 0xFF141414.toInt()
        pRemp.setShadowLayer(flou(5f), 0f, 3f * d, 0xCC000000.toInt())
        c.drawRoundRect(r1, rad, rad, pRemp)
        pRemp.clearShadowLayer()
        pRemp.shader = LinearGradient(0f, r1.top, 0f, r1.bottom, 0xFF141414.toInt(), 0xFF2A2A2A.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(r1, rad, rad, pRemp)
        pRemp.shader = null
        // interieur
        r2.set(r1); r2.inset(2f * d, 2f * d)
        val inner = RectF(r2)
        c.save()
        chemin.reset(); chemin.addRoundRect(inner, 7f * d, 7f * d, Path.Direction.CW)
        c.clipPath(chemin)
        // ombre interieure (inset 0 2px 4px)
        pRemp.shader = LinearGradient(0f, inner.top, 0f, inner.top + 4f * d, 0xB3000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(inner, pRemp)
        val cols = when {
            cible <= 25f -> intArrayOf(0xFFFF8A7A.toInt(), 0xFFC31C10.toInt(), 0xFF7A0D05.toInt())
            cible <= 55f -> intArrayOf(0xFFFFE066.toInt(), 0xFFE0A21A.toInt(), 0xFF8A5E05.toInt())
            else -> intArrayOf(0xFF7EF29A.toInt(), 0xFF19A64A.toInt(), 0xFF0B6B2E.toInt())
        }
        val fin = inner.left + inner.width() * clampf(pct, 0f, 100f) / 100f
        if (fin > inner.left) {
            pRemp.shader = LinearGradient(0f, inner.top, 0f, inner.bottom, cols, floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(inner.left, inner.top, fin, inner.bottom, pRemp)
            pRemp.shader = null
            pRemp.color = 0x59FFFFFF
            c.drawRect(inner.left, inner.top, fin, inner.top + 2f * d, pRemp)
        }
        pRemp.shader = null
        c.restore()
        pTrait.color = 0xFFF0E2B0.toInt(); pTrait.strokeWidth = 2f * d
        r2.set(r1); r2.inset(d, d)
        c.drawRoundRect(r2, 8f * d, 8f * d, pTrait)
    }

    private fun dessinerCommandes(c: Canvas) {
        // joystick
        val js = joyTaille(); val jc = .8f
        val cx = joyCx() * d; val cy = joyCy() * d
        pRemp.color = 0xCC080808.toInt()
        c.drawCircle(cx, cy, js / 2f * jc * d, pRemp)
        pTrait.color = 0xFF666666.toInt(); pTrait.strokeWidth = 5f * jc * d
        c.drawCircle(cx, cy, (js / 2f - 2.5f) * jc * d, pTrait)
        val st = if (petit()) 60f else 74f
        val sl = if (petit()) 38f else 53f
        val lx = 5f + sl + st / 2f - js / 2f + stickX
        val ly = 5f + sl + st / 2f - js / 2f + stickY
        pRemp.color = 0xFF242424.toInt()
        pRemp.setShadowLayer(flou(22f) * jc, 0f, 0f, Color.BLACK)
        c.drawCircle(cx + lx * jc * d, cy + ly * jc * d, st / 2f * jc * d, pRemp)
        pRemp.clearShadowLayer()

        // boutons
        for (b in commandes) {
            val dia = if (petit()) b.petitD else b.grand
            val bx = W() * b.px / 100f * d; val by = H() * b.py / 100f * d
            val rr = dia / 2f * b.ech * d
            pRemp.color = b.couleur
            c.drawCircle(bx, by, rr, pRemp)
            pTrait.color = 0xFF555555.toInt(); pTrait.strokeWidth = 5f * b.ech * d
            c.drawCircle(bx, by, rr - 2.5f * b.ech * d, pTrait)
            pTexte.textSize = b.police * b.ech * d; pTexte.letterSpacing = 0f
            pTexte.clearShadowLayer(); pTexte.color = Color.WHITE
            texteCentre(c, b.texte, bx, by, pTexte)
        }
    }

    private fun dessinerCompte(c: Canvas, s: String) {
        c.drawColor(0x1F000000)
        pTexte.textSize = vwClamp(90f, 22f, 260f) * d; pTexte.letterSpacing = 0f
        texteOmbre(c, s, width / 2f, height / 2f, 0xFFFFD800.toInt(),
            listOf(5f), listOf(16f to Color.BLACK, 32f to Color.BLACK))
    }

    /** bouton « pilule » du HTML : anneau colore, bord blanc, fond noir .82/.85 */
    private fun pilule(c: Canvas, r: RectF, texte: String, fs: Float, rayon: Float, anneau: Int, fondA: Int, flouOmbre: Float) {
        val px = RectF(r.left * d, r.top * d, r.right * d, r.bottom * d)
        chemin.reset(); chemin.addRoundRect(px, rayon * d, rayon * d, Path.Direction.CW)
        // les box-shadow ne se voient qu'autour du bouton
        c.save()
        c.clipOutPath(chemin)
        pRemp.color = Color.BLACK
        pRemp.setShadowLayer(flou(flouOmbre), 0f, 5f * d, Color.BLACK)
        c.drawPath(chemin, pRemp)
        pRemp.clearShadowLayer()
        r2.set(px); r2.inset(-3f * d, -3f * d)
        pRemp.color = anneau
        c.drawRoundRect(r2, (rayon + 3f) * d, (rayon + 3f) * d, pRemp)
        c.restore()
        // fond semi-transparent, puis bord blanc de 3 px
        pRemp.color = Color.argb(fondA, 0, 0, 0)
        c.drawPath(chemin, pRemp)
        pTrait.color = Color.WHITE; pTrait.strokeWidth = 3f * d
        r2.set(px); r2.inset(1.5f * d, 1.5f * d)
        c.drawRoundRect(r2, (rayon - 1.5f) * d, (rayon - 1.5f) * d, pTrait)
        pTexte.textSize = fs * d; pTexte.letterSpacing = 0f; pTexte.clearShadowLayer(); pTexte.color = Color.WHITE
        texteCentre(c, texte, px.centerX(), px.centerY(), pTexte)
    }

    private fun dessinerDepart(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(Color.BLACK)
        depart?.let { couvrir(c, it, 0f, 0f, w, h) }
        val r = rectCommencer(RectF())
        pilule(c, r, "COMMENCER", vwClamp(18f, 3f, 34f), 16f, 0xFFB40000.toInt(), 209, 18f)
    }

    private fun carteIn(t: Long, t0: Long, delai: Long): FloatArray {
        val p = ((t - t0 - delai) / 500f).coerceIn(0f, 1f)
        val e = bezier(.2f, .9f, .3f, 1.3f, p)
        return floatArrayOf(e.coerceIn(0f, 1f), 38f * (1f - e), .9f + .1f * e)
    }

    private fun dessinerChoix(c: Canvas, t: Long) {
        val w = width.toFloat(); val h = height.toFloat()
        val fondu = ease(((t - tChoix) / 450f).coerceIn(0f, 1f))
        val calque = if (fondu < 1f) c.saveLayerAlpha(0f, 0f, w, h, (fondu * 255).toInt()) else -1
        c.drawColor(Color.BLACK)
        val st = stage(RectF())
        val sp = RectF(st.left * d, st.top * d, st.right * d, st.bottom * d)
        choixImg?.let { c.drawBitmap(it, null, sp, pImage) }

        // flash blanc de validation (sous les cartes)
        if (fini) {
            val p = ((t - tValide) / 500f).coerceIn(0f, 1f)
            val op = .9f * (1f - easeOut(p))
            if (op > 0f) { pRemp.color = Color.argb((op * 255).toInt(), 255, 255, 255); c.drawRect(sp, pRemp) }
        }

        // portraits et plaques de Valor et Mody
        for (i in 1..2) {
            val an = carteIn(t, tChoix, 220)
            val art = arts[i]!!; val pl = plaques[i]!!
            val ra = RectF(st.left + st.width() * art[0] / 100f, st.top + st.height() * art[1] / 100f,
                st.left + st.width() * (art[0] + art[2]) / 100f, st.top + st.height() * (art[1] + art[3]) / 100f)
            val rp = RectF(st.left + st.width() * pl[0] / 100f, st.top + st.height() * pl[1] / 100f,
                st.left + st.width() * (pl[0] + pl[2]) / 100f, st.top + st.height() * (pl[1] + pl[3]) / 100f)
            for ((k, rr) in listOf(0 to ra, 1 to rp)) {
                c.save()
                c.translate(0f, an[1] * d)
                c.scale(an[2], an[2], rr.centerX() * d, rr.centerY() * d)
                val px = RectF(rr.left * d, rr.top * d, rr.right * d, rr.bottom * d)
                val a = (an[0] * 255).toInt()
                if (k == 0) {
                    r2.set(px); r2.inset(-2f * d, -2f * d)
                    pRemp.color = Color.argb((0.85f * a).toInt(), 0, 0, 0)
                    c.drawRoundRect(r2, 6f * d, 6f * d, pRemp)
                    pRemp.color = Color.argb(a, 11, 11, 11)
                    c.drawRoundRect(px, 4f * d, 4f * d, pRemp)
                    val ic = if (i == 1) iconeValor else iconeMody
                    ic?.let {
                        val s = min(px.width() / it.width, px.height() / it.height)
                        val dw = it.width * s; val dh = it.height * s
                        r2.set(px.centerX() - dw / 2f, px.centerY() - dh / 2f, px.centerX() + dw / 2f, px.centerY() + dh / 2f)
                        pImage.alpha = a
                        c.drawBitmap(it, null, r2, pImage)
                        pImage.alpha = 255
                    }
                } else {
                    pRemp.color = Color.argb(a, 11, 11, 11)
                    c.drawRoundRect(px, 4f * d, 4f * d, pRemp)
                    pTrait.color = Color.argb(a, 0xD8, 0xA4, 0x00); pTrait.strokeWidth = 2f * d
                    r2.set(px); r2.inset(d, d)
                    c.drawRoundRect(r2, 3f * d, 3f * d, pTrait)
                    val fs = vwClamp(11f, 1.6f, 22f)
                    pTexte.textSize = fs * d; pTexte.letterSpacing = 1f / fs; pTexte.clearShadowLayer()
                    pTexte.color = Color.argb(a, 0, 0, 0)
                    texteCentre(c, nomsCartes[i], px.centerX(), px.centerY() + 2f * d, pTexte)
                    pTexte.color = Color.argb(a, 0xFF, 0xD8, 0x00)
                    texteCentre(c, nomsCartes[i], px.centerX(), px.centerY(), pTexte)
                }
                c.restore()
            }
        }

        // cadre jaune qui pulse autour de la carte choisie
        run {
            val an = carteIn(t, tChoix, 100L + 120L * actif)
            val cr = carteRect(actif, RectF())
            val e = vaEtVient(t - tChoix, 850)
            val flouG = 12f + 18f * e
            val etal = 3f + 7f * e
            val alpha = .55f + .4f * e
            val sc = 1f + .02f * e
            c.save()
            c.translate(0f, an[1] * d)
            c.scale(an[2], an[2], cr.centerX() * d, cr.centerY() * d)
            c.scale(sc, sc, cr.centerX() * d, cr.centerY() * d)
            val fr = RectF((cr.left - 7f) * d, (cr.top - 7f) * d, (cr.right + 7f) * d, (cr.bottom + 7f) * d)
            val a = an[0]
            val jaune = Color.argb((alpha * a * 255).toInt(), 255, 216, 0)
            pTrait.color = jaune
            pTrait.strokeWidth = (2f * etal) * d
            pTrait.setShadowLayer(flou(flouG), 0f, 0f, jaune)
            c.drawRoundRect(fr, 9f * d, 9f * d, pTrait)
            pTrait.clearShadowLayer()
            pTrait.color = Color.argb((a * 255).toInt(), 255, 216, 0)
            pTrait.strokeWidth = 4f * d
            r2.set(fr); r2.inset(2f * d, 2f * d)
            c.drawRoundRect(r2, 7f * d, 7f * d, pTrait)
            c.restore()
        }

        if (!fini) {
            // fleches
            val fs = vwClamp(22f, 4f, 48f)
            val opF = .45f + .55f * vaEtVient(t, 1000)
            for (droite in listOf(false, true)) {
                val fr = flecheRect(droite, RectF())
                val gx = fr.left + 10f + .09f * fs
                val cy = fr.centerY() + .05f * fs
                val lw = .44f * fs; val lh = .69f * fs
                chemin.reset()
                if (droite) {
                    chemin.moveTo(gx * d, (cy - lh / 2f) * d); chemin.lineTo((gx + lw) * d, cy * d); chemin.lineTo(gx * d, (cy + lh / 2f) * d)
                } else {
                    chemin.moveTo((gx + lw) * d, (cy - lh / 2f) * d); chemin.lineTo(gx * d, cy * d); chemin.lineTo((gx + lw) * d, (cy + lh / 2f) * d)
                }
                chemin.close()
                val a = (opF * 255).toInt()
                pRemp.color = Color.argb(a, 0, 0, 0)
                pRemp.setShadowLayer(flou(12f), 0f, 0f, Color.argb(a, 0, 0, 0))
                c.drawPath(chemin, pRemp)
                pRemp.clearShadowLayer()
                c.save(); c.translate(0f, 3f * d); c.drawPath(chemin, pRemp); c.restore()
                pRemp.color = Color.argb(a, 255, 216, 0)
                c.drawPath(chemin, pRemp)
            }
            // astuce
            val fa = vwClamp(10f, 1.5f, 18f)
            val opA = .35f + .65f * vaEtVient(t, 1200)
            pTexte.textSize = fa * d; pTexte.letterSpacing = 0f
            val cyA = st.bottom - st.height() * .025f - .575f * fa
            val calA = c.saveLayerAlpha(0f, 0f, w, h, (opA * 255).toInt())
            pTexte.color = Color.BLACK; pTexte.setShadowLayer(flou(3f), 0f, 2f * d, Color.BLACK)
            texteCentre(c, "APPUIE SUR LE COMBATTANT POUR LE CHOISIR", st.centerX() * d, cyA * d, pTexte)
            pTexte.clearShadowLayer(); pTexte.color = Color.WHITE
            texteCentre(c, "APPUIE SUR LE COMBATTANT POUR LE CHOISIR", st.centerX() * d, cyA * d, pTexte)
            c.restoreToCount(calA)
        } else {
            // nom du combattant choisi
            val p = ((t - tValide) / 900f).coerceIn(0f, 1f)
            val (op, sc) = if (p < .35f) { val q = easeOut(p / .35f); q to (.6f + .52f * q) }
                           else { val q = easeOut((p - .35f) / .65f); 1f to (1.12f - .12f * q) }
            val fs = vwClamp(30f, 7f, 90f)
            val cx = st.centerX() * d; val cy = (st.top + st.height() * .22f) * d
            c.save(); c.scale(sc, sc, cx, cy)
            pTexte.textSize = fs * d; pTexte.letterSpacing = 0f
            val cal = c.saveLayerAlpha(0f, 0f, w, h, (op * 255).toInt())
            texteOmbre(c, nomChoisi, cx, cy, 0xFFFFD800.toInt(), listOf(5f), listOf(24f to Color.BLACK))
            c.restoreToCount(cal)
            c.restore()
        }
        if (calque >= 0) c.restoreToCount(calque)
    }

    private fun dessinerFin(c: Canvas, t: Long) {
        val w = width.toFloat(); val h = height.toFloat()
        val fondu = ease(((t - tFin) / 250f).coerceIn(0f, 1f))
        val calque = if (fondu < 1f) c.saveLayerAlpha(0f, 0f, w, h, (fondu * 255).toInt()) else -1
        c.drawColor(Color.BLACK)
        val st = stage(RectF())
        finImg?.let { c.drawBitmap(it, null, RectF(st.left * d, st.top * d, st.right * d, st.bottom * d), pImage) }
        val a = RectF(); val b = RectF()
        rectsFin(a, b)
        val fs = vwClamp(14f, 2.2f, 28f)
        val sc = 1f + .05f * vaEtVient(t - tFin, 900)
        for ((r, txt, col) in listOf(Triple(a, "REJOUER", 0xFFB40000.toInt()), Triple(b, "RETOUR", 0xFF087D20.toInt()))) {
            c.save(); c.scale(sc, sc, r.centerX() * d, r.centerY() * d)
            pilule(c, r, txt, fs, 14f, col, 217, 16f)
            c.restore()
        }
        if (calque >= 0) c.restoreToCount(calque)
    }
}
