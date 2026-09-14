package com.rudy.chambre

import android.app.Application
import java.io.File

/**
 * Capte les plantages de n'importe quel ecran de l'application et les ecrit
 * dans un fichier, que la chambre sait relire.
 *
 * Chaque emulateur a bien son propre journal, mais il ne sert a rien quand
 * l'arret survient avant que ce journal soit pret. Celui-ci est en place des
 * le demarrage de l'application, donc toujours a temps.
 */
class Chambre : Application() {

    /**
     * Les classes d'application des emulateurs, dans l'ordre ou elles doivent
     * s'ouvrir. Chacune est absente si son emulateur n'est pas dans cet APK,
     * et l'on passe alors simplement a la suivante.
     */
    private val applicationsEmulateurs: List<String>
        get() {
            // la liste relevee pendant la compilation, dans leurs manifestes
            val relevees = try {
                assets.open("chambre/applications_emulateurs.txt")
                    .bufferedReader().readLines()
                    .map { it.trim() }.filter { it.isNotEmpty() }
            } catch (_: Throwable) { emptyList() }
            // et, en secours, les deux que l'on connait de nom
            val connues = listOf(
                "paulscode.android.mupen64plusae.AppMupen64Plus",
                "org.dolphinemu.dolphinemu.DolphinApplication"
            )
            return (relevees + connues).distinct()
        }

    private fun reveillerLesEmulateurs() {
        for (nom in applicationsEmulateurs) {
            try {
                val classe = Class.forName(nom)
                val appli = classe.getDeclaredConstructor().newInstance() as Application

                // lui donner le contexte, comme Android le fait avant onCreate
                val poser = android.content.ContextWrapper::class.java
                    .getDeclaredMethod("attachBaseContext", android.content.Context::class.java)
                poser.isAccessible = true
                poser.invoke(appli, this)

                appli.onCreate()
                noterDemarrage("application reveillee : " + nom)
            } catch (_: ClassNotFoundException) {
                // cet emulateur n'est pas dans cet APK : rien a faire
            } catch (e: Throwable) {
                noterDemarrage("echec du reveil de " + nom + " : " + e.toString().take(120))
            }
        }
    }

    /** Une ligne dans le journal que la chambre sait relire. */
    private fun noterDemarrage(texte: String) {
        try {
            val d = java.io.File(filesDir, "systeme").apply { mkdirs() }
            java.io.File(d, "journal_appli.txt").appendText(texte + "\n")
        } catch (_: Throwable) {}
    }

    /** Nombre d'ecrans de l'application actuellement visibles. */
    private var ecransVisibles = 0

    /** Vrai tant qu'au moins un ecran de l'application est a l'ecran. */
    val enAvantPlan: Boolean get() = ecransVisibles > 0

    override fun onCreate() {
        super.onCreate()

        /*
         * Reveiller les applications des emulateurs.
         *
         * Mupen64Plus et Dolphin declarent chacun leur propre classe
         * d'application — AppMupen64Plus, DolphinApplication. C'est elle qui,
         * avant tout le reste, prepare leurs chemins, charge leurs
         * bibliotheques natives et installe leurs reglages. Quand ils sont une
         * application a part, Android la lance tout seul.
         *
         * Ici, c'est la Chambre qui est l'application : la leur ne serait
         * jamais executee, et l'emulateur demarrerait sans rien de tout cela.
         * On la lance donc nous-memes, exactement comme Android le ferait :
         * on la cree, on lui donne le contexte, puis on l'ouvre.
         */
        reveillerLesEmulateurs()

        // On suit les ecrans qui s'ouvrent et se ferment : la radio doit
        // continuer quand on passe de la chambre a un jeu, et ne s'arreter
        // que si l'application entiere passe en arriere-plan.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) { ecransVisibles++ }
            override fun onActivityStopped(a: android.app.Activity) {
                ecransVisibles = (ecransVisibles - 1).coerceAtLeast(0)
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        val precedent = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { fil, e ->
            try {
                val texte = StringBuilder()
                texte.append(java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.FRANCE)
                        .format(java.util.Date())).append('\n')
                texte.append(e.toString()).append('\n')
                var cause: Throwable? = e.cause
                var profondeur = 0
                while (cause != null && profondeur < 3) {
                    texte.append("cause : ").append(cause.toString()).append('\n')
                    cause = cause.cause; profondeur++
                }
                e.stackTrace.take(14).forEach { texte.append("  ").append(it).append('\n') }
                File(filesDir, "dernier_plantage.txt").writeText(texte.toString())
            } catch (_: Throwable) {}
            precedent?.uncaughtException(fil, e)
        }
    }
}
