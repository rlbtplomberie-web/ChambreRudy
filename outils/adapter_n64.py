"""
Transforme le projet Mupen64Plus en morceau de la Chambre.

Son fichier de compilation est écrit pour une application autonome : il déclare
un identifiant d'application et renomme ses APK. Dans notre projet il devient
une bibliothèque, et ces passages n'ont plus de sens — on les retire.
"""
import re
import sys


def retirer_bloc(texte: str, mot: str) -> str:
    """Retire un bloc entier, accolades comprises, partout où le mot apparaît."""
    i = texte.find(mot)
    while i != -1:
        ouvrante = texte.find('{', i)
        if ouvrante == -1:
            break
        profondeur, k = 0, ouvrante
        while k < len(texte):
            if texte[k] == '{':
                profondeur += 1
            elif texte[k] == '}':
                profondeur -= 1
                if profondeur == 0:
                    break
            k += 1
        debut = texte.rfind('\n', 0, i) + 1
        texte = texte[:debut] + texte[k + 1:]
        i = texte.find(mot)
    return texte


def adapter_compilation(chemin: str) -> None:
    t = open(chemin, encoding='utf-8').read()

    # le fichier racine ne declare pas de module : on n'y touche qu'aux
    # valeurs partagees, sans le transformer en bibliotheque
    if 'android {' not in t and 'ext' in t:
        t = aligner_version_minimale(t)
        t = retirer_sucrage(t)
        open(chemin, 'w', encoding='utf-8').write(t)
        print('valeurs partagees alignees :', chemin)
        return

    t = t.replace('com.android.application', 'com.android.library')
    # tous les reglages qui n'ont de sens que pour une application autonome
    # les plus longs d'abord : « applicationIdSuffix » contient « applicationId »
    for mot in ('applicationIdSuffix', 'versionNameSuffix', 'testApplicationId',
                'applicationId', 'versionCode', 'versionName'):
        # en debut de ligne
        t = re.sub(r'^\s*' + mot + r'\s*=?\s*[^\n]*$', '', t, flags=re.M)
        # ou glisse dans un bloc ecrit sur une seule ligne
        t = re.sub(mot + r'\s*=?\s*[\'"][^\'"]*[\'"]', '', t)
        t = re.sub(mot + r'\s*=?\s*\d+', '', t)
    for mot in ('applicationVariants', 'splits', 'bundle'):
        t = retirer_bloc(t, mot)
    t = retirer_sucrage(t)
    t = limiter_architecture(t)
    t = aligner_version_minimale(t)
    t = accorder_traduction_java(t)
    open(chemin, 'w', encoding='utf-8').write(t)
    print('fichier de compilation adapte :', chemin)


def poser_espace_de_noms(gradle: str, manifeste: str) -> None:
    """
    Les outils Android récents exigent que chaque morceau déclare son espace de
    noms dans son fichier de compilation, alors que les projets plus anciens le
    mettaient dans le manifeste. On déplace donc l'information.
    """
    m = open(manifeste, encoding='utf-8').read()
    trouve = re.search(r'package\s*=\s*"([^"]+)"', m)
    if not trouve:
        return
    paquet = trouve.group(1)
    m = m.replace(trouve.group(0), '')
    open(manifeste, 'w', encoding='utf-8').write(m)

    t = open(gradle, encoding='utf-8').read()
    if 'namespace' in t:
        return
    # on l'insere juste apres l'ouverture du bloc android
    i = t.find('android')
    j = t.find('{', i)
    if j == -1:
        return
    t = t[:j + 1] + "\n    namespace '" + paquet + "'\n" + t[j + 1:]
    open(gradle, 'w', encoding='utf-8').write(t)
    print('espace de noms pose :', paquet)


def retirer_sucrage(t: str) -> str:
    """
    Retirer le sucrage des modules de Mupen64Plus.

    Ses modules demandent « isCoreLibraryDesugaringEnabled », et Android exige
    alors que notre application l'active aussi : « Dependency ':m64' requires
    core library desugaring to be enabled ». Or c'est justement ce reglage qui
    declenchait l'erreur D8 sur android.jar.

    Depuis qu'on demande Android 29, les fonctions Java recentes sont
    disponibles d'origine : cette traduction ne sert plus a rien. On la retire
    donc partout, et la question ne se pose plus des deux cotes.
    """
    # Le reglage, quelle que soit son ecriture : avec ou sans « is » devant,
    # avec ou sans signe egal. Son propre fichier emploie la quatrieme forme,
    # « coreLibraryDesugaringEnabled = true », que mes premiers motifs
    # laissaient passer.
    t = re.sub(r'^[^\n]*[Cc]oreLibraryDesugaringEnabled[^\n]*$', '', t, flags=re.M)
    # et les bibliotheques qui l'accompagnent, une par ligne
    t = re.sub(r'^[^\n]*\bcoreLibraryDesugaring\s*[\'"(][^\n]*$', '', t, flags=re.M)
    return t


def accorder_traduction_java(t: str) -> str:
    """
    Mettre d'accord le réglage et la bibliothèque.

    Un module qui active la traduction Java doit aussi déclarer la bibliothèque
    qui l'assure, sinon Gradle refuse : « coreLibraryDesugaring configuration
    contains no dependencies ». On ne touche pas au réglage — on complète.
    """
    if 'coreLibraryDesugaringEnabled' not in t:
        return t
    if 'desugar_jdk_libs' in t:
        return t

    ligne = "\n    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs_nio:2.1.5'\n"
    i = t.find('dependencies')
    if i != -1:
        j = t.find('{', i)
        if j != -1:
            return t[:j + 1] + ligne + t[j + 1:]
    return t + "\ndependencies {" + ligne + "}\n"


def aligner_version_minimale(t: str) -> str:
    """
    Aligner la version minimale d'Android sur celle de la Chambre.

    Ces modules reclament une version plus recente que la notre, et Gradle
    refuse alors de les assembler : « use a compatible library with a minSdk of
    at most 24 ». On ramene donc chacun a 24, comme notre application.
    """
    # les quatre ecritures rencontrees, y compris dans les valeurs partagees
    # du fichier racine (« ext { minSdkVersion = 29 } »)
    t = re.sub(r'minSdkVersion\s*=\s*\d+', 'minSdkVersion = 29', t)
    t = re.sub(r'minSdkVersion\s+\d+', 'minSdkVersion 29', t)
    t = re.sub(r'minSdk\s*=\s*\d+', 'minSdk = 29', t)
    t = re.sub(r'minSdk\s+\d+', 'minSdk 29', t)
    return t


def limiter_architecture(t: str) -> str:
    """
    Ne compiler que pour arm64.

    Ce projet se construit par defaut pour quatre architectures, dont deux qui
    n'existent que sur les ordinateurs. Sur un telephone recent, seule arm64
    sert : on divise ainsi le temps de compilation par deux, et le poids de
    l'application d'autant.
    """
    if "abiFilters 'arm64-v8a'" in t or 'abiFilters "arm64-v8a"' in t:
        return t
    # on remplace toute liste d'architectures existante
    # on s'arrete a l'accolade : sinon on emporte la fermeture du bloc,
    # et le fichier devient illisible pour Gradle
    t = re.sub(r"abiFilters[^\n}]*", "abiFilters 'arm64-v8a'", t)
    if "abiFilters 'arm64-v8a'" in t:
        return t
    # Sinon on l'ajoute. Attention : ce reglage n'existe que dans defaultConfig.
    # Le poser directement dans android provoque « Could not find method ndk() ».
    i = t.find('defaultConfig')
    if i != -1:
        j = t.find('{', i)
        if j != -1:
            return t[:j + 1] + "\n        ndk { abiFilters 'arm64-v8a' }\n" + t[j + 1:]

    # pas de defaultConfig : on en cree un dans le bloc android
    i = t.find('android')
    if i != -1:
        j = t.find('{', i)
        if j != -1:
            return (t[:j + 1]
                    + "\n    defaultConfig {\n        ndk { abiFilters 'arm64-v8a' }\n    }\n"
                    + t[j + 1:])
    return t


def adapter_manifeste(chemin: str) -> None:
    """
    Son manifeste ne doit plus se comporter en application autonome.

    On lui retire son écran d'accueil, sa classe d'application et les réglages
    généraux qu'il voudrait imposer : c'est le manifeste de la Chambre qui
    décide, sinon la fusion échoue.
    """
    t = open(chemin, encoding='utf-8').read()
    t = t.replace('android.intent.category.LAUNCHER', 'android.intent.category.DEFAULT')
    # une version minimale inscrite ici ferait echouer la fusion des manifestes
    t = re.sub(r'<uses-sdk[^>]*/>', '', t)

    # les attributs generaux : on les efface, les notres s'appliqueront
    for attribut in ('android:name', 'android:label', 'android:icon',
                     'android:roundIcon', 'android:theme', 'android:allowBackup',
                     'android:supportsRtl', 'android:largeHeap',
                     'android:hardwareAccelerated', 'android:banner',
                     'android:requestLegacyExternalStorage',
                     'android:appComponentFactory', 'android:networkSecurityConfig'):
        t = re.sub(r'(<application\b[^>]*?)\s' + attribut + r'\s*=\s*"[^"]*"',
                   r'\1', t, flags=re.S)

    # Certaines bibliotheques de Mupen64Plus reclament un Android plus recent
    # que notre minimum, et le fusionneur refuse alors tout le manifeste. On
    # lui demande de passer outre, en nommant les paquets concernes.
    if 'tools:overrideLibrary' not in t:
        passe = ('    <uses-sdk tools:overrideLibrary="paulscode.android.mupen64plusae,'
                 'org.mupen64plusae.v3.alpha,org.libsdl.app,'
                 'com.bda.controller,org.apache.http.legacy" />\n')
        t = t.replace('<application', passe + '    <application', 1)
        if 'xmlns:tools' not in t:
            t = t.replace('<manifest', '<manifest xmlns:tools="http://schemas.android.com/tools"', 1)

    open(chemin, 'w', encoding='utf-8').write(t)
    print('manifeste adapte :', chemin)


if __name__ == '__main__':
    adapter_compilation(sys.argv[1])
    if len(sys.argv) > 2:
        adapter_manifeste(sys.argv[2])
        poser_espace_de_noms(sys.argv[1], sys.argv[2])
