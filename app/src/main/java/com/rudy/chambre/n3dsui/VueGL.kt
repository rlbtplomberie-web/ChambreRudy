package com.rudy.chambre.n3dsui

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.rudy.chambre.n3dsui.core.Coeur3DS
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
class VueGL(ctx: Context, private val coeur: Coeur3DS) : GLSurfaceView(ctx) {

    @Volatile var boutons = 0
    @Volatile var stickX = 0f
    @Volatile var stickY = 0f
    @Volatile var stickCX = 0f
    @Volatile var stickCY = 0f
    @Volatile var avanceRapide = false

    /**
     * Rectangles des deux ecrans, en pixels de la vue. Poses par le skin.
     * Un habillage a un seul ecran laisse l'autre a null.
     */
    @Volatile var cadreHaut: FloatArray? = null
    @Volatile var cadreBas: FloatArray? = null
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
    /** true des que le coeur a annonce qu'il dessinait lui-meme en OpenGL. */
    @Volatile var rendulMateriel = false
        private set

    private val rendu = Rendu()

    /**
     * Ajuste le tampon de rendu tout de suite, sur le fil OpenGL.
     *
     * A appeler AVANT de charger un jeu quand la finesse a change : sinon le
     * redimensionnement tombe une fois la partie lancee, et le coeur doit
     * refaire toutes ses ressources graphiques en pleine action.
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
        fun ajusterTampon() {
            val (w, h) = tailleInterne()
            if (w != fboL || h != fboH) {
                coeur.glTaille(w, h)
                fboL = w; fboH = h
                surJournal?.invoke("tampon ajusté à " + w + " x " + h + " avant chargement")
            }
            tailleAChanger = false
        }

        private fun tailleInterne(): Pair<Int, Int> {
            // Deux ecrans de 256 x 192 empiles, multiplies par la resolution
            // interne choisie.
            var w = coeur.maxLargeur
            var h = coeur.maxHauteur
            if (w <= 0 || h <= 0) {
                val q = qualite.coerceIn(1, 8)
                w = 256 * q; h = 384 * q
            }
            return Pair(w.coerceIn(256, 4096), h.coerceIn(192, 4096))
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (!pret) return

            val (bw, bh) = tailleInterne()
            if (tailleAChanger && (bw != fboL || bh != fboH)) {
                coeur.glTaille(bw, bh)
                fboL = bw; fboH = bh
                surJournal?.invoke("tampon de rendu ajuste a " + bw + " x " + bh)
            }
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

            rendulMateriel = coeur.rendulMateriel
            if (premiere) {
                premiere = false
                surJournal?.invoke("premiere image : " + coeur.taille[0] + "x" + coeur.taille[1] +
                    ", rendu " + (if (rendulMateriel) "matériel, tracé en OpenGL"
                                  else "logiciel, relu puis dessiné"))
            }

            if (rendulMateriel) {
                // Le coeur nous rend une image ou les deux ecrans sont empiles.
                // On la coupe en deux et on pose chaque moitie dans son
                // rectangle : c'est ce qui permet les cinq presentations sans
                // rien demander au coeur.
                remettreEtatAPlat()
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, vueL, vueH)
                cadreHaut?.let { dessiner(tex, it, true) }
                cadreBas?.let { dessiner(tex, it, false) }
            } else if (coeur.lireImage()) {
                surImage?.invoke()
            }
        }

        /**
         * Remet l'etat OpenGL a plat avant de tracer.
         *
         * Le coeur laisse derriere lui son propre etat, et surtout un tampon
         * de sommets encore lie : nos sommets vivant en memoire ordinaire, le
         * trace ne donnerait rien tant que ce tampon reste en place.
         */
        private fun remettreEtatAPlat() {
            GLES30.glBindVertexArray(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthMask(false)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glDisable(GLES20.GL_STENCIL_TEST)
            GLES20.glColorMask(true, true, true, true)
            for (i in 0 until 8) GLES20.glDisableVertexAttribArray(i)
        }

        /** Trace une moitie de l'image du coeur dans le rectangle donne. */
        private fun dessiner(tex: Int, cadre: FloatArray, haut: Boolean) {
            val bw = coeur.taille[0].coerceAtLeast(1)
            val bh = coeur.taille[1].coerceAtLeast(1)
            val su = (bw.toFloat() / fboL).coerceIn(0f, 1f)
            val sv = (bh.toFloat() / fboH).coerceIn(0f, 1f)
            // moitie haute ou basse de la portion utile de la texture
            val v0 = if (haut) 0f else sv / 2f
            val v1 = if (haut) sv / 2f else sv

            val cl = cadre[0]; val ct = cadre[1]; val cr = cadre[2]; val cb = cadre[3]
            // L'ecran remplit son rectangle : sa taille se regle dans
            // l'editeur de disposition, pas au fil du jeu.
            val w = cr - cl
            val h = cb - ct
            val cx = (cl + cr) / 2f; val cy = (ct + cb) / 2f
            val x0 = cx - w / 2; val x1 = cx + w / 2
            val y0 = cy - h / 2; val y1 = cy + h / 2

            fun nx(v: Float) = v / vueL * 2f - 1f
            fun ny(v: Float) = 1f - v / vueH * 2f

            // v inverse : l'origine d'OpenGL est en bas, celle de l'ecran en haut
            val d = floatArrayOf(
                nx(x0), ny(y1), 0f, v0,
                nx(x1), ny(y1), su, v0,
                nx(x0), ny(y0), 0f, v1,
                nx(x1), ny(y0), su, v1)
            sommets.position(0); sommets.put(d); sommets.position(0)

            GLES20.glUseProgram(prog)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            val filtre = if (lissage) GLES20.GL_LINEAR else GLES20.GL_NEAREST
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filtre)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filtre)
            GLES20.glUniform1i(uTex, 0)
            sommets.position(0)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, sommets)
            sommets.position(2)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, sommets)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aTex)
        }
    }
}
