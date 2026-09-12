/*
 * Pont entre l'application et le coeur PCSX-ReARMed (format libretro).
 *
 * Par rapport au pont Super Nintendo, trois choses changent :
 *  - le jeu est charge par son chemin, pas par son contenu : une image de
 *    disque pese des centaines de Mo et se compose souvent de plusieurs
 *    fichiers (.cue + .bin) ;
 *  - la manette a deux sticks analogiques, lus en continu ;
 *  - le coeur se configure par des variables texte (type de manette, rendu
 *    ameliore...), que Kotlin fixe avant et pendant la partie.
 */
#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define TAG "SkinPS1"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ---------- extraits de libretro.h ---------- */
struct retro_game_info { const char *path; const void *data; size_t size; const char *meta; };
struct retro_game_geometry { unsigned base_width, base_height, max_width, max_height; float aspect_ratio; };
struct retro_system_timing { double fps, sample_rate; };
struct retro_system_av_info { struct retro_game_geometry geometry; struct retro_system_timing timing; };
struct retro_variable { const char *key; const char *value; };
struct retro_log_callback { void (*log)(int level, const char *fmt, ...); };

typedef bool   (*retro_environment_t)(unsigned cmd, void *data);
typedef void   (*retro_video_refresh_t)(const void *data, unsigned w, unsigned h, size_t pitch);
typedef void   (*retro_audio_sample_t)(int16_t l, int16_t r);
typedef size_t (*retro_audio_sample_batch_t)(const int16_t *data, size_t frames);
typedef void   (*retro_input_poll_t)(void);
typedef int16_t(*retro_input_state_t)(unsigned port, unsigned device, unsigned index, unsigned id);

enum {
    ENV_GET_CAN_DUPE = 3, ENV_GET_SYSTEM_DIRECTORY = 9, ENV_SET_PIXEL_FORMAT = 10,
    ENV_GET_VARIABLE = 15, ENV_SET_VARIABLES = 16, ENV_GET_VARIABLE_UPDATE = 17,
    ENV_SET_SUPPORT_NO_GAME = 18, ENV_GET_LOG_INTERFACE = 27, ENV_GET_SAVE_DIRECTORY = 31,
    ENV_GET_LANGUAGE = 39, ENV_GET_INPUT_BITMASKS = 51, ENV_GET_CORE_OPTIONS_VERSION = 52, ENV_SET_CORE_OPTIONS = 53,
    ENV_SET_CORE_OPTIONS_INTL = 54, ENV_SET_CORE_OPTIONS_V2 = 67, ENV_SET_CORE_OPTIONS_V2_INTL = 68
};
enum { PIX_0RGB1555 = 0, PIX_XRGB8888 = 1, PIX_RGB565 = 2 };
enum { DEVICE_JOYPAD = 1, DEVICE_ANALOG = 5 };
enum { ANALOG_GAUCHE = 0, ANALOG_DROIT = 1, ANALOG_X = 0, ANALOG_Y = 1 };

/* ---------- fonctions du coeur ---------- */
static void   (*p_set_environment)(retro_environment_t);
static void   (*p_set_video_refresh)(retro_video_refresh_t);
static void   (*p_set_audio_sample)(retro_audio_sample_t);
static void   (*p_set_audio_sample_batch)(retro_audio_sample_batch_t);
static void   (*p_set_input_poll)(retro_input_poll_t);
static void   (*p_set_input_state)(retro_input_state_t);
static void   (*p_init)(void);
static void   (*p_deinit)(void);
static bool   (*p_load_game)(const struct retro_game_info *);
static void   (*p_unload_game)(void);
static void   (*p_run)(void);
static void   (*p_reset)(void);
static void   (*p_get_system_av_info)(struct retro_system_av_info *);
static void   (*p_set_controller_port_device)(unsigned, unsigned);
static size_t (*p_serialize_size)(void);
static bool   (*p_serialize)(void *, size_t);
static bool   (*p_unserialize)(const void *, size_t);

static void *g_lib = NULL;
static bool g_init = false, g_charge = false;
static char g_dossier[512] = "/data/local/tmp";
static int g_format = PIX_0RGB1555;

/* ---------- variables de configuration ---------- */
#define MAX_VARS 32
static char g_var_cle[MAX_VARS][64];
static char g_var_val[MAX_VARS][64];
static int g_nvars = 0;
static bool g_vars_changees = true;

static void var_poser(const char *cle, const char *val) {
    for (int i = 0; i < g_nvars; i++)
        if (!strcmp(g_var_cle[i], cle)) {
            if (strcmp(g_var_val[i], val)) { strncpy(g_var_val[i], val, 63); g_vars_changees = true; }
            return;
        }
    if (g_nvars < MAX_VARS) {
        strncpy(g_var_cle[g_nvars], cle, 63);
        strncpy(g_var_val[g_nvars], val, 63);
        g_nvars++; g_vars_changees = true;
    }
}

static const char *var_lire(const char *cle) {
    for (int i = 0; i < g_nvars; i++) if (!strcmp(g_var_cle[i], cle)) return g_var_val[i];
    return NULL;
}

/* ---------- image ---------- */
#define MAXW 1280
#define MAXH 1024
static uint32_t g_image[MAXW * MAXH];
static unsigned g_w = 320, g_h = 240;
static bool g_nouvelle = false;

/* ---------- son ---------- */
#define SON_CAP (48000 * 2)            /* une seconde stereo */
static int16_t g_son[SON_CAP];
static size_t g_son_n = 0;
static double g_sample_rate = 44100.0, g_fps = 59.94;

/* ---------- manette ---------- */
static int g_boutons = 0;              /* masque libretro : bit = id */
static int16_t g_stick[2][2] = {{0, 0}, {0, 0}};

/* ============================================================ rappels */
static void journal(int level, const char *fmt, ...) {
    (void)level; (void)fmt;            /* silencieux : le coeur est bavard */
}

static bool environnement(unsigned cmd, void *data) {
    switch (cmd & 0xFFFF) {
        case ENV_GET_CAN_DUPE: *(bool *)data = true; return true;
        case ENV_GET_SYSTEM_DIRECTORY:
        case ENV_GET_SAVE_DIRECTORY:
            *(const char **)data = g_dossier; return true;
        case ENV_SET_PIXEL_FORMAT:
            g_format = *(int *)data;
            return g_format == PIX_RGB565 || g_format == PIX_XRGB8888 || g_format == PIX_0RGB1555;
        case ENV_GET_LOG_INTERFACE:
            ((struct retro_log_callback *)data)->log = (void (*)(int, const char *, ...))journal;
            return true;
        case ENV_GET_LANGUAGE: *(unsigned *)data = 3; return true;   /* francais */
        case ENV_GET_VARIABLE: {
            struct retro_variable *v = (struct retro_variable *)data;
            v->value = var_lire(v->key);
            return v->value != NULL;
        }
        case ENV_GET_VARIABLE_UPDATE:
            *(bool *)data = g_vars_changees; g_vars_changees = false; return true;
        case ENV_SET_VARIABLES: case ENV_SET_CORE_OPTIONS: case ENV_SET_CORE_OPTIONS_INTL:
        case ENV_SET_CORE_OPTIONS_V2: case ENV_SET_CORE_OPTIONS_V2_INTL:
            return true;
        case ENV_GET_CORE_OPTIONS_VERSION: *(unsigned *)data = 2; return true;
        case ENV_SET_SUPPORT_NO_GAME: return true;
        case ENV_GET_INPUT_BITMASKS: return true;
        default: return false;
    }
}

static void video(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!data || w > MAXW || h > MAXH) return;      /* image dupliquee : on garde la precedente */
    g_w = w; g_h = h;
    const uint8_t *src = (const uint8_t *)data;
    for (unsigned y = 0; y < h; y++) {
        uint32_t *dst = g_image + y * w;
        if (g_format == PIX_RGB565) {
            const uint16_t *s = (const uint16_t *)(src + y * pitch);
            for (unsigned x = 0; x < w; x++) {
                uint16_t p = s[x];
                unsigned r = (p >> 11) & 31, g = (p >> 5) & 63, b = p & 31;
                dst[x] = 0xFF000000u | ((r << 3 | r >> 2) << 16) | ((g << 2 | g >> 4) << 8) | (b << 3 | b >> 2);
            }
        } else if (g_format == PIX_XRGB8888) {
            const uint32_t *s = (const uint32_t *)(src + y * pitch);
            for (unsigned x = 0; x < w; x++) dst[x] = 0xFF000000u | (s[x] & 0x00FFFFFFu);
        } else {                                       /* 0RGB1555 */
            const uint16_t *s = (const uint16_t *)(src + y * pitch);
            for (unsigned x = 0; x < w; x++) {
                uint16_t p = s[x];
                unsigned r = (p >> 10) & 31, g = (p >> 5) & 31, b = p & 31;
                dst[x] = 0xFF000000u | ((r << 3 | r >> 2) << 16) | ((g << 3 | g >> 2) << 8) | (b << 3 | b >> 2);
            }
        }
    }
    g_nouvelle = true;
}

static void son_un(int16_t l, int16_t r) {
    if (g_son_n + 2 <= SON_CAP) { g_son[g_son_n++] = l; g_son[g_son_n++] = r; }
}

static size_t son_lot(const int16_t *data, size_t frames) {
    size_t n = frames * 2;
    if (g_son_n + n > SON_CAP) n = SON_CAP - g_son_n;
    memcpy(g_son + g_son_n, data, n * sizeof(int16_t));
    g_son_n += n;
    return frames;
}

static void manette_poll(void) {}

static int16_t manette_etat(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0) return 0;
    if (device == DEVICE_JOYPAD) {
        if (id == 256) return (int16_t)(g_boutons & 0xFFFF);   /* masque complet */
        return (id <= 15) ? (g_boutons >> id) & 1 : 0;
    }
    if (device == DEVICE_ANALOG && index <= 1 && id <= 1) return g_stick[index][id];
    return 0;
}

/* ============================================================ JNI */
#define CHARGE(nom) do { p_##nom = dlsym(g_lib, "retro_" #nom); \
    if (!p_##nom) { LOGE("symbole manquant : retro_" #nom); return JNI_FALSE; } } while (0)

JNIEXPORT jboolean JNICALL
Java_com_skinps1_app_core_CoeurPs1_natInit(JNIEnv *env, jobject self, jstring chemin, jstring dossier) {
    (void)self;
    if (g_init) return JNI_TRUE;
    const char *c = (*env)->GetStringUTFChars(env, chemin, NULL);
    const char *d = (*env)->GetStringUTFChars(env, dossier, NULL);
    strncpy(g_dossier, d, sizeof(g_dossier) - 1);
    g_lib = dlopen(c, RTLD_NOW | RTLD_LOCAL);
    (*env)->ReleaseStringUTFChars(env, chemin, c);
    (*env)->ReleaseStringUTFChars(env, dossier, d);
    if (!g_lib) { LOGE("dlopen : %s", dlerror()); return JNI_FALSE; }

    CHARGE(set_environment); CHARGE(set_video_refresh); CHARGE(set_audio_sample);
    CHARGE(set_audio_sample_batch); CHARGE(set_input_poll); CHARGE(set_input_state);
    CHARGE(init); CHARGE(deinit); CHARGE(load_game); CHARGE(unload_game); CHARGE(run);
    CHARGE(reset); CHARGE(get_system_av_info); CHARGE(set_controller_port_device);
    CHARGE(serialize_size); CHARGE(serialize); CHARGE(unserialize);

    /* configuration par defaut : manette DualShock, rendu natif */
    var_poser("pcsx_rearmed_pad1type", "dualshock");
    var_poser("pcsx_rearmed_neon_enhancement_enable", "disabled");
    var_poser("pcsx_rearmed_neon_enhancement_no_main", "disabled");
    var_poser("pcsx_rearmed_show_bios_bootlogo", "enabled");

    p_set_environment(environnement);
    p_set_video_refresh(video);
    p_set_audio_sample(son_un);
    p_set_audio_sample_batch(son_lot);
    p_set_input_poll(manette_poll);
    p_set_input_state(manette_etat);
    p_init();
    g_init = true;
    LOGI("coeur PCSX-ReARMed pret");
    return JNI_TRUE;
}

/* Le jeu est charge par son chemin : le coeur lit lui-meme l'image de disque. */
JNIEXPORT jboolean JNICALL
Java_com_skinps1_app_core_CoeurPs1_natCharger(JNIEnv *env, jobject self, jstring chemin) {
    (void)self;
    if (!g_init) return JNI_FALSE;
    if (g_charge) { p_unload_game(); g_charge = false; }
    const char *c = (*env)->GetStringUTFChars(env, chemin, NULL);
    static char copie[1024];
    strncpy(copie, c, sizeof(copie) - 1);
    (*env)->ReleaseStringUTFChars(env, chemin, c);

    struct retro_game_info info = { copie, NULL, 0, NULL };
    if (!p_load_game(&info)) { LOGE("jeu refuse : %s", copie); return JNI_FALSE; }
    struct retro_system_av_info av;
    memset(&av, 0, sizeof av);
    p_get_system_av_info(&av);
    if (av.timing.sample_rate > 1000) g_sample_rate = av.timing.sample_rate;
    if (av.timing.fps > 10) g_fps = av.timing.fps;
    /* Le port doit etre declare JOYPAD : c'est la variable pcsx_rearmed_pad1type
       qui en fait une DualShock. Declare ANALOG, le coeur cessait de lire les
       boutons numeriques, d'ou des touches qui s'animaient sans effet. */
    p_set_controller_port_device(0, DEVICE_JOYPAD);
    g_charge = true;
    g_son_n = 0;
    LOGI("jeu charge : %u x %u, %.0f Hz, %.3f i/s", av.geometry.base_width, av.geometry.base_height,
         g_sample_rate, g_fps);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_skinps1_app_core_CoeurPs1_natVariable(JNIEnv *env, jobject self, jstring cle, jstring val) {
    (void)self;
    const char *k = (*env)->GetStringUTFChars(env, cle, NULL);
    const char *v = (*env)->GetStringUTFChars(env, val, NULL);
    var_poser(k, v);
    (*env)->ReleaseStringUTFChars(env, cle, k);
    (*env)->ReleaseStringUTFChars(env, val, v);
}

/* Une image : boutons + deux sticks (de -32768 a 32767). Renvoie largeur << 16 | hauteur. */
JNIEXPORT jint JNICALL
Java_com_skinps1_app_core_CoeurPs1_natImage(JNIEnv *env, jobject self, jint boutons,
                                            jint gx, jint gy, jint dx, jint dy, jintArray sortie) {
    (void)self;
    if (!g_charge) return 0;
    g_boutons = boutons;
    g_stick[ANALOG_GAUCHE][ANALOG_X] = (int16_t)gx; g_stick[ANALOG_GAUCHE][ANALOG_Y] = (int16_t)gy;
    g_stick[ANALOG_DROIT][ANALOG_X] = (int16_t)dx;  g_stick[ANALOG_DROIT][ANALOG_Y] = (int16_t)dy;
    g_nouvelle = false;
    p_run();
    if (!g_nouvelle) return 0;
    jsize cap = (*env)->GetArrayLength(env, sortie);
    jsize n = (jsize)(g_w * g_h);
    if (n > cap) n = cap;
    (*env)->SetIntArrayRegion(env, sortie, 0, n, (const jint *)g_image);
    return (jint)((g_w << 16) | g_h);
}

JNIEXPORT jint JNICALL
Java_com_skinps1_app_core_CoeurPs1_natSon(JNIEnv *env, jobject self, jshortArray sortie) {
    (void)self;
    jsize cap = (*env)->GetArrayLength(env, sortie);
    jsize n = (jsize)g_son_n;
    if (n > cap) n = cap;
    (*env)->SetShortArrayRegion(env, sortie, 0, n, g_son);
    g_son_n = 0;
    return n;
}

JNIEXPORT jdouble JNICALL
Java_com_skinps1_app_core_CoeurPs1_natFrequence(JNIEnv *env, jobject self) {
    (void)env; (void)self; return g_sample_rate;
}

JNIEXPORT jdouble JNICALL
Java_com_skinps1_app_core_CoeurPs1_natFps(JNIEnv *env, jobject self) {
    (void)env; (void)self; return g_fps;
}

JNIEXPORT void JNICALL
Java_com_skinps1_app_core_CoeurPs1_natReset(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    if (g_charge) p_reset();
}

JNIEXPORT void JNICALL
Java_com_skinps1_app_core_CoeurPs1_natEjecter(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    if (g_charge) { p_unload_game(); g_charge = false; }
    memset(g_image, 0, sizeof g_image);
    g_son_n = 0;
}

JNIEXPORT jbyteArray JNICALL
Java_com_skinps1_app_core_CoeurPs1_natSauver(JNIEnv *env, jobject self) {
    (void)self;
    if (!g_charge) return NULL;
    size_t n = p_serialize_size();
    if (!n) return NULL;
    void *buf = malloc(n);
    if (!p_serialize(buf, n)) { free(buf); return NULL; }
    jbyteArray out = (*env)->NewByteArray(env, (jsize)n);
    (*env)->SetByteArrayRegion(env, out, 0, (jsize)n, (const jbyte *)buf);
    free(buf);
    return out;
}

JNIEXPORT jboolean JNICALL
Java_com_skinps1_app_core_CoeurPs1_natRestaurer(JNIEnv *env, jobject self, jbyteArray donnees) {
    (void)self;
    if (!g_charge) return JNI_FALSE;
    jsize n = (*env)->GetArrayLength(env, donnees);
    jbyte *buf = (*env)->GetByteArrayElements(env, donnees, NULL);
    bool ok = p_unserialize(buf, (size_t)n);
    (*env)->ReleaseByteArrayElements(env, donnees, buf, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
