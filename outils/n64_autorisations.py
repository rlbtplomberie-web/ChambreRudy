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

_rapport = []


def dire(texte: str) -> None:
    """Ecrire a l'ecran de la compilation ET dans le bilan de l'application."""
    print(texte)
    _rapport.append(texte)


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

    # La verification elle-meme.
    #
    # Remplacer les noms ne suffit pas toujours : certaines revisions
    # verifient autrement, ou reclament l'autorisation dans une bibliotheque
    # qu'on ne voit pas. On fait donc dire « oui » a la comparaison : partout
    # ou le code demande « cette autorisation est-elle accordee ? », la
    # reponse est desormais oui, et le chemin d'echec n'est plus emprunte.
    comparaisons = [
        # le prefixe eventuel — ContextCompat., ActivityCompat., this. — doit
        # partir avec le reste, sinon il resterait « ContextCompat.false »
        (r'[\w.]*\b[Cc]heck(?:Self)?Permission\s*\([^;{}]*?\)\s*!=\s*[\w.]*PERMISSION_GRANTED',
         'false'),
        (r'[\w.]*\b[Cc]heck(?:Self)?Permission\s*\([^;{}]*?\)\s*==\s*[\w.]*PERMISSION_GRANTED',
         'true'),
        # la comparaison a « refusee », l'autre facon d'ecrire la meme chose
        (r'[\w.]*\b[Cc]heck(?:Self)?Permission\s*\([^;{}]*?\)\s*==\s*[\w.]*PERMISSION_DENIED',
         'false'),
        (r'[\w.]*\b[Cc]heck(?:Self)?Permission\s*\([^;{}]*?\)\s*!=\s*[\w.]*PERMISSION_DENIED',
         'true'),
        (r'!\s*[\w.]*\bisExternalStorageManager\s*\(\s*\)', 'false'),
        (r'[\w.]*\bisExternalStorageManager\s*\(\s*\)', 'true'),
        # et la demande elle-meme : si elle part, Android refuse l'ecriture
        # externe d'office et son rappel affiche le message. On la retire.
        (r'[\w.]*\brequestPermissions\s*\([^;]*?\)\s*;', '/* demande retiree */;'),
    ]
    for motif, reponse in comparaisons:
        t, k = re.subn(motif, reponse, t)
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
        if ('EXTERNAL_STORAGE' in t or 'READ_MEDIA_' in t
                or 'PERMISSION_GRANTED' in t
                or 'isExternalStorageManager' in t):
            cibles.append(f)
    if not cibles:
        print('::warning::aucun fichier ne reclame d autorisation de stockage')
        return
    dire(f'{len(cibles)} fichier(s) reclament une autorisation de stockage')

    # D'ou vient exactement le message ? On le dit dans le journal de
    # compilation : si le barrage tient encore, on saura ou regarder sans
    # avoir a refaire un tour pour rien.
    for f in list(racine.glob('**/*.xml')) + list(racine.glob('**/*.java')) + \
             list(racine.glob('**/*.kt')):
        try:
            contenu = f.read_text(encoding='utf-8', errors='ignore')
        except OSError:
            continue
        if 'cannot proceed' in contenu.lower():
            for num, ligne in enumerate(contenu.splitlines(), 1):
                if 'cannot proceed' in ligne.lower():
                    dire(f'   message trouve : {f.relative_to(racine)}:{num}')

    total = 0
    for f in cibles:
        n = alleger(f)
        total += n
        if n: dire(f'   {f.name} : {n} remplacement(s)')
    if total == 0:
        dire('N64 autorisations : AUCUNE trouvee — le barrage vient d ailleurs')
    else:
        dire(f'N64 autorisations : {total} remplacement(s)')

    # le bilan que la tele affiche
    try:
        with open('rapport_n64.txt', 'w', encoding='utf-8') as f:
            f.write('\n'.join(_rapport) + '\n')
    except OSError:
        pass


if __name__ == '__main__':
    main()
