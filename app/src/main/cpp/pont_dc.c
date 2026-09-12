/*
 * Pont entre l'application et le coeur Flycast (format libretro).
 *
 * Difference majeure avec les ponts NES, Super Nintendo et PlayStation :
 * Flycast n'a pas de rendu logiciel. Il dessine en OpenGL dans un tampon que
 * nous devons lui fournir. Toute l'emulation se deroule donc sur le fil du
 * rendu, et le coeur ne nous rend plus une image en memoire : il remplit une
 * texture que l'application affiche ensuite dans le cadre du skin.
 *
 * D'ou les trois fonctions supplementaires appelees depuis le fil GL :
 *   natGlInit    cree le tampon de rendu et declare le contexte au coeur
 *   natGlTaille  redimensionne ce tampon quand la resolution interne change
 *   natGlImage   avance d'une image et renvoie la texture remplie
 */
#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <stdio.h>
#include <stdarg.h>
#include <unistd.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>

#define TAG "SkinDC"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ---------- extraits de libretro.h ---------- */
struct retro_game_info { const char *path; const void *data; size_t size; const char *meta; };
struct retro_game_geometry { unsigned base_width, base_height, max_width, max_height; float aspect_ratio; };
struct retro_system_timing { double fps, sample_rate; };
struct retro_system_av_info { struct retro_game_geometry geometry; struct retro_system_timing timing; };
struct retro_variable { const char *key; const char *value; };
struct retro_log_callback { void (*log)(int level, const char *fmt, ...); };

typedef uintptr_t (*retro_hw_get_current_framebuffer_t)(void);
typedef void *(*retro_proc_address_t)(void);
typedef retro_proc_address_t (*retro_hw_get_proc_address_t)(const char *sym);

struct retro_hw_render_callback {
    unsigned context_type;
    void (*context_reset)(void);
    retro_hw_get_current_framebuffer_t get_current_framebuffer;
    retro_hw_get_proc_address_t get_proc_address;
    bool depth, stencil, bottom_left_origin;
    unsigned version_major, version_minor;
    bool cache_context;
    void (*context_destroy)(void);
    bool debug_context;
};

typedef bool   (*retro_environment_t)(unsigned cmd, void *data);
typedef void   (*retro_video_refresh_t)(const void *data, unsigned w, unsigned h, size_t pitch);
typedef void   (*retro_audio_sample_t)(int16_t l, int16_t r);
typedef size_t (*retro_audio_sample_batch_t)(const int16_t *data, size_t frames);
typedef void   (*retro_input_poll_t)(void);
typedef int16_t(*retro_input_state_t)(unsigned port, unsigned device, unsigned index, unsigned id);

enum {
    ENV_GET_CAN_DUPE = 3, ENV_GET_SYSTEM_DIRECTORY = 9, ENV_SET_PIXEL_FORMAT = 10,
    ENV_SET_HW_RENDER = 14, ENV_GET_VARIABLE = 15, ENV_SET_VARIABLES = 16,
    ENV_GET_VARIABLE_UPDATE = 17, ENV_SET_SUPPORT_NO_GAME = 18,
    ENV_GET_LOG_INTERFACE = 27, ENV_GET_SAVE_DIRECTORY = 31, ENV_GET_LANGUAGE = 39,
    ENV_GET_PREFERRED_HW_RENDER = 56, ENV_GET_CORE_OPTIONS_VERSION = 52,
    ENV_SET_CORE_OPTIONS = 53, ENV_SET_CORE_OPTIONS_INTL = 54,
    ENV_SET_CORE_OPTIONS_V2 = 67, ENV_SET_CORE_OPTIONS_V2_INTL = 68
};
/* Valeurs exactes de libretro.h : mes constantes etaient decalees d'un cran,
   et je repondais "version personnalisee" la ou je croyais dire "GLES 3". */
enum { HW_NONE = 0, HW_OPENGL = 1, HW_OPENGLES2 = 2, HW_OPENGL_CORE = 3,
       HW_OPENGLES3 = 4, HW_OPENGLES_VERSION = 5, HW_VULKAN = 6 };
enum { PIX_0RGB1555 = 0, PIX_XRGB8888 = 1, PIX_RGB565 = 2 };
enum { DEVICE_JOYPAD = 1, DEVICE_ANALOG = 5 };
enum { ANALOG_GAUCHE = 0, ANALOG_DROIT = 1 };
#define FB_DUPLIQUE ((void *) -1)

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
static void   (*p_cheat_reset)(void);
static void   (*p_cheat_set)(unsigned index, bool enabled, const char *code);
static void  *(*p_get_memory_data)(unsigned id);
static size_t (*p_get_memory_size)(unsigned id);

static void *g_lib = NULL;
static bool g_init = false, g_charge = false;
static char g_dossier[512] = "/data/local/tmp";

/* ---------- variables de configuration ---------- */
#define MAX_VARS 48
static char g_cle[MAX_VARS][64], g_val[MAX_VARS][64];
static int g_nvars = 0;
static bool g_vars_changees = true;

static void var_poser(const char *cle, const char *val) {
    for (int i = 0; i < g_nvars; i++)
        if (!strcmp(g_cle[i], cle)) {
            if (strcmp(g_val[i], val)) { strncpy(g_val[i], val, 63); g_vars_changees = true; }
            return;
        }
    if (g_nvars < MAX_VARS) {
        strncpy(g_cle[g_nvars], cle, 63); strncpy(g_val[g_nvars], val, 63);
        g_nvars++; g_vars_changees = true;
    }
}
static const char *var_lire(const char *cle) {
    for (int i = 0; i < g_nvars; i++) if (!strcmp(g_cle[i], cle)) return g_val[i];
    return NULL;
}

/* ---------- rendu materiel ---------- */
static struct retro_hw_render_callback g_hw;
static bool g_hw_demande = false, g_hw_pret = false;
static GLuint g_fbo = 0, g_tex = 0, g_depth = 0;
static int g_fw = 640, g_fh = 480;      /* taille du tampon de rendu */
static unsigned g_bw = 640, g_bh = 480; /* taille utile annoncee par le coeur */
static unsigned g_maxw = 640, g_maxh = 480; /* taille maximale qu'il peut dessiner */

/* ---------- codes de triche ----------
 *
 * Les codes de la base RetroArch n'utilisent pas le mecanisme du coeur : ils
 * ecrivent une valeur a une adresse de la memoire vive de la console, et il
 * faut la reecrire a chaque image, sinon le jeu la remplace aussitot. On
 * recupere donc le pointeur sur cette memoire et on y depose les valeurs
 * apres chaque tour d'emulation.
 */
#define MEMOIRE_SYSTEME 2
#define MAX_TRICHES 512

typedef struct {
    unsigned adresse;
    unsigned valeur;
    unsigned char taille;      /* 1, 2 ou 4 octets */
    unsigned char gros_boutiste;
    unsigned short repetitions;
    int pas_adresse;
    int pas_valeur;
} Triche;

static Triche g_triches[MAX_TRICHES];
static int g_ntriches = 0;

static void ecrire(uint8_t *ram, size_t taille_ram, unsigned adr, unsigned val,
                   int noctets, int gros_boutiste) {
    if (adr + (unsigned)noctets > taille_ram) return;
    for (int i = 0; i < noctets; i++) {
        int dec = gros_boutiste ? (noctets - 1 - i) : i;
        ram[adr + i] = (uint8_t)((val >> (8 * dec)) & 0xFF);
    }
}

static void appliquer_triches(void) {
    if (!g_ntriches || !p_get_memory_data || !p_get_memory_size) return;
    uint8_t *ram = (uint8_t *)p_get_memory_data(MEMOIRE_SYSTEME);
    size_t n = p_get_memory_size(MEMOIRE_SYSTEME);
    if (!ram || !n) return;
    for (int i = 0; i < g_ntriches; i++) {
        Triche *t = &g_triches[i];
        unsigned a = t->adresse, v = t->valeur;
        int rep = t->repetitions ? t->repetitions : 1;
        for (int k = 0; k < rep; k++) {
            ecrire(ram, n, a, v, t->taille, t->gros_boutiste);
            a += (unsigned)t->pas_adresse;
            v += (unsigned)t->pas_valeur;
        }
    }
}

/* ---------- lecture de l'image ----------
 *
 * Plutot que de compter sur une surface OpenGL posee derriere la fenetre —
 * mecanisme capricieux qui depend du theme et du fabricant — on relit les
 * pixels du tampon de rendu et on les remet a l'application, qui les dessine
 * elle-meme dans le skin. C'est le chemin deja eprouve sur les autres
 * consoles, et il est verifiable : on peut mesurer ce qu'on a lu.
 */
static uint8_t *g_lecture = NULL;
static size_t g_lecture_taille = 0;
static int g_mesures = 0;

/* Relecture differee.
 *
 * Un glReadPixels ordinaire attend que le processeur graphique ait fini de
 * dessiner : on perd tout le parallelisme, et l'a-coup se sent. On passe donc
 * par deux tampons de transfert utilises en alternance — on demande la copie
 * d'une image et on releve celle demandee au tour precedent, qui est prete. */
static GLuint g_pbo[2] = {0, 0};
static int g_pbo_courant = 0;
static bool g_pbo_rempli[2] = {false, false};
static unsigned g_pbo_l[2] = {0, 0}, g_pbo_h[2] = {0, 0};
static size_t g_pbo_taille = 0;

static void liberer_pbo(void) {
    if (g_pbo[0] || g_pbo[1]) glDeleteBuffers(2, g_pbo);
    g_pbo[0] = g_pbo[1] = 0;
    g_pbo_rempli[0] = g_pbo_rempli[1] = false;
    g_pbo_taille = 0;
}

static bool preparer_pbo(size_t taille) {
    if (g_pbo[0] && taille == g_pbo_taille) return true;
    liberer_pbo();
    glGenBuffers(2, g_pbo);
    for (int i = 0; i < 2; i++) {
        glBindBuffer(GL_PIXEL_PACK_BUFFER, g_pbo[i]);
        glBufferData(GL_PIXEL_PACK_BUFFER, (GLsizeiptr)taille, NULL, GL_STREAM_READ);
    }
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    g_pbo_taille = taille;
    return g_pbo[0] != 0;
}

/* ---------- son ---------- */
#define SON_CAP (48000 * 2)
static int16_t g_son[SON_CAP];
static size_t g_son_n = 0;
static double g_sample_rate = 44100.0, g_fps = 60.0;

/* ---------- manette ---------- */
static int g_boutons = 0;
static int16_t g_stick[2][2] = {{0, 0}, {0, 0}};
static int16_t g_gachette[2] = {0, 0};   /* L et R, analogiques sur Dreamcast */

/* ============================================================ journal
 *
 * Le coeur est bavard, et ses messages sont la seule facon de savoir pourquoi
 * un jeu refuse de demarrer. On les ecrit dans un fichier que l'application
 * sait afficher : sur telephone, personne ne lit un logcat.
 */
static FILE *g_journal = NULL;
static bool g_tmp_ok = false;
static int g_vus = 0;

static void ecrire_journal(const char *fmt, va_list ap) {
    if (!g_journal) return;
    vfprintf(g_journal, fmt, ap);
    size_t n = strlen(fmt);
    if (!n || fmt[n - 1] != '\n') fputc('\n', g_journal);
    fflush(g_journal);
}

static void noter(const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    ecrire_journal(fmt, ap);
    va_end(ap);
    va_start(ap, fmt);
    __android_log_vprint(ANDROID_LOG_INFO, TAG, fmt, ap);
    va_end(ap);
}

/* ============================================================ rappels */
static void journal(int level, const char *fmt, ...) {
    (void)level;
    va_list ap; va_start(ap, fmt);
    ecrire_journal(fmt, ap);
    va_end(ap);
}

static uintptr_t fb_courant(void) { return (uintptr_t)g_fbo; }

static retro_proc_address_t proc_addr(const char *sym) {
    return (retro_proc_address_t)eglGetProcAddress(sym);
}

static bool environnement(unsigned cmd, void *data) {
    switch (cmd & 0xFFFF) {
        case ENV_GET_CAN_DUPE: *(bool *)data = true; return true;
        case ENV_GET_SYSTEM_DIRECTORY:
        case ENV_GET_SAVE_DIRECTORY: *(const char **)data = g_dossier; return true;
        case ENV_SET_PIXEL_FORMAT: return true;
        case ENV_GET_LOG_INTERFACE:
            ((struct retro_log_callback *)data)->log = (void (*)(int, const char *, ...))journal;
            return true;
        case ENV_GET_LANGUAGE: *(unsigned *)data = 3; return true;
        case ENV_GET_VARIABLE: {
            struct retro_variable *v = (struct retro_variable *)data;
            v->value = var_lire(v->key);
            /* On note les cinquante premieres options demandees : leurs noms
               changent d'une version du coeur a l'autre, et les deviner ne
               marche pas. */
            if (g_vus < 50) {
                g_vus++;
                noter("option demandee : %s -> %s", v->key, v->value ? v->value : "(non fixee)");
            }
            return v->value != NULL;
        }
        case ENV_GET_VARIABLE_UPDATE:
            *(bool *)data = g_vars_changees; g_vars_changees = false; return true;
        case ENV_SET_VARIABLES: case ENV_SET_CORE_OPTIONS: case ENV_SET_CORE_OPTIONS_INTL:
        case ENV_SET_CORE_OPTIONS_V2: case ENV_SET_CORE_OPTIONS_V2_INTL:
            return true;
        case ENV_GET_CORE_OPTIONS_VERSION: *(unsigned *)data = 2; return true;
        case ENV_SET_SUPPORT_NO_GAME: return true;
        case ENV_GET_PREFERRED_HW_RENDER: *(unsigned *)data = HW_OPENGLES2; return true;
        case ENV_SET_HW_RENDER: {
            /* le coeur reclame un contexte : on accepte et on lui donne le notre */
            struct retro_hw_render_callback *cb = (struct retro_hw_render_callback *)data;
            g_hw = *cb;
            g_hw.get_current_framebuffer = fb_courant;
            g_hw.get_proc_address = proc_addr;
            cb->get_current_framebuffer = fb_courant;
            cb->get_proc_address = proc_addr;
            g_hw_demande = true;
                    noter("rendu materiel demande : type %u, version %u.%u, profondeur %d",
                  cb->context_type, cb->version_major, cb->version_minor, (int)cb->depth);
            return true;
        }
        default: return false;
    }
}

static void video(const void *data, unsigned w, unsigned h, size_t pitch) {
    (void)data; (void)pitch;
    if (w && h) { g_bw = w; g_bh = h; }
}

static void son_un(int16_t l, int16_t r) {
    if (g_son_n + 2 <= SON_CAP) { g_son[g_son_n++] = l; g_son[g_son_n++] = r; }
}
static size_t son_lot(const int16_t *d, size_t frames) {
    size_t n = frames * 2;
    if (g_son_n + n > SON_CAP) n = SON_CAP - g_son_n;
    memcpy(g_son + g_son_n, d, n * sizeof(int16_t));
    g_son_n += n;
    return frames;
}
static void manette_poll(void) {}

static int16_t manette_etat(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0) return 0;
    if (device == DEVICE_JOYPAD) {
        if (id == 256) return (int16_t)(g_boutons & 0xFFFF);
        return (id <= 15) ? (g_boutons >> id) & 1 : 0;
    }
    if (device == DEVICE_ANALOG) {
        if (index <= 1 && id <= 1) return g_stick[index][id];
        /* index 2 : les boutons lus en analogique. Les gachettes de la
           Dreamcast s'y presentent sous les identifiants L2 et R2, pas 0 et 1
           comme je l'avais suppose. */
        if (index == 2) {
            if (id == 12) return g_gachette[0];
            if (id == 13) return g_gachette[1];
        }
    }
    return 0;
}

/* ============================================================ tampon de rendu */
static void liberer_fbo(void) {
    if (g_fbo) { glDeleteFramebuffers(1, &g_fbo); g_fbo = 0; }
    if (g_tex) { glDeleteTextures(1, &g_tex); g_tex = 0; }
    if (g_depth) { glDeleteRenderbuffers(1, &g_depth); g_depth = 0; }
}

static bool creer_fbo(int w, int h) {
    liberer_fbo();
    liberer_pbo();
    g_fw = w; g_fh = h;
    glGenTextures(1, &g_tex);
    glBindTexture(GL_TEXTURE_2D, g_tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glGenFramebuffers(1, &g_fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, g_fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g_tex, 0);

    glGenRenderbuffers(1, &g_depth);
    glBindRenderbuffer(GL_RENDERBUFFER, g_depth);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, w, h);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, g_depth);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_STENCIL_ATTACHMENT, GL_RENDERBUFFER, g_depth);

    GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (st != GL_FRAMEBUFFER_COMPLETE) { LOGE("tampon de rendu incomplet : 0x%x", st); return false; }
    noter("tampon de rendu %d x %d", w, h);
    return true;
}

/* ============================================================ JNI */
#define CHARGE(nom) do { p_##nom = dlsym(g_lib, "retro_" #nom); \
    if (!p_##nom) { noter("symbole manquant : retro_" #nom); return JNI_FALSE; } } while (0)
#define CHARGE_OPT(nom) p_##nom = dlsym(g_lib, "retro_" #nom)

JNIEXPORT jboolean JNICALL
Java_com_rudy_chambre_dcui_core_CoeurDc_natInit(JNIEnv *env, jobject self, jstring chemin,
                                         jstring dossier, jstring cache) {
    (void)self;
    if (g_init) return JNI_TRUE;
    const char *c = (*env)->GetStringUTFChars(env, chemin, NULL);
    const char *d = (*env)->GetStringUTFChars(env, dossier, NULL);
    const char *tmp = (*env)->GetStringUTFChars(env, cache, NULL);
    strncpy(g_dossier, d, sizeof(g_dossier) - 1);

    /* Flycast alloue sa memoire virtuelle dans un fichier temporaire. Sur
       Android il n'y a ni /tmp ni /dev/shm, d'ou l'echec avec errno 13 vu
       dans le journal : il basculait alors sur un mode de repli qui repose
       sur l'interception des fautes de segmentation, et se plantait. On lui
       designe donc un dossier ou il a le droit d'ecrire. */
    setenv("TMPDIR", tmp, 1);
    setenv("HOME", d, 1);
    setenv("XDG_RUNTIME_DIR", tmp, 1);
    {   /* on verifie tout de suite qu'on peut y ecrire : c'est la condition
           qui manquait, et le journal le dira sans ambiguite */
        char essai[700];
        snprintf(essai, sizeof essai, "%s/essai_ecriture", tmp);
        FILE *t = fopen(essai, "wb");
        if (t) { fclose(t); remove(essai); }
        g_tmp_ok = (t != NULL);
    }
    {
        char jc[600];
        snprintf(jc, sizeof jc, "%s/journal.txt", g_dossier);
        /* en ajout, pas en ecrasement : sinon la trace d'un plantage
           disparait des la relance suivante */
        g_journal = fopen(jc, "a");
        noter("");
        noter("--- demarrage du coeur ---");
        noter("dossier systeme : %s", g_dossier);
        noter("dossier temporaire : %s", tmp);
        noter("coeur : %s", c);
    }
    noter("dossier temporaire accessible en ecriture : %s", g_tmp_ok ? "oui" : "NON");
    g_lib = dlopen(c, RTLD_NOW | RTLD_LOCAL);
    (*env)->ReleaseStringUTFChars(env, chemin, c);
    (*env)->ReleaseStringUTFChars(env, dossier, d);
    (*env)->ReleaseStringUTFChars(env, cache, tmp);
    if (!g_lib) { noter("ECHEC dlopen : %s", dlerror()); return JNI_FALSE; }

    CHARGE(set_environment); CHARGE(set_video_refresh); CHARGE(set_audio_sample);
    CHARGE(set_audio_sample_batch); CHARGE(set_input_poll); CHARGE(set_input_state);
    CHARGE(init); CHARGE(deinit); CHARGE(load_game); CHARGE(unload_game); CHARGE(run);
    CHARGE(reset); CHARGE(get_system_av_info); CHARGE(set_controller_port_device);
    CHARGE(serialize_size); CHARGE(serialize); CHARGE(unserialize);
    CHARGE_OPT(cheat_reset); CHARGE_OPT(cheat_set);
    CHARGE_OPT(get_memory_data); CHARGE_OPT(get_memory_size);

    var_poser("reicast_internal_resolution", "640x480");
    var_poser("reicast_widescreen_hack", "disabled");
    var_poser("reicast_enable_dsp", "enabled");
    /* Rendu en fil separe : c'est le reglage par defaut du coeur, et il sort
       le rendu du chemin critique. Je l'avais coupe par precaution en
       cherchant la cause des plantages ; ceux-ci venaient d'ailleurs. */
    var_poser("reicast_threaded_rendering", "enabled");
    var_poser("reicast_boot_to_bios", "disabled");
    var_poser("reicast_cable_type", "TV (RGB)");
    var_poser("reicast_region", "Europe");
    /* Ces noms viennent de la liste que le coeur reclame reellement, relevee
       dans le journal. Mes precedentes suppositions (reicast_dynarec_enable)
       n'existent pas dans cette version et etaient donc sans effet. */
    var_poser("reicast_hle_bios", "disabled");
    var_poser("reicast_alpha_sorting", "Per-Triangle (Normal)");
    var_poser("reicast_emulate_framebuffer", "disabled");

    p_set_environment(environnement);
    p_set_video_refresh(video);
    p_set_audio_sample(son_un);
    p_set_audio_sample_batch(son_lot);
    p_set_input_poll(manette_poll);
    p_set_input_state(manette_etat);
    p_init();
    g_init = true;
    noter("coeur Flycast pret, %d variables posees", g_nvars);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_rudy_chambre_dcui_core_CoeurDc_natVariable(JNIEnv *env, jobject self, jstring cle, jstring val) {
    (void)self;
    const char *k = (*env)->GetStringUTFChars(env, cle, NULL);
    const char *v = (*env)->GetStringUTFChars(env, val, NULL);
    var_poser(k, v);
    (*env)->ReleaseStringUTFChars(env, cle, k);
    (*env)->ReleaseStringUTFChars(env, val, v);
}

/* Cree le tampon de rendu et previent le coeur que le contexte existe.
   A appeler depuis le fil OpenGL, une fois le contexte courant. */
JNIEXPORT jboolean JNICALL
Java_com_rudy_chambre_dcui_core_CoeurDc_natGlInit(JNIEnv *env, jobject self, jint w, jint h) {
    (void)env; (void)self;
    if (!creer_fbo(w, h)) return JNI_FALSE;
    if (g_hw_demande && g_hw.context_reset) g_hw.context_reset();
    g_hw_pret = true;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_rudy_chambre_dcui_core_CoeurDc_natGlTaille(JNIEnv *env, jobject self, jint w, jint h) {
    (void)env; (void)self;
    if (w == g_fw && h == g_fh) return JNI_TRUE;
    if (!creer_fbo(w, h)) return JNI_FALSE;
    if (g_hw_demande && g_hw.context_reset) g_hw.context_reset();
    return JNI_TRUE;
}

/* Avance d'une image. Renvoie l'identifiant de texture, ou 0 si rien.
   La taille utile est deposee dans taille[0] et taille[1]. */
JNIEXPORT jint JNICALL
Java_com_rudy_chambre_dcui_core_CoeurDc_natGlImage(JNIEnv *env, jobject self, jint boutons,
                                            jint gx, jint gy, jint dx, jint dy,
                                            jint gl_, jint gr_, jintArray taille) {
    (void)self;
    if (!g_charge || !g_hw_pret) return 0;
    g_boutons = boutons;
    g_stick[ANALOG_GAUCHE][0] = (int16_t)gx; g_stick[ANALOG_GAUCHE][1] = (int16_t)gy;
    g_stick[ANALOG_DROIT][0] = (int16_t)dx;  g_stick[ANALOG_DROIT][1] = (int16_t)dy;
    g_gachette[0] = (int16_t)gl_; g_gachette[1] = (int16_t)gr_;

    glBindFramebuffer(GL_FRAMEBUFFER, g_fbo);
    glViewport(0, 0, g_fw, g_fh);
    p_run();
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    appliquer_triches();      /* le jeu reecrit ses valeurs a chaque tour */

    jint t[2] = { (jint)g_bw, (jint)g_bh };
    (*env)->SetIntArrayRegion(env, taille, 0, 2, t);
    return (jint)g_tex;
}

JNIEXPORT jboolean JNICALL
Java_com_rudy_chambre_dcui_core_CoeurDc_natCharger(JNIEnv *env, jobject self, jstring chemin) {
    (void)self;
    if (!g_init) return JNI_FALSE;
    if (g_charge) { p_unload_game(); g_charge = false; }
    const char *c = (*env)->GetStringUTFChars(env, chemin, NULL);
    static char copie[1024];
    strncpy(copie, c, sizeof(copie) - 1);
    (*env)->ReleaseStringUTFChars(env, chemin, c);

    /* on verifie nous-memes la presence du BIOS : Flycast leve une exception
       C++ quand il manque, et une exception qui traverse JNI fait tomber
       l'application au lieu de renvoyer une erreur */
    {
        char b[700]; FILE *f;
        snprintf(b, sizeof b, "%s/dc/dc_boot.bin", g_dossier);
        f = fopen(b, "rb");
        if (!f) { snprintf(b, sizeof b, "%s/dc_boot.bin", g_dossier); f = fopen(b, "rb"); }
        if (!f) { noter("REFUS : dc_boot.bin introuvable sous %s", g_dossier); return JNI_FALSE; }
        fseek(f, 0, SEEK_END); long n = ftell(f); fclose(f);
        noter("dc_boot.bin trouve : %s (%ld octets)", b, n);
    }
    struct retro_game_info info = { copie, NULL, 0, NULL };
    noter("chargement du jeu : %s", copie);
    if (!p_load_game(&info)) { noter("REFUS : le coeur n'a pas accepte le jeu"); return JNI_FALSE; }
    noter("jeu accepte par le coeur");
    struct retro_system_av_info av;
    memset(&av, 0, sizeof av);
    p_get_system_av_info(&av);
    if (av.timing.sample_rate > 1000) g_sample_rate = av.timing.sample_rate;
    if (av.timing.fps > 10) g_fps = av.timing.fps;
    if (av.geometry.base_width) { g_bw = av.geometry.base_width; g_bh = av.geometry.base_height; }
    if (av.geometry.max_width) { g_maxw = av.geometry.max_width; g_maxh = av.geometry.max_height; }
    noter("geometrie : base %u x %u, maximum %u x %u",
          av.geometry.base_width, av.geometry.base_height, g_maxw, g_maxh);
    /* Le port doit etre declare JOYPAD, pas ANALOG : declare ANALOG, le coeur
       ne reconnait plus de manette sur le port A — c'est exactement l'erreur
       que j'avais faite sur la PlayStation. Les sticks restent lus par les
       requetes analogiques, qui fonctionnent quel que soit ce type. */
    p_set_controller_port_device(0, DEVICE_JOYPAD);
    /* Flycast ne declare son rendu materiel qu'au chargement du jeu : c'est
       donc maintenant qu'il faut lui signaler que le contexte existe, et non
       a la creation de la surface. */
    if (g_hw_demande && g_hw.context_reset) {
        g_hw.context_reset();
        g_hw_pret = true;
    }
    g_charge = true;
    g_son_n = 0;
    noter("jeu pret : %u x %u, %.0f Hz, %.2f i/s, rendu materiel %s",
          g_bw, g_bh, g_sample_rate, g_fps, g_hw_demande ? "oui" : "NON");
    return JNI_TRUE;
}

/* Relit l'image rendue et la depose dans le tableau fourni, en ARGB.
   Renvoie largeur << 16 | hauteur, ou 0 si aucune image n'est encore prete.
   A appeler depuis le fil OpenGL, juste apres natGlImage. */
JNIEXPORT jint JNICALL
Java_com_rudy_chambre_dcui_core_CoeurDc_natLirePixels(JNIEnv *env, jobject self, jintArray sortie) {
    (void)self;
    if (!g_charge || !g_hw_pret || !g_fbo) return 0;
    unsigned w = g_bw, h = g_bh;
    if (!w || !h) return 0;
    if (w > (unsigned)g_fw) w = g_fw;
    if (h > (unsigned)g_fh) h = g_fh;
    size_t n = (size_t)w * h * 4;
    if (!preparer_pbo(n)) return 0;

    /* on demande la copie de l'image courante, sans attendre */
    glBindFramebuffer(GL_FRAMEBUFFER, g_fbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, g_pbo[g_pbo_courant]);
    glReadPixels(0, 0, (GLsizei)w, (GLsizei)h, GL_RGBA, GL_UNSIGNED_BYTE, 0);
    g_pbo_rempli[g_pbo_courant] = true;
    g_pbo_l[g_pbo_courant] = w;
    g_pbo_h[g_pbo_courant] = h;

    /* et on releve celle du tour precedent, qui a eu le temps d'arriver */
    int autre = 1 - g_pbo_courant;
    g_pbo_courant = autre;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (!g_pbo_rempli[autre]) { glBindBuffer(GL_PIXEL_PACK_BUFFER, 0); return 0; }

    unsigned pw = g_pbo_l[autre], ph = g_pbo_h[autre];
    glBindBuffer(GL_PIXEL_PACK_BUFFER, g_pbo[autre]);
    const uint8_t *src0 = (const uint8_t *)glMapBufferRange(
        GL_PIXEL_PACK_BUFFER, 0, (GLsizeiptr)((size_t)pw * ph * 4), GL_MAP_READ_BIT);
    if (!src0) { glBindBuffer(GL_PIXEL_PACK_BUFFER, 0); return 0; }

    jsize cap = (*env)->GetArrayLength(env, sortie);
    if ((jsize)(pw * ph) > cap) {
        glUnmapBuffer(GL_PIXEL_PACK_BUFFER); glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        return 0;
    }
    jint *dst = (*env)->GetIntArrayElements(env, sortie, NULL);
    if (!dst) {
        glUnmapBuffer(GL_PIXEL_PACK_BUFFER); glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        return 0;
    }
    unsigned long somme = 0;
    for (unsigned y = 0; y < ph; y++) {
        /* OpenGL a son origine en bas, l'ecran en haut : on retourne */
        const uint8_t *src = src0 + (size_t)(ph - 1 - y) * pw * 4;
        jint *ligne = dst + (size_t)y * pw;
        for (unsigned x = 0; x < pw; x++) {
            uint8_t r = src[x * 4], g = src[x * 4 + 1], b = src[x * 4 + 2];
            ligne[x] = (jint)(0xFF000000u | ((unsigned)r << 16) | ((unsigned)g << 8) | b);
            if (y == ph / 2) somme += (unsigned)r + g + b;
        }
    }
    (*env)->ReleaseIntArrayElements(env, sortie, dst, 0);
    glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);

    if (g_mesures < 8) {
        g_mesures++;
        noter("image relue %u x %u, clarte moyenne de la ligne mediane : %lu",
              pw, ph, somme / (3UL * pw));
    }
    return (jint)((pw << 16) | ph);
}

/* Depose les echantillons dans le tampon fourni et renvoie leur nombre.
   Aucune allocation : c'est appele soixante fois par seconde. */
JNIEXPORT jint JNICALL
Java_com_rudy_chambre_dcui_core_CoeurDc_natSon(JNIEnv *env, jobject self, jshortArray sortie) {
    (void)self;
    jsize cap = (*env)->GetArrayLength(env, sortie);
    jsize n = (jsize)g_son_n;
    if (n > cap) n = cap;
    (*env)->SetShortArrayRegion(env, sortie, 0, n, g_son);
    g_son_n = 0;
    return n;
}

JNIEXPORT jint JNICALL
Java_com_rudy_chambre_dcui_core_CoeurDc_natMaxL(JNIEnv *e, jobject s) { (void)e; (void)s; return (jint)g_maxw; }
JNIEXPORT jint JNICALL
Java_com_rudy_chambre_dcui_core_CoeurDc_natMaxH(JNIEnv *e, jobject s) { (void)e; (void)s; return (jint)g_maxh; }

JNIEXPORT jdouble JNICALL Java_com_rudy_chambre_dcui_core_CoeurDc_natFrequence(JNIEnv *e, jobject s) { (void)e; (void)s; return g_sample_rate; }
JNIEXPORT jdouble JNICALL Java_com_rudy_chambre_dcui_core_CoeurDc_natFps(JNIEnv *e, jobject s) { (void)e; (void)s; return g_fps; }
JNIEXPORT void JNICALL Java_com_rudy_chambre_dcui_core_CoeurDc_natReset(JNIEnv *e, jobject s) { (void)e; (void)s; if (g_charge) p_reset(); }

JNIEXPORT void JNICALL
Java_com_rudy_chambre_dcui_core_CoeurDc_natEjecter(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    if (g_charge) { p_unload_game(); g_charge = false; }
    g_son_n = 0;
}

/* ---------- codes de triche exposes a Kotlin ---------- */
JNIEXPORT jboolean JNICALL
Java_com_rudy_chambre_dcui_core_CoeurDc_natTricheDispo(JNIEnv *e, jobject s) {
    (void)e; (void)s;
    if (!p_get_memory_data || !p_get_memory_size) return JNI_FALSE;
    return p_get_memory_size(MEMOIRE_SYSTEME) > 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_rudy_chambre_dcui_core_CoeurDc_natMemoireTaille(JNIEnv *e, jobject s) {
    (void)e; (void)s;
    if (!p_get_memory_size) return 0;
    return (jint)p_get_memory_size(MEMOIRE_SYSTEME);
}

JNIEXPORT void JNICALL
Java_com_rudy_chambre_dcui_core_CoeurDc_natTricheVider(JNIEnv *e, jobject s) {
    (void)e; (void)s; g_ntriches = 0;
}

/* Un code : adresse, valeur, taille en octets, boutisme, et une eventuelle
   repetition avec ses pas. */
JNIEXPORT void JNICALL
Java_com_rudy_chambre_dcui_core_CoeurDc_natTricheAjouter(JNIEnv *env, jobject self,
        jint adresse, jint valeur, jint taille, jboolean gros_boutiste,
        jint repetitions, jint pas_adresse, jint pas_valeur) {
    (void)env; (void)self;
    if (g_ntriches >= MAX_TRICHES) return;
    Triche *t = &g_triches[g_ntriches++];
    t->adresse = (unsigned)adresse;
    t->valeur = (unsigned)valeur;
    t->taille = (unsigned char)(taille == 1 || taille == 2 || taille == 4 ? taille : 2);
    t->gros_boutiste = gros_boutiste == JNI_TRUE ? 1 : 0;
    t->repetitions = (unsigned short)(repetitions > 0 ? repetitions : 1);
    t->pas_adresse = pas_adresse;
    t->pas_valeur = pas_valeur;
}

JNIEXPORT jbyteArray JNICALL
Java_com_rudy_chambre_dcui_core_CoeurDc_natSauver(JNIEnv *env, jobject self) {
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
Java_com_rudy_chambre_dcui_core_CoeurDc_natRestaurer(JNIEnv *env, jobject self, jbyteArray donnees) {
    (void)self;
    if (!g_charge) return JNI_FALSE;
    jsize n = (*env)->GetArrayLength(env, donnees);
    jbyte *buf = (*env)->GetByteArrayElements(env, donnees, NULL);
    bool ok = p_unserialize(buf, (size_t)n);
    (*env)->ReleaseByteArrayElements(env, donnees, buf, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
