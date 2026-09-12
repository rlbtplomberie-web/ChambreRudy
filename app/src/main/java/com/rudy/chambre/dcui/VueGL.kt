package com.rudy.chambre.dcui

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.rudy.chambre.dcui.core.CoeurDc
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Contexte OpenGL sur lequel tourne Flycast.
 *
 * Cette vue ne dessine rien a l'ecran. Elle n'existe que pour fournir un
 * contexte graphique au coeur, qui en exige un, et pour porter le fil sur
 * lequel toute l'emulation s'execute — un contexte n'appartient qu'a un seul
 * fil.
 *
 * L'image rendue est relue puis remise a l'application, qui la dessine
 * elle-meme dans le skin. Compter sur la visibilite de cette surface, posee
 * derriere la fenetre, s'est revele illusoire : cela depend du theme, du
 * format de la fenetre et du fabricant.
 */
class VueGL(ctx: Context, private val coeur: CoeurDc) : GLSurfaceView(ctx) {

    @Volatile var boutons = 0
    @Volatile var stickX = 0f
    @Volatile var stickY = 0f
    @Volatile var gachetteL = 0f
    @Volatile var gachetteR = 0f
    @Volatile var avanceRapide = false

    /**
     * Cadence visee, en images par seconde. Zero signifie celle de la console,
     * annoncee par le coeur — 59,95 sur Dreamcast.
     *
     * Une console a une cadence fixe : la changer change la vitesse du jeu.
     * Trente images le font tourner a la moitie de sa vitesse, cent vingt au
     * double. C'est un reglage de vitesse, pas de fluidite.
     */
    @Volatile var cadenceCible = 0

    /** Resolution interne demandee, de 1 a 8. */
    @Volatile var qualite = 1
        set(v) { field = v.coerceIn(1, 8); tailleAChanger = true }
    private var tailleAChanger = true

    var surSon: ((ShortArray, Int) -> Unit)? = null
    /** Images emulees par seconde, mesurees : sert au diagnostic. */
    @Volatile var cadenceMesuree = 0.0
    /** Appele apres chaque image relue, sur le fil OpenGL. */
    var surImage: (() -> Unit)? = null
    var surJournal: ((String) -> Unit)? = null

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 8)
        preserveEGLContextOnPause = true
        setRenderer(Rendu())
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /**
     * Execute une action sur le fil OpenGL, contexte courant. Indispensable
     * pour charger un jeu, reinitialiser, sauver ou restaurer un etat :
     * Flycast cree ses ressources graphiques a ces moments-la.
     */
    fun surFilGl(action: () -> Unit) = queueEvent(action)

    private inner class Rendu : GLSurfaceView.Renderer {

        private var pret = false
        private var premiere = true
        private var fboL = 0
        private var fboH = 0

        /* Cadence.
         *
         * Cette methode est appelee a chaque rafraichissement de l'ecran, qui
         * peut tourner a 90 ou 120 Hz. Faire avancer la console a ce rythme la
         * fait tourner au double ou au triple de sa vitesse : d'ou le jeu en
         * avance rapide permanente. On accumule donc le temps ecoule et on
         * n'avance que du nombre d'images que la console aurait produites. */
        private var dernierNs = 0L
        private var dernierNs2 = 0L
        private var reste = 0.0
        private var imagesComptees = 0
        private var compteDepuis = 0L

        /* Chronometrage.
         *
         * Un a-coup regulier ne se voit pas dans un journal d'evenements : il
         * faut mesurer. On releve donc la duree de chaque etape et, toutes les
         * trois secondes, on note la moyenne et surtout le pire cas — c'est
         * lui qui se sent. */
        private var nEmul = 0
        private var tEmul = 0L
        private var pireEmul = 0L
        private var tAudio = 0L
        private var pireAudio = 0L
        private var tLecture = 0L
        private var pireLecture = 0L
        private var tDessin = 0L
        private var pireDessin = 0L
        private var pireIntervalle = 0L
        private var bilanDepuis = 0L
        private var imagesSautees = 0

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            val (w, h) = tailleInterne()
            pret = coeur.glInit(w, h)
            fboL = w; fboH = h
            tailleAChanger = false
            surJournal?.invoke("contexte OpenGL pret : " + (if (pret) "oui" else "NON"))
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        /**
         * Taille du tampon de rendu. Elle doit couvrir ce que le coeur dessine
         * reellement : Flycast annonce jusqu'a 853 pixels de large pour un jeu
         * en seize neuviemes, et un tampon plus petit tronquerait l'image.
         */
        private fun tailleInterne(): Pair<Int, Int> {
            val q = qualite.coerceIn(1, 8)
            var w = 640 * q
            var h = 480 * q
            val mw = coeur.maxLargeur
            val mh = coeur.maxHauteur
            if (mw > 0) w = maxOf(w, mw)
            if (mh > 0) h = maxOf(h, mh)
            return Pair(w, h)
        }

        /** Note un bilan chiffre toutes les trois secondes. */
        private fun bilanEventuel(ns: Long) {
            if (bilanDepuis == 0L) { bilanDepuis = ns; return }
            if (ns - bilanDepuis < 3_000_000_000L || nEmul == 0) return
            fun ms(v: Long) = v / 1_000_000.0
            surJournal?.invoke(
                "cadence %.1f i/s | coeur moy %.1f pire %.1f | audio moy %.1f pire %.1f | lecture moy %.1f pire %.1f | dessin moy %.1f pire %.1f | pire intervalle %.1f"
                    .format(cadenceMesuree, ms(tEmul / nEmul), ms(pireEmul),
                            ms(tAudio / nEmul), ms(pireAudio),
                            ms(tLecture / nEmul), ms(pireLecture),
                            ms(tDessin / nEmul), ms(pireDessin),
                            ms(pireIntervalle)))
            nEmul = 0; tEmul = 0; pireEmul = 0; tAudio = 0; pireAudio = 0
            tLecture = 0; pireLecture = 0
            tDessin = 0; pireDessin = 0
            pireIntervalle = 0; imagesSautees = 0
            bilanDepuis = ns
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (!pret) return

            val (bw, bh) = tailleInterne()
            if (tailleAChanger || bw != fboL || bh != fboH) {
                coeur.glTaille(bw, bh)
                fboL = bw; fboH = bh
                tailleAChanger = false
                surJournal?.invoke("tampon de rendu ajuste a " + bw + " x " + bh)
            }

            val ns = System.nanoTime()
            if (dernierNs == 0L) dernierNs = ns
            var ecoule = (ns - dernierNs).toDouble()
            dernierNs = ns
            val visee = if (cadenceCible > 0) cadenceCible.toDouble()
                        else if (coeur.imagesParSeconde > 10) coeur.imagesParSeconde
                        else 60.0
            val nsParImage = 1_000_000_000.0 / visee
            if (ecoule > 250_000_000.0) ecoule = nsParImage   // retour d'arriere-plan
            reste += ecoule

            // L'ecriture audio est bloquante : c'est elle qui cale reellement
            // la cadence. Ce compte a rebours ne sert plus que de filet, pour
            // ne jamais depasser la vitesse nominale.
            // Une marge d'un dixieme d'image evite de sauter un tour quand le
            // rafraichissement de l'ecran et celui de la console sont presque
            // en phase : sans elle, la cadence tombait a cinquante images par
            // seconde au lieu de soixante.
            val marge = nsParImage * 0.1
            val tours = when {
                avanceRapide -> 3
                reste >= nsParImage - marge -> 1
                else -> 0
            }
            if (ns - dernierNs2 > pireIntervalle && dernierNs2 != 0L) pireIntervalle = ns - dernierNs2
            dernierNs2 = ns
            if (tours == 0) { imagesSautees++; bilanEventuel(ns); return }
            reste -= nsParImage * tours
            if (reste < 0.0) reste = 0.0
            if (reste > nsParImage) reste = nsParImage

            var tex = 0
            var dureeCoeur = 0L
            var dureeAudio = 0L
            repeat(tours) {
                val a0 = System.nanoTime()
                tex = coeur.glImage(boutons, stickX, stickY, 0f, 0f, gachetteL, gachetteR)
                val n = coeur.son()
                val a1 = System.nanoTime()
                if (n > 0 && !avanceRapide) surSon?.invoke(coeur.echantillons, n)
                val a2 = System.nanoTime()
                dureeCoeur += a1 - a0
                dureeAudio += a2 - a1
            }
            nEmul++
            tEmul += dureeCoeur
            if (dureeCoeur > pireEmul) pireEmul = dureeCoeur
            tAudio += dureeAudio
            if (dureeAudio > pireAudio) pireAudio = dureeAudio
            imagesComptees += tours
            if (ns - compteDepuis > 1_000_000_000L) {
                cadenceMesuree = imagesComptees * 1e9 / (ns - compteDepuis)
                imagesComptees = 0; compteDepuis = ns
            }
            if (tex == 0) return

            if (premiere) {
                premiere = false
                surJournal?.invoke("premiere image rendue, utile " +
                    coeur.taille[0] + "x" + coeur.taille[1])
            }
            val t2 = System.nanoTime()
            val ok = coeur.lireImage()
            val t3 = System.nanoTime()
            tLecture += t3 - t2
            if (t3 - t2 > pireLecture) pireLecture = t3 - t2
            if (ok) {
                surImage?.invoke()
                val t4 = System.nanoTime()
                tDessin += t4 - t3
                if (t4 - t3 > pireDessin) pireDessin = t4 - t3
            }
            bilanEventuel(System.nanoTime())
        }
    }
}
