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
    open(chemin, 'w', encoding='utf-8').write(t)
    print('fichier de compilation adapte :', chemin)


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
