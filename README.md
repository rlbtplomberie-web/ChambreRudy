# Skin SNES

Émulateur Super Nintendo pour Android, habillé d'un skin de manette PAL avec
des touches animées. Même construction que Skin NES : tout se compile depuis
GitHub Actions, sans PC.

## Ce qui change par rapport à Skin NES

- Le moteur n'est pas écrit maison : c'est **Snes9x**, via son interface
  libretro. Le workflow télécharge le cœur déjà compilé pour Android depuis le
  buildbot libretro et l'embarque dans l'APK sous le nom `libsnes9x.so`.
  Un petit pont en C (`app/src/main/cpp/pont.c`) l'ouvre et le relie à Kotlin.
- Les touches ne sont plus animées à la volée : chaque touche a ses images
  d'appui pré-calculées (repos, mi-course, fond de course ; huit directions
  pour la croix), rangées dans `assets/skin/portrait` et `assets/skin/paysage`
  avec un `positions.json` qui donne leur place.
- Quatre boutons d'action (X, Y, A, B), deux gâchettes (L, R), son stéréo à la
  fréquence annoncée par le cœur (32 040 Hz).

## Les trois présentations horizontales

Le bouton manette passe de l'une à la suivante, et chacune garde sa propre
disposition enregistrée :

1. **Écran normal** — `assets/skin/paysage`
2. **Grand écran** — `assets/skin/paysage2`
3. **Touches transparentes** — `assets/skin/paysage3` : pas de console dessinée,
   seulement le tracé des touches posé sur le jeu, qui occupe tout l'écran.
   Sur cette présentation, le mode Modifier affiche un curseur qui règle
   l'opacité des touches, des plus discrètes aux plus visibles ; le réglage
   est retenu.

## Ce qui est repris tel quel

Catalogue de jeux dans l'écran, dossier de ROMs mémorisé, sauvegarde et
rechargement d'état (via Snes9x), éditeur de disposition, barre d'outils
appelée par le bouton MENU, bouton Retour en filet de sécurité.

## Formats acceptés

`.sfc`, `.smc`, `.fig`, `.swc`, et les archives `.zip` qui en contiennent une.

## Licence du cœur

Snes9x est distribué sous une licence qui **interdit l'usage commercial**.
Cette application est prévue pour un usage personnel.
