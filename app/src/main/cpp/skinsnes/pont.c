/*
 * Pont entre l'application et le coeur Snes9x, au format libretro.
 *
 * Le coeur est une bibliotheque partagee telechargee toute compilee. On
 * l'ouvre a l'execution, on lui fournit les rappels qu'il attend (image,
 * son, manette, environnement), et on expose a Kotlin cinq operations :
 * charger une ROM, avancer d'une image, lire le son, sauver et restaurer.
 *
 * Aucun en-tete libretro n'est necessaire : les quelques constantes et
 * structures utilisees sont redeclarees ici, a l'identique.
 */
#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define TAG "SkinSNES"
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
    ENV_GET_LOG_INTERFACE = 27, ENV_GET_SAVE_DIRECTORY = 31, ENV_GET_LANGUAGE = 39
};
enum { PIX_0RGB1555 = 0, PIX_XRGB8888 = 1, PIX_RGB565 = 2 };
enum { DEVICE_JOYPAD = 1 };

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
static size_t (*p_serialize_size)(void);
static bool   (*p_serialize)(void *, size_t);
static bool   (*p_unserialize)(const void *, size_t);

static void *g_lib = NULL;
static bool g_init = false, g_charge = false;
static char g_dossier[512] = "/data/local/tmp";
static int g_format = PIX_0RGB1555;

/* ---------- image ---------- */
#define MAXW 512
#define MAXH 478
static uint32_t g_image[MAXW * MAXH];
static unsigned g_w = 256, g_h = 224;
static bool g_nouvelle = false;

/* ---------- son ---------- */
#define SON_CAP (32040 * 2)            /* une seconde stereo */
static int16_t g_son[SON_CAP];
static size_t g_son_n = 0;
static double g_sample_rate = 32040.0, g_fps = 60.0988;

/* ---------- manette ---------- */
static int g_boutons = 0;              /* masque libretro : bit = id */

/* ============================================================ rappels */
static void journal(int level, const char *fmt, ...) {
    (void)level; (void)fmt;            /* silencieux : le coeur est bavard */
}

static bool environnement(unsigned cmd, void *data) {
    switch (cmd) {
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
        case ENV_GET_VARIABLE: ((struct retro_variable *)data)->value = NULL; return false;
        case ENV_GET_VARIABLE_UPDATE: *(bool *)data = false; return true;
        case ENV_SET_VARIABLES: return true;
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
    (void)index;
    if (port != 0 || device != DEVICE_JOYPAD || id > 15) return 0;
    return (g_boutons >> id) & 1;
}

/* ============================================================ JNI */
#define CHARGE(nom) do { p_##nom = dlsym(g_lib, "retro_" #nom); \
    if (!p_##nom) { LOGE("symbole manquant : retro_" #nom); return JNI_FALSE; } } while (0)

JNIEXPORT jboolean JNICALL
Java_com_skinsnes_app_core_CoeurSnes_natInit(JNIEnv *env, jobject self, jstring chemin, jstring dossier) {
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
    CHARGE(reset); CHARGE(get_system_av_info); CHARGE(serialize_size); CHARGE(serialize);
    CHARGE(unserialize);

    p_set_environment(environnement);
    p_set_video_refresh(video);
    p_set_audio_sample(son_un);
    p_set_audio_sample_batch(son_lot);
    p_set_input_poll(manette_poll);
    p_set_input_state(manette_etat);
    p_init();
    g_init = true;
    LOGI("coeur Snes9x pret");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_skinsnes_app_core_CoeurSnes_natCharger(JNIEnv *env, jobject self, jbyteArray rom) {
    (void)self;
    if (!g_init) return JNI_FALSE;
    if (g_charge) { p_unload_game(); g_charge = false; }
    jsize n = (*env)->GetArrayLength(env, rom);
    /* le coeur peut garder le pointeur : on copie dans un tampon a nous */
    static uint8_t *copie = NULL;
    free(copie);
    copie = malloc(n);
    (*env)->GetByteArrayRegion(env, rom, 0, n, (jbyte *)copie);
    struct retro_game_info info = { "rom.sfc", copie, (size_t)n, NULL };
    if (!p_load_game(&info)) { LOGE("ROM refusee par le coeur"); return JNI_FALSE; }
    struct retro_system_av_info av;
    memset(&av, 0, sizeof av);
    p_get_system_av_info(&av);
    if (av.timing.sample_rate > 1000) g_sample_rate = av.timing.sample_rate;
    if (av.timing.fps > 10) g_fps = av.timing.fps;
    g_charge = true;
    g_son_n = 0;
    LOGI("ROM chargee : %u x %u, %.0f Hz, %.4f i/s", av.geometry.base_width, av.geometry.base_height,
         g_sample_rate, g_fps);
    return JNI_TRUE;
}

/* Avance d'une image. Renvoie largeur << 16 | hauteur, ou 0 si pas de nouvelle image. */
JNIEXPORT jint JNICALL
Java_com_skinsnes_app_core_CoeurSnes_natImage(JNIEnv *env, jobject self, jint boutons, jintArray sortie) {
    (void)self;
    if (!g_charge) return 0;
    g_boutons = boutons;
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
Java_com_skinsnes_app_core_CoeurSnes_natSon(JNIEnv *env, jobject self, jshortArray sortie) {
    (void)self;
    jsize cap = (*env)->GetArrayLength(env, sortie);
    jsize n = (jsize)g_son_n;
    if (n > cap) n = cap;
    (*env)->SetShortArrayRegion(env, sortie, 0, n, g_son);
    g_son_n = 0;
    return n;
}

JNIEXPORT jdouble JNICALL
Java_com_skinsnes_app_core_CoeurSnes_natFrequence(JNIEnv *env, jobject self) {
    (void)env; (void)self; return g_sample_rate;
}

JNIEXPORT jdouble JNICALL
Java_com_skinsnes_app_core_CoeurSnes_natFps(JNIEnv *env, jobject self) {
    (void)env; (void)self; return g_fps;
}

JNIEXPORT void JNICALL
Java_com_skinsnes_app_core_CoeurSnes_natReset(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    if (g_charge) p_reset();
}

JNIEXPORT void JNICALL
Java_com_skinsnes_app_core_CoeurSnes_natEjecter(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    if (g_charge) { p_unload_game(); g_charge = false; }
    memset(g_image, 0, sizeof g_image);
    g_son_n = 0;
}

JNIEXPORT jbyteArray JNICALL
Java_com_skinsnes_app_core_CoeurSnes_natSauver(JNIEnv *env, jobject self) {
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
Java_com_skinsnes_app_core_CoeurSnes_natRestaurer(JNIEnv *env, jobject self, jbyteArray donnees) {
    (void)self;
    if (!g_charge) return JNI_FALSE;
    jsize n = (*env)->GetArrayLength(env, donnees);
    jbyte *buf = (*env)->GetByteArrayElements(env, donnees, NULL);
    bool ok = p_unserialize(buf, (size_t)n);
    (*env)->ReleaseByteArrayElements(env, donnees, buf, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
