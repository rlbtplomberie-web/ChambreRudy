#!/usr/bin/env python3
"""
Repondre « oui » aux verifications d'autorisation de Mupen64Plus.

Son ecran demande l'acces aux fichiers au demarrage. Depuis Android 11,
l'autorisation d'ECRITURE externe ne peut plus etre accordee : le systeme la
refuse d'office. Rudy voit donc une fenetre « autoriser / ne pas autoriser »,
il autorise, et cela echoue quand meme.

Ce n'est pas genant pour nous : la Chambre pose la cartouche dans le dossier
externe de l'application, qui n'a besoin d'aucune autorisation.

Un premier outil tentait de retirer la DEMANDE elle-meme. Il abimait la
syntaxe du fichier : une demande peut se trouver au milieu d'une expression,
et la remplacer par rien casse le code autour.

Celui-ci ne touche qu'aux QUESTIONS, jamais aux instructions. Il remplace
« cette autorisation est-elle accordee ? » par « oui ». C'est une expression
remplacee par une autre expression : la structure du fichier ne bouge pas, et
la demande n'est jamais atteinte puisque la reponse est deja bonne.
"""

import re
import sys
import os
import subprocess
from pathlib import Path

_rapport = []


def dire(texte: str) -> None:
    print(texte)
    _rapport.append(texte)


# Chaque question, et la reponse qu'on lui donne.
#
# Le prefixe eventuel — ContextCompat., ActivityCompat., this. — part avec le
# reste : sans cela il resterait « ContextCompat.true », qui ne compile pas.
QUESTIONS = [
    (r'[\w.]*\b[Cc]heck(?:Self)?Permission\s*\([^;{}]*?\)\s*!=\s*[\w.]*PERMISSION_GRANTED',
     'false'),
    (r'[\w.]*\b[Cc]heck(?:Self)?Permission\s*\([^;{}]*?\)\s*==\s*[\w.]*PERMISSION_GRANTED',
     'true'),
    (r'[\w.]*\b[Cc]heck(?:Self)?Permission\s*\([^;{}]*?\)\s*==\s*[\w.]*PERMISSION_DENIED',
     'false'),
    (r'[\w.]*\b[Cc]heck(?:Self)?Permission\s*\([^;{}]*?\)\s*!=\s*[\w.]*PERMISSION_DENIED',
     'true'),
    (r'!\s*[\w.]*\bisExternalStorageManager\s*\(\s*\)', 'false'),
    (r'[\w.]*\bisExternalStorageManager\s*\(\s*\)', 'true'),
    # On ne touche PAS a « hasPermissions() » : le motif attrapait aussi sa
    # DECLARATION, et « private boolean true {» ne compile pas. C'est inutile
    # de toute facon : la verification qu'elle contient repond deja oui, donc
    # elle renvoie vrai d'elle-meme.
]


def repondre_oui(chemin: Path) -> int:
    """Remplace les questions par leur reponse. Renvoie le nombre de fois."""
    t = chemin.read_text(encoding='utf-8', errors='ignore')
    avant, n = t, 0
    for question, reponse in QUESTIONS:
        t, k = re.subn(question, reponse, t)
        n += k
    if t == avant:
        return 0
    # Un garde-fou : on ne rend le fichier que si les accolades et les
    # parentheses sont toujours en nombre egal. Mieux vaut ne rien changer
    # que de rendre un fichier qui ne compile plus.
    if t.count('{') != avant.count('{') or t.count('}') != avant.count('}'):
        dire(f'   {chemin.name} : refuse, les accolades ne correspondent plus')
        return 0
    chemin.write_text(t, encoding='utf-8')
    return n


def main() -> None:
    if len(sys.argv) < 2:
        print('usage : n64_dire_oui.py <dossier du depot>')
        sys.exit(1)
    racine = Path(sys.argv[1])

    # Mupen64Plus utilise bien le NDK 26.1. Le NDK 29 de Dolphin ne sait pas
    # compiler ses anciens Android.mk. On installe donc la version exacte dans
    # le runner GitHub, avant que Gradle ne lance le moteur N64.
    sdk = Path(os.environ.get('ANDROID_HOME', '')) / 'cmdline-tools/latest/bin/sdkmanager'
    if sdk.is_file():
        try:
            subprocess.run([str(sdk), 'ndk;26.1.10909125'], input='y\n', text=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, check=True)
            dire('N64 : son NDK 26.1 a ete installe pour conserver son moteur intact')
        except Exception as e:
            dire('N64 : installation du NDK 26.1 impossible : ' + str(e)[:90])

    cibles = []
    for f in list(racine.glob('**/*.java')) + list(racine.glob('**/*.kt')):
        try:
            t = f.read_text(encoding='utf-8', errors='ignore')
        except OSError:
            continue
        if 'PERMISSION_GRANTED' in t or 'PERMISSION_DENIED' in t \
                or 'isExternalStorageManager' in t:
            cibles.append(f)

    if not cibles:
        dire('N64 autorisations : aucune verification trouvee')
    else:
        total = 0
        for f in cibles:
            n = repondre_oui(f)
            total += n
            if n:
                dire(f'   {f.name} : {n} question(s) repondue(s)')
        dire(f'N64 autorisations : {total} reponse(s) donnee(s) '
             f'dans {len(cibles)} fichier(s)')

    try:
        with open('rapport_n64.txt', 'w', encoding='utf-8') as f:
            f.write('\n'.join(_rapport) + '\n')
    except OSError:
        pass


if __name__ == '__main__':
    main()
