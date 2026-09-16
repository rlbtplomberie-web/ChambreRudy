#!/usr/bin/env python3
"""
Faire de Dolphin une bibliotheque, sans toucher a sa configuration.

Le projet Android de Dolphin se compile tout seul, avec son propre Gradle, sa
propre version des outils et son catalogue de versions. On n'y touche pas :
c'est justement ce qui permet a la Chambre de garder les siens.

La seule chose que l'on change, c'est la nature de son module principal. Au
lieu de produire une application — un APK — il produit une bibliotheque, un
fichier .aar. Ce fichier porte tout ce dont la Chambre a besoin : les classes
de Dolphin, ses ressources, ses assets, et ses bibliotheques natives compilees
par CMake. Il suffit ensuite de l'embarquer.

Trois retouches, et pas une de plus :

  1. le greffon « application » devient « library » ;
  2. l'identifiant d'application disparait — une bibliotheque n'en a pas ;
  3. les reglages qui n'ont de sens que pour une application (la signature,
     les variantes de sortie) sont retires.
"""

import re
import sys
from pathlib import Path


def basculer_en_bibliotheque(chemin: Path) -> bool:
    """Retouche le fichier de compilation du module. Renvoie vrai si modifie."""
    t = chemin.read_text(encoding='utf-8')
    avant = t

    # 1. le greffon
    t = t.replace('id("com.android.application")', 'id("com.android.library")')
    t = t.replace("id 'com.android.application'", "id 'com.android.library'")
    # Dolphin declare son greffon par un raccourci de son catalogue de
    # versions : « alias(libs.plugins.android.application) ». Le raccourci
    # vers la bibliotheque n'existe pas forcement dans ce catalogue — on
    # ecrit donc le nom en clair, qui marche toujours.
    t = t.replace('alias(libs.plugins.android.application)',
                  'id("com.android.library")')
    t = t.replace('alias(libs.plugins.android.application())',
                  'id("com.android.library")')
    t = re.sub(r"apply\s+plugin:\s*['\"]com\.android\.application['\"]",
               "apply plugin: 'com.android.library'", t)

    # 2. l'identifiant d'application : une bibliotheque n'en porte pas
    # meme quand plusieurs reglages partagent une ligne, separes par « ; »
    # Le mot doit etre entier : sans la seconde borne, « applicationId »
    # mangeait le debut de « applicationIdSuffix » et laissait une ligne
    # invalide du type « = \".debug\" ». C'est exactement le type d'erreur
    # Gradle qui ne donne ensuite qu'un vague « exit code 1 » dans GitHub.
    # targetSdk appartient a l'application finale. Quand Dolphin est une
    # bibliotheque, c'est RetroRom qui le declare dans son propre module.
    for mot in ('applicationId', 'applicationIdSuffix', 'versionCode', 'versionName',
                'versionNameSuffix', 'targetSdk'):
        entier = r'\b' + mot + r'\b'
        t = re.sub(entier + r'\s*=\s*["\'][^"\']*["\']\s*;?', '', t)
        t = re.sub(entier + r'\s*=\s*[\w.()]+\s*;?', '', t)

    # Cette optimisation n'est autorisee que lorsque le module produit son
    # propre APK. Le moteur Dolphin est maintenant embarque dans RetroRom,
    # donc Android interdit ce drapeau sur la bibliotheque.
    t = re.sub(r'\bisShrinkResources\s*=\s*(true|false)\s*;?', '', t)

    # Le greffon « application » genere deux constantes que l'interface de
    # Dolphin emploie pour son ecran A propos et son adaptateur GameCube.
    # Une bibliotheque ne les genere pas : on les fournit explicitement pour
    # conserver son interface et ses pads, sans creer une seconde APK.
    marque = 'RETRO_ROM_DOLPHIN_LIBRARY_CONSTANTS'
    if marque not in t:
        ajout = (
            'defaultConfig {\n'
            '        // ' + marque + '\n'
            '        buildConfigField("String", "VERSION_NAME", "\\\"2606\\\"")\n'
            '        buildConfigField("String", "APPLICATION_ID", '
            '"\\\"org.dolphinemu.dolphinemu\\\"")\n'
        )
        t = t.replace('defaultConfig {', ajout, 1)

    # 3. On ne retire PAS de blocs Gradle par recherche de mot.
    #
    # « bundle » et « splits » peuvent apparaitre dans une dependance ou un
    # commentaire. L'ancien outil cherchait simplement ce mot puis supprimait
    # jusqu'a la prochaine accolade : il coupait ainsi build.gradle.kts et
    # provoquait « Expecting '}' ». Le plugin library signalera lui-meme une
    # option incompatible s'il en rencontre une ; le fichier, lui, reste
    # syntaxiquement intact.

    if t != avant:
        chemin.write_text(t, encoding='utf-8')
        return True
    return False


def retirer_bloc(texte: str, mot: str) -> str:
    """Retire un bloc entier, accolades comprises, partout ou le mot apparait."""
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
        texte = texte[:i] + texte[k + 1:]
        i = texte.find(mot)
    return texte


def relever_application(racine: Path) -> None:
    """
    Relever la classe d'application de Dolphin avant qu'elle ne se perde.

    Comme celle de Mupen64Plus, elle prepare les chemins et charge les
    bibliotheques natives. Dans une bibliotheque, Android ne la lance plus :
    c'est la Chambre qui s'en chargera, a partir de ce releve.
    """
    manifeste = racine / 'app/src/main/AndroidManifest.xml'
    if not manifeste.exists():
        return
    m = manifeste.read_text(encoding='utf-8')
    trouve = re.search(r'<application\b[^>]*?\sandroid:name\s*=\s*"([^"]+)"', m, re.S)
    if not trouve:
        return
    nom = trouve.group(1)
    if nom.startswith('.'):
        paquet = re.search(r'namespace\s*=?\s*["\']([^"\']+)["\']',
                           (racine / 'app/build.gradle.kts').read_text(encoding='utf-8')
                           if (racine / 'app/build.gradle.kts').exists() else '')
        if paquet:
            nom = paquet.group(1) + nom
    try:
        with open('applications_emulateurs.txt', 'a', encoding='utf-8') as f:
            f.write(nom + '\n')
        print('classe d application de Dolphin relevee :', nom)
    except OSError:
        pass


def main() -> None:
    if len(sys.argv) < 2:
        print('usage : dolphin_en_bibliotheque.py <dossier Source/Android>')
        sys.exit(1)
    racine = Path(sys.argv[1])
    if not racine.is_dir():
        print('dossier introuvable :', racine)
        sys.exit(1)

    relever_application(racine)

    fait = 0
    for nom in ('app/build.gradle.kts', 'app/build.gradle'):
        f = racine / nom
        if f.exists() and basculer_en_bibliotheque(f):
            print('module devenu bibliotheque :', nom)
            fait += 1
    if not fait:
        print('aucun fichier de compilation retouche — a verifier')
        sys.exit(1)


if __name__ == '__main__':
    main()
