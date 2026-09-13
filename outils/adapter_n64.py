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
    t = t.replace('com.android.application', 'com.android.library')
    # tous les reglages qui n'ont de sens que pour une application autonome
    for mot in ('applicationId', 'applicationIdSuffix', 'versionNameSuffix',
                'testApplicationId', 'versionCode', 'versionName'):
        t = re.sub(r'^\s*' + mot + r'\s.*$', '', t, flags=re.M)
        t = re.sub(r'^\s*' + mot + r'\s*=.*$', '', t, flags=re.M)
    for mot in ('applicationVariants', 'splits', 'bundle'):
        t = retirer_bloc(t, mot)
    t = limiter_architecture(t)
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
    t = re.sub(r"abiFilters\s*[^\n]*", "abiFilters 'arm64-v8a'", t)
    if "abiFilters 'arm64-v8a'" in t:
        return t
    # sinon on l'ajoute dans defaultConfig, ou dans android faute de mieux
    for bloc in ('defaultConfig', 'android'):
        i = t.find(bloc)
        if i == -1:
            continue
        j = t.find('{', i)
        if j == -1:
            continue
        return t[:j + 1] + "\n        ndk { abiFilters 'arm64-v8a' }\n" + t[j + 1:]
    return t


def adapter_manifeste(chemin: str) -> None:
    """Son écran d'accueil ne doit plus se déclarer comme celui du téléphone."""
    t = open(chemin, encoding='utf-8').read()
    t = t.replace('android.intent.category.LAUNCHER', 'android.intent.category.DEFAULT')
    open(chemin, 'w', encoding='utf-8').write(t)
    print('manifeste adapte :', chemin)


if __name__ == '__main__':
    adapter_compilation(sys.argv[1])
    if len(sys.argv) > 2:
        adapter_manifeste(sys.argv[2])
        poser_espace_de_noms(sys.argv[1], sys.argv[2])
