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


def alleger(chemin: Path) -> bool:
    """Vide la liste des autorisations reclamees. Renvoie vrai si modifie."""
    t = chemin.read_text(encoding='utf-8', errors='ignore')
    avant = t

    # 1. la liste, dans ses ecritures habituelles :
    #    String[] PERMISSIONS = { ... };   ou   new String[]{ ... }
    def vider(m):
        return m.group(1) + '{ }' + m.group(3)

    t = re.sub(
        r'(\bString\s*\[\s*\]\s+\w*PERMISSIONS?\w*\s*=\s*)(\{[^;]*?\})(\s*;)',
        vider, t, flags=re.S | re.I)
    t = re.sub(
        r'(\bnew\s+String\s*\[\s*\]\s*)(\{[^;]*?Manifest\.permission[^;]*?\})(\s*[;,\)])',
        vider, t, flags=re.S)

    # 2. certaines revisions dressent la liste dans une variable locale
    t = re.sub(
        r'(\bList<String>\s+\w*[Pp]ermissions?\w*\s*=\s*)new\s+ArrayList<>\s*\([^)]*\)(\s*;)',
        r'\1new java.util.ArrayList<>()\2', t)

    if t == avant:
        return False
    chemin.write_text(t, encoding='utf-8')
    return True


def main() -> None:
    if len(sys.argv) < 2:
        print('usage : n64_autorisations.py <dossier du depot Mupen64Plus>')
        sys.exit(1)
    racine = Path(sys.argv[1])

    cibles = list(racine.glob('**/SplashActivity.java')) + \
             list(racine.glob('**/SplashActivity.kt'))
    if not cibles:
        print('::warning::aucun SplashActivity trouve : la demande '
              'd autorisations n a pas pu etre allegee')
        return

    for f in cibles:
        t = f.read_text(encoding='utf-8', errors='ignore')
        combien = len(re.findall(r'Manifest\.permission\.\w+', t))
        print(f'{f} : {combien} autorisation(s) reclamee(s)')
        if alleger(f):
            reste = len(re.findall(r'Manifest\.permission\.\w+',
                                   f.read_text(encoding='utf-8', errors='ignore')))
            print(f'   liste videe, il en reste {reste}')
        else:
            print('   ::warning::liste non reconnue, rien n a ete change')


if __name__ == '__main__':
    main()
