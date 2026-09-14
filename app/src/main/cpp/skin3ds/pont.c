/*
 * Pont entre l'application et le coeur Citra.
 *
 * Le coeur est une bibliotheque libretro : on l'ouvre avec dlopen, on lui
 * fournit les fonctions dont il a besoin — image, son, manette — puis on lui
 * demande une image a la fois.
 *
 * Ce fichier ne concerne que la PSP. Rien n'y vient d'une autre console.
 */
#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <unistd.h>
#include <signal.h>
#include <pthread.h>
#include <GLES3/gl3.h>
#include <EGL/egl.h>

/* Le journal est defini plus bas mais utilise plus haut : on l'annonce ici,
   sinon le compilateur refuse les appels qui le precedent. */
static void noter(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

/* Journal du coeur, annonce ici pour la meme raison : il est defini plus bas
   mais fourni au coeur plus haut. */
static void journal_coeur(unsigned niveau, const char *fmt, ...)
    __attribute__((format(printf, 2, 3)));

/* Fournies au coeur des qu'il demande le rendu materiel, donc avant d'etre
   definies plus bas. */
static uintptr_t fb_courant(void);
static void *adresse_gl(const char *nom);

/* ==================================================== interface libretro */

enum {
    ENV_SET_ROTATION = 1, ENV_GET_OVERSCAN = 2, ENV_GET_CAN_DUPE = 3,
    ENV_SET_MESSAGE = 6, ENV_SHUTDOWN = 7, ENV_SET_PERFORMANCE_LEVEL = 8,
    ENV_GET_SYSTEM_DIRECTORY = 9, ENV_SET_PIXEL_FORMAT = 10,
    ENV_SET_INPUT_DESCRIPTORS = 11, ENV_SET_HW_RENDER = 14,
    ENV_GET_VARIABLE = 15, ENV_SET_VARIABLES = 16, ENV_GET_VARIABLE_UPDATE = 17,
    ENV_SET_SUPPORT_NO_GAME = 18, ENV_GET_LOG_INTERFACE = 27,
    ENV_GET_PERF_INTERFACE = 28, ENV_GET_CORE_ASSETS_DIRECTORY = 30,
    ENV_GET_SAVE_DIRECTORY = 31, ENV_SET_SYSTEM_AV_INFO = 32,
    ENV_SET_GEOMETRY = 37, ENV_GET_USERNAME = 38, ENV_GET_LANGUAGE = 39,
    ENV_GET_INPUT_BITMASKS = 51 | 0x10000,
    ENV_GET_CORE_OPTIONS_VERSION = 52, ENV_SET_CORE_OPTIONS = 53,
    ENV_SET_CORE_OPTIONS_INTL = 54, ENV_SET_CORE_OPTIONS_DISPLAY = 55,
    ENV_SET_CORE_OPTIONS_V2 = 67, ENV_SET_CORE_OPTIONS_V2_INTL = 68,
};

enum { PIX_0RGB1555 = 0, PIX_XRGB8888 = 1, PIX_RGB565 = 2 };
enum { DEVICE_JOYPAD = 1, DEVICE_POINTER = 6, DEVICE_ANALOG = 5 };
/* Types de contexte graphique, dans les valeurs exactes de libretro.
   J'avais mis OPENGLES2 a 1, qui est en realite l'OpenGL de bureau : ma
   verification refusait alors le contexte que Citra demandait. */
enum { HW_NONE = 0, HW_OPENGL = 1, HW_OPENGLES2 = 2, HW_OPENGL_CORE = 3,
       HW_OPENGLES3 = 4, HW_OPENGLES_VERSION = 5, HW_VULKAN = 6 };

#define FB_DUPLIQUE ((void *)-1)

struct retro_variable { const char *key; const char *value; };
struct retro_log_callback { void (*log)(unsigned, const char *, ...); };

struct retro_hw_render_callback {
    unsigned context_type;
    void (*context_reset)(void);
    uintptr_t (*get_current_framebuffer)(void);
    void *(*get_proc_address)(const char *sym);
    bool depth, stencil, bottom_left_origin;
    unsigned version_major, version_minor;
    bool cache_context;
    void (*context_destroy)(void);
    bool debug_context;
};

struct retro_game_geometry {
    unsigned base_width, base_height, max_width, max_height;
    float aspect_ratio;
};
struct retro_system_timing { double fps, sample_rate; };
struct retro_system_av_info {
    struct retro_game_geometry geometry;
    struct retro_system_timing timing;
};
struct retro_system_info {
    const char *library_name, *library_version, *valid_extensions;
    bool need_fullpath, block_extract;
};
struct retro_game_info {
    const char *path;
    const void *data;
    size_t size;
    const char *meta;
};

/* ==================================================== etat du pont */

static void *g_lib = NULL;
static bool g_init = false;          /* bibliotheque ouverte */
static bool g_coeur_init = false;    /* retro_init appele */
static bool g_charge = false;        /* un jeu est charge */

static char g_dossier[600] = {0};
static char g_cache[600] = {0};
static FILE *g_journal = NULL;
static pthread_mutex_t g_journal_verrou = PTHREAD_MUTEX_INITIALIZER;

/* Ou en est le pont : le capteur d'arret brutal l'affiche. */
static const char *g_etape = "demarrage";
static unsigned long g_images = 0;

/* pointeurs vers les fonctions du coeur */
static void   (*p_set_environment)(bool (*)(unsigned, void *));
static void   (*p_set_video_refresh)(void (*)(const void *, unsigned, unsigned, size_t));
static void   (*p_set_audio_sample)(void (*)(int16_t, int16_t));
static size_t (*p_set_audio_sample_batch)(size_t (*)(const int16_t *, size_t));
static void   (*p_set_input_poll)(void (*)(void));
static void   (*p_set_input_state)(int16_t (*)(unsigned, unsigned, unsigned, unsigned));
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

#define CHARGE(nom) do { p_##nom = dlsym(g_lib, "retro_" #nom); \
    if (!p_##nom) { noter("symbole manquant : retro_" #nom); return JNI_FALSE; } } while (0)

/* ==================================================== journal */

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
    va_list ap; va_start(ap, fmt); ecrire_journal(fmt, ap); va_end(ap);
}

/* ==================================================== arrets brutaux */

static struct sigaction g_avant[5];
static const int SIGNAUX[] = { SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT };

static void gerer_signal(int sig, siginfo_t *info, void *ctx) {
    const char *nom = sig == SIGSEGV ? "acces memoire interdit"
                    : sig == SIGBUS  ? "acces mal aligne"
                    : sig == SIGILL  ? "instruction illegale"
                    : sig == SIGFPE  ? "erreur de calcul"
                    : sig == SIGABRT ? "abandon" : "signal inconnu";
    noter("ARRET BRUTAL : %s pendant « %s », apres %lu images",
          nom, g_etape, g_images);
    if (g_journal) { fflush(g_journal); fclose(g_journal); g_journal = NULL; }
    for (unsigned i = 0; i < sizeof SIGNAUX / sizeof SIGNAUX[0]; i++) {
        if (SIGNAUX[i] != sig) continue;
        if ((g_avant[i].sa_flags & SA_SIGINFO) && g_avant[i].sa_sigaction) {
            g_avant[i].sa_sigaction(sig, info, ctx); return;
        }
        if (g_avant[i].sa_handler && g_avant[i].sa_handler != SIG_DFL
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

/* ==================================================== options du coeur */

#define MAX_VARS 64
static char g_cle[MAX_VARS][64];
static char g_val[MAX_VARS][64];
static int g_nvars = 0;
static bool g_vars_changees = true;

static void var_poser(const char *cle, const char *val) {
    for (int i = 0; i < g_nvars; i++) {
        if (strcmp(g_cle[i], cle) == 0) {
            snprintf(g_val[i], sizeof g_val[i], "%s", val);
            g_vars_changees = true;
            return;
        }
    }
    if (g_nvars >= MAX_VARS) return;
    snprintf(g_cle[g_nvars], sizeof g_cle[0], "%s", cle);
    snprintf(g_val[g_nvars], sizeof g_val[0], "%s", val);
    g_nvars++;
    g_vars_changees = true;
}

static const char *var_lire(const char *cle) {
    for (int i = 0; i < g_nvars; i++)
        if (strcmp(g_cle[i], cle) == 0) return g_val[i];
    return NULL;
}

/* ==================================================== rendu */

static GLuint g_fbo = 0, g_tex = 0, g_depth = 0;
static int g_fw = 0, g_fh = 0;
static unsigned g_bw = 480, g_bh = 272;
static unsigned g_maxw = 480, g_maxh = 272;
static double g_fps = 60.0, g_sample_rate = 44100.0;
static bool g_hw_demande = false, g_hw_pret = false;
static struct retro_hw_render_callback g_hw;
static int g_format = PIX_XRGB8888;
static int g_mesures = 0;
/* Clarte moyenne de la derniere image relue : dit si le coeur dessine. */
static int g_clarte = -1;

/*
 * Tampon de sortie : l'image reduite qu'on relit reellement.
 *
 * Le jeu est calcule en haute definition, mais on ne relit qu'une image
 * ramenee a une taille fixe. La relecture coute donc la meme chose quelle que
 * soit la finesse choisie, et le moyennage des pixels adoucit les contours.
 */
static GLuint g_fbo_sortie = 0, g_tex_sortie = 0;
static int g_sortie_l = 0, g_sortie_h = 0;
static int g_reduction = 960;

/*
 * Barriere par tampon de transfert : elle dit quand la copie demandee a la
 * carte graphique est reellement terminee. Sans elle on lit un tampon encore
 * en cours d'ecriture, et l'image se dechire des qu'elle grossit.
 */
static GLsync g_barriere[2] = {0, 0};
static GLuint g_pbo[2] = {0, 0};
static int g_pbo_courant = 0;
static bool g_pbo_rempli[2] = {false, false};
static unsigned g_pbo_l[2] = {0, 0}, g_pbo_h[2] = {0, 0};
static size_t g_pbo_taille = 0;

static void liberer_fbo(void) {
    if (g_fbo) { glDeleteFramebuffers(1, &g_fbo); g_fbo = 0; }
    if (g_tex) { glDeleteTextures(1, &g_tex); g_tex = 0; }
    if (g_depth) { glDeleteRenderbuffers(1, &g_depth); g_depth = 0; }
    g_fw = g_fh = 0;
}

static bool creer_fbo(int w, int h) {
    liberer_fbo();
    glGenTextures(1, &g_tex);
    glBindTexture(GL_TEXTURE_2D, g_tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glGenRenderbuffers(1, &g_depth);
    glBindRenderbuffer(GL_RENDERBUFFER, g_depth);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, w, h);

    glGenFramebuffers(1, &g_fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, g_fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g_tex, 0);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT,
                              GL_RENDERBUFFER, g_depth);
    GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (st != GL_FRAMEBUFFER_COMPLETE) {
        noter("tampon de rendu %d x %d incomplet (0x%x)", w, h, st);
        liberer_fbo();
        return false;
    }
    g_fw = w; g_fh = h;
    return true;
}

static void liberer_sortie(void) {
    if (g_fbo_sortie) { glDeleteFramebuffers(1, &g_fbo_sortie); g_fbo_sortie = 0; }
    if (g_tex_sortie) { glDeleteTextures(1, &g_tex_sortie); g_tex_sortie = 0; }
    g_sortie_l = g_sortie_h = 0;
}

static bool preparer_sortie(int w, int h) {
    if (g_fbo_sortie && w == g_sortie_l && h == g_sortie_h) return true;
    liberer_sortie();
    glGenTextures(1, &g_tex_sortie);
    glBindTexture(GL_TEXTURE_2D, g_tex_sortie);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glGenFramebuffers(1, &g_fbo_sortie);
    glBindFramebuffer(GL_FRAMEBUFFER, g_fbo_sortie);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g_tex_sortie, 0);
    GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (st != GL_FRAMEBUFFER_COMPLETE) { liberer_sortie(); return false; }
    g_sortie_l = w; g_sortie_h = h;
    noter("image reduite a %d x %d avant relecture", w, h);
    return true;
}

static void liberer_pbo(void) {
    for (int i = 0; i < 2; i++)
        if (g_barriere[i]) { glDeleteSync(g_barriere[i]); g_barriere[i] = 0; }
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

/* ==================================================== tracé direct
 *
 * On dessine la texture du coeur directement a l'ecran, comme le fait
 * l'application de reference. La relecture pixel par pixel etait une
 * impasse : elle coute cher et depend de la taille de la surface.
 */
static GLuint g_prog = 0, g_vao = 0, g_vbo = 0;
static GLint g_uni_tex = -1;

static const char *VS =
    "#version 300 es\n"
    "layout(location=0) in vec2 pos;\n"
    "layout(location=1) in vec2 uv;\n"
    "out vec2 v_uv;\n"
    "void main() { v_uv = uv; gl_Position = vec4(pos, 0.0, 1.0); }\n";

static const char *FS =
    "#version 300 es\n"
    "precision mediump float;\n"
    "in vec2 v_uv;\n"
    "uniform sampler2D t;\n"
    "out vec4 couleur;\n"
    "void main() { couleur = vec4(texture(t, v_uv).rgb, 1.0); }\n";

static GLuint compiler(GLenum type, const char *code) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &code, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(s, sizeof log, NULL, log);
        noter("ECHEC compilation du shader : %s", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

static bool preparer_trace(void) {
    if (g_prog) return true;
    GLuint vs = compiler(GL_VERTEX_SHADER, VS);
    GLuint fs = compiler(GL_FRAGMENT_SHADER, FS);
    if (!vs || !fs) return false;
    g_prog = glCreateProgram();
    glAttachShader(g_prog, vs);
    glAttachShader(g_prog, fs);
    glLinkProgram(g_prog);
    GLint ok = 0;
    glGetProgramiv(g_prog, GL_LINK_STATUS, &ok);
    glDeleteShader(vs); glDeleteShader(fs);
    if (!ok) {
        char log[512];
        glGetProgramInfoLog(g_prog, sizeof log, NULL, log);
        noter("ECHEC edition de liens du shader : %s", log);
        glDeleteProgram(g_prog); g_prog = 0;
        return false;
    }
    g_uni_tex = glGetUniformLocation(g_prog, "t");
    glGenVertexArrays(1, &g_vao);
    glGenBuffers(1, &g_vbo);
    noter("trace direct pret");
    return true;
}

/* ==================================================== son */

#define SON_CAP (48000 * 2)
static int16_t g_son[SON_CAP];
static size_t g_son_n = 0;
static pthread_mutex_t g_son_verrou = PTHREAD_MUTEX_INITIALIZER;

/*
 * Le coeur peut nous livrer le son depuis son propre fil : les deux bouts
 * touchent au meme compteur, et sans verrou une copie finit par deborder.
 */
static void son_un(int16_t g, int16_t d) {
    pthread_mutex_lock(&g_son_verrou);
    if (g_son_n + 2 <= SON_CAP) { g_son[g_son_n++] = g; g_son[g_son_n++] = d; }
    pthread_mutex_unlock(&g_son_verrou);
}

static size_t son_lot(const int16_t *d, size_t images) {
    pthread_mutex_lock(&g_son_verrou);
    size_t n = images * 2;
    if (g_son_n + n > SON_CAP) n = (g_son_n < SON_CAP) ? SON_CAP - g_son_n : 0;
    if (n) { memcpy(g_son + g_son_n, d, n * sizeof(int16_t)); g_son_n += n; }
    pthread_mutex_unlock(&g_son_verrou);
    return images;
}

/* Stylet : position sur l'ecran du bas, en unites libretro. */
static int16_t g_stylet_x = 0, g_stylet_y = 0;
static int g_stylet_pose = 0;
/* Combien de fois le coeur a interroge l'ecran tactile. S'il ne le fait
   jamais, le probleme n'est pas dans nos coordonnees. */
static unsigned long g_pointeur_lu = 0;
/* Derniere position transmise, pour la relire dans « Etat ». */
static float g_st_fx = -1, g_st_fy = -1, g_st_gx = -1, g_st_gy = -1;
/*
 * Convention de coordonnees du stylet.
 *
 * 0 : rapportees a l'IMAGE ENTIERE, les deux ecrans empiles. C'est la regle
 *     libretro, et celle qui marche sur la DS.
 * 1 : rapportees a l'ECRAN DU BAS seul. Certains coeurs appliquent eux-memes
 *     leur mise en page et attendent des coordonnees deja ramenees a la dalle
 *     tactile.
 */
static int g_convention = 0;

/* ==================================================== manette */

static int g_boutons = 0;
static int16_t g_stick[2] = {0, 0};

static void manette_poll(void) { }

static int16_t manette_etat(unsigned port, unsigned device, unsigned index, unsigned id) {
    /* Le pointeur peut etre interroge sur un autre port que la manette :
       on le sert quel que soit le port, sinon l'ecran tactile reste muet. */
    if (device != DEVICE_POINTER && port != 0) return 0;
    if (device == DEVICE_ANALOG) {
        if (index != 0) return 0;         /* la PSP n'a qu'un stick */
        return (id == 0) ? g_stick[0] : (id == 1) ? g_stick[1] : 0;
    }
    if (device == DEVICE_POINTER) {
        g_pointeur_lu++;
        /*
         * L'ecran du bas est tactile.
         *
         * Le coeur demande d'abord COMBIEN de doigts sont poses — c'est
         * l'identifiant 3. Sans cette reponse, il en conclut qu'il n'y en a
         * aucun et n'interroge meme pas leur position : le stylet restait donc
         * sans effet.
         */
        if (id == 3) return (int16_t)g_stylet_pose;   /* nombre de points */
        if (index != 0) return 0;
        if (id == 0) return g_stylet_x;
        if (id == 1) return g_stylet_y;
        if (id == 2) return (int16_t)g_stylet_pose;
        return 0;
    }
    if (device != DEVICE_JOYPAD) return 0;
    if (id == 256) return (int16_t)(g_boutons & 0xFFFF);   /* lecture groupee */
    return (id < 16 && (g_boutons & (1 << id))) ? 1 : 0;
}

/* ==================================================== rappels du coeur */

/*
 * Image logicielle : les pixels que le coeur nous remet directement.
 *
 * Un coeur peut dessiner de deux facons. En materiel, il ecrit dans le tampon
 * qu'on lui a designe. En logiciel, il nous remet les pixels ici meme — et je
 * les jetais, ce qui donnait un ecran noir alors que l'emulation tournait.
 */
#define SOFT_MAX (1024 * 1024)
static uint32_t g_soft[SOFT_MAX];
static unsigned g_soft_l = 0, g_soft_h = 0;
static bool g_soft_pret = false;

static void video(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (w && h) { g_bw = w; g_bh = h; }
    if (!data) return;                       /* image identique a la precedente */
    if (data == FB_DUPLIQUE) return;         /* le coeur a dessine en materiel */
    if (!w || !h || (size_t)w * h > SOFT_MAX) return;

    if (g_format == PIX_XRGB8888) {
        const uint8_t *src = data;
        for (unsigned y = 0; y < h; y++) {
            const uint32_t *l = (const uint32_t *)(src + (size_t)y * pitch);
            uint32_t *o = g_soft + (size_t)y * w;
            for (unsigned x = 0; x < w; x++) o[x] = 0xFF000000u | (l[x] & 0x00FFFFFFu);
        }
    } else if (g_format == PIX_RGB565) {
        const uint8_t *src = data;
        for (unsigned y = 0; y < h; y++) {
            const uint16_t *l = (const uint16_t *)(src + (size_t)y * pitch);
            uint32_t *o = g_soft + (size_t)y * w;
            for (unsigned x = 0; x < w; x++) {
                unsigned p = l[x];
                unsigned r = ((p >> 11) & 0x1F) * 255 / 31;
                unsigned v = ((p >> 5) & 0x3F) * 255 / 63;
                unsigned b = (p & 0x1F) * 255 / 31;
                o[x] = 0xFF000000u | (r << 16) | (v << 8) | b;
            }
        }
    } else {
        const uint8_t *src = data;
        for (unsigned y = 0; y < h; y++) {
            const uint16_t *l = (const uint16_t *)(src + (size_t)y * pitch);
            uint32_t *o = g_soft + (size_t)y * w;
            for (unsigned x = 0; x < w; x++) {
                unsigned p = l[x];
                unsigned r = ((p >> 10) & 0x1F) * 255 / 31;
                unsigned v = ((p >> 5) & 0x1F) * 255 / 31;
                unsigned b = (p & 0x1F) * 255 / 31;
                o[x] = 0xFF000000u | (r << 16) | (v << 8) | b;
            }
        }
    }
    g_soft_l = w; g_soft_h = h;
    g_soft_pret = true;
    if (g_mesures < 3) {
        g_mesures++;
        noter("image LOGICIELLE recue : %u x %u, format %d", w, h, g_format);
    }
}

static bool environnement(unsigned cmd, void *data) {
    switch (cmd) {
        case ENV_GET_CAN_DUPE:
            *(bool *)data = true; return true;

        case ENV_SET_PIXEL_FORMAT:
            g_format = *(const int *)data;
            return true;

        case ENV_GET_SYSTEM_DIRECTORY:
        case ENV_GET_SAVE_DIRECTORY:
        case ENV_GET_CORE_ASSETS_DIRECTORY:
            *(const char **)data = g_dossier;
            return true;

        case ENV_SET_HW_RENDER: {
            struct retro_hw_render_callback *cb = data;

            /*
             * On renseigne la structure DU COEUR, pas seulement notre copie.
             *
             * Je gardais une copie et je mettais un pointeur nul dans la
             * sienne, en pensant le renseigner plus tard — mais plus tard je
             * ne touchais que ma copie. Le coeur appelait donc un pointeur nul
             * pour savoir ou dessiner, et l'emulation tombait au bout de
             * quelques images, le temps qu'il ait besoin du tampon.
             */
            cb->get_current_framebuffer = fb_courant;
            cb->get_proc_address = adresse_gl;
            g_hw = *cb;
            g_hw_demande = true;
            const char *nom =
                g_hw.context_type == HW_OPENGLES2 ? "OpenGL ES 2" :
                g_hw.context_type == HW_OPENGLES3 ? "OpenGL ES 3" :
                g_hw.context_type == HW_OPENGLES_VERSION ? "OpenGL ES, version demandee" :
                g_hw.context_type == HW_OPENGL ? "OpenGL de bureau" :
                g_hw.context_type == HW_VULKAN ? "Vulkan" :
                g_hw.context_type == HW_NONE ? "aucun" : "inconnu";
            noter("rendu materiel demande : %s (type %u), profondeur %d, pochoir %d, "
                  "origine en bas %d",
                  nom, g_hw.context_type, (int)g_hw.depth, (int)g_hw.stencil,
                  (int)g_hw.bottom_left_origin);
            /*
             * On accepte tout ce qui est OpenGL ES, y compris la forme
             * « version demandee » que Citra emploie.
             *
             * Refuser ne servait a rien : le coeur dessine en materiel de
             * toute facon, et sans tampon lie de notre part il ecrivait
             * n'importe ou — d'ou l'acces memoire interdit apres quelques
             * images. Mieux vaut fournir le tampon et signaler l'inconnu.
             */
            if (g_hw.context_type != HW_OPENGLES2 &&
                g_hw.context_type != HW_OPENGLES3 &&
                g_hw.context_type != HW_OPENGLES_VERSION) {
                noter("ATTENTION : contexte %u inattendu, on le sert quand meme",
                      g_hw.context_type);
            }
            return true;
        }

        case ENV_GET_VARIABLE: {
            struct retro_variable *v = data;
            const char *val = var_lire(v->key);
            v->value = val;
            return val != NULL;
        }

        case ENV_GET_VARIABLE_UPDATE:
            *(bool *)data = g_vars_changees;
            g_vars_changees = false;
            return true;

        case ENV_SET_VARIABLES:
        case ENV_SET_CORE_OPTIONS:
        case ENV_SET_CORE_OPTIONS_INTL:
        case ENV_SET_CORE_OPTIONS_V2:
        case ENV_SET_CORE_OPTIONS_V2_INTL:
        case ENV_SET_CORE_OPTIONS_DISPLAY:
        case ENV_SET_INPUT_DESCRIPTORS:
        case ENV_SET_PERFORMANCE_LEVEL:
        case ENV_SET_SUPPORT_NO_GAME:
        case ENV_SET_MESSAGE:
        case ENV_SET_ROTATION:
            return true;

        case ENV_GET_LOG_INTERFACE: {
            struct retro_log_callback *cb = data;
            cb->log = journal_coeur;
            return true;
        }

        case ENV_GET_CORE_OPTIONS_VERSION:
            *(unsigned *)data = 2; return true;

        case ENV_GET_INPUT_BITMASKS:
            return true;

        case ENV_GET_LANGUAGE:
            *(unsigned *)data = 1;   /* francais */
            return true;

        case ENV_SET_SYSTEM_AV_INFO:
        case ENV_SET_GEOMETRY: {
            const struct retro_system_av_info *av = data;
            if (av->geometry.base_width) g_bw = av->geometry.base_width;
            if (av->geometry.base_height) g_bh = av->geometry.base_height;
            return true;
        }

        default:
            return false;
    }
}

/* Combien de fois le coeur a demande ou dessiner. S'il ne le demande jamais,
   c'est qu'il dessine ailleurs, et relire notre tampon ne donne que du noir. */
static unsigned long g_fb_demande = 0;

static uintptr_t fb_courant(void) {
    g_fb_demande++;
    return (uintptr_t)g_fbo;
}

/* Le coeur demande lui-meme les fonctions OpenGL dont il a besoin. */
static void *adresse_gl(const char *nom) {
    return (void *)eglGetProcAddress(nom);
}

/*
 * Journal du coeur.
 *
 * Sans lui, un coeur qui demande ce canal recoit un refus, et certains s'en
 * servent quand meme : l'appel part alors vers un pointeur jamais renseigne,
 * ce qui donne exactement l'acces memoire interdit constate. En le
 * fournissant, on evite ce piege ET on recupere les messages du coeur, qui
 * disent ce qui lui manque.
 */
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

/* ==================================================== fonctions Java */

JNIEXPORT jboolean JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natInit(JNIEnv *env, jobject self, jstring chemin,
                                           jstring dossier, jstring cache) {
    (void)self;
    if (g_init) return JNI_TRUE;
    const char *c = (*env)->GetStringUTFChars(env, chemin, NULL);
    const char *d = (*env)->GetStringUTFChars(env, dossier, NULL);
    const char *t = (*env)->GetStringUTFChars(env, cache, NULL);
    snprintf(g_dossier, sizeof g_dossier, "%s", d);
    snprintf(g_cache, sizeof g_cache, "%s", t);

    /* Citra cree des fichiers temporaires : sur Android il n'y a ni /tmp ni
       /dev/shm, on lui designe donc un dossier ou il a le droit d'ecrire. */
    setenv("TMPDIR", g_cache, 1);
    setenv("HOME", g_dossier, 1);

    {
        char jc[700];
        snprintf(jc, sizeof jc, "%s/journal.txt", g_dossier);
        g_journal = fopen(jc, "a");     /* en ajout : une trace doit survivre */
        noter("");
        noter("--- ouverture du coeur ---");
        noter("dossier systeme : %s", g_dossier);
        noter("dossier temporaire : %s", g_cache);
        noter("bibliotheque : %s", c);
    }

    g_lib = dlopen(c, RTLD_NOW | RTLD_LOCAL);
    (*env)->ReleaseStringUTFChars(env, chemin, c);
    (*env)->ReleaseStringUTFChars(env, dossier, d);
    (*env)->ReleaseStringUTFChars(env, cache, t);
    if (!g_lib) { noter("ECHEC dlopen : %s", dlerror()); return JNI_FALSE; }

    CHARGE(set_environment); CHARGE(set_video_refresh); CHARGE(set_audio_sample);
    CHARGE(set_audio_sample_batch); CHARGE(set_input_poll); CHARGE(set_input_state);
    CHARGE(init); CHARGE(deinit); CHARGE(load_game); CHARGE(unload_game);
    CHARGE(run); CHARGE(reset); CHARGE(get_system_info); CHARGE(get_system_av_info);
    CHARGE(set_controller_port_device); CHARGE(serialize_size);
    CHARGE(serialize); CHARGE(unserialize);

    /*
     * Les reglages de Citra, dans les noms exacts du coeur.
     *
     * La resolution interne est le coeur du sujet : la console dessine en
     * 480 sur 272, et ce facteur multiplie cette definition. Elle ne change
     * jamais la taille affichee, seulement la finesse.
     */
    /* Citra : rendu materiel, seul capable de monter la resolution interne.
     *
     * La disposition « Default Top-Bottom » empile les deux ecrans dans une
     * seule image : celui du haut, 400 sur 240, occupe toute la largeur ;
     * celui du bas, 320 sur 240, est centre en dessous. L'application trace
     * chaque moitie dans le rectangle voulu par l'habillage.
     */
    var_poser("citra_use_hw_renderer", "enabled");
    var_poser("citra_use_hw_shader", "enabled");
    var_poser("citra_use_shader_jit", "enabled");
    var_poser("citra_resolution_factor", "1x (Native)");
    var_poser("citra_layout_option", "Default Top-Bottom Screen");
    /* On n'intervertit jamais les deux ecrans : l'ecran du haut reste en
       haut de l'image, celui du bas en dessous. C'est sur cette hypothese que
       reposent le decoupage de l'image ET la position du stylet. */
    var_poser("citra_swap_screen", "Top");
    var_poser("citra_use_frame_limit", "enabled");
    /*
     * ECRAN TACTILE.
     *
     * Citra ne tient pas compte du stylet tant que cette option est
     * desactivee — et elle l'est par defaut. C'est pour cela qu'il
     * interrogeait le pointeur des milliers de fois sans reagir : il lisait la
     * position, puis la jetait.
     */
    var_poser("citra_touch_touchscreen", "enabled");
    var_poser("citra_mouse_touchscreen", "enabled");
    /* on ne dessine pas de curseur par-dessus le jeu */
    var_poser("citra_render_touchscreen", "disabled");

    var_poser("citra_use_virtual_sd", "enabled");
    var_poser("citra_is_new_3ds", "Old 3DS");
    var_poser("citra_region_value", "Auto");
    var_poser("citra_language", "French");
    p_set_environment(environnement);
    p_set_video_refresh(video);
    p_set_audio_sample(son_un);
    p_set_audio_sample_batch(son_lot);
    p_set_input_poll(manette_poll);
    p_set_input_state(manette_etat);

    /* Le capteur AVANT toute initialisation : c'est la qu'un plantage natif
       peut survenir, et sans lui l'application disparait sans laisser de
       trace. */
    installer_signaux();

    /*
     * retro_init ICI, au chargement de la bibliotheque.
     *
     * C'est l'ordre attendu par un coeur libretro, et celui qu'emploient les
     * emulateurs qui fonctionnent. Le repousser laissait le coeur dans un
     * etat incomplet : il ne prenait jamais en compte le contexte graphique,
     * et son image restait noire.
     */
    p_init();
    g_coeur_init = true;
    g_init = true;
    noter("bibliotheque ouverte ; le coeur sera initialise au premier jeu");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natGlInit(JNIEnv *env, jobject self, jint w, jint h) {
    (void)env; (void)self;
    if (!creer_fbo(w, h)) return JNI_FALSE;
    /* On previent le coeur que le contexte graphique existe. */
    if (g_hw_demande && g_hw.context_reset) g_hw.context_reset();
    g_hw_pret = true;
    noter("tampon de rendu cree en %d x %d", w, h);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natGlTaille(JNIEnv *env, jobject self, jint w, jint h) {
    (void)env; (void)self;
    if (w == g_fw && h == g_fh) return JNI_TRUE;
    /* Si un jeu tourne, on suit le protocole : perte du contexte, nouveau
       tampon, reprise. Sans la premiere annonce le coeur fabriquerait un
       second jeu de ressources sans liberer le premier. */
    bool actif = g_charge && g_hw_demande;
    if (actif && g_hw.context_destroy) g_hw.context_destroy();
    if (!creer_fbo(w, h)) return JNI_FALSE;
    if (actif && g_hw.context_reset) g_hw.context_reset();
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natCharger(JNIEnv *env, jobject self, jstring fichier) {
    (void)self;
    const char *f = (*env)->GetStringUTFChars(env, fichier, NULL);
    static char copie[1024];
    snprintf(copie, sizeof copie, "%s", f);
    (*env)->ReleaseStringUTFChars(env, fichier, f);

    /* Chaque coeur recoit un jeu a sa maniere : certains veulent un chemin,
       d'autres le contenu en memoire. On le lui demande. */
    struct retro_system_info si;
    memset(&si, 0, sizeof si);
    p_get_system_info(&si);
    noter("coeur : %s %s, extensions %s, chemin complet %s",
          si.library_name ? si.library_name : "?",
          si.library_version ? si.library_version : "?",
          si.valid_extensions ? si.valid_extensions : "?",
          si.need_fullpath ? "requis" : "non requis");

    static uint8_t *contenu = NULL;
    free(contenu); contenu = NULL;
    struct retro_game_info info = { copie, NULL, 0, NULL };

    if (!si.need_fullpath) {
        FILE *fp = fopen(copie, "rb");
        if (!fp) { noter("REFUS : fichier illisible"); return JNI_FALSE; }
        fseek(fp, 0, SEEK_END);
        long taille = ftell(fp);
        fseek(fp, 0, SEEK_SET);
        if (taille <= 0) { fclose(fp); noter("REFUS : fichier vide"); return JNI_FALSE; }
        contenu = malloc((size_t)taille);
        if (!contenu) { fclose(fp); noter("REFUS : memoire insuffisante"); return JNI_FALSE; }
        size_t lus = fread(contenu, 1, (size_t)taille, fp);
        fclose(fp);
        if (lus != (size_t)taille) {
            noter("REFUS : lecture incomplete"); free(contenu); contenu = NULL;
            return JNI_FALSE;
        }
        info.data = contenu; info.size = (size_t)taille;
        noter("jeu lu en memoire : %ld octets", taille);
    }

    g_etape = "chargement du jeu";
    noter("chargement : %s", copie);
    if (!p_load_game(&info)) { noter("REFUS : le coeur n'a pas accepte le jeu"); return JNI_FALSE; }

    struct retro_system_av_info av;
    memset(&av, 0, sizeof av);
    p_get_system_av_info(&av);
    if (av.geometry.base_width)  g_bw = av.geometry.base_width;
    if (av.geometry.base_height) g_bh = av.geometry.base_height;
    g_maxw = av.geometry.max_width  ? av.geometry.max_width  : g_bw;
    g_maxh = av.geometry.max_height ? av.geometry.max_height : g_bh;
    if (av.timing.fps > 10.0) g_fps = av.timing.fps;
    if (av.timing.sample_rate > 8000.0) g_sample_rate = av.timing.sample_rate;
    noter("geometrie : base %u x %u, maximum %u x %u", g_bw, g_bh, g_maxw, g_maxh);

    /*
     * Le tampon est mis a la taille annoncee par le coeur AVANT de lui
     * signaler que le contexte existe. Sans cela il dessine plus grand que le
     * tampon, ne remplit qu'un coin, et c'est ce coin qui s'affiche.
     * On redescend par moities si la carte refuse la taille demandee.
     */
    if (g_hw_demande) {
        int vw = (int)g_maxw, vh = (int)g_maxh;
        if (vw < 480) vw = 480;
        if (vh < 272) vh = 272;
        if (vw > 4096) vw = 4096;
        if (vh > 4096) vh = 4096;
        if (vw != g_fw || vh != g_fh) {
            bool pose = false;
            int tw = vw, th = vh;
            for (int essai = 0; essai < 4 && !pose; essai++) {
                if (creer_fbo(tw, th)) { pose = true; break; }
                noter("tampon %d x %d refuse, on redescend", tw, th);
                tw /= 2; th /= 2;
                if (tw < 480 || th < 272) { tw = 480; th = 272; }
            }
            if (!pose && !creer_fbo(480, 272)) {
                noter("ECHEC : aucun tampon de rendu possible");
                return JNI_FALSE;
            }
            noter("tampon porte a %d x %d (demande %d x %d)", g_fw, g_fh, vw, vh);
        }
        if (g_hw.context_reset) { g_hw.context_reset(); g_hw_pret = true; }
    }

    p_set_controller_port_device(0, DEVICE_JOYPAD);
    /* On declare aussi le pointeur : c'est par lui que passe l'ecran
       tactile de la console. */
    p_set_controller_port_device(1, DEVICE_POINTER);
    g_charge = true;
    g_images = 0;
    pthread_mutex_lock(&g_son_verrou); g_son_n = 0; pthread_mutex_unlock(&g_son_verrou);
    noter("jeu pret : %u x %u, %.0f Hz, %.2f i/s, rendu %s",
          g_bw, g_bh, g_sample_rate, g_fps, g_hw_demande ? "materiel" : "logiciel");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natEjecter(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    if (g_charge) {
        /* On annonce au coeur que son contexte disparait : sans cette annonce
           il garde ses ressources, et le chargement suivant lui en fait creer
           un second jeu par-dessus. */
        if (g_hw_demande && g_hw_pret && g_hw.context_destroy) {
            g_hw.context_destroy();
            noter("contexte graphique libere avant ejection");
        }
        g_hw_pret = false;
        p_unload_game();
        g_charge = false;
    }
    pthread_mutex_lock(&g_son_verrou); g_son_n = 0; pthread_mutex_unlock(&g_son_verrou);
}

JNIEXPORT void JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natReset(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    if (g_charge) p_reset();
}

JNIEXPORT jint JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natGlImage(JNIEnv *env, jobject self, jint boutons,
                                              jfloat sx, jfloat sy) {
    (void)env; (void)self;
    if (!g_charge) return 0;
    if (g_hw_demande && !g_hw_pret) return 0;

    g_boutons = boutons;
    g_stick[0] = (int16_t)(sx * 32767.0f);
    g_stick[1] = (int16_t)(sy * 32767.0f);

    g_etape = "emulation d'une image";
    if (g_hw_demande) {
        glBindFramebuffer(GL_FRAMEBUFFER, g_fbo);
        glViewport(0, 0, g_fw, g_fh);
    }
    p_run();
    if (g_hw_demande) {
        /* Citra travaille sur son propre fil de rendu : ses commandes ne
           sont pas forcement executees quand p_run revient. On attend qu'il
           ait fini avant de relire, sinon on lit un tampon encore vide. */
        glFinish();
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }
    g_etape = "apres l'emulation";
    g_images++;
    if (g_images == 30) {
        noter("apres 30 images : ecran tactile interroge %lu fois", g_pointeur_lu);
        if (g_pointeur_lu == 0)
            noter("ATTENTION : le coeur n'interroge jamais l'ecran tactile");
    }
    if (g_images == 30) {
        noter("apres 30 images : le coeur a demande le tampon %lu fois",
              g_fb_demande);
        if (g_fb_demande == 0)
            noter("ATTENTION : le coeur ne dessine pas dans notre tampon");
    }
    return 1;
}

/*
 * Dessine l'image du coeur dans le rectangle de l'ecran.
 *
 * Les coordonnees sont celles de la vue, en pixels, origine en haut a gauche.
 * OpenGL compte du bas vers le haut : la conversion se fait ici.
 */
JNIEXPORT jboolean JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natGlDessiner(JNIEnv *env, jobject self,
                                                 jint x, jint y, jint w, jint h,
                                                 jint vueL, jint vueH,
                                                 jint partie) {
    (void)env; (void)self;
    if (!g_charge || !g_hw_pret || !g_tex || w <= 0 || h <= 0) return JNI_FALSE;
    if (!preparer_trace()) return JNI_FALSE;
    g_etape = "trace de l'image";

    /* du repere de la vue vers celui d'OpenGL, normalise entre -1 et 1 */
    float gx0 = (float)x / vueL * 2.0f - 1.0f;
    float gx1 = (float)(x + w) / vueL * 2.0f - 1.0f;
    float gy0 = 1.0f - (float)y / vueH * 2.0f;
    float gy1 = 1.0f - (float)(y + h) / vueH * 2.0f;

    /* Portion de la texture a tracer.
     *
     * Citra empile les deux ecrans : celui du haut, 400 sur 240, occupe toute
     * la largeur ; celui du bas, 320 sur 240, est centre juste en dessous.
     * « partie » vaut 0 pour l'ecran du haut, 1 pour celui du bas, 2 pour
     * l'image entiere.
     */
    float u = (g_fw > 0) ? (float)g_bw / (float)g_fw : 1.0f;
    float v = (g_fh > 0) ? (float)g_bh / (float)g_fh : 1.0f;
    if (u > 1.0f) u = 1.0f;
    if (v > 1.0f) v = 1.0f;

    float u0 = 0.0f, u1 = u, v0 = 0.0f, v1 = v;
    if (partie == 0) {                     /* ecran du haut : moitie superieure */
        v0 = v * 0.5f; v1 = v;
    } else if (partie == 1) {              /* ecran du bas : centre, plus etroit */
        float large = u * (320.0f / 400.0f);
        u0 = (u - large) * 0.5f;
        u1 = u0 + large;
        v0 = 0.0f; v1 = v * 0.5f;
    }

    /* v inverse : la texture du coeur a son origine en bas */
    /* v inverse : la texture du coeur a son origine en bas */
    const GLfloat quad[] = {
        gx0, gy0, u0, v1,
        gx1, gy0, u1, v1,
        gx0, gy1, u0, v0,
        gx1, gy1, u1, v0,
    };

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, vueL, vueH);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_CULL_FACE);

    glUseProgram(g_prog);
    glBindVertexArray(g_vao);
    glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof quad, quad, GL_STREAM_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), (void *)0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat),
                          (void *)(2 * sizeof(GLfloat)));
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, g_tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glUniform1i(g_uni_tex, 0);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (g_mesures < 4) {
        g_mesures++;
        GLenum err = glGetError();
        noter("trace : rectangle %d,%d %dx%d dans une vue %dx%d, "
              "texture %ux%u sur %dx%d%s",
              x, y, w, h, vueL, vueH, g_bw, g_bh, g_fw, g_fh,
              err ? "  ERREUR OpenGL" : "");
    }
    glBindVertexArray(0);
    glUseProgram(0);
    g_etape = "apres le trace";
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natLirePixels(JNIEnv *env, jobject self, jintArray sortie) {
    (void)self;
    if (!g_charge) return 0;
    g_etape = "relecture de l'image";

    /* Le coeur a dessine en logiciel : ses pixels sont deja prets. */
    if (g_soft_pret) {
        jsize cap = (*env)->GetArrayLength(env, sortie);
        if ((jsize)(g_soft_l * g_soft_h) > cap) return 0;
        (*env)->SetIntArrayRegion(env, sortie, 0,
                                  (jsize)(g_soft_l * g_soft_h), (const jint *)g_soft);
        unsigned long somme = 0;
        const uint32_t *l = g_soft + (size_t)(g_soft_h / 2) * g_soft_l;
        for (unsigned x = 0; x < g_soft_l; x++)
            somme += (l[x] & 0xFF) + ((l[x] >> 8) & 0xFF) + ((l[x] >> 16) & 0xFF);
        g_clarte = (int)(somme / (g_soft_l * 3));
        return (jint)((g_soft_l << 16) | g_soft_h);
    }

    if (!g_hw_pret || !g_fbo) return 0;

    unsigned w = g_bw, h = g_bh;
    if (!w || !h) return 0;
    if (w > (unsigned)g_fw) w = g_fw;
    if (h > (unsigned)g_fh) h = g_fh;

    /* Reduction sur la carte graphique, avant la relecture : le cout ne depend
       plus de la finesse choisie, et le moyennage adoucit les contours. */
    unsigned sw = w, sh = h;
    if (g_reduction > 0 && (int)w > g_reduction) {
        sw = (unsigned)g_reduction;
        sh = (unsigned)((double)h * (double)g_reduction / (double)w + 0.5);
        if (sh < 1) sh = 1;
    }
    size_t n = (size_t)sw * sh * 4;
    if (g_mesures < 4) noter("relecture : %u x %u calcule, reduit a %u x %u", w, h, sw, sh);
    if (!preparer_pbo(n)) return 0;

    GLuint source = g_fbo;
    if (sw != w || sh != h) {
        if (!preparer_sortie((int)sw, (int)sh)) return 0;
        glBindFramebuffer(GL_READ_FRAMEBUFFER, g_fbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, g_fbo_sortie);
        glBlitFramebuffer(0, 0, (GLint)w, (GLint)h, 0, 0, (GLint)sw, (GLint)sh,
                          GL_COLOR_BUFFER_BIT, GL_LINEAR);
        source = g_fbo_sortie;
    }
    w = sw; h = sh;

    glBindFramebuffer(GL_FRAMEBUFFER, source);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, g_pbo[g_pbo_courant]);
    glReadPixels(0, 0, (GLsizei)w, (GLsizei)h, GL_RGBA, GL_UNSIGNED_BYTE, 0);
    if (g_barriere[g_pbo_courant]) glDeleteSync(g_barriere[g_pbo_courant]);
    g_barriere[g_pbo_courant] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    g_pbo_rempli[g_pbo_courant] = true;
    g_pbo_l[g_pbo_courant] = w;
    g_pbo_h[g_pbo_courant] = h;

    int autre = 1 - g_pbo_courant;
    g_pbo_courant = autre;
    if (!g_pbo_rempli[autre]) { glBindBuffer(GL_PIXEL_PACK_BUFFER, 0); return 0; }

    /* On attend que la copie du tour precedent soit reellement terminee.
       L'attente est bornee : plutot sauter une image que d'en montrer une
       dechiree, et plutot que de bloquer le jeu. */
    if (g_barriere[autre]) {
        GLenum r = glClientWaitSync(g_barriere[autre], GL_SYNC_FLUSH_COMMANDS_BIT, 8000000);
        if (r == GL_TIMEOUT_EXPIRED) { glBindBuffer(GL_PIXEL_PACK_BUFFER, 0); return 0; }
        glDeleteSync(g_barriere[autre]);
        g_barriere[autre] = 0;
    }

    unsigned pw = g_pbo_l[autre], ph = g_pbo_h[autre];
    glBindBuffer(GL_PIXEL_PACK_BUFFER, g_pbo[autre]);
    const uint8_t *src = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0,
                                          (GLsizeiptr)((size_t)pw * ph * 4), GL_MAP_READ_BIT);
    if (!src) { glBindBuffer(GL_PIXEL_PACK_BUFFER, 0); return 0; }

    jsize cap = (*env)->GetArrayLength(env, sortie);
    if ((jsize)(pw * ph) > cap) {
        noter("ECHEC : image %u x %u trop grande pour le tableau (%d)", pw, ph, (int)cap);
        glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        return 0;
    }
    jint *dst = (*env)->GetIntArrayElements(env, sortie, NULL);
    /* OpenGL compte ses lignes du bas vers le haut, l'ecran du haut vers le
       bas : on retourne en recopiant. */
    for (unsigned y = 0; y < ph; y++) {
        const uint8_t *l = src + (size_t)(ph - 1 - y) * pw * 4;
        jint *o = dst + (size_t)y * pw;
        for (unsigned x = 0; x < pw; x++) {
            o[x] = (jint)(0xFF000000u | ((unsigned)l[x * 4] << 16)
                          | ((unsigned)l[x * 4 + 1] << 8) | (unsigned)l[x * 4 + 2]);
        }
    }
    (*env)->ReleaseIntArrayElements(env, sortie, dst, 0);
    glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    {
        unsigned long somme = 0;
        const uint8_t *l = src + (size_t)(ph / 2) * pw * 4;
        for (unsigned x = 0; x < pw; x++)
            somme += (unsigned)l[x * 4] + l[x * 4 + 1] + l[x * 4 + 2];
        g_clarte = (int)(somme / (pw * 3));
    }
    if (g_mesures < 6) {
        g_mesures++;
        /* Moyenne d'une ligne : une image entierement noire se voit tout de
           suite, et distingue « rien n'est dessine » de « rien n'est relu ». */
        unsigned long somme = 0;
        const uint8_t *l = src + (size_t)(ph / 2) * pw * 4;
        for (unsigned x = 0; x < pw; x++)
            somme += (unsigned)l[x * 4] + l[x * 4 + 1] + l[x * 4 + 2];
        noter("image recue : %u x %u, clarte moyenne %.1f%s",
              pw, ph, somme / (double)(pw * 3),
              somme == 0 ? "  (IMAGE NOIRE)" : "");
    }
    return (jint)((pw << 16) | ph);
}

JNIEXPORT jint JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natSon(JNIEnv *env, jobject self, jshortArray sortie) {
    (void)self;
    jsize cap = (*env)->GetArrayLength(env, sortie);
    static int16_t copie[SON_CAP];
    pthread_mutex_lock(&g_son_verrou);
    jsize n = (jsize)g_son_n;
    if (n > cap) n = cap;
    if (n > 0) memcpy(copie, g_son, (size_t)n * sizeof(int16_t));
    g_son_n = 0;
    pthread_mutex_unlock(&g_son_verrou);
    if (n > 0) (*env)->SetShortArrayRegion(env, sortie, 0, n, copie);
    return n;
}

/*
 * Position du stylet, donnee en fractions de l'ecran du bas.
 *
 * Citra empile les deux ecrans : celui du bas, 320 sur 240, est centre sous
 * celui du haut. Une position donnee sur l'ecran tactile doit donc etre
 * ramenee dans cette fenetre-la, pas dans la moitie basse exacte.
 */
JNIEXPORT void JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natStylet(JNIEnv *e, jobject s, jfloat fx, jfloat fy) {
    (void)e; (void)s;
    if (fx < 0.0f || fy < 0.0f || fx > 1.0f || fy > 1.0f) { g_stylet_pose = 0; return; }
    /*
     * Le coeur raisonne sur l'image entiere, ou les deux ecrans sont empiles :
     * celui du haut, 400 sur 240, occupe toute la largeur ; celui du bas,
     * 320 sur 240, est centre juste en dessous. On calcule donc a partir des
     * dimensions REELLES annoncees par le coeur, comme le fait la DS, plutot
     * que de supposer des proportions.
     */
    float largeur = (g_bw > 0) ? (float)g_bw : 400.0f;
    float hauteur = (g_bh > 0) ? (float)g_bh : 480.0f;
    float lbas = largeur * (320.0f / 400.0f);
    float hbas = hauteur * 0.5f;
    float x0 = (largeur - lbas) * 0.5f;
    float gx = (x0 + fx * lbas) / largeur;
    float gy = (hbas + fy * hbas) / hauteur;
    if (g_convention == 1) { gx = fx; gy = fy; }
    g_stylet_x = (int16_t)((gx * 2.0f - 1.0f) * 32767.0f);
    g_stylet_y = (int16_t)((gy * 2.0f - 1.0f) * 32767.0f);
    g_stylet_pose = 1;
    g_st_fx = fx; g_st_fy = fy; g_st_gx = gx; g_st_gy = gy;
    if (g_mesures < 6) {
        g_mesures++;
        noter("stylet : ecran %.2f,%.2f -> image %.2f,%.2f (image %ux%u)",
              fx, fy, gx, gy, g_bw, g_bh);
    }
}

JNIEXPORT void JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natSonVider(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    pthread_mutex_lock(&g_son_verrou); g_son_n = 0; pthread_mutex_unlock(&g_son_verrou);
}

JNIEXPORT void JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natVariable(JNIEnv *env, jobject self,
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
Java_com_skin3ds_app_core_Coeur3DS_natReduction(JNIEnv *env, jobject self, jint largeur) {
    (void)env; (void)self;
    g_reduction = largeur;
    noter("relecture ramenee a %d px de large", largeur);
}

/* 1 si la derniere image venait du rendu logiciel, 0 du materiel. */
JNIEXPORT jint JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natLogiciel(JNIEnv *e, jobject s) {
    (void)e; (void)s; return g_soft_pret ? 1 : 0;
}

/* Change la convention de coordonnees du stylet. */
JNIEXPORT void JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natConvention(JNIEnv *e, jobject s, jint c) {
    (void)e; (void)s;
    g_convention = c;
    noter("stylet : coordonnees rapportees a %s",
          c == 1 ? "l'ecran du bas" : "l'image entiere");
}

/* Etat du stylet en clair, pour l'ecran « Etat ». */
JNIEXPORT jstring JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natStyletEtat(JNIEnv *env, jobject self) {
    (void)self;
    char t[256];
    snprintf(t, sizeof t,
             "stylet %s · écran %.2f,%.2f → transmis %.2f,%.2f · image %ux%u · "
             "lu %lu fois · repère %s",
             g_stylet_pose ? "posé" : "levé",
             g_st_fx, g_st_fy, g_st_gx, g_st_gy, g_bw, g_bh, g_pointeur_lu,
             g_convention == 1 ? "écran du bas" : "image entière");
    return (*env)->NewStringUTF(env, t);
}

/* Combien de fois le coeur a interroge l'ecran tactile. */
JNIEXPORT jint JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natPointeurLu(JNIEnv *e, jobject s) {
    (void)e; (void)s; return (jint)(g_pointeur_lu & 0x7FFFFFFF);
}

JNIEXPORT jint JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natClarte(JNIEnv *e, jobject s) {
    (void)e; (void)s; return (jint)g_clarte;
}

JNIEXPORT jint JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natImages(JNIEnv *e, jobject s) {
    (void)e; (void)s; return (jint)(g_images & 0x7FFFFFFF);
}

JNIEXPORT jint JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natMaxL(JNIEnv *e, jobject s) { (void)e; (void)s; return (jint)g_maxw; }
JNIEXPORT jint JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natMaxH(JNIEnv *e, jobject s) { (void)e; (void)s; return (jint)g_maxh; }
JNIEXPORT jfloat JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natFps(JNIEnv *e, jobject s) { (void)e; (void)s; return (jfloat)g_fps; }
JNIEXPORT jint JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natFrequence(JNIEnv *e, jobject s) { (void)e; (void)s; return (jint)g_sample_rate; }

JNIEXPORT jbyteArray JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natSauver(JNIEnv *env, jobject self) {
    (void)self;
    if (!g_charge) return NULL;
    size_t n = p_serialize_size();
    if (!n) return NULL;
    void *tampon = malloc(n);
    if (!tampon) return NULL;
    jbyteArray res = NULL;
    if (p_serialize(tampon, n)) {
        res = (*env)->NewByteArray(env, (jsize)n);
        if (res) (*env)->SetByteArrayRegion(env, res, 0, (jsize)n, tampon);
    }
    free(tampon);
    return res;
}

JNIEXPORT jboolean JNICALL
Java_com_skin3ds_app_core_Coeur3DS_natRestaurer(JNIEnv *env, jobject self, jbyteArray etat) {
    (void)self;
    if (!g_charge || !etat) return JNI_FALSE;
    jsize n = (*env)->GetArrayLength(env, etat);
    jbyte *p = (*env)->GetByteArrayElements(env, etat, NULL);
    bool ok = p_unserialize(p, (size_t)n);
    (*env)->ReleaseByteArrayElements(env, etat, p, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
