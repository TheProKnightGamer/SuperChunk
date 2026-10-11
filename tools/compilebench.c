// compilebench: time the OpenCL driver compiling SuperChunk's real programs, outside the game.
//
// Dump the programs first: run a server with -Dsuperchunk.gpu.dumpProgramsTo=<dir> and an EMPTY
// config/superchunk-gpu-cache (every program built from source is written as <key>.cl + <key>.opts).
// Then, with the driver's own cache off so every build is cold:
//
//   gcc -O2 -pthread -o compilebench compilebench.c -lOpenCL
//   CUDA_CACHE_DISABLE=1 ./compilebench <dir> [--threads N] [--append "<opts>"] [--strip "<opt>"]
//                                       [--limit K] [--verbose]
//
// --threads N  builds N programs at a time (one context, one cl_program per build; OpenCL allows
//              concurrent clBuildProgram on distinct programs). 1 = the game's sequential order.
// --append     extra options after each program's own (e.g. "-cl-nv-opt-level=0").
// --strip      removes one option from each program's own options (repeatable).
// --verbose    prints the build log (with -cl-nv-verbose: ptxas register/spill lines).
// --split      after each source build, rebuilds from the returned binary (PTX on NVIDIA) and
//              times that too: the source build is front end + ptxas, the binary build ptxas only.
//
// Prints each program's compile time, its binary size, and the total wall time.
#define CL_TARGET_OPENCL_VERSION 120
#include <CL/cl.h>
#include <dirent.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

typedef struct {
    char name[128];
    char *src;
    size_t len;
    char opts[4096];
    double ms;
    double binMs;
    size_t binSize;
    int ok;
} Prog;

static Prog *progs;
static int nprogs;
static cl_context ctx;
static cl_device_id dev;
static int next_index;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static int verbose;
static int split;

static double now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

static char *read_file(const char *path, size_t *len) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    char *b = malloc(n + 1);
    if (fread(b, 1, n, f) != (size_t) n) { fclose(f); free(b); return NULL; }
    b[n] = 0;
    fclose(f);
    if (len) *len = n;
    return b;
}

static void strip_opt(char *opts, const char *opt) {
    size_t n = strlen(opt);
    char *p;
    while ((p = strstr(opts, opt)) != NULL) {
        if ((p == opts || p[-1] == ' ') && (p[n] == 0 || p[n] == ' ')) {
            memmove(p, p + n, strlen(p + n) + 1);
        } else {
            break;
        }
    }
}

static void build_one(Prog *p) {
    cl_int err;
    const char *src = p->src;
    cl_program prog = clCreateProgramWithSource(ctx, 1, &src, &p->len, &err);
    if (err != CL_SUCCESS) { fprintf(stderr, "%s: create failed %d\n", p->name, err); return; }
    double t0 = now_ms();
    err = clBuildProgram(prog, 1, &dev, p->opts, NULL, NULL);
    p->ms = now_ms() - t0;
    p->ok = err == CL_SUCCESS;
    if (!p->ok || verbose) {
        size_t logLen = 0;
        clGetProgramBuildInfo(prog, dev, CL_PROGRAM_BUILD_LOG, 0, NULL, &logLen);
        char *log = malloc(logLen + 1);
        clGetProgramBuildInfo(prog, dev, CL_PROGRAM_BUILD_LOG, logLen, log, NULL);
        log[logLen] = 0;
        pthread_mutex_lock(&lock);
        fprintf(stderr, "---- %s (%s) err=%d\n%s\n", p->name, p->opts, err, log);
        pthread_mutex_unlock(&lock);
        free(log);
    }
    if (p->ok) {
        clGetProgramInfo(prog, CL_PROGRAM_BINARY_SIZES, sizeof(size_t), &p->binSize, NULL);
    }
    if (p->ok && split && p->binSize > 0) {
        unsigned char *bin = malloc(p->binSize);
        unsigned char *bins[1] = {bin};
        clGetProgramInfo(prog, CL_PROGRAM_BINARIES, sizeof bins, bins, NULL);
        const unsigned char *cbin = bin;
        cl_int st;
        cl_program fromBin = clCreateProgramWithBinary(ctx, 1, &dev, &p->binSize, &cbin, &st, &err);
        if (err == CL_SUCCESS) {
            double t1 = now_ms();
            err = clBuildProgram(fromBin, 1, &dev, p->opts, NULL, NULL);
            p->binMs = err == CL_SUCCESS ? now_ms() - t1 : -1;
            clReleaseProgram(fromBin);
        }
        free(bin);
    }
    clReleaseProgram(prog);
}

static void *worker(void *arg) {
    (void) arg;
    for (;;) {
        pthread_mutex_lock(&lock);
        int i = next_index++;
        pthread_mutex_unlock(&lock);
        if (i >= nprogs) return NULL;
        build_one(&progs[i]);
    }
}

static int by_size_desc(const void *a, const void *b) {
    const Prog *x = a, *y = b;
    return (y->len > x->len) - (y->len < x->len);
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <dumpdir> [--threads N] [--append opts] [--strip opt]... [--limit K] [--verbose]\n", argv[0]);
        return 2;
    }
    const char *dir = argv[1];
    int threads = 1, limit = 1 << 30;
    const char *append = "";
    const char *strips[16];
    int nstrips = 0;
    for (int i = 2; i < argc; i++) {
        if (!strcmp(argv[i], "--threads") && i + 1 < argc) threads = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--append") && i + 1 < argc) append = argv[++i];
        else if (!strcmp(argv[i], "--strip") && i + 1 < argc && nstrips < 16) strips[nstrips++] = argv[++i];
        else if (!strcmp(argv[i], "--limit") && i + 1 < argc) limit = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--verbose")) verbose = 1;
        else if (!strcmp(argv[i], "--split")) split = 1;
    }

    DIR *d = opendir(dir);
    if (!d) { perror(dir); return 1; }
    progs = calloc(4096, sizeof(Prog));
    struct dirent *e;
    while ((e = readdir(d)) != NULL && nprogs < 4096) {
        size_t n = strlen(e->d_name);
        if (n < 4 || strcmp(e->d_name + n - 3, ".cl") != 0) continue;
        Prog *p = &progs[nprogs];
        char path[4096];
        snprintf(path, sizeof path, "%s/%s", dir, e->d_name);
        p->src = read_file(path, &p->len);
        if (!p->src) continue;
        snprintf(p->name, sizeof p->name, "%.12s", e->d_name);
        snprintf(path, sizeof path, "%s/%.*s.opts", dir, (int) (n - 3), e->d_name);
        char *o = read_file(path, NULL);
        snprintf(p->opts, sizeof p->opts, "%s", o ? o : "");
        free(o);
        for (int s = 0; s < nstrips; s++) strip_opt(p->opts, strips[s]);
        if (*append) {
            size_t ol = strlen(p->opts);
            snprintf(p->opts + ol, sizeof p->opts - ol, "%s%s", ol ? " " : "", append);
        }
        nprogs++;
    }
    closedir(d);
    qsort(progs, nprogs, sizeof(Prog), by_size_desc); // biggest first: best packing when parallel
    if (nprogs > limit) nprogs = limit;

    cl_uint np = 0;
    clGetPlatformIDs(0, NULL, &np);
    cl_platform_id plats[16];
    clGetPlatformIDs(np < 16 ? np : 16, plats, NULL);
    int found = 0;
    for (cl_uint i = 0; i < np && !found; i++) {
        char name[256];
        clGetPlatformInfo(plats[i], CL_PLATFORM_NAME, sizeof name, name, NULL);
        if (strstr(name, "NVIDIA") && clGetDeviceIDs(plats[i], CL_DEVICE_TYPE_GPU, 1, &dev, NULL) == CL_SUCCESS) found = 1;
    }
    if (!found) { fprintf(stderr, "no NVIDIA OpenCL GPU\n"); return 1; }
    char devName[256], drv[256];
    clGetDeviceInfo(dev, CL_DEVICE_NAME, sizeof devName, devName, NULL);
    clGetDeviceInfo(dev, CL_DRIVER_VERSION, sizeof drv, drv, NULL);
    cl_int err;
    ctx = clCreateContext(NULL, 1, &dev, NULL, NULL, &err);
    if (err != CL_SUCCESS) { fprintf(stderr, "context failed %d\n", err); return 1; }

    const char *cache = getenv("CUDA_CACHE_DISABLE");
    printf("%s (driver %s): %d programs, %d thread(s), append='%s', driver cache %s\n", devName, drv, nprogs,
           threads, append, cache && *cache == '1' ? "OFF" : "ON (builds may be cache hits!)");
    double t0 = now_ms();
    pthread_t th[256];
    if (threads > 256) threads = 256;
    for (int i = 0; i < threads; i++) pthread_create(&th[i], NULL, worker, NULL);
    for (int i = 0; i < threads; i++) pthread_join(th[i], NULL);
    double wall = now_ms() - t0;

    double sum = 0;
    int failed = 0;
    for (int i = 0; i < nprogs; i++) {
        Prog *p = &progs[i];
        printf("  %-12s %8zu chars  %9.0f ms  bin %8zu", p->name, p->len, p->ms, p->binSize);
        if (split) printf("  from-binary %9.0f ms", p->binMs);
        printf("  %s\n", p->ok ? "" : "FAILED");
        sum += p->ms;
        failed += !p->ok;
    }
    printf("TOTAL wall %.1f s, sum of builds %.1f s, failed %d\n", wall / 1e3, sum / 1e3, failed);
    clReleaseContext(ctx);
    return failed != 0;
}
