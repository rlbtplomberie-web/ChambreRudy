cmake_minimum_required(VERSION 3.18)
project(ponts C)

find_library(log-lib log)

# Pont simple : celui des premiers emulateurs (SNES, PS1...)
add_library(pont SHARED pont.c)
target_link_libraries(pont ${log-lib} dl)

# Pont complet : triches, sauvegardes d'etat, reglages de coeur
# (Mega Drive, Game Boy, Game Boy Advance)
add_library(pontplus SHARED pontplus.c)
target_link_libraries(pontplus ${log-lib} dl)
