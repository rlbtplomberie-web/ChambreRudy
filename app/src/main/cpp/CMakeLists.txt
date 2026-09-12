cmake_minimum_required(VERSION 3.18)
project(pont C)

# Un seul pont pour toutes les consoles : il ouvre n'importe quel coeur
# libretro dont on lui donne le chemin.
add_library(pont SHARED pont.c)
find_library(log-lib log)
target_link_libraries(pont ${log-lib} dl)
