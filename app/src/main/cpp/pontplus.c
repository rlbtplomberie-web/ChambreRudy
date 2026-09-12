/*
 * Pont entre l'application et le coeur Genesis Plus GX, au format libretro.
 *
 * Ce fichier est ecrit pour la Game Boy et la Game Boy Color, et pour elles
 * seules. Le coeur y calcule ses images LUI-MEME et nous remet ses pixels :
 * il n'y a ni contexte graphique a partager, ni tampon a relire. C'est la
 * voie la plus simple, et la plus sure.
 *
 * Les codes de triche passent par retro_cheat_set, que Genesis Plus GX comprend au
 * format Game Genie et GameShark.
 */
#include <jni.h>
#include <dlfcn.h>
#include <errno.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* ==================================================== journal */

static char g_dossier[512];
static const char *g_etape = "au repos";
static unsigned long g_images = 0;

static void noter(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

static void noter(const char *fmt, ...) {
    char ligne[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(ligne, sizeof ligne, fmt, ap);
    va_end(ap);
    char chemin[600];
    snprintf(chemin, sizeof chemin, "%s/journal.txt", g_dossier);
    FILE *f = fopen(chemin, "a");
    if (!f) return;
    fprintf(f, "%s\n", ligne);
    fflush(f);
    fclose(f);
}

static void journal_coeur(unsigned niveau, const char *fmt, ...)
    __attribute__((format(printf, 2, 3)));

/* ==================================================== libretro */

/*
 * Constantes de l'interface libretro, dans leurs valeurs exactes.
 *
 * Une valeur inventee ne provoque aucune erreur : le coeur ignore la demande
 * en silence, et l'on cherche ensuite ailleurs pendant des heures.
 */
enum {
    ENV_GET_CAN_DUPE = 3,
    ENV_SET_PIXEL_FORMAT = 10,
    ENV_GET_SYSTEM_DIRECTORY = 9,
    ENV_GET_VARIABLE = 15,
    ENV_SET_VARIABLES = 16,
    ENV_GET_VARIABLE_UPDATE = 17,
    ENV_GET_LOG_INTERFACE = 27,
    ENV_GET_SAVE_DIRECTORY = 31,
    ENV_SET_GEOMETRY = 37,
    ENV_GET_CORE_OPTIONS_VERSION = 52,
    ENV_SET_CORE_OPTIONS = 53,
    ENV_SET_CORE_OPTIONS_INTL = 54,
    ENV_SET_CORE_OPTIONS_V2 = 67,
    ENV_SET_CORE_OPTIONS_V2_INTL = 68,
};

enum { PIX_XRGB8888 = 1, PIX_RGB565 = 2 };
enum { DEVICE_JOYPAD = 1 };

struct retro_variable { const char *key; const char *value; };
struct retro_log_callback { void (*log)(unsigned, const char *, ...); };

struct retro_game_geometry {
    unsigned base_width, base_height, max_width, max_height;
    float aspect_ratio;
};
struct retro_system_timing { double fps, sample_rate; };
struct retro_system_av_info {
    struct retro_game_geometry geometry;
    struct retro_system_timing timing;
};
struct retro_game_info {
    const char *path;
    const void *data;
    size_t size;
    const char *meta;
};

/* ==================================================== etat */

static void *g_lib = NULL;
static bool g_init = false, g_charge = false;

static void (*p_set_environment)(bool (*)(unsigned, void *));
static void (*p_set_video_refresh)(void (*)(const void *, unsigned, unsigned, size_t));
static void (*p_set_audio_sample)(void (*)(int16_t, int16_t));
static void (*p_set_audio_sample_batch)(size_t (*)(const int16_t *, size_t));
static void (*p_set_input_poll)(void (*)(void));
static void (*p_set_input_state)(int16_t (*)(unsigned, unsigned, unsigned, unsigned));
static void (*p_init)(void);
static void (*p_deinit)(void);
static bool (*p_load_game)(const struct retro_game_info *);
static void (*p_unload_game)(void);
static void (*p_run)(void);
static void (*p_reset)(void);
static void (*p_get_system_av_info)(struct retro_system_av_info *);
static void (*p_set_controller_port_device)(unsigned, unsigned);
static size_t (*p_serialize_size)(void);
static bool (*p_serialize)(void *, size_t);
static bool (*p_unserialize)(const void *, size_t);
static void (*p_cheat_reset)(void);
static void (*p_cheat_set)(unsigned, bool, const char *);

static unsigned g_bw = 160, g_bh = 144;
static double g_fps = 59.7275, g_sample_rate = 32768.0;
static unsigned g_format = PIX_RGB565;

/* ==================================================== reglages */

#define MAX_VARS 48
static struct { char cle[64]; char val[96]; } g_vars[MAX_VARS];
static int g_nvars = 0;
static bool g_vars_changees = false;

static void var_poser(const char *cle, const char *val) {
    for (int i = 0; i < g_nvars; i++) {
        if (strcmp(g_vars[i].cle, cle) == 0) {
            snprintf(g_vars[i].val, sizeof g_vars[i].val, "%s", val);
            g_vars_changees = true;
            return;
        }
    }
    if (g_nvars >= MAX_VARS) return;
    snprintf(g_vars[g_nvars].cle, sizeof g_vars[g_nvars].cle, "%s", cle);
    snprintf(g_vars[g_nvars].val, sizeof g_vars[g_nvars].val, "%s", val);
    g_nvars++;
    g_vars_changees = true;
}

static const char *var_lire(const char *cle) {
    for (int i = 0; i < g_nvars; i++)
        if (strcmp(g_vars[i].cle, cle) == 0) return g_vars[i].val;
    return NULL;
}

/* ==================================================== image */

/*
 * L'ecran de la console est petit — 160 sur 144 — mais le coeur peut le
 * doubler en mode Game Boy Color. On prend large.
 */
#define IMAGE_MAX (512 * 512)
static uint32_t g_pixels[IMAGE_MAX];
static unsigned g_il = 0, g_ih = 0;
static bool g_image_prete = false;
static int g_clarte = -1;

/* ==================================================== rappels */

static void journal_coeur(unsigned niveau, const char *fmt, ...) {
    static const char *NOMS[] = { "debug", "info", "attention", "ERREUR" };
    char ligne[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(ligne, sizeof ligne, fmt, ap);
    va_end(ap);
    size_t n = strlen(ligne);
    while (n && (ligne[n - 1] == '\n' || ligne[n - 1] == '\r')) ligne[--n] = 0;
    if (n) noter("[%s] %s", NOMS[niveau < 4 ? niveau : 1], ligne);
}

static bool environnement(unsigned cmd, void *data) {
    switch (cmd) {
        case ENV_GET_CAN_DUPE:
            *(bool *)data = true;
            return true;

        case ENV_SET_PIXEL_FORMAT:
            g_format = *(const unsigned *)data;
            noter("format des pixels : %s",
                  g_format == PIX_RGB565 ? "RGB565" :
                  g_format == PIX_XRGB8888 ? "XRGB8888" : "autre");
            return true;

        case ENV_GET_SYSTEM_DIRECTORY:
        case ENV_GET_SAVE_DIRECTORY:
            *(const char **)data = g_dossier;
            return true;

        case ENV_GET_LOG_INTERFACE: {
            struct retro_log_callback *cb = data;
            cb->log = journal_coeur;
            return true;
        }

        case ENV_GET_VARIABLE: {
            struct retro_variable *v = data;
            const char *val = var_lire(v->key);
            v->value = val;
            return val != NULL;
        }

        case ENV_SET_VARIABLES:
        case ENV_SET_CORE_OPTIONS:
        case ENV_SET_CORE_OPTIONS_INTL:
        case ENV_SET_CORE_OPTIONS_V2:
        case ENV_SET_CORE_OPTIONS_V2_INTL:
            return true;

        case ENV_GET_VARIABLE_UPDATE:
            *(bool *)data = g_vars_changees;
            g_vars_changees = false;
            return true;

        case ENV_GET_CORE_OPTIONS_VERSION:
            *(unsigned *)data = 2;
            return true;

        case ENV_SET_GEOMETRY: {
            const struct retro_game_geometry *g = data;
            if (g->base_width && g->base_height) {
                g_bw = g->base_width;
                g_bh = g->base_height;
            }
            return true;
        }

        default:
            return false;
    }
}

static void video(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!data || !w || !h) return;
    if ((size_t)w * h > IMAGE_MAX) return;
    g_bw = w; g_bh = h;

    const uint8_t *src = data;
    if (g_format == PIX_RGB565) {
        for (unsigned y = 0; y < h; y++) {
            const uint16_t *l = (const uint16_t *)(src + (size_t)y * pitch);
            uint32_t *o = g_pixels + (size_t)y * w;
            for (unsigned x = 0; x < w; x++) {
                unsigned p = l[x];
                unsigned r = ((p >> 11) & 0x1F);
                unsigned v = ((p >> 5) & 0x3F);
                unsigned b = (p & 0x1F);
                /* on etale sur toute la plage, sinon l'image est terne */
                r = (r << 3) | (r >> 2);
                v = (v << 2) | (v >> 4);
                b = (b << 3) | (b >> 2);
                o[x] = 0xFF000000u | (r << 16) | (v << 8) | b;
            }
        }
    } else {
        for (unsigned y = 0; y < h; y++) {
            const uint32_t *l = (const uint32_t *)(src + (size_t)y * pitch);
            uint32_t *o = g_pixels + (size_t)y * w;
            for (unsigned x = 0; x < w; x++) o[x] = 0xFF000000u | (l[x] & 0x00FFFFFFu);
        }
    }
    g_il = w; g_ih = h;
    g_image_prete = true;

    /* clarte de la ligne mediane : zero signale une image noire */
    const uint32_t *l = g_pixels + (size_t)(h / 2) * w;
    unsigned long somme = 0;
    for (unsigned x = 0; x < w; x++)
        somme += (l[x] & 0xFF) + ((l[x] >> 8) & 0xFF) + ((l[x] >> 16) & 0xFF);
    g_clarte = (int)(somme / (w * 3));
}

/* ==================================================== son */

#define SON_MAX 32768
static int16_t g_son[SON_MAX];
static volatile int g_son_ecrit = 0, g_son_lu = 0;

static void son_pousser(int16_t g, int16_t d) {
    int suivant = (g_son_ecrit + 2) % SON_MAX;
    if (suivant == g_son_lu) return;
    g_son[g_son_ecrit] = g;
    g_son[(g_son_ecrit + 1) % SON_MAX] = d;
    g_son_ecrit = suivant;
}

static void son_un(int16_t g, int16_t d) { son_pousser(g, d); }

static size_t son_lot(const int16_t *donnees, size_t images) {
    for (size_t i = 0; i < images; i++)
        son_pousser(donnees[i * 2], donnees[i * 2 + 1]);
    return images;
}

/* ==================================================== manette */

static int g_boutons = 0;
static void manette_poll(void) { }

static int16_t manette_etat(unsigned port, unsigned device, unsigned index, unsigned id) {
    (void)index;
    if (port != 0 || device != DEVICE_JOYPAD) return 0;
    return (g_boutons >> id) & 1;
}

/* ==================================================== arrets brutaux */

static void sur_signal(int sig) {
    char chemin[600];
    snprintf(chemin, sizeof chemin, "%s/chute.txt", g_dossier);
    FILE *f = fopen(chemin, "w");
    if (f) {
        fprintf(f, "ARRET BRUTAL : %s pendant « %s », apres %lu images\n",
                sig == SIGSEGV ? "acces memoire interdit" :
                sig == SIGBUS ? "acces mal aligne" :
                sig == SIGILL ? "instruction interdite" :
                sig == SIGFPE ? "calcul impossible" : "abandon",
                g_etape, g_images);
        fclose(f);
    }
    noter("ARRET BRUTAL pendant « %s », apres %lu images", g_etape, g_images);
    _exit(1);
}

static void installer_signaux(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = sur_signal;
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
    sigaction(SIGILL, &sa, NULL);
    sigaction(SIGFPE, &sa, NULL);
    sigaction(SIGABRT, &sa, NULL);
}

/* ==================================================== ouverture */

#define LIER(nom, cible) do { \
    *(void **)(&cible) = dlsym(g_lib, nom); \
    if (!cible) { noter("symbole absent : %s", nom); manque = true; } \
} while (0)

#define LIER_MOU(nom, cible) do { \
    *(void **)(&cible) = dlsym(g_lib, nom); \
} while (0)

JNIEXPORT jboolean JNICALL
Java_com_rudy_chambre_core_CoeurPlus_natInit(JNIEnv *env, jobject self, jstring chemin,
                                       jstring dossier) {
    (void)self;
    const char *c = (*env)->GetStringUTFChars(env, chemin, NULL);
    const char *d = (*env)->GetStringUTFChars(env, dossier, NULL);
    snprintf(g_dossier, sizeof g_dossier, "%s", d);
    setenv("HOME", d, 1);

    noter("");
    noter("--- ouverture du coeur ---");
    noter("dossier : %s", d);
    noter("bibliotheque : %s", c);
    installer_signaux();

    if (g_init) {
        (*env)->ReleaseStringUTFChars(env, chemin, c);
        (*env)->ReleaseStringUTFChars(env, dossier, d);
        return JNI_TRUE;
    }

    g_lib = dlopen(c, RTLD_NOW | RTLD_LOCAL);
    if (!g_lib) {
        noter("ECHEC de l'ouverture : %s", dlerror());
        (*env)->ReleaseStringUTFChars(env, chemin, c);
        (*env)->ReleaseStringUTFChars(env, dossier, d);
        return JNI_FALSE;
    }

    bool manque = false;
    LIER("retro_set_environment", p_set_environment);
    LIER("retro_set_video_refresh", p_set_video_refresh);
    LIER("retro_set_audio_sample", p_set_audio_sample);
    LIER("retro_set_audio_sample_batch", p_set_audio_sample_batch);
    LIER("retro_set_input_poll", p_set_input_poll);
    LIER("retro_set_input_state", p_set_input_state);
    LIER("retro_init", p_init);
    LIER("retro_deinit", p_deinit);
    LIER("retro_load_game", p_load_game);
    LIER("retro_unload_game", p_unload_game);
    LIER("retro_run", p_run);
    LIER("retro_reset", p_reset);
    LIER("retro_get_system_av_info", p_get_system_av_info);
    LIER("retro_set_controller_port_device", p_set_controller_port_device);
    LIER("retro_serialize_size", p_serialize_size);
    LIER("retro_serialize", p_serialize);
    LIER("retro_unserialize", p_unserialize);
    /* Les triches sont facultatives : leur absence ne doit pas tout arreter. */
    LIER_MOU("retro_cheat_reset", p_cheat_reset);
    LIER_MOU("retro_cheat_set", p_cheat_set);

    (*env)->ReleaseStringUTFChars(env, chemin, c);
    (*env)->ReleaseStringUTFChars(env, dossier, d);
    if (manque) return JNI_FALSE;
    noter("codes de triche : %s",
          (p_cheat_set && p_cheat_reset) ? "acceptes par le coeur" : "NON pris en charge");

    /* Reglages, poses AVANT retro_init. */
    var_poser("genesis_plus_gx_system_hw", "auto");
    var_poser("genesis_plus_gx_region_detect", "auto");
    var_poser("genesis_plus_gx_frameskip", "disabled");
    var_poser("genesis_plus_gx_lcd_filter", "disabled");
    var_poser("genesis_plus_gx_overscan", "disabled");
    var_poser("genesis_plus_gx_ym2413", "auto");
    /* Gambatte */
    var_poser("gambatte_gb_colorization", "auto");
    var_poser("gambatte_gb_internal_palette", "GBC - Grayscale");
    var_poser("gambatte_gbc_color_correction", "GBC only");
    var_poser("gambatte_mix_frames", "disabled");
    var_poser("gambatte_up_down_allowed", "disabled");
    /* mGBA */
    var_poser("mgba_solar_sensor_level", "0");
    var_poser("mgba_allow_opposing_directions", "no");
    var_poser("mgba_frameskip", "disabled");

    p_set_environment(environnement);
    p_set_video_refresh(video);
    p_set_audio_sample(son_un);
    p_set_audio_sample_batch(son_lot);
    p_set_input_poll(manette_poll);
    p_set_input_state(manette_etat);

    g_etape = "initialisation du coeur";
    p_init();
    g_init = true;
    noter("coeur pret, %d reglages poses", g_nvars);
    return JNI_TRUE;
}

/* ==================================================== chargement */

JNIEXPORT jboolean JNICALL
Java_com_rudy_chambre_core_CoeurPlus_natCharger(JNIEnv *env, jobject self, jstring jeu) {
    (void)self;
    if (!g_init) return JNI_FALSE;
    const char *c = (*env)->GetStringUTFChars(env, jeu, NULL);

    g_etape = "chargement du jeu";
    noter("chargement : %s", c);

    /*
     * On lit la cartouche EN MEMOIRE.
     *
     * Une cartouche Game Boy pese au plus quelques megaoctets : la charger
     * entierement evite de dependre du chemin, qu'Android ne laisse pas
     * toujours ouvrir.
     */
    FILE *f = fopen(c, "rb");
    if (!f) {
        noter("REFUS : impossible d'ouvrir le fichier (%s)", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, jeu, c);
        return JNI_FALSE;
    }
    fseek(f, 0, SEEK_END);
    long taille = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (taille <= 0 || taille > 16 * 1024 * 1024) {
        noter("REFUS : taille inattendue (%ld octets)", taille);
        fclose(f);
        (*env)->ReleaseStringUTFChars(env, jeu, c);
        return JNI_FALSE;
    }
    void *donnees = malloc((size_t)taille);
    if (!donnees || fread(donnees, 1, (size_t)taille, f) != (size_t)taille) {
        noter("REFUS : lecture incomplete");
        free(donnees);
        fclose(f);
        (*env)->ReleaseStringUTFChars(env, jeu, c);
        return JNI_FALSE;
    }
    fclose(f);
    noter("  cartouche lue : %ld octets", taille);

    struct retro_game_info info;
    memset(&info, 0, sizeof info);
    info.path = c;
    info.data = donnees;
    info.size = (size_t)taille;

    bool ok = p_load_game(&info);
    free(donnees);
    (*env)->ReleaseStringUTFChars(env, jeu, c);
    if (!ok) {
        noter("REFUS : le coeur n'a pas accepte le jeu");
        return JNI_FALSE;
    }

    struct retro_system_av_info av;
    memset(&av, 0, sizeof av);
    p_get_system_av_info(&av);
    if (av.geometry.base_width) g_bw = av.geometry.base_width;
    if (av.geometry.base_height) g_bh = av.geometry.base_height;
    if (av.timing.fps > 1.0) g_fps = av.timing.fps;
    if (av.timing.sample_rate > 1000.0) g_sample_rate = av.timing.sample_rate;

    p_set_controller_port_device(0, DEVICE_JOYPAD);
    g_charge = true;
    g_images = 0;
    g_image_prete = false;
    noter("jeu pret : %u x %u, %.0f Hz, %.4f images/s",
          g_bw, g_bh, g_sample_rate, g_fps);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_rudy_chambre_core_CoeurPlus_natDecharger(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    if (!g_charge) return;
    g_etape = "dechargement";
    p_unload_game();
    g_charge = false;
    g_image_prete = false;
}

/* ==================================================== emulation */

JNIEXPORT void JNICALL
Java_com_rudy_chambre_core_CoeurPlus_natImage(JNIEnv *env, jobject self, jint boutons) {
    (void)env; (void)self;
    if (!g_charge) return;
    g_boutons = boutons;
    g_etape = "emulation d'une image";
    p_run();
    g_etape = "apres l'emulation";
    g_images++;
}

/* Depose la derniere image. Renvoie largeur et hauteur empaquetees. */
JNIEXPORT jint JNICALL
Java_com_rudy_chambre_core_CoeurPlus_natLireImage(JNIEnv *env, jobject self, jintArray sortie) {
    (void)self;
    if (!g_charge || !g_image_prete) return 0;
    jsize cap = (*env)->GetArrayLength(env, sortie);
    if ((jsize)(g_il * g_ih) > cap) return 0;
    (*env)->SetIntArrayRegion(env, sortie, 0, (jsize)(g_il * g_ih), (const jint *)g_pixels);
    g_image_prete = false;
    return (jint)((g_il << 16) | g_ih);
}

JNIEXPORT jint JNICALL
Java_com_rudy_chambre_core_CoeurPlus_natSonLire(JNIEnv *env, jobject self, jshortArray sortie) {
    (void)self;
    jsize cap = (*env)->GetArrayLength(env, sortie);
    int dispo = (g_son_ecrit - g_son_lu + SON_MAX) % SON_MAX;
    if (dispo > cap) dispo = (int)cap;
    if (dispo <= 0) return 0;
    if (g_son_lu + dispo <= SON_MAX) {
        (*env)->SetShortArrayRegion(env, sortie, 0, dispo, g_son + g_son_lu);
    } else {
        int premier = SON_MAX - g_son_lu;
        (*env)->SetShortArrayRegion(env, sortie, 0, premier, g_son + g_son_lu);
        (*env)->SetShortArrayRegion(env, sortie, premier, dispo - premier, g_son);
    }
    g_son_lu = (g_son_lu + dispo) % SON_MAX;
    return dispo;
}

/* ==================================================== triches */

/*
 * Les codes de triche.
 *
 * Genesis Plus GX accepte les codes Game Genie — quatre caracteres, un tiret,
 * quatre — et les codes bruts de la forme adresse:valeur. On les lui passe
 * tels quels : c'est lui qui reconnait le format et les interprete.
 */
JNIEXPORT void JNICALL
Java_com_rudy_chambre_core_CoeurPlus_natTrichesEffacer(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    if (p_cheat_reset) p_cheat_reset();
}

JNIEXPORT void JNICALL
Java_com_rudy_chambre_core_CoeurPlus_natTricheAjouter(JNIEnv *env, jobject self,
                                                jint rang, jstring code) {
    (void)self;
    if (!p_cheat_set) return;
    const char *c = (*env)->GetStringUTFChars(env, code, NULL);
    p_cheat_set((unsigned)rang, true, c);
    noter("triche posee (%d) : %s", (int)rang, c);
    (*env)->ReleaseStringUTFChars(env, code, c);
}

JNIEXPORT jboolean JNICALL
Java_com_rudy_chambre_core_CoeurPlus_natTrichesPossibles(JNIEnv *e, jobject s) {
    (void)e; (void)s;
    return (p_cheat_set && p_cheat_reset) ? JNI_TRUE : JNI_FALSE;
}

/* ==================================================== reglages et etat */

JNIEXPORT void JNICALL
Java_com_rudy_chambre_core_CoeurPlus_natVariable(JNIEnv *env, jobject self,
                                           jstring cle, jstring val) {
    (void)self;
    const char *k = (*env)->GetStringUTFChars(env, cle, NULL);
    const char *v = (*env)->GetStringUTFChars(env, val, NULL);
    var_poser(k, v);
    noter("reglage : %s = %s", k, v);
    (*env)->ReleaseStringUTFChars(env, cle, k);
    (*env)->ReleaseStringUTFChars(env, val, v);
}

JNIEXPORT void JNICALL
Java_com_rudy_chambre_core_CoeurPlus_natReinitialiser(JNIEnv *e, jobject s) {
    (void)e; (void)s;
    if (g_charge) p_reset();
}

JNIEXPORT jdouble JNICALL
Java_com_rudy_chambre_core_CoeurPlus_natImagesParSeconde(JNIEnv *e, jobject s) {
    (void)e; (void)s; return g_fps;
}

JNIEXPORT jint JNICALL
Java_com_rudy_chambre_core_CoeurPlus_natFrequence(JNIEnv *e, jobject s) {
    (void)e; (void)s; return (jint)g_sample_rate;
}

JNIEXPORT jint JNICALL
Java_com_rudy_chambre_core_CoeurPlus_natClarte(JNIEnv *e, jobject s) {
    (void)e; (void)s; return (jint)g_clarte;
}

JNIEXPORT jint JNICALL
Java_com_rudy_chambre_core_CoeurPlus_natImagesEmulees(JNIEnv *e, jobject s) {
    (void)e; (void)s; return (jint)(g_images & 0x7FFFFFFF);
}

JNIEXPORT jbyteArray JNICALL
Java_com_rudy_chambre_core_CoeurPlus_natSauver(JNIEnv *env, jobject self) {
    (void)self;
    if (!g_charge) return NULL;
    size_t n = p_serialize_size();
    if (!n) return NULL;
    void *tampon = malloc(n);
    if (!tampon) return NULL;
    bool ok = p_serialize(tampon, n);
    jbyteArray r = NULL;
    if (ok) {
        r = (*env)->NewByteArray(env, (jsize)n);
        if (r) (*env)->SetByteArrayRegion(env, r, 0, (jsize)n, tampon);
    }
    free(tampon);
    return r;
}

JNIEXPORT jboolean JNICALL
Java_com_rudy_chambre_core_CoeurPlus_natRestaurer(JNIEnv *env, jobject self, jbyteArray etat) {
    (void)self;
    if (!g_charge || !etat) return JNI_FALSE;
    jsize n = (*env)->GetArrayLength(env, etat);
    jbyte *p = (*env)->GetByteArrayElements(env, etat, NULL);
    bool ok = p_unserialize(p, (size_t)n);
    (*env)->ReleaseByteArrayElements(env, etat, p, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
