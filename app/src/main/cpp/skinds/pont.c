/*
 * Pont entre l'application et le coeur melonDS (format libretro).
 *
 * Difference majeure avec les ponts NES, Super Nintendo et PlayStation :
 * melonDS n'a pas de rendu logiciel. Il dessine en OpenGL dans un tampon que
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
#include <signal.h>
#include <pthread.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>

#define TAG "SkinDS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ---------- extraits de libretro.h ---------- */
struct retro_game_info { const char *path; const void *data; size_t size; const char *meta; };
struct retro_system_info { const char *library_name, *library_version, *valid_extensions;
                           bool need_fullpath, block_extract; };
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
enum { DEVICE_JOYPAD = 1, DEVICE_ANALOG = 5, DEVICE_POINTER = 6 };
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
static void   (*p_get_system_info)(struct retro_system_info *);
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

/* ---------- lecture de l'image ----------
 *
 * Plutot que de compter sur une surface OpenGL posee derriere la fenetre —
 * mecanisme capricieux qui depend du theme et du fabricant — on relit les
 * pixels du tampon de rendu et on les remet a l'application, qui les dessine
 * elle-meme dans le skin.
 *
 * Un glReadPixels ordinaire attend que le processeur graphique ait fini de
 * dessiner : on perd tout le parallelisme, et l'a-coup se sent. On passe donc
 * par deux tampons de transfert utilises en alternance — on demande la copie
 * d'une image et on releve celle demandee au tour precedent, qui est prete.
 */
/* Rendu logiciel : le coeur nous remet directement les pixels. */
static uint32_t *g_image_log = NULL;
static size_t g_image_log_cap = 0;
static unsigned g_image_log_l = 0, g_image_log_h = 0;
static int g_format = PIX_XRGB8888;
static pthread_mutex_t g_image_verrou = PTHREAD_MUTEX_INITIALIZER;

static uint8_t *g_lecture = NULL;
static size_t g_lecture_taille = 0;
static int g_mesures = 0;

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

/* ---------- codes de triche ----------
 *
 * Contrairement a ceux de la Dreamcast, qui ecrivaient directement dans la
 * memoire de la console a chaque image, les codes Nintendo 64 sont au format
 * GameShark : c'est le coeur qui sait les interpreter. On se contente de les
 * lui transmettre.
 */

/* ---------- son ----------
 *
 * Mupen64Plus fait tourner l'emulation sur son propre fil — le journal le
 * montre : « [EmuThread] M64CMD_EXECUTE ». Les echantillons nous arrivent
 * donc depuis ce fil-la, pendant que l'application les retire depuis le fil
 * OpenGL. Sans protection, les deux touchent au meme compteur en meme temps :
 * il suffit qu'ils se croisent au mauvais moment pour qu'une copie deborde du
 * tampon et abime la memoire. C'est ce qui faisait abandonner le programme au
 * bout de quelques secondes, a un moment variable — trente-cinq images une
 * fois, deux cent vingt-trois une autre.
 */
#define SON_CAP (48000 * 2)
static int16_t g_son[SON_CAP];
static size_t g_son_n = 0;
static pthread_mutex_t g_son_verrou = PTHREAD_MUTEX_INITIALIZER;
static double g_sample_rate = 44100.0, g_fps = 60.0;

/* ---------- manette ---------- */
static int g_boutons = 0;
static int16_t g_stick[2][2] = {{0, 0}, {0, 0}};
static int16_t g_gachette[2] = {0, 0};
/* Stylet : position sur l'ecran du bas, de -32767 a 32767, et contact. */
static int16_t g_stylet_x = 0, g_stylet_y = 0;
static int g_stylet_pose = 0;   /* inutilise sur Nintendo 64 */

/* ============================================================ journal
 *
 * Le coeur est bavard, et ses messages sont la seule facon de savoir pourquoi
 * un jeu refuse de demarrer. On les ecrit dans un fichier que l'application
 * sait afficher : sur telephone, personne ne lit un logcat.
 */
static FILE *g_journal = NULL;
static unsigned long g_images_emulees = 0;
/* Ou en est le pont a cet instant : le capteur d'arret brutal l'affiche, ce
   qui distingue un plantage du coeur d'un plantage de notre relecture. */
static const char *g_etape = "demarrage";
static bool g_tmp_ok = false;
static int g_vus = 0;

static pthread_mutex_t g_journal_verrou = PTHREAD_MUTEX_INITIALIZER;

static void ecrire_journal(const char *fmt, va_list ap) {
    if (!g_journal) return;
    pthread_mutex_lock(&g_journal_verrou);
    vfprintf(g_journal, fmt, ap);
    size_t n = strlen(fmt);
    if (!n || fmt[n - 1] != '\n') fputc('\n', g_journal);
    fflush(g_journal);
    pthread_mutex_unlock(&g_journal_verrou);
}

static void noter(const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    ecrire_journal(fmt, ap);
    va_end(ap);
    va_start(ap, fmt);
    __android_log_vprint(ANDROID_LOG_INFO, TAG, fmt, ap);
    va_end(ap);
}

/* ============================================================ capture des arrets brutaux
 *
 * Un plantage natif ne laisse aucune trace lisible depuis le telephone : le
 * journal s'arrete au milieu d'une phrase et c'est tout. On intercepte donc
 * les signaux fatals pour ecrire ce qui s'est passe avant de mourir, puis on
 * repasse la main a qui les gerait avant nous — le coeur en installe parfois
 * pour son propre compte, et le priver des siens le casserait.
 */
static struct sigaction g_avant[8];
static const int SIGNAUX[] = { SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT };

static void gerer_signal(int sig, siginfo_t *info, void *ctx) {
    const char *nom = sig == SIGSEGV ? "acces memoire interdit (SIGSEGV)"
                    : sig == SIGBUS  ? "acces mal aligne (SIGBUS)"
                    : sig == SIGILL  ? "instruction illegale (SIGILL)"
                    : sig == SIGFPE  ? "erreur de calcul (SIGFPE)"
                    : sig == SIGABRT ? "abandon (SIGABRT)" : "signal inconnu";
    noter("ARRET BRUTAL : %s pendant « %s », apres %lu images (adresse %p)",
          nom, g_etape, g_images_emulees, info ? info->si_addr : NULL);
    if (g_journal) { fflush(g_journal); fclose(g_journal); g_journal = NULL; }
    for (unsigned i = 0; i < sizeof SIGNAUX / sizeof SIGNAUX[0]; i++) {
        if (SIGNAUX[i] != sig) continue;
        if (g_avant[i].sa_flags & SA_SIGINFO) {
            if (g_avant[i].sa_sigaction) { g_avant[i].sa_sigaction(sig, info, ctx); return; }
        } else if (g_avant[i].sa_handler && g_avant[i].sa_handler != SIG_DFL
                   && g_avant[i].sa_handler != SIG_IGN) {
            g_avant[i].sa_handler(sig); return;
        }
        break;
    }
    signal(sig, SIG_DFL);
    raise(sig);
}

static void installer_signaux(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_sigaction = gerer_signal;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    for (unsigned i = 0; i < sizeof SIGNAUX / sizeof SIGNAUX[0]; i++)
        sigaction(SIGNAUX[i], &sa, &g_avant[i]);
    noter("capture des arrets brutaux installee");
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
        case ENV_SET_PIXEL_FORMAT:
            g_format = *(const int *)data;
            noter("format des pixels : %s",
                  g_format == PIX_XRGB8888 ? "XRGB8888" :
                  g_format == PIX_RGB565 ? "RGB565" : "0RGB1555");
            return true;
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

/* Appele depuis le fil d'emulation du coeur, pas depuis le notre : on ne fait
   qu'y deposer deux nombres, et la relecture les borne au tampon. */
static void video(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!w || !h) return;
    if ((w != g_bw || h != g_bh) && g_mesures < 8)
        noter("le coeur annonce %u x %u", w, h);
    g_bw = w; g_bh = h;

    /* Rendu materiel : data vaut le marqueur, l'image est dans notre tampon.
       Rendu logiciel : data pointe sur les pixels, on les recopie. */
    if (!data || data == FB_DUPLIQUE) return;

    pthread_mutex_lock(&g_image_verrou);
    size_t besoin = (size_t)w * h;
    if (besoin > g_image_log_cap) {
        free(g_image_log);
        g_image_log = (uint32_t *)malloc(besoin * sizeof(uint32_t));
        g_image_log_cap = g_image_log ? besoin : 0;
    }
    if (g_image_log) {
        for (unsigned y = 0; y < h; y++) {
            const uint8_t *ligne = (const uint8_t *)data + (size_t)y * pitch;
            uint32_t *dst = g_image_log + (size_t)y * w;
            if (g_format == PIX_RGB565) {
                const uint16_t *src = (const uint16_t *)ligne;
                for (unsigned x = 0; x < w; x++) {
                    uint16_t v = src[x];
                    unsigned r = (v >> 11) & 0x1F, g = (v >> 5) & 0x3F, b = v & 0x1F;
                    dst[x] = 0xFF000000u | ((r * 255 / 31) << 16)
                                         | ((g * 255 / 63) << 8) | (b * 255 / 31);
                }
            } else if (g_format == PIX_0RGB1555) {
                const uint16_t *src = (const uint16_t *)ligne;
                for (unsigned x = 0; x < w; x++) {
                    uint16_t v = src[x];
                    unsigned r = (v >> 10) & 0x1F, g = (v >> 5) & 0x1F, b = v & 0x1F;
                    dst[x] = 0xFF000000u | ((r * 255 / 31) << 16)
                                         | ((g * 255 / 31) << 8) | (b * 255 / 31);
                }
            } else {
                const uint32_t *src = (const uint32_t *)ligne;
                for (unsigned x = 0; x < w; x++) dst[x] = 0xFF000000u | (src[x] & 0x00FFFFFFu);
            }
        }
        g_image_log_l = w; g_image_log_h = h;
    }
    pthread_mutex_unlock(&g_image_verrou);
}

static void son_un(int16_t l, int16_t r) {
    pthread_mutex_lock(&g_son_verrou);
    if (g_son_n + 2 <= SON_CAP) { g_son[g_son_n++] = l; g_son[g_son_n++] = r; }
    pthread_mutex_unlock(&g_son_verrou);
}
static size_t son_lot(const int16_t *d, size_t frames) {
    pthread_mutex_lock(&g_son_verrou);
    size_t n = frames * 2;
    if (g_son_n + n > SON_CAP) n = (g_son_n < SON_CAP) ? SON_CAP - g_son_n : 0;
    if (n) {
        memcpy(g_son + g_son_n, d, n * sizeof(int16_t));
        g_son_n += n;
    }
    pthread_mutex_unlock(&g_son_verrou);
    return frames;
}

static void manette_poll(void) {}

static int g_vus_manette = 0;

static int16_t manette_etat(unsigned port, unsigned device, unsigned index, unsigned id) {
    /* On note les premieres interrogations : elles disent quel port et quel
       type de peripherique le coeur lit reellement, ce qui distingue un skin
       muet d'un coeur qui regarde ailleurs. */
    if (g_vus_manette < 6) {
        g_vus_manette++;
        noter("le coeur lit : port %u, peripherique %u, index %u, id %u (boutons %04X)",
              port, device, index, id, g_boutons);
    }
    if (port != 0) return 0;
    if (device == DEVICE_JOYPAD) {
        if (id == 256) return (int16_t)(g_boutons & 0xFFFF);
        return (id <= 15) ? (g_boutons >> id) & 1 : 0;
    }
    if (device == DEVICE_POINTER) {
        /* L'ecran du bas est tactile : le coeur l'interroge ainsi. */
        if (index != 0) return 0;
        if (id == 0) return g_stylet_x;
        if (id == 1) return g_stylet_y;
        if (id == 2) return (int16_t)g_stylet_pose;
        return 0;
    }
    if (device == DEVICE_ANALOG) {
        if (index <= 1 && id <= 1) return g_stick[index][id];
        /* index 2 : les boutons lus en analogique. Les gachettes de la
           certaines consoles s'y presentent sous les identifiants L2 et R2
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
Java_com_skinds_app_core_CoeurDS_natInit(JNIEnv *env, jobject self, jstring chemin,
                                         jstring dossier, jstring cache) {
    (void)self;
    if (g_init) return JNI_TRUE;
    const char *c = (*env)->GetStringUTFChars(env, chemin, NULL);
    const char *d = (*env)->GetStringUTFChars(env, dossier, NULL);
    const char *tmp = (*env)->GetStringUTFChars(env, cache, NULL);
    strncpy(g_dossier, d, sizeof(g_dossier) - 1);

    /* melonDS alloue sa memoire virtuelle dans un fichier temporaire. Sur
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
    CHARGE(reset); CHARGE(get_system_info); CHARGE(get_system_av_info); CHARGE(set_controller_port_device);
    CHARGE(serialize_size); CHARGE(serialize); CHARGE(unserialize);
    CHARGE_OPT(cheat_reset); CHARGE_OPT(cheat_set);
    CHARGE_OPT(get_memory_data); CHARGE_OPT(get_memory_size);

    /* Options du coeur melonDS. Les noms seront confirmes par le
       journal, qui note chaque option reellement reclamee. */
    /* melonDS avec le moteur Angrylion : un rendu entierement logiciel.
     *
     * Toute la couche OpenGL n'existait que parce que Flycast l'exigeait sur
     * Dreamcast. Un coeur qui dessine en memoire la rend inutile — et avec
     * elle disparait la classe entiere de problemes qui nous bloque : plus de
     * contexte a partager, plus de tampon a relire, plus de moteur graphique
     * qui abandonne faute d'une fonction manquante.
     *
     * C'est plus lent qu'un rendu materiel, mais c'est exact et ca tient. */
    /* Rendu materiel par defaut, maintenant que le signal de contexte n'est
       plus envoye trois fois. Le filet de securite de l'application ramene au
       rendu logiciel si ca ne tient pas. */
    /* melonDS : rendu OpenGL, seul capable de monter la resolution interne.
       Les deux ecrans arrivent empiles dans une seule image ; l'application la
       coupe en deux et place chaque moitie dans le rectangle voulu par
       l'habillage. C'est ce qui permet les cinq presentations sans rien
       demander au coeur. */
    var_poser("melonds_render_mode", "opengl");
    var_poser("melonds_opengl_resolution", "1");
    var_poser("melonds_screen_layout1", "Top/Bottom");
    var_poser("melonds_screen_gap", "0");
    var_poser("melonds_console_mode", "ds");
    var_poser("melonds_boot_mode", "direct");
    var_poser("melonds_opengl_better_polygons", "enabled");
    var_poser("melonds_touch_mode", "Touch");
    /* Le coeur dessine son propre curseur par-dessus l'ecran tactile : un
       point qui n'a rien a faire la, puisque le doigt est deja sur la dalle. */
    var_poser("melonds_show_cursor", "disabled");
    var_poser("melonds_cursor_timeout", "1");
    var_poser("melonds_hybrid_ratio", "2");
    /* Le recompilateur dynamique traduit le code de la console en code natif
       a la volee, ce qui exige d'ecrire dans de la memoire executable. C'est
       la que l'application tombait, apres quelques images — le temps qu'il
       compile ses premiers blocs. L'interprete avec cache est nettement plus
       stable, et reste rapide. Le bouton Essais permet de retenter l'autre. */

    p_set_environment(environnement);
    p_set_video_refresh(video);
    p_set_audio_sample(son_un);
    p_set_audio_sample_batch(son_lot);
    p_set_input_poll(manette_poll);
    p_set_input_state(manette_etat);
    p_init();
    installer_signaux();
    g_init = true;
    noter("coeur melonDS pret, %d variables posees", g_nvars);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_skinds_app_core_CoeurDS_natVariable(JNIEnv *env, jobject self, jstring cle, jstring val) {
    (void)self;
    const char *k = (*env)->GetStringUTFChars(env, cle, NULL);
    const char *v = (*env)->GetStringUTFChars(env, val, NULL);
    var_poser(k, v);
    (*env)->ReleaseStringUTFChars(env, cle, k);
    (*env)->ReleaseStringUTFChars(env, val, v);
}

/* Cree le tampon de rendu.
 *
 * On ne previent PAS le coeur ici : a ce moment aucun jeu n'est charge, et
 * surtout le signal « le contexte est pret » ne doit etre donne qu'une seule
 * fois par contexte. Je l'envoyais a trois endroits — creation de la surface,
 * redimensionnement du tampon, chargement du jeu — et le coeur recreait ses
 * ressources graphiques en pleine partie. C'est ce qui faisait tomber
 * l'application quelques images apres le demarrage, en rendu materiel.
 */
JNIEXPORT jboolean JNICALL
Java_com_skinds_app_core_CoeurDS_natGlInit(JNIEnv *env, jobject self, jint w, jint h) {
    (void)env; (void)self;
    if (!creer_fbo(w, h)) return JNI_FALSE;
    g_hw_pret = true;
    return JNI_TRUE;
}

/* Redimensionne le tampon de rendu.
 *
 * Si un jeu tourne, on suit le protocole : on annonce d'abord la perte du
 * contexte, on refait le tampon, puis on annonce qu'il est de nouveau pret.
 * Sans la premiere annonce, le coeur fabriquait un second jeu de ressources
 * sans liberer le premier.
 */
JNIEXPORT jboolean JNICALL
Java_com_skinds_app_core_CoeurDS_natGlTaille(JNIEnv *env, jobject self, jint w, jint h) {
    (void)env; (void)self;
    if (w == g_fw && h == g_fh) return JNI_TRUE;
    bool actif = g_charge && g_hw_demande;
    if (actif && g_hw.context_destroy) {
        noter("redimensionnement : contexte libere");
        g_hw.context_destroy();
    }
    if (!creer_fbo(w, h)) return JNI_FALSE;
    if (actif && g_hw.context_reset) {
        g_hw.context_reset();
        noter("redimensionnement : contexte repris en %d x %d", w, h);
    }
    return JNI_TRUE;
}

/* Avance d'une image. Renvoie l'identifiant de texture, ou 0 si rien.
   La taille utile est deposee dans taille[0] et taille[1]. */
JNIEXPORT jint JNICALL
Java_com_skinds_app_core_CoeurDS_natGlImage(JNIEnv *env, jobject self, jint boutons,
                                            jint gx, jint gy, jint dx, jint dy,
                                            jint gl_, jint gr_, jintArray taille) {
    (void)self;
    /* Un coeur a rendu logiciel n'a pas de contexte a preparer : on exige le
       tampon materiel seulement s'il en a reclame un. */
    if (!g_charge) return 0;
    if (g_hw_demande && !g_hw_pret) return 0;
    g_boutons = boutons;
    g_stick[ANALOG_GAUCHE][0] = (int16_t)gx; g_stick[ANALOG_GAUCHE][1] = (int16_t)gy;
    g_stick[ANALOG_DROIT][0] = (int16_t)dx;  g_stick[ANALOG_DROIT][1] = (int16_t)dy;
    g_gachette[0] = (int16_t)gl_; g_gachette[1] = (int16_t)gr_;

    g_etape = "emulation d'une image";
    if (g_hw_demande) {
        glBindFramebuffer(GL_FRAMEBUFFER, g_fbo);
        glViewport(0, 0, g_fw, g_fh);
    }
    p_run();
    if (g_hw_demande) glBindFramebuffer(GL_FRAMEBUFFER, 0);
    g_etape = "apres l'emulation";

    jint t[2] = { (jint)g_bw, (jint)g_bh };
    (*env)->SetIntArrayRegion(env, taille, 0, 2, t);
    /* En rendu logiciel il n'y a pas de texture : on renvoie un jeton non nul
       pour signaler qu'une image a bien ete produite. */
    return g_hw_demande ? (jint)g_tex : 1;
}

JNIEXPORT jboolean JNICALL
Java_com_skinds_app_core_CoeurDS_natCharger(JNIEnv *env, jobject self, jstring chemin) {
    (void)self;
    if (!g_init) return JNI_FALSE;
    if (g_charge) { p_unload_game(); g_charge = false; }
    const char *c = (*env)->GetStringUTFChars(env, chemin, NULL);
    static char copie[1024];
    strncpy(copie, c, sizeof(copie) - 1);
    (*env)->ReleaseStringUTFChars(env, chemin, c);

    /* La Nintendo 64 n'a pas de BIOS : rien a verifier avant de charger.
       Une verification du BIOS Dreamcast trainait ici et refusait tous les
       jeux avant meme de les transmettre au coeur. */
    {
        char b[700];
        snprintf(b, sizeof b, "%s/mupen64plus.ini", g_dossier);
        FILE *f = fopen(b, "rb");
        if (f) { fseek(f, 0, SEEK_END); noter("catalogue : %ld octets", ftell(f)); fclose(f); }
        else noter("catalogue absent sous %s", g_dossier);
    }

    /* Chaque coeur a sa maniere de recevoir un jeu.
     *
     * Celui de la Dreamcast lit une image de disque et se contente d'un
     * chemin. Mupen64Plus veut la cartouche entiere en memoire : il l'annonce
     * par need_fullpath a faux, et refusait tout puisqu'on ne lui passait
     * qu'un chemin. On lui demande donc ce qu'il attend au lieu de le
     * supposer, ce qui vaudra pour n'importe quel coeur. */
    struct retro_system_info si;
    memset(&si, 0, sizeof si);
    p_get_system_info(&si);
    noter("coeur : %s %s, extensions %s, chemin complet %s",
          si.library_name ? si.library_name : "?",
          si.library_version ? si.library_version : "?",
          si.valid_extensions ? si.valid_extensions : "?",
          si.need_fullpath ? "requis" : "non requis");

    static uint8_t *contenu = NULL;
    free(contenu);
    contenu = NULL;
    struct retro_game_info info = { copie, NULL, 0, NULL };

    if (!si.need_fullpath) {
        FILE *fp = fopen(copie, "rb");
        if (!fp) { noter("REFUS : fichier illisible %s", copie); return JNI_FALSE; }
        fseek(fp, 0, SEEK_END);
        long taille = ftell(fp);
        fseek(fp, 0, SEEK_SET);
        if (taille <= 0) { fclose(fp); noter("REFUS : fichier vide"); return JNI_FALSE; }
        contenu = (uint8_t *)malloc((size_t)taille);
        if (!contenu) { fclose(fp); noter("REFUS : memoire insuffisante"); return JNI_FALSE; }
        size_t lus = fread(contenu, 1, (size_t)taille, fp);
        fclose(fp);
        if (lus != (size_t)taille) {
            noter("REFUS : lecture incomplete");
            free(contenu); contenu = NULL; return JNI_FALSE;
        }
        info.data = contenu;
        info.size = (size_t)taille;
        noter("cartouche lue en memoire : %ld octets", taille);
    }

    g_etape = "chargement du jeu";
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
    /* Manette Nintendo 64 : un JOYPAD. Son unique stick est lu par les
       requetes analogiques, comme sur les autres consoles. */
    /* Le journal disait : « Game controller 0 has nothing plugged in ». On
       declare donc la manette AVANT que le coeur ne lance l'emulation, et on
       redit les quatre ports pour qu'aucun ne reste ambigu. */
    /* Une seule manette, sur le port 1, apres le chargement. Declarer les
       ports vides ou appeler avant le chargement n'apporte rien et fait
       abandonner certains coeurs. */
    p_set_controller_port_device(0, DEVICE_JOYPAD);
    /* melonDS ne declare son rendu materiel qu'au chargement du jeu : c'est
       donc maintenant qu'il faut lui signaler que le contexte existe, et non
       a la creation de la surface. */
    if (g_hw_demande && g_hw.context_reset) {
        g_hw.context_reset();
        g_hw_pret = true;
    }
    g_charge = true;
    g_son_n = 0;
    noter("jeu pret : %u x %u, %.0f Hz, %.2f i/s", g_bw, g_bh, g_sample_rate, g_fps);
    noter(g_hw_demande
          ? "moteur graphique MATERIEL : la resolution interne peut monter"
          : "moteur graphique LOGICIEL : la resolution restera celle d'origine");
    return JNI_TRUE;
}

/* Relit l'image rendue et la depose dans le tableau fourni, en ARGB.
   Renvoie largeur << 16 | hauteur, ou 0 si aucune image n'est encore prete.
   A appeler depuis le fil OpenGL, juste apres natGlImage. */
JNIEXPORT jint JNICALL
Java_com_skinds_app_core_CoeurDS_natLirePixels(JNIEnv *env, jobject self, jintArray sortie) {
    (void)self;
    if (!g_charge) return 0;
    g_etape = "relecture de l'image";

    /* Coeur a rendu logiciel : l'image est deja chez nous, rien a demander a
       la carte graphique. C'est le chemin le plus simple et le plus sur, et
       il doit passer avant toute exigence de contexte materiel. */
    if (!g_hw_demande) {
        pthread_mutex_lock(&g_image_verrou);
        unsigned lw = g_image_log_l, lh = g_image_log_h;
        if (!g_image_log || !lw || !lh) { pthread_mutex_unlock(&g_image_verrou); return 0; }
        jsize cap0 = (*env)->GetArrayLength(env, sortie);
        if ((jsize)(lw * lh) > cap0) { pthread_mutex_unlock(&g_image_verrou); return 0; }
        (*env)->SetIntArrayRegion(env, sortie, 0, (jsize)(lw * lh), (const jint *)g_image_log);
        pthread_mutex_unlock(&g_image_verrou);
        if (g_mesures < 8) { g_mesures++; noter("image logicielle %u x %u", lw, lh); }
        return (jint)((lw << 16) | lh);
    }

    if (!g_hw_pret || !g_fbo) return 0;
    unsigned w = g_bw, h = g_bh;
    if (!w || !h) return 0;
    if (w > (unsigned)g_fw) w = g_fw;
    if (h > (unsigned)g_fh) h = g_fh;
    size_t n = (size_t)w * h * 4;

    if (g_mesures < 4) noter("relecture : %u x %u dans un tampon %d x %d", w, h, g_fw, g_fh);
    if (!preparer_pbo(n)) { noter("ECHEC : tampons de transfert indisponibles"); return 0; }

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
    if (!src0) {
        noter("ECHEC : tampon de transfert illisible (%u x %u)", pw, ph);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        return 0;
    }

    jsize cap = (*env)->GetArrayLength(env, sortie);
    if ((jsize)(pw * ph) > cap) {
        noter("ECHEC : image %u x %u trop grande pour le tableau (%d)", pw, ph, (int)cap);
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
    g_etape = "fin de relecture";
    glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);

    if (++g_images_emulees % 60 == 0) noter("%lu images emulees", g_images_emulees);
    if (g_mesures < 8) {
        g_mesures++;
        noter("image relue %u x %u, clarte moyenne de la ligne mediane : %lu",
              pw, ph, somme / (3UL * pw));
    }
    return (jint)((pw << 16) | ph);
}

/* Depose les echantillons dans le tampon fourni et renvoie leur nombre.
   Aucune allocation : c'est appele soixante fois par seconde. */
/* Position du stylet, en fractions de l'ecran du bas. Une valeur hors de
   l'intervalle signifie que le doigt s'est leve. */
JNIEXPORT void JNICALL
Java_com_skinds_app_core_CoeurDS_natStylet(JNIEnv *e, jobject s, jfloat fx, jfloat fy) {
    (void)e; (void)s;
    if (fx < 0.0f || fy < 0.0f || fx > 1.0f || fy > 1.0f) { g_stylet_pose = 0; return; }

    /* Le coeur raisonne sur l'image entiere, ou les deux ecrans sont empiles.
       L'ecran tactile est celui du bas : il occupe une bande dont la hauteur
       vaut 192/256 de la largeur de l'image, calee en bas. Couper au milieu
       decalait le stylet des que le coeur laissait une bande entre les deux
       ecrans. */
    float haut_un = (g_bw > 0 && g_bh > 0)
                  ? ((float)g_bw * 192.0f / 256.0f) / (float)g_bh
                  : 0.5f;
    if (haut_un <= 0.05f || haut_un > 1.0f) haut_un = 0.5f;
    float gy = (1.0f - haut_un) + fy * haut_un;
    g_stylet_x = (int16_t)((fx * 2.0f - 1.0f) * 32767.0f);
    g_stylet_y = (int16_t)((gy * 2.0f - 1.0f) * 32767.0f);
    g_stylet_pose = 1;
    if (g_mesures < 6) {
        g_mesures++;
        noter("stylet : ecran %.2f,%.2f -> image %.2f,%.2f", fx, fy, fx, gy);
    }
}

/* Jette les echantillons en attente : apres une relance, ils appartiennent a
   la partie precedente et se jouent d'un coup. */
JNIEXPORT void JNICALL
Java_com_skinds_app_core_CoeurDS_natSonVider(JNIEnv *e, jobject s) {
    (void)e; (void)s;
    pthread_mutex_lock(&g_son_verrou);
    g_son_n = 0;
    pthread_mutex_unlock(&g_son_verrou);
}

JNIEXPORT jint JNICALL
Java_com_skinds_app_core_CoeurDS_natSon(JNIEnv *env, jobject self, jshortArray sortie) {
    (void)self;
    g_etape = "transfert du son";
    jsize cap = (*env)->GetArrayLength(env, sortie);
    static int16_t copie_son[SON_CAP];
    pthread_mutex_lock(&g_son_verrou);
    jsize n = (jsize)g_son_n;
    if (n > cap) n = cap;
    if (n > 0) memcpy(copie_son, g_son, (size_t)n * sizeof(int16_t));
    g_son_n = 0;
    pthread_mutex_unlock(&g_son_verrou);
    if (n > 0) (*env)->SetShortArrayRegion(env, sortie, 0, n, copie_son);
    return n;
}

JNIEXPORT jboolean JNICALL
Java_com_skinds_app_core_CoeurDS_natRenduMateriel(JNIEnv *e, jobject s) {
    (void)e; (void)s; return g_hw_demande ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_skinds_app_core_CoeurDS_natMaxL(JNIEnv *e, jobject s) { (void)e; (void)s; return (jint)g_maxw; }
JNIEXPORT jint JNICALL
Java_com_skinds_app_core_CoeurDS_natMaxH(JNIEnv *e, jobject s) { (void)e; (void)s; return (jint)g_maxh; }

JNIEXPORT jdouble JNICALL Java_com_skinds_app_core_CoeurDS_natFrequence(JNIEnv *e, jobject s) { (void)e; (void)s; return g_sample_rate; }
JNIEXPORT jdouble JNICALL Java_com_skinds_app_core_CoeurDS_natFps(JNIEnv *e, jobject s) { (void)e; (void)s; return g_fps; }
JNIEXPORT void JNICALL Java_com_skinds_app_core_CoeurDS_natReset(JNIEnv *e, jobject s) { (void)e; (void)s; if (g_charge) p_reset(); }

JNIEXPORT void JNICALL
Java_com_skinds_app_core_CoeurDS_natEjecter(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    if (g_charge) { p_unload_game(); g_charge = false; }
    g_son_n = 0;
}

JNIEXPORT jbyteArray JNICALL
Java_com_skinds_app_core_CoeurDS_natSauver(JNIEnv *env, jobject self) {
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
Java_com_skinds_app_core_CoeurDS_natRestaurer(JNIEnv *env, jobject self, jbyteArray donnees) {
    (void)self;
    if (!g_charge) return JNI_FALSE;
    jsize n = (*env)->GetArrayLength(env, donnees);
    jbyte *buf = (*env)->GetByteArrayElements(env, donnees, NULL);
    bool ok = p_unserialize(buf, (size_t)n);
    (*env)->ReleaseByteArrayElements(env, donnees, buf, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
