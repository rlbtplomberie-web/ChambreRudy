cmake_minimum_required(VERSION 3.18)
project(ponts C CXX)

find_library(log-lib log)
find_library(egl-lib EGL)
find_library(gles-lib GLESv3)

# Chaque emulateur garde son pont natif, son nom et ses fonctions d'origine :
# rien n'est renomme, donc rien ne peut se desaccorder.

add_library(skinsnes SHARED skinsnes/pont.c)
target_link_libraries(skinsnes ${log-lib} dl)

add_library(gbgbc SHARED gbgbc/pont.c)
target_link_libraries(gbgbc ${log-lib} dl)

add_library(skingba SHARED skingba/pont.c)
target_link_libraries(skingba ${log-lib} dl)

add_library(skinmd SHARED skinmd/pont.c)
target_link_libraries(skinmd ${log-lib} dl)

add_library(skinps1 SHARED skinps1/pont.c)
target_link_libraries(skinps1 ${log-lib} dl)

add_library(skindc SHARED skindc/pont.c)
target_link_libraries(skindc ${log-lib} ${egl-lib} ${gles-lib} dl)

add_library(skinds SHARED skinds/pont.c)
target_link_libraries(skinds ${log-lib} ${egl-lib} ${gles-lib} dl)

# GameCube et Wii : Dolphin
add_library(skingc SHARED skingc/pont.c stl.cpp)
target_link_libraries(skingc ${log-lib} ${egl-lib} ${gles-lib} dl)

# PSP : PPSSPP, avec la bibliotheque C++ partagee
add_library(skinpsp SHARED skinpsp/pont.c stl.cpp)
target_link_libraries(skinpsp ${log-lib} ${egl-lib} ${gles-lib} dl)

# La 3DS : son pont, plus un fichier C++ vide pour que libc++_shared.so
# soit embarquee dans l'APK, comme Citra l'exige.
add_library(skin3ds SHARED skin3ds/pont.c stl.cpp)
target_link_libraries(skin3ds ${log-lib} ${egl-lib} ${gles-lib} dl)
