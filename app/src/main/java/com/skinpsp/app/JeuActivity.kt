package com.skinpsp.app

import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Point
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.graphics.RectF
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import org.ppsspp.ppsspp.PpssppActivity

/**
 * L'ecran de jeu.
 *
 * ELLE HERITE de l'activite de PPSSPP au lieu de la remplacer.
 *
 * C'est tout le changement par rapport a la version precedente. Avant, j'avais
 * ecrit moi-meme le pont vers l'emulateur : creation du contexte graphique,
 * boucle d'images, relecture des pixels. C'est ce pont qui echouait, toujours
 * au meme endroit, et aucune de mes corrections ne l'a rattrape.
 *
 * Ici, PPSSPP fait tout ce qu'il sait faire — il demarre son moteur, cree son
 * contexte, dessine le jeu, gere ses menus, ses sauvegardes et ses triches. On
 * ne touche a rien de tout cela. On se contente de POSER l'habillage par-dessus
 * son image, et de lui envoyer les touches.
 *
 * L'habillage est une vue ordinaire ajoutee au-dessus de la sienne. Elle ne
 * dessine que les touches et le boitier ; le trou de l'ecran laisse voir le jeu
 * en dessous.
 */
class JeuActivity : PpssppActivity() {

    private var pad: PadView? = null

    /* Recompose immediatement la vue que PPSSPP vient d'installer. Les
       callbacks Surface/Vulkan sont livres ensuite par la boucle Android :
       le moteur voit donc directement les dimensions finales du cadre. */
    private fun installerPadEtCadre() {
        val view = trouverSurface(findViewById(android.R.id.content)) ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        val conteneur = FrameLayout(this)
        conteneur.addView(view, parametresCadre(calculerCadreInitial()))
        pad = PadView(this).also { vue ->
            vue.surCadreEcran = { cadre -> appliquerCadreJeu(cadre) }
            vue.surActionSysteme = { action -> executerActionSysteme(action) }
            conteneur.addView(vue, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.FILL
            ))
        }
        super.setContentView(conteneur)
    }

    override fun onCreate(bundle: Bundle?) {
        // AppCompat exige ce theme avant son propre onCreate.
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar)
        pleinEcranTotal()
        /*
         * Le filet AVANT tout le reste.
         *
         * En remplacant l'ancienne application j'ai supprime son journal, et
         * je me suis retrouve sans rien pour comprendre une fermeture. On note
         * donc l'erreur dans un fichier avant de laisser Android fermer, et on
         * l'affiche au lancement suivant. Le filet est pose AVANT
         * super.onCreate : c'est la que le demarrage du moteur peut echouer.
         */
        val precedent = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { fil, e ->
            try { noterChute(e) } catch (_: Throwable) {}
            precedent?.uncaughtException(fil, e)
        }

        /* Une chute notee au lancement precedent : on la montre AVANT
           d'essayer de redemarrer le moteur, au cas ou il retomberait. */
        /*
         * On ENTOURE le demarrage du moteur.
         *
         * Mon filet precedent notait bien l'erreur, mais ne la montrait qu'au
         * lancement suivant, dans onResume — c'est-a-dire jamais, puisque
         * l'application retombait avant d'y arriver. En l'attrapant ici, on
         * garde la main : on affiche le message au lieu de disparaitre.
         */
        try {
            // D'abord PPSSPP : il installe sa surface de rendu et demarre le moteur.
            super.onCreate(bundle)
            installerPadEtCadre()
        } catch (e: Throwable) {
            noterChute(e)
            afficherChute(decrire(e))
            return
        }

        pleinEcranTotal()
        pad?.post { pad?.let { appliquerCadreJeu(it.cadreEcran()) } }
    }

    /** Etend l'affichage sous la camera et sous les barres Android. */
    private fun pleinEcranTotal() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun trouverSurface(v: View): SurfaceView? {
        if (v is SurfaceView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) {
            trouverSurface(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** Meme calcul proportionnel que PadView, mais disponible avant son layout. */
    private fun calculerCadreInitial(): RectF {
        val bornes = if (android.os.Build.VERSION.SDK_INT >= 30) {
            windowManager.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            val taille = Point()
            windowManager.defaultDisplay.getRealSize(taille)
            android.graphics.Rect(0, 0, taille.x, taille.y)
        }
        val l = bornes.width().toFloat().coerceAtLeast(1f)
        val h = bornes.height().toFloat().coerceAtLeast(1f)
        val paysage = l >= h
        val sw = if (paysage) 1844f else 853f
        val sh = if (paysage) 853f else 1844f
        val sx = if (paysage) 320f else 48f
        val sy = if (paysage) 54f else 170f
        val ew = if (paysage) 1203f else 758f
        val eh = if (paysage) 633f else 706f
        val e = kotlin.math.max(l / sw, h / sh)
        val dx = (l - sw * e) / 2f
        val dy = (h - sh * e) / 2f
        return RectF(dx + sx * e, dy + sy * e, dx + (sx + ew) * e, dy + (sy + eh) * e)
    }

    private fun parametresCadre(cadre: RectF) = FrameLayout.LayoutParams(
        cadre.width().toInt().coerceAtLeast(1),
        cadre.height().toInt().coerceAtLeast(1)
    ).apply {
        leftMargin = cadre.left.toInt()
        topMargin = cadre.top.toInt()
    }

    /** Place l'image PPSSPP exactement dans l'ecran noir dessine sur le pad. */
    private fun appliquerCadreJeu(cadre: RectF) {
        val surface = trouverSurface(findViewById(android.R.id.content)) ?: return
        val parent = surface.parent as? ViewGroup ?: return
        val p = parametresCadre(cadre)
        if (parent is FrameLayout) surface.layoutParams = p
        surface.clipToOutline = true
        surface.requestLayout()
        try {
            surface.holder.setFixedSize(
                cadre.width().toInt().coerceAtLeast(1),
                cadre.height().toInt().coerceAtLeast(1)
            )
        } catch (_: Throwable) {}
        /* PPSSPP peut avoir conserve une taille fixe calculee avant le pad.
           On rend le buffer de nouveau dependant du layout : SurfaceChanged
           transmet alors immediatement la bonne taille au moteur. */
        surface.post {
            try {
                // Le buffer et la vue ont exactement la meme taille. Cela
                // evite le rendu tronque dans un coin apres une rotation.
                surface.holder.setFixedSize(
                    cadre.width().toInt().coerceAtLeast(1),
                    cadre.height().toInt().coerceAtLeast(1)
                )
            } catch (_: Throwable) {}
        }
    }

    private fun executerActionSysteme(action: String) {
        when (action) {
            "quit" -> finishAndRemoveTask()
            "jeux" -> {
                // Redemarre uniquement l'ecran PPSSPP sans raccourci de jeu :
                // il revient directement au navigateur du dossier de jeux.
                recreate()
            }
            // Retour est la commande officielle utilisee par PpssppActivity
            // pour afficher son menu (reglages et sauvegarder/charger).
            "menu", "cheat" -> onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun decrire(e: Throwable): String {
        val trace = StringWriter()
        e.printStackTrace(PrintWriter(trace))
        return trace.toString()
    }

    /**
     * Ecrit l'erreur a DEUX endroits.
     *
     * Le dossier interne n'est lisible que par l'application ; celui de la
     * carte, sous Android/data, s'ouvre depuis un explorateur de fichiers.
     * Si l'application ne se lance plus du tout, le second reste consultable.
     */
    private fun noterChute(e: Throwable) {
        val texte = "fil : " + Thread.currentThread().name + "\n\n" + decrire(e)
        for (dossier in listOf(filesDir, getExternalFilesDir(null))) {
            try { if (dossier != null) File(dossier, "chute.txt").writeText(texte) }
            catch (_: Throwable) {}
        }
    }

    /**
     * Affiche l'erreur, avec de quoi la copier.
     *
     * On tente d'abord une boite ; si l'activite est trop abimee pour en
     * porter une, on se rabat sur une vue de texte, qui ne demande presque
     * rien.
     */
    private fun afficherChute(texte: String) {
        try {
            AlertDialog.Builder(this)
                .setTitle("L'application s'est fermée")
                .setMessage(texte.take(4000))
                .setPositiveButton("Fermer", null)
                .setNeutralButton("Copier") { _, _ -> copier(texte) }
                .show()
        } catch (_: Throwable) {
            try {
                val vue = android.widget.TextView(this)
                vue.text = texte.take(4000)
                vue.setTextIsSelectable(true)
                vue.setPadding(24, 24, 24, 24)
                setContentView(android.widget.ScrollView(this).apply { addView(vue) })
                copier(texte)
            } catch (_: Throwable) {}
        }
    }

    private fun copier(texte: String) {
        try {
            val presse = getSystemService(CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            presse.setPrimaryClip(android.content.ClipData.newPlainText("chute", texte))
        } catch (_: Throwable) {}
    }

    /**
     * Montre l'erreur de la fois precedente, s'il y en a eu une.
     *
     * On l'affiche APRES le demarrage, et on efface le fichier une fois lu :
     * sinon la meme erreur reapparaitrait a chaque lancement, meme reparee.
     */
    private fun montrerChute() {
        val f = File(filesDir, "chute.txt")
        if (!f.isFile) return
        val texte = try { f.readText() } catch (_: Throwable) { return }
        f.delete()
        afficherChute(texte)
    }

    override fun onResume() {
        super.onResume()
        pleinEcranTotal()
        // L'orientation a pu changer pendant la mise en veille : on relit
        // l'habillage qui convient.
        pad?.relire()
        pad?.post { pad?.let { appliquerCadreJeu(it.cadreEcran()) } }
        montrerChute()
    }

    override fun onConfigurationChanged(nouvelle: Configuration) {
        super.onConfigurationChanged(nouvelle)
        pleinEcranTotal()
        pad?.relire()
        pad?.let { appliquerCadreJeu(it.cadreEcran()) }
        // Deuxieme mesure apres le layout Android, utile lorsque les dimensions
        // physiques sont inversees quelques millisecondes apres Configuration.
        pad?.post { pad?.let { appliquerCadreJeu(it.cadreEcran()) } }
    }
}
