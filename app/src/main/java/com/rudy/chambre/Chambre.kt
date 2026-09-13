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

    /** Nombre d'ecrans de l'application actuellement visibles. */
    private var ecransVisibles = 0

    /** Vrai tant qu'au moins un ecran de l'application est a l'ecran. */
    val enAvantPlan: Boolean get() = ecransVisibles > 0

    override fun onCreate() {
        super.onCreate()

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
