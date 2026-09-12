package com.skingc.app

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.skingc.app.core.CoeurGC
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Contexte OpenGL sur lequel tourne le coeur, et affichage de son image.
 *
 * Deux chemins, selon ce que le coeur sait faire.
 *
 * Rendu materiel : le coeur dessine dans une texture, et on trace cette
 * texture directement a l'ecran, dans le cadre du skin. C'est ainsi que
 * procedent tous les emulateurs, et c'est ce qui permet de monter la
 * resolution interne : rien ne transite par le processeur.
 *
 * Rendu logiciel : le coeur nous remet des pixels, on les relit et le skin les
 * dessine. Simple et sur, mais limite a la resolution d'origine — relire une
 * image de 1920 x 1440 soixante fois par seconde demanderait 664 megaoctets
 * par seconde, ce qu'aucun telephone ne suivrait.
 */
class VueGL(ctx: Context, private val coeur: CoeurGC) : GLSurfaceView(ctx) {

    @Volatile var boutons = 0
    @Volatile var stickX = 0f
    @Volatile var stickY = 0f
    @Volatile var stickCX = 0f
    @Volatile var stickCY = 0f
    @Volatile var avanceRapide = false

    /** Rectangle de l'ecran de jeu, en pixels de la vue. Pose par le skin. */
    @Volatile var cadre = floatArrayOf(0f, 0f, 1f, 1f)
    @Volatile var lissage = true

    /** Cadence visee ; zero signifie celle de la console. */
    @Volatile var cadenceCible = 0
    /** Resolution interne demandee, de 1 a 8. */
    @Volatile var qualite = 1
        set(v) { field = v.coerceIn(1, 8); tailleAChanger = true }
    private var tailleAChanger = true

    /** Images emulees par seconde, mesurees. */
    @Volatile var cadenceMesuree = 0.0

    var surSon: ((ShortArray, Int) -> Unit)? = null
    /** Appele apres chaque image relue, en rendu logiciel uniquement. */
    var surImage: (() -> Unit)? = null
    var surJournal: ((String) -> Unit)? = null
    /**
     * Appele a chaque image emulee, quel que soit le mode de rendu.
     * [surImage] ne concerne que la relecture : s'y fier laissait croire que
     * le rendu materiel n'avait jamais tenu.
     */
    var surImageEmise: (() -> Unit)? = null
    /** true des que le coeur a annonce qu'il dessinait lui-meme en OpenGL. */
    @Volatile var rendulMateriel = false
        private set

    private val rendu = Rendu()

    /**
     * Ajuste le tampon de rendu tout de suite, sur le fil OpenGL.
     *
     * A appeler AVANT de charger un jeu quand la finesse a change. Sinon le
     * tampon est redimensionne au premier tour d'affichage, c'est-a-dire une
     * fois le jeu deja lance — et le coeur doit alors refaire toutes ses
     * ressources graphiques en pleine partie, ce qui la fait deraper.
     */
    fun ajusterTampon() = rendu.ajusterTampon()

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 8)
        preserveEGLContextOnPause = true
        setRenderer(rendu)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun surFilGl(action: () -> Unit) = queueEvent(action)

    private inner class Rendu : GLSurfaceView.Renderer {

        private var prog = 0
        private var aPos = 0
        private var aTex = 0
        private var uTex = 0
        private var vueL = 1
        private var vueH = 1
        private var pret = false
        private var premiere = true
        private var tracesFaites = 0
        private var fboL = 0
        private var fboH = 0

        private var dernierNs = 0L
        private var reste = 0.0
        private var imagesComptees = 0
        private var compteDepuis = 0L

        private val sommets = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

        private val VERT = """
            attribute vec2 aPos; attribute vec2 aTex; varying vec2 vTex;
            void main() { vTex = aTex; gl_Position = vec4(aPos, 0.0, 1.0); }
        """
        private val FRAG = """
            precision mediump float; varying vec2 vTex; uniform sampler2D uTex;
            void main() { gl_FragColor = texture2D(uTex, vTex); }
        """

        private fun compiler(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            return s
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, compiler(GLES20.GL_VERTEX_SHADER, VERT))
            GLES20.glAttachShader(prog, compiler(GLES20.GL_FRAGMENT_SHADER, FRAG))
            GLES20.glLinkProgram(prog)
            aPos = GLES20.glGetAttribLocation(prog, "aPos")
            aTex = GLES20.glGetAttribLocation(prog, "aTex")
            uTex = GLES20.glGetUniformLocation(prog, "uTex")

            val (w, h) = tailleInterne()
            pret = coeur.glInit(w, h)
            fboL = w; fboH = h
            tailleAChanger = false
            surJournal?.invoke("contexte OpenGL pret : " + (if (pret) "oui" else "NON"))
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            vueL = width; vueH = height
        }

        /**
         * Taille du tampon de rendu.
         *
         * Elle doit couvrir ce que le coeur dessine, sans plus. Je multipliais
         * sa geometrie maximale par le facteur de qualite, alors qu'elle tient
         * deja compte de la resolution demandee : a 1600 x 1200 et au facteur
         * cinq, cela reclamait un tampon de 8000 x 6000, soit cent quatre-vingts
         * megaoctets pour rien.
         */
        /**
         * Ne fait plus que lever l'intention.
         *
         * C'est le pont qui dimensionne le tampon au chargement, d'apres la
         * taille que le coeur annonce lui-meme. Le faire ici revenait a
         * deviner avant que le coeur ait parle : le tampon restait a la taille
         * de la partie precedente, le coeur ne remplissait qu'un coin, et
         * c'est ce coin qu'on affichait en plein ecran.
         */
        fun ajusterTampon() {
            tailleAChanger = false
        }

        private fun tailleInterne(): Pair<Int, Int> {
            var w = coeur.maxLargeur
            var h = coeur.maxHauteur
            if (w <= 0 || h <= 0) {
                val q = qualite.coerceIn(1, 8)
                w = 640 * q; h = 480 * q
            }
            return Pair(w.coerceIn(320, 4096), h.coerceIn(240, 4096))
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (!pret) return

            tailleAChanger = false

            val ns = System.nanoTime()
            if (dernierNs == 0L) dernierNs = ns
            var ecoule = (ns - dernierNs).toDouble()
            dernierNs = ns
            val visee = if (cadenceCible > 0) cadenceCible.toDouble()
                        else if (coeur.imagesParSeconde > 10) coeur.imagesParSeconde else 60.0
            val nsParImage = 1_000_000_000.0 / visee
            if (ecoule > 250_000_000.0) ecoule = nsParImage
            reste += ecoule

            val marge = nsParImage * 0.1
            val tours = when {
                avanceRapide -> 3
                reste >= nsParImage - marge -> 1
                else -> 0
            }
            if (tours == 0) return
            reste -= nsParImage * tours
            if (reste < 0.0) reste = 0.0
            if (reste > nsParImage) reste = nsParImage

            var tex = 0
            repeat(tours) {
                tex = coeur.glImage(boutons, stickX, stickY, stickCX, stickCY, 0f, 0f)
                val n = coeur.son()
                if (n > 0 && !avanceRapide) surSon?.invoke(coeur.echantillons, n)
            }

            imagesComptees += tours
            if (ns - compteDepuis > 1_000_000_000L) {
                cadenceMesuree = imagesComptees * 1e9 / (ns - compteDepuis)
                imagesComptees = 0; compteDepuis = ns
            }
            if (tex == 0) return
            surImageEmise?.invoke()

            rendulMateriel = coeur.rendulMateriel
            if (premiere) {
                premiere = false
                surJournal?.invoke("premiere image : " + coeur.taille[0] + "x" + coeur.taille[1] +
                    ", moteur " + (if (rendulMateriel) "matériel" else "logiciel") +
                    ", affichage par relecture")
            }

            // Un seul chemin d'affichage : on relit l'image et le skin la
            // dessine. Le trace direct en OpenGL, sous le skin, serait plus
            // economique — mais il ne s'affiche pas sur cet appareil, et je
            // l'ai tente deux fois sans y parvenir. Mieux vaut une methode qui
            // marche.
            //
            // Le coeur, lui, continue de dessiner en materiel : c'est ce qui
            // permet de monter la resolution interne. Seul l'affichage change.
            if (coeur.lireImage()) surImage?.invoke()
        }

    }
}
