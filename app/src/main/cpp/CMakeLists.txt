cmake_minimum_required(VERSION 3.18)
project(ponts C CXX)

find_library(log-lib log)
find_library(egl-lib EGL)
find_library(gles-lib GLESv3)

# Chaque emulateur garde le pont natif de son projet d'origine : ils different
# trop pour etre fondus en un seul sans risque.

# NES / Super Nintendo : pont simple
add_library(pont SHARED pont.c)
target_link_libraries(pont ${log-lib} dl)

# Mega Drive, Game Boy, Game Boy Advance : triches et reglages de coeur
add_library(pontplus SHARED pontplus.c)
target_link_libraries(pontplus ${log-lib} dl)

# PlayStation
add_library(pont_ps1 SHARED pont_ps1.c)
target_link_libraries(pont_ps1 ${log-lib} dl)

# Dreamcast, Nintendo DS, Nintendo 3DS : rendu par OpenGL
add_library(pont_dc SHARED pont_dc.c)
target_link_libraries(pont_dc ${log-lib} ${egl-lib} ${gles-lib} dl)

add_library(pont_ds SHARED pont_ds.c)
target_link_libraries(pont_ds ${log-lib} ${egl-lib} ${gles-lib} dl)

add_library(pont_n3ds SHARED pont_n3ds.c stl.cpp)
target_link_libraries(pont_n3ds ${log-lib} ${egl-lib} ${gles-lib} dl)
