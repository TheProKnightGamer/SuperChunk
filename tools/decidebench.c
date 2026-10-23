// SuperChunk decide-kernel replay bench (2026-10-22).
//
// Replays the REAL compact-ids decide kernel (sc_decide_multichunk) outside the game on batches
// dumped by a server run with
//   -Dsuperchunk.gpu.dumpDecideTo=<dir>
// which writes decideN.* per batch: the program as built (.cl .meta), its noise state (.desc
// .factors .perm .active .xo .yo .zo .amp), the device corner buffer (.corners) and every aux
// input (.origins .auxi .auxl .loc .fl .ft .beard .bkern .btab). Only batches holding a GPU-beard chunk
// are dumped.
//
// Variants are program sources: the first is normally the dumped .cl, the rest edited copies of
// it. Each variant is built with the dump's options and run on every dumped batch, interleaved
// round-robin with a rotating start (this box's GPU is shared). The id bytes of every run are
// FNV-hashed: a variant is bit-exact iff each batch's hash matches variant 0's. Times are the
// decide NDRange's device time (OpenCL event profiling), summed over the batches of one rep.
//
//   gcc -O2 -o decidebench decidebench.c -lOpenCL -lm
//   ./decidebench <reps> <dump-dir> <nbatches> <variant.cl>[@local]...
// A variant may name a work-group size (variant.cl@32); without one the driver picks.
#define CL_TARGET_OPENCL_VERSION 120
#include <CL/cl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#define MAXV 8
#define MAXB 16
#define MAXR 512

#define CK(x) do { cl_int _e = (x); if (_e != CL_SUCCESS) { \
    fprintf(stderr, "%s:%d OpenCL err %d\n", __FILE__, __LINE__, _e); exit(1); } } while (0)

static char *slurp(const char *path, size_t *len) {
    FILE *f = fopen(path, "rb");
    if (!f) { fprintf(stderr, "cannot open %s\n", path); exit(1); }
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    char *b = malloc(n + 1);
    if (fread(b, 1, n, f) != (size_t) n) { fprintf(stderr, "short read %s\n", path); exit(1); }
    b[n] = 0; fclose(f);
    if (len) *len = (size_t) n;
    return b;
}

static long meta_get(const char *meta, const char *key) {
    char k[64]; snprintf(k, sizeof k, "\n%s=", key);
    const char *p = strstr(meta, k);
    if (!p) { fprintf(stderr, "meta key %s missing\n", key); exit(1); }
    return strtol(p + strlen(k), NULL, 10);
}

typedef struct {
    int K, M, cornerN, dimX, dimZ, sx, sy, sz, fullX, fullY, fullZ, fullN, total, cellN;
    double flowingSim;
    cl_mem in[18];   // desc factors perm active xo yo zo amp corners origins auxi auxl loc fl ft beard bkern btab
    cl_mem ids;
    size_t idsBytes;
} Batch;

static const char *EXT[18] = {"desc", "factors", "perm", "active", "xo", "yo", "zo", "amp",
                              "corners", "origins", "auxi", "auxl", "loc", "fl", "ft", "beard", "bkern", "btab"};

typedef struct {
    char name[256];
    size_t local;
    cl_program prog;
    cl_kernel k;
    double t[MAXR];
    uint64_t hash[MAXR][MAXB];
} Variant;

static int cmpd(const void *a, const void *b) {
    double x = *(const double *) a, y = *(const double *) b;
    return x < y ? -1 : (x > y ? 1 : 0);
}

int main(int argc, char **argv) {
    if (argc < 5) {
        fprintf(stderr, "usage: decidebench <reps> <dump-dir> <nbatches> <variant.cl>...\n");
        return 2;
    }
    int reps = atoi(argv[1]);
    const char *dir = argv[2];
    int nb = atoi(argv[3]);
    if (reps > MAXR) reps = MAXR;
    if (nb > MAXB) nb = MAXB;
    int nv = argc - 4;
    if (nv > MAXV) nv = MAXV;

    cl_uint nplat = 0; CK(clGetPlatformIDs(0, NULL, &nplat));
    cl_platform_id *plats = calloc(nplat, sizeof *plats); CK(clGetPlatformIDs(nplat, plats, NULL));
    cl_device_id dev = NULL; char pname[256];
    for (cl_uint i = 0; i < nplat && !dev; i++) {
        CK(clGetPlatformInfo(plats[i], CL_PLATFORM_NAME, sizeof pname, pname, NULL));
        if (!strstr(pname, "NVIDIA")) continue;
        cl_uint nd = 0;
        if (clGetDeviceIDs(plats[i], CL_DEVICE_TYPE_GPU, 1, &dev, &nd) != CL_SUCCESS || nd == 0) dev = NULL;
    }
    if (!dev) { fprintf(stderr, "no NVIDIA GPU device\n"); return 1; }
    cl_int err;
    cl_context ctx = clCreateContext(NULL, 1, &dev, NULL, NULL, &err); CK(err);
    cl_command_queue q = clCreateCommandQueue(ctx, dev, CL_QUEUE_PROFILING_ENABLE, &err); CK(err);

    // Batches (all from one run: same program options and noise state).
    char path[1024], opts[1024] = "";
    Batch *b = calloc(nb, sizeof *b);
    for (int i = 0; i < nb; i++) {
        snprintf(path, sizeof path, "%s/decide%d.meta", dir, i + 1);
        char *m = slurp(path, NULL);
        char *meta = malloc(strlen(m) + 2); meta[0] = '\n'; strcpy(meta + 1, m);
        if (i == 0) {
            const char *o = strstr(meta, "\noptions=");
            if (o) { o += 9; size_t n = strcspn(o, "\n"); memcpy(opts, o, n); opts[n] = 0; }
        }
        Batch *x = &b[i];
        x->K = meta_get(meta, "K"); x->M = meta_get(meta, "M"); x->cornerN = meta_get(meta, "cornerN");
        x->dimX = meta_get(meta, "dimX"); x->dimZ = meta_get(meta, "dimZ");
        x->sx = meta_get(meta, "sx"); x->sy = meta_get(meta, "sy"); x->sz = meta_get(meta, "sz");
        x->fullX = meta_get(meta, "fullX"); x->fullY = meta_get(meta, "fullY"); x->fullZ = meta_get(meta, "fullZ");
        x->fullN = meta_get(meta, "fullN"); x->total = meta_get(meta, "total"); x->cellN = meta_get(meta, "cellN");
        const char *fb = strstr(meta, "\nflowingSimBits=") + 16;
        uint64_t bits = strtoull(fb, NULL, 16); memcpy(&x->flowingSim, &bits, 8);
        for (int s = 0; s < 18; s++) {
            size_t len;
            snprintf(path, sizeof path, "%s/decide%d.%s", dir, i + 1, EXT[s]);
            char *data = slurp(path, &len);
            x->in[s] = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, len ? len : 1, data, &err); CK(err);
            free(data);
        }
        x->idsBytes = (size_t) x->total;
        x->ids = clCreateBuffer(ctx, CL_MEM_READ_WRITE, x->idsBytes, NULL, &err); CK(err);
        free(m); free(meta);
    }
    printf("%d batches, options '%s'\n", nb, opts);

    Variant *v = calloc(nv, sizeof *v);
    for (int i = 0; i < nv; i++) {
        char p[1024];
        snprintf(p, sizeof p, "%s", argv[4 + i]);
        char *at = strchr(p, '@');
        if (at) { *at = 0; v[i].local = (size_t) atoi(at + 1); }
        const char *base = strrchr(argv[4 + i], '/');
        snprintf(v[i].name, sizeof v[i].name, "%s", base ? base + 1 : argv[4 + i]);
        size_t len;
        char *src = slurp(p, &len);
        const char *srcs[] = { src };
        v[i].prog = clCreateProgramWithSource(ctx, 1, srcs, &len, &err); CK(err);
        if (clBuildProgram(v[i].prog, 1, &dev, opts, NULL, NULL) != CL_SUCCESS) {
            size_t ln = 0; clGetProgramBuildInfo(v[i].prog, dev, CL_PROGRAM_BUILD_LOG, 0, NULL, &ln);
            char *log = malloc(ln + 1);
            clGetProgramBuildInfo(v[i].prog, dev, CL_PROGRAM_BUILD_LOG, ln, log, NULL);
            log[ln] = 0;
            fprintf(stderr, "BUILD FAILED (%s):\n%s\n", v[i].name, log);
            return 1;
        }
        free(src);
        v[i].k = clCreateKernel(v[i].prog, "sc_decide_multichunk", &err); CK(err);
        printf("built %s\n", v[i].name);
    }

    unsigned char *host = NULL; size_t hostCap = 0;
    for (int r = -1; r < reps; r++) {      // r = -1 is an untimed warm-up
        int rr = r < 0 ? 0 : r;
        for (int j = 0; j < nv; j++) {
            Variant *x = &v[(j + (rr % nv)) % nv];
            double ms = 0;
            for (int bi = 0; bi < nb; bi++) {
                Batch *B = &b[bi];
                cl_kernel k = x->k;
                int a = 0;
                for (int s = 0; s < 9; s++) CK(clSetKernelArg(k, a++, sizeof(cl_mem), &B->in[s]));   // noise + corners
                CK(clSetKernelArg(k, a++, sizeof(cl_mem), &B->in[9]));                              // origins
                int sc[12] = {B->M, B->cornerN, B->dimX, B->dimZ, B->sx, B->sy, B->sz,
                              B->fullX, B->fullY, B->fullZ, B->fullN, B->total};
                for (int s = 0; s < 12; s++) CK(clSetKernelArg(k, a++, sizeof(int), &sc[s]));
                for (int s = 10; s < 15; s++) CK(clSetKernelArg(k, a++, sizeof(cl_mem), &B->in[s])); // auxi auxl loc fl ft
                CK(clSetKernelArg(k, a++, sizeof(int), &B->cellN));
                CK(clSetKernelArg(k, a++, sizeof(double), &B->flowingSim));
                CK(clSetKernelArg(k, a++, sizeof(cl_mem), &B->ids));
                CK(clSetKernelArg(k, a++, sizeof(cl_mem), &B->in[15]));                             // beard
                CK(clSetKernelArg(k, a++, sizeof(cl_mem), &B->in[16]));                             // bkern
                CK(clSetKernelArg(k, a++, sizeof(cl_mem), &B->in[17]));                             // btab
                size_t lc = x->local ? x->local : 64;
                size_t g = ((size_t) (B->total / B->sz) + lc - 1) / lc * lc;
                cl_event e;
                CK(clEnqueueNDRangeKernel(q, k, 1, NULL, &g, x->local ? &x->local : NULL, 0, NULL, &e));
                CK(clFinish(q));
                cl_ulong s0, s1;
                CK(clGetEventProfilingInfo(e, CL_PROFILING_COMMAND_START, sizeof s0, &s0, NULL));
                CK(clGetEventProfilingInfo(e, CL_PROFILING_COMMAND_END, sizeof s1, &s1, NULL));
                clReleaseEvent(e);
                ms += (s1 - s0) * 1e-6;
                if (B->idsBytes > hostCap) { hostCap = B->idsBytes; host = realloc(host, hostCap); }
                CK(clEnqueueReadBuffer(q, B->ids, CL_TRUE, 0, B->idsBytes, host, 0, NULL, NULL));
                uint64_t h = 1469598103934665603ULL;
                for (size_t i = 0; i < B->idsBytes; i++) { h ^= host[i]; h *= 1099511628211ULL; }
                if (r >= 0) x->hash[r][bi] = h;
            }
            if (r >= 0) x->t[r] = ms;
        }
    }

    int mismatch = 0;
    for (int r = 0; r < reps; r++)
        for (int i = 1; i < nv; i++)
            for (int bi = 0; bi < nb; bi++)
                if (v[i].hash[r][bi] != v[0].hash[r][bi]) {
                    if (mismatch < 5) printf("MISMATCH rep %d batch %d: %s vs %s\n", r, bi + 1, v[i].name, v[0].name);
                    mismatch++;
                }
    printf("\n%d reps over %d batches, decide device ms per rep (all batches):\n", reps, nb);
    double base = 0;
    for (int i = 0; i < nv; i++) {
        double s[MAXR]; memcpy(s, v[i].t, sizeof(double) * reps);
        qsort(s, reps, sizeof(double), cmpd);
        double med = s[reps / 2];
        if (i == 0) base = med;
        printf("  %-32s median %8.3f  p10 %8.3f  p90 %8.3f  (%+.1f%% vs %s)\n", v[i].name, med, s[reps / 10],
               s[(reps * 9) / 10], 100.0 * (med - base) / base, v[0].name);
    }
    printf("bit-exact: %s (%d batch-runs differ)\n", mismatch ? "NO" : "yes", mismatch);
    return mismatch ? 3 : 0;
}
