#!/usr/bin/env python3
"""
Lever le barrage des autorisations de Mupen64Plus.

Son ecran d'accueil reclame l'acces aux fichiers avant de demarrer, et refuse
d'aller plus loin sans lui : « This app cannot proceed without these
permissions ». C'etait la bonne facon de faire a l'epoque de cette revision.

Depuis Android 11, l'autorisation d'ECRITURE externe n'existe plus : le
systeme la refuse d'office, quoi qu'on fasse. Sa verification ne peut donc
jamais aboutir sur un telephone recent, et l'emulateur s'arrete la.

Ce n'est pas genant pour nous : la Chambre lui passe le jeu par son chemin, et
l'emulateur ecrit ses sauvegardes dans son propre dossier, qui ne demande
aucune autorisation. On vide donc la liste de ce qu'il reclame. Sa
verification trouve alors qu'il ne manque rien et le laisse demarrer.

L'outil dit toujours ce qu'il a trouve et ce qu'il a fait : si le code a
change de forme, cela se verra dans le journal de compilation au lieu de
passer inapercu.
"""

import re
import sys
from pathlib import Path


def alleger(chemin: Path) -> int:
    """
    Remplacer les autorisations impossibles par une autorisation acquise.

    Plutot que de vider des listes — dont la forme change d'une revision a
    l'autre — on remplace chaque mention des autorisations de stockage par
    « INTERNET ». C'est une autorisation ordinaire, qu'Android accorde
    d'office : la verification de l'emulateur la trouve donc satisfaite et le
    laisse demarrer, sans qu'on ait touche a la structure de son code.

    Renvoie le nombre de remplacements.
    """
    t = chemin.read_text(encoding='utf-8', errors='ignore')
    avant = t

    impossibles = (
        'WRITE_EXTERNAL_STORAGE',
        'READ_EXTERNAL_STORAGE',
        'MANAGE_EXTERNAL_STORAGE',
        'READ_MEDIA_IMAGES',
        'READ_MEDIA_VIDEO',
        'READ_MEDIA_AUDIO',
    )
    n = 0
    for nom in impossibles:
        # la forme par constante : Manifest.permission.WRITE_EXTERNAL_STORAGE
        motif = r'(Manifest\.permission\.)' + nom + r'\b'
        t, k = re.subn(motif, r'\1INTERNET', t)
        n += k
        # la forme par texte : "android.permission.WRITE_EXTERNAL_STORAGE"
        motif = r'(["\'])android\.permission\.' + nom + r'(["\'])'
        t, k = re.subn(motif, r'\1android.permission.INTERNET\2', t)
        n += k

    if t != avant:
        chemin.write_text(t, encoding='utf-8')
    return n


def main() -> None:
    if len(sys.argv) < 2:
        print('usage : n64_autorisations.py <dossier du depot Mupen64Plus>')
        sys.exit(1)
    racine = Path(sys.argv[1])

    # tout fichier qui reclame une autorisation de stockage, ou qu'il soit :
    # l'ecran d'accueil, mais aussi ce qu'il appelle
    cibles = []
    for f in list(racine.glob('**/*.java')) + list(racine.glob('**/*.kt')):
        try:
            t = f.read_text(encoding='utf-8', errors='ignore')
        except OSError:
            continue
        if 'EXTERNAL_STORAGE' in t or 'READ_MEDIA_' in t:
            cibles.append(f)
    if not cibles:
        print('::warning::aucun fichier ne reclame d autorisation de stockage')
        return
    print(f'{len(cibles)} fichier(s) reclament une autorisation de stockage')

    total = 0
    for f in cibles:
        n = alleger(f)
        total += n
        print(f'{f.relative_to(racine)} : {n} autorisation(s) remplacee(s)')
    if total == 0:
        print('::warning::aucune autorisation de stockage trouvee dans ces '
              'fichiers : le barrage vient peut-etre d ailleurs')
    else:
        print(f'au total, {total} autorisation(s) impossible(s) remplacee(s) '
              f'par INTERNET, qu Android accorde d office')


if __name__ == '__main__':
    main()
